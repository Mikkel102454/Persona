package nu.miguel.persona.behavior;

import nu.miguel.persona.behavior.BehaviorDefinition.BehaviorNode;
import nu.miguel.persona.content.Durations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Stateful, single-threaded interpreter. Durable fields use absolute deadlines. */
public final class BehaviorRuntime {
    public interface Environment {
        long now();
        boolean condition(BehaviorNode node,BehaviorEvent event);
        CompletionStage<BehaviorStatus> action(BehaviorNode node);
        default boolean condition(String behaviorId,BehaviorNode node,BehaviorEvent event){return condition(node,event);}
        default CompletionStage<BehaviorStatus> action(String behaviorId,BehaviorNode node){return action(node);}
        default void cancel(BehaviorNode node){}
        default void cancel(String behaviorId,BehaviorNode node){cancel(node);}
        default void wake(){}
    }
    public static final class Budget {
        private int remaining;private final long deadlineNanos;
        public Budget(int evaluations,long nanos){remaining=evaluations;deadlineNanos=System.nanoTime()+nanos;}
        private boolean exhausted;boolean take(){if(remaining<=0||System.nanoTime()>=deadlineNanos){exhausted=true;return false;}remaining--;return true;}
        public int remaining(){return remaining;}
        public boolean exhausted(){return exhausted;}
    }
    /** World-independent durable location used by private presentations. */
    public record LogicalPosition(String world,double x,double y,double z,float yaw,float pitch) {}
    /** Durable state for the native logical-travel action. */
    public record LogicalTravel(String behaviorId,String nodeId,String source,String destination,long startedAt,long durationMillis) {
        public LogicalTravel {
            Objects.requireNonNull(behaviorId,"behaviorId");Objects.requireNonNull(nodeId,"nodeId");Objects.requireNonNull(destination,"destination");
            if(durationMillis<0)throw new IllegalArgumentException("durationMillis cannot be negative");
        }
        public long completesAt(){return saturatedAdd(startedAt,durationMillis);}
    }
    /** Complete durable state for one logical NPC runtime. */
    public record Snapshot(Map<String,Integer> progress,Map<String,Long> deadlines,Map<String,Object> blackboard,
                           String anchor,LogicalPosition position,boolean visible,String checkpoint,long wakeAt,
                           Map<String,String> nodeTypes,String checkpointStructure,LogicalTravel logicalTravel,String treeHash) {
        public Snapshot {
            progress=Map.copyOf(progress);deadlines=Map.copyOf(deadlines);blackboard=Map.copyOf(blackboard);nodeTypes=Map.copyOf(nodeTypes);
        }
        /** Compatibility constructor for snapshots created before durable migration metadata. */
        public Snapshot(Map<String,Integer> progress,Map<String,Long> deadlines,Map<String,Object> blackboard,
                        String anchor,LogicalPosition position,boolean visible,String checkpoint){
            this(progress,deadlines,blackboard,anchor,position,visible,checkpoint,0,Map.of(),null,null,null);
        }
    }
    public record TraceEntry(long at,String node,BehaviorStatus status,Map<String,Object> inputs,Object output,String detail) {
        public TraceEntry { inputs=Map.copyOf(inputs); }
    }
    private record NodeRef(String behaviorId,BehaviorNode node) {String key(){return stateKey(behaviorId,node.id());}}

    private static final String PARALLEL_MARKER="#parallel:";
    private static final String STATE_SEPARATOR="/";
    private final BehaviorDefinition definition;private final Map<String,BehaviorDefinition> definitions;private final Environment environment;
    private final Map<String,Integer> progress=new HashMap<>();private final Map<String,Long> deadlines=new HashMap<>();private final Map<String,Object> blackboard=new HashMap<>();
    private final Map<String,CompletionStage<BehaviorStatus>> running=new HashMap<>();private final Deque<BehaviorEvent> inbox=new ArrayDeque<>();private final Deque<TraceEntry> outcomes=new ArrayDeque<>();
    private final int inboxLimit;private final long eventTtlMillis;private String runningLeaf,checkpoint,anchor;private List<String> runningPath=List.of();private LogicalPosition position;private LogicalTravel logicalTravel;private boolean visible=true,cancelled,paused;private long generation,wakeAt,droppedEvents;
    private final Deque<String> evaluationPath=new ArrayDeque<>();
    private long proposedWake;private boolean wakeProposed;

    public BehaviorRuntime(BehaviorDefinition definition,Environment environment){this(definition,Map.of(definition.id(),definition),environment,64,30_000);}
    public BehaviorRuntime(BehaviorDefinition definition,Map<String,BehaviorDefinition> definitions,Environment environment,int inboxLimit,long eventTtlMillis){this.definition=Objects.requireNonNull(definition);this.definitions=Map.copyOf(definitions);this.environment=Objects.requireNonNull(environment);if(inboxLimit<1)throw new IllegalArgumentException("inboxLimit must be positive");if(eventTtlMillis<0)throw new IllegalArgumentException("eventTtlMillis cannot be negative");this.inboxLimit=inboxLimit;this.eventTtlMillis=eventTtlMillis;}

    public BehaviorStatus tick(Budget budget){
        if(cancelled)return BehaviorStatus.FAILURE;
        if(paused)return BehaviorStatus.RUNNING;
        expireEvents();wakeProposed=false;proposedWake=Long.MAX_VALUE;
        BehaviorStatus status=evaluate(definition,definition.root(),budget,currentEvent());
        wakeAt=status==BehaviorStatus.RUNNING&&!budget.exhausted()&&wakeProposed?proposedWake:0;
        return status;
    }
    public void signal(BehaviorEvent event){Objects.requireNonNull(event,"event");expireEvents();while(inbox.size()>=inboxLimit){inbox.removeFirst();droppedEvents++;}inbox.addLast(event);environment.wake();}
    public void cancel(){cancelled=true;cancelRunning();}
    public void restartTransient(){cancelRunning();}
    public void pause(){if(!paused){paused=true;cancelRunning();}}
    public void resume(){if(paused){paused=false;environment.wake();}}
    /** Restarts all behavior-owned state while retaining only presentation state. */
    public void restart(){cancelRunning();progress.clear();deadlines.clear();blackboard.clear();checkpoint=null;logicalTravel=null;wakeAt=0;inbox.clear();paused=false;environment.wake();}
    private void cancelRunning(){generation++;for(String key:new ArrayList<>(running.keySet())){NodeRef ref=findNode(key);if(ref!=null)environment.cancel(ref.behaviorId(),ref.node());}running.clear();runningLeaf=null;runningPath=List.of();}

    public Snapshot snapshot(){
        Map<String,String> types=new HashMap<>();
        Set<String> keys=new HashSet<>(progress.keySet());keys.addAll(deadlines.keySet());
        if(checkpoint!=null)keys.add(checkpoint);
        if(logicalTravel!=null)keys.add(stateKey(logicalTravel.behaviorId(),logicalTravel.nodeId()));
        for(String key:keys){NodeRef ref=findNode(key);if(ref!=null)types.put(baseKey(key),nodeKind(ref.node()));}
        return new Snapshot(progress,deadlines,blackboard,anchor,position,visible,checkpoint,nextWakeAt(),types,
                checkpoint==null?null:checkpointStructure(checkpoint),logicalTravel,definition.hash());
    }

    /**
     * Restores only state that is safe for the currently loaded tree. Node IDs are
     * qualified by behavior ID, node kinds must match, and a changed checkpoint child
     * structure restarts that checkpoint tree. Blackboard data is runtime-owned and is
     * intentionally retained; NPC memories live in separate tables.
     */
    public void restore(Snapshot snapshot){
        progress.clear();deadlines.clear();blackboard.clear();checkpoint=null;logicalTravel=null;wakeAt=0;
        if(snapshot==null)return;
        blackboard.putAll(snapshot.blackboard());anchor=snapshot.anchor();position=snapshot.position();visible=snapshot.visible();
        boolean unchangedTree=Objects.equals(snapshot.treeHash(),definition.hash());
        String restoredCheckpoint=normalizeNodeKey(snapshot.checkpoint());
        boolean validCheckpoint=restoredCheckpoint!=null&&isKind(restoredCheckpoint,"checkpoint")
                &&snapshot.checkpointStructure()!=null
                &&snapshot.checkpointStructure().equals(checkpointStructure(restoredCheckpoint));
        if(validCheckpoint)checkpoint=restoredCheckpoint;

        snapshot.deadlines().forEach((raw,value)->{
            String key=normalizeStateKey(raw);if(validState(key,snapshot.nodeTypes(),unchangedTree))deadlines.put(key,value);
        });
        if(validCheckpoint)snapshot.progress().forEach((raw,value)->{
            String key=normalizeStateKey(raw);if(validState(key,snapshot.nodeTypes(),unchangedTree))progress.put(key,value);
        });
        // A moved, removed, or structurally changed checkpoint restarts the affected
        // behavior tree. Absolute cooldown/wait deadlines survive only when their
        // owning node remains valid and no incompatible checkpoint was active.
        if(snapshot.checkpoint()!=null&&!validCheckpoint){progress.clear();deadlines.clear();}

        LogicalTravel travel=snapshot.logicalTravel();
        if(travel!=null){String key=normalizeNodeKey(stateKey(travel.behaviorId(),travel.nodeId()));NodeRef ref=findNode(key);if(ref!=null&&isLogicalTravel(key)&&validState(key,snapshot.nodeTypes(),unchangedTree))logicalTravel=new LogicalTravel(ref.behaviorId(),ref.node().id(),travel.source(),travel.destination(),travel.startedAt(),travel.durationMillis());}
        long durable=logicalTravel==null?Long.MAX_VALUE:logicalTravel.completesAt();
        long persistedWake=snapshot.wakeAt();
        wakeAt=persistedWake>0&&persistedWake<Long.MAX_VALUE&&(deadlines.containsValue(persistedWake)||durable==persistedWake)?persistedWake:0;
        if(durable!=Long.MAX_VALUE&&(wakeAt<=0||durable<wakeAt))wakeAt=durable;
        if(snapshot.checkpoint()!=null&&!validCheckpoint)wakeAt=logicalTravel==null?0:logicalTravel.completesAt();
        restartTransient();
    }

    public Map<String,Object> blackboard(){return blackboard;}
    public String anchor(){return anchor;}public void anchor(String value){anchor=value;}
    public LogicalPosition position(){return position;}public void position(LogicalPosition value){position=value;}
    public boolean visible(){return visible;}public void visible(boolean value){visible=value;}
    public String checkpoint(){return checkpoint;}public String runningLeaf(){return runningLeaf;}public List<String> runningPath(){return runningPath;}
    public String behaviorId(){return definition.id();}public String treeHash(){return definition.hash();}public boolean paused(){return paused;}
    public Map<String,Integer> progress(){return Collections.unmodifiableMap(progress);}public Map<String,Long> deadlines(){return Collections.unmodifiableMap(deadlines);}
    public LogicalTravel logicalTravel(){return logicalTravel;}public void logicalTravel(LogicalTravel value){logicalTravel=value;}
    public long nextWakeAt(){long travelAt=logicalTravel==null?Long.MAX_VALUE:logicalTravel.completesAt();return Math.min(wakeAt<=0?Long.MAX_VALUE:wakeAt,travelAt)==Long.MAX_VALUE?wakeAt:Math.min(wakeAt<=0?Long.MAX_VALUE:wakeAt,travelAt);}
    public boolean due(){long next=nextWakeAt();return next<=0||next<=environment.now();}
    public List<String> recentOutcomes(){return outcomes.stream().map(x->x.node()+"="+x.status()+(x.detail()==null?"":" ("+x.detail()+")")).toList();}
    public List<TraceEntry> traceHistory(){return List.copyOf(outcomes);}
    public List<BehaviorEvent> inbox(){return List.copyOf(inbox);}
    public long droppedEvents(){return droppedEvents;}

    private BehaviorStatus evaluate(BehaviorDefinition owner,BehaviorNode node,Budget budget,BehaviorEvent event){
        if(!budget.take())return BehaviorStatus.RUNNING;
        String pathKey=stateKey(owner.id(),node.id());evaluationPath.addLast(pathKey);
        try{return switch(node.type()){
            case "sequence"->composite(owner,node,budget,event,true);
            case "selector"->composite(owner,node,budget,event,false);
            case "priority-selector"->priority(owner,node,budget,event);
            case "parallel"->parallel(owner,node,budget,event);
            case "invert"->invert(evaluate(owner,node.child(),budget,event));
            case "repeat"->repeat(owner,node,budget,event,false);
            case "retry"->repeat(owner,node,budget,event,true);
            case "timeout"->timeout(owner,node,budget,event);
            case "cooldown"->cooldown(owner,node,budget,event);
            case "checkpoint"->checkpoint(owner,node,budget,event);
            case "wait"->waitNode(owner,node);
            case "condition"->condition(owner,node,event);
            case "action"->action(owner,node);
            case "subtree"->{BehaviorDefinition subtree=definitions.get(node.subtree());yield subtree==null?BehaviorStatus.FAILURE:evaluate(subtree,subtree.root(),budget,event);}
            default->BehaviorStatus.FAILURE;
        };}finally{evaluationPath.removeLast();}
    }
    private BehaviorStatus composite(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e,boolean sequence){String key=stateKey(owner.id(),n.id());int i=progress.getOrDefault(key,0);List<String> failed=new ArrayList<>();while(i<n.children().size()){BehaviorNode child=n.children().get(i);BehaviorStatus s=evaluate(owner,child,b,e);if(s==BehaviorStatus.RUNNING){progress.put(key,i);return s;}if(s==BehaviorStatus.FAILURE)failed.add(child.id());if(sequence&&s==BehaviorStatus.FAILURE||!sequence&&s==BehaviorStatus.SUCCESS){progress.remove(key);record(owner,n,s,null);return s;}i++;}progress.remove(key);BehaviorStatus done=sequence?BehaviorStatus.SUCCESS:BehaviorStatus.FAILURE;record(owner,n,done,Map.of(),done,done==BehaviorStatus.FAILURE?"branches failed: "+String.join(", ",failed):null);return done;}
    private BehaviorStatus priority(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e){String key=stateKey(owner.id(),n.id());int previous=progress.getOrDefault(key,-1);List<String> failed=new ArrayList<>();for(int i=0;i<n.children().size();i++){BehaviorNode child=n.children().get(i);BehaviorStatus s=evaluate(owner,child,b,e);if(b.exhausted())return BehaviorStatus.RUNNING;if(s==BehaviorStatus.FAILURE)failed.add(child.id());if(s!=BehaviorStatus.FAILURE){if(previous>=0&&previous!=i)cancelTree(owner,n.children().get(previous));if(s==BehaviorStatus.RUNNING)progress.put(key,i);else progress.remove(key);return s;}}progress.remove(key);record(owner,n,BehaviorStatus.FAILURE,Map.of(),false,"branches failed: "+String.join(", ",failed));return BehaviorStatus.FAILURE;}
    private BehaviorStatus parallel(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e){String parent=stateKey(owner.id(),n.id());int success=0,failure=0;for(BehaviorNode c:n.children()){String key=parallelKey(parent,c.id());int saved=progress.getOrDefault(key,0);BehaviorStatus s=saved==1?BehaviorStatus.SUCCESS:saved==2?BehaviorStatus.FAILURE:evaluate(owner,c,b,e);if(b.exhausted())return BehaviorStatus.RUNNING;if(s==BehaviorStatus.SUCCESS){success++;progress.put(key,1);}else if(s==BehaviorStatus.FAILURE){failure++;progress.put(key,2);}}int st=intOption(n,"success-threshold",n.children().size()),ft=intOption(n,"failure-threshold",1);if(success>=st){finishParallel(owner,n,BehaviorStatus.SUCCESS);return BehaviorStatus.SUCCESS;}if(failure>=ft){finishParallel(owner,n,BehaviorStatus.FAILURE);return BehaviorStatus.FAILURE;}return BehaviorStatus.RUNNING;}
    private void finishParallel(BehaviorDefinition owner,BehaviorNode n,BehaviorStatus result){String policy=Objects.toString(n.options().getOrDefault("cancel-remaining","always"));if(policy.equals("always")||policy.equals("on-success")&&result==BehaviorStatus.SUCCESS||policy.equals("on-failure")&&result==BehaviorStatus.FAILURE)cancelChildren(owner,n);String parent=stateKey(owner.id(),n.id());for(BehaviorNode c:n.children())progress.remove(parallelKey(parent,c.id()));}
    /** Repeat/retry deliberately perform at most one completed iteration per tick. */
    private BehaviorStatus repeat(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e,boolean retry){String key=stateKey(owner.id(),n.id());int done=progress.getOrDefault(key,0),times=intOption(n,"times",1);boolean forever=boolOption(n,"forever",false);BehaviorStatus s=evaluate(owner,n.child(),b,e);if(s==BehaviorStatus.RUNNING)return s;boolean target=retry?s==BehaviorStatus.FAILURE:s==BehaviorStatus.SUCCESS;if(!target){progress.remove(key);return s;}done++;if(!forever&&done>=times){progress.remove(key);return retry?BehaviorStatus.FAILURE:BehaviorStatus.SUCCESS;}progress.put(key,done);clearTree(owner,n.child());proposeWake(environment.now());return BehaviorStatus.RUNNING;}
    private BehaviorStatus timeout(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e){String key=stateKey(owner.id(),n.id());long now=environment.now(),end=deadlines.computeIfAbsent(key,x->saturatedAdd(now,duration(n).toMillis()));if(now>=end){cancelTree(owner,n.child());deadlines.remove(key);return BehaviorStatus.FAILURE;}BehaviorStatus s=evaluate(owner,n.child(),b,e);if(s!=BehaviorStatus.RUNNING)deadlines.remove(key);else proposeWake(end);return s;}
    private BehaviorStatus cooldown(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e){String key=stateKey(owner.id(),n.id());long now=environment.now(),end=deadlines.getOrDefault(key,0L);if(now<end){proposeWake(end);return BehaviorStatus.FAILURE;}BehaviorStatus s=evaluate(owner,n.child(),b,e);if(s==BehaviorStatus.SUCCESS)deadlines.put(key,saturatedAdd(now,duration(n).toMillis()));return s;}
    private BehaviorStatus checkpoint(BehaviorDefinition owner,BehaviorNode n,Budget b,BehaviorEvent e){String key=stateKey(owner.id(),n.id());checkpoint=key;BehaviorStatus s=evaluate(owner,n.child(),b,e);if(s!=BehaviorStatus.RUNNING&&Objects.equals(checkpoint,key))checkpoint=null;return s;}
    private BehaviorStatus waitNode(BehaviorDefinition owner,BehaviorNode n){String key=stateKey(owner.id(),n.id());long now=environment.now(),end=deadlines.computeIfAbsent(key,x->saturatedAdd(now,duration(n).toMillis()));if(now<end){setRunning(key);proposeWake(end);return BehaviorStatus.RUNNING;}deadlines.remove(key);clearRunning(key);return BehaviorStatus.SUCCESS;}
    private BehaviorStatus condition(BehaviorDefinition owner,BehaviorNode n,BehaviorEvent fallback){boolean eventCondition=isEventCondition(n);BehaviorEvent selected=eventCondition?matchingEvent(n):fallback;boolean ok=(!eventCondition||selected!=null)&&environment.condition(owner.id(),n,selected);BehaviorStatus s=ok?BehaviorStatus.SUCCESS:BehaviorStatus.FAILURE;if(ok&&eventCondition&&boolOption(n,"consume",true)&&selected.policy()==BehaviorEvent.ConsumptionPolicy.CONSUMABLE)inbox.remove(selected);record(owner,n,s,safeInputs(n,selected),ok,ok?null:"condition false");return s;}
    private BehaviorStatus action(BehaviorDefinition owner,BehaviorNode n){String key=stateKey(owner.id(),n.id());CompletionStage<BehaviorStatus> future=running.get(key);if(future==null){future=environment.action(owner.id(),n);running.put(key,future);setRunning(key);long startedIn=generation;CompletionStage<BehaviorStatus> expected=future;future.whenComplete((x,t)->{if(!cancelled&&generation==startedIn&&running.get(key)==expected)environment.wake();});}var cf=future.toCompletableFuture();if(!cf.isDone()){setRunning(key);proposeWake(Long.MAX_VALUE);return BehaviorStatus.RUNNING;}running.remove(key);clearRunning(key);try{BehaviorStatus result=cf.join();record(owner,n,result,Map.of(),result,null);return result;}catch(RuntimeException ex){record(owner,n,BehaviorStatus.FAILURE,Map.of(),null,ex.getMessage());return BehaviorStatus.FAILURE;}}
    private void cancelChildren(BehaviorDefinition owner,BehaviorNode n){n.children().forEach(x->cancelTree(owner,x));}
    private void cancelTree(BehaviorDefinition owner,BehaviorNode n){String key=stateKey(owner.id(),n.id());if(running.remove(key)!=null)environment.cancel(owner.id(),n);clearTree(owner,n);}
    private void clearTree(BehaviorDefinition owner,BehaviorNode n){String key=stateKey(owner.id(),n.id());progress.remove(key);deadlines.remove(key);progress.keySet().removeIf(x->x.startsWith(key+PARALLEL_MARKER));if(n.child()!=null)clearTree(owner,n.child());n.children().forEach(x->clearTree(owner,x));if(n.type().equals("subtree")){BehaviorDefinition subtree=definitions.get(n.subtree());if(subtree!=null)clearTree(subtree,subtree.root());}}
    private BehaviorEvent currentEvent(){return inbox.peekFirst();}
    private BehaviorEvent matchingEvent(BehaviorNode n){String expected=Objects.toString(n.options().getOrDefault("event",n.options().getOrDefault("name","")));for(BehaviorEvent event:inbox)if(event.type().equals(expected))return event;return null;}
    private static boolean isEventCondition(BehaviorNode n){return n.type().equals("condition")&&Objects.equals(String.valueOf(n.options().get("condition")),"event");}
    private void expireEvents(){long cutoff=environment.now()-eventTtlMillis;while(!inbox.isEmpty()&&inbox.peekFirst().occurredAt().toEpochMilli()<=cutoff)inbox.removeFirst();}
    private void record(BehaviorDefinition owner,BehaviorNode n,BehaviorStatus s,String detail){record(owner,n,s,Map.of(),null,detail);}
    private void record(BehaviorDefinition owner,BehaviorNode n,BehaviorStatus s,Map<String,Object> inputs,Object output,String detail){outcomes.addLast(new TraceEntry(environment.now(),stateKey(owner.id(),n.id()),s,inputs,output,detail));while(outcomes.size()>32)outcomes.removeFirst();}
    private Map<String,Object> safeInputs(BehaviorNode n,BehaviorEvent event){Map<String,Object> safe=new LinkedHashMap<>();n.options().forEach((k,v)->safe.put(k,mustRedact(k)?"<redacted>":safeValue(v)));if(event!=null){safe.put("event-id",event.id().toString());safe.put("event-type",event.type());}return Map.copyOf(safe);}
    private static boolean mustRedact(String key){String k=key.toLowerCase(Locale.ROOT);return k.contains("password")||k.contains("secret")||k.contains("token")||k.equals("value")||k.contains("memory");}
    private static Object safeValue(Object value){return value==null?"<null>":value instanceof Number||value instanceof Boolean?value:String.valueOf(value);}
    private void setRunning(String leaf){runningLeaf=leaf;List<String> path=new ArrayList<>(evaluationPath);if(path.isEmpty()||!path.getLast().equals(leaf))path.add(leaf);runningPath=List.copyOf(path);}
    private void clearRunning(String leaf){if(Objects.equals(runningLeaf,leaf)){runningLeaf=null;runningPath=List.of();}}
    private void proposeWake(long deadline){if(!wakeProposed||deadline<proposedWake){proposedWake=deadline;wakeProposed=true;}}

    private boolean validState(String key,Map<String,String> savedTypes,boolean unchangedTree){if(key==null)return false;NodeRef ref=findNode(key);if(ref==null)return false;String saved=savedTypes.get(baseKey(key));if(saved==null)saved=savedTypes.get(ref.behaviorId()+"::"+ref.node().id());return saved==null?unchangedTree:saved.equals(nodeKind(ref.node()));}
    private boolean isKind(String key,String kind){NodeRef ref=findNode(key);return ref!=null&&ref.node().type().equals(kind);}
    private boolean isLogicalTravel(String key){NodeRef ref=findNode(key);return ref!=null&&nodeKind(ref.node()).equals("action:logical-travel");}
    private String normalizeStateKey(String raw){
        if(raw==null)return null;
        int marker=raw.indexOf(PARALLEL_MARKER);
        if(marker>=0){String parent=normalizeNodeKey(raw.substring(0,marker));if(parent==null||!isKind(parent,"parallel"))return null;String child=raw.substring(marker+PARALLEL_MARKER.length());NodeRef p=findNode(parent);return p.node().children().stream().anyMatch(x->x.id().equals(child))?parallelKey(parent,child):null;}
        String direct=normalizeNodeKey(raw);if(direct!=null)return direct;
        // Version 4 stored parallel completion as "parent::child" without a behavior namespace.
        int split=raw.indexOf("::");if(split>0){String parentId=raw.substring(0,split),childId=raw.substring(split+2);List<NodeRef> matches=new ArrayList<>();for(BehaviorDefinition d:definitions.values()){BehaviorNode parent=d.nodes().get(parentId);if(parent!=null&&parent.type().equals("parallel")&&parent.children().stream().anyMatch(x->x.id().equals(childId)))matches.add(new NodeRef(d.id(),parent));}if(matches.size()==1)return parallelKey(matches.getFirst().key(),childId);}
        return null;
    }
    private String normalizeNodeKey(String raw){
        if(raw==null)return null;
        int split=raw.indexOf(STATE_SEPARATOR);if(split>0){BehaviorDefinition d=definitions.get(raw.substring(0,split));if(d!=null&&d.nodes().containsKey(raw.substring(split+1)))return raw;}
        for(BehaviorDefinition d:definitions.values())if(raw.startsWith(d.id()+"::")&&d.nodes().containsKey(raw.substring(d.id().length()+2)))return stateKey(d.id(),raw.substring(d.id().length()+2));
        List<NodeRef> matches=new ArrayList<>();for(BehaviorDefinition d:definitions.values()){BehaviorNode n=d.nodes().get(raw);if(n!=null)matches.add(new NodeRef(d.id(),n));}return matches.size()==1?matches.getFirst().key():null;
    }
    private NodeRef findNode(String stateKey){String key=baseKey(stateKey);if(key==null)return null;int split=key.indexOf(STATE_SEPARATOR);if(split<=0)return null;BehaviorDefinition d=definitions.get(key.substring(0,split));BehaviorNode n=d==null?null:d.nodes().get(key.substring(split+1));return n==null?null:new NodeRef(d.id(),n);}
    private String checkpointStructure(String key){NodeRef ref=findNode(key);if(ref==null||!ref.node().type().equals("checkpoint"))return null;BehaviorDefinition owner=definitions.get(ref.behaviorId());String path=owner==null?null:nodePath(owner.root(),ref.node().id(),"root");return sha256(Objects.toString(path,"")+"|"+structure(ref.behaviorId(),ref.node().child(),new HashSet<>()));}
    private String nodePath(BehaviorNode current,String target,String path){if(current.id().equals(target))return path+"/"+current.type();if(current.child()!=null){String found=nodePath(current.child(),target,path+"/child");if(found!=null)return found;}for(int i=0;i<current.children().size();i++){String found=nodePath(current.children().get(i),target,path+"/"+current.type()+"["+i+"]");if(found!=null)return found;}return null;}
    private String structure(String owner,BehaviorNode n,Set<String> visited){if(n==null)return "";StringBuilder value=new StringBuilder(owner).append("::").append(n.id()).append(':').append(nodeKind(n));if(n.child()!=null)value.append('(').append(structure(owner,n.child(),visited)).append(')');for(BehaviorNode child:n.children())value.append('[').append(structure(owner,child,visited)).append(']');if(n.type().equals("subtree")&&visited.add(n.subtree())){BehaviorDefinition subtree=definitions.get(n.subtree());if(subtree!=null)value.append('{').append(structure(subtree.id(),subtree.root(),visited)).append('}');}return value.toString();}
    private static String stateKey(String behaviorId,String nodeId){return behaviorId+STATE_SEPARATOR+nodeId;}
    private static String parallelKey(String parent,String child){return parent+PARALLEL_MARKER+child;}
    private static String baseKey(String key){if(key==null)return null;int marker=key.indexOf(PARALLEL_MARKER);return marker<0?key:key.substring(0,marker);}
    private static String nodeKind(BehaviorNode n){if(n.type().equals("action"))return "action:"+Objects.toString(n.options().get("action"),"");if(n.type().equals("condition"))return "condition:"+Objects.toString(n.options().get("condition"),"");return n.type();}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static long saturatedAdd(long left,long right){try{return Math.addExact(left,right);}catch(ArithmeticException e){return Long.MAX_VALUE;}}
    private static BehaviorStatus invert(BehaviorStatus s){return s==BehaviorStatus.RUNNING?s:s==BehaviorStatus.SUCCESS?BehaviorStatus.FAILURE:BehaviorStatus.SUCCESS;}
    private static int intOption(BehaviorNode n,String key,int fallback){Object v=n.options().get(key);return v==null?fallback:v instanceof Number x?x.intValue():Integer.parseInt(String.valueOf(v));}
    private static boolean boolOption(BehaviorNode n,String key,boolean fallback){Object v=n.options().get(key);return v==null?fallback:v instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(v));}
    private static Duration duration(BehaviorNode n){return Durations.parse(n.options().get("duration"));}
}
