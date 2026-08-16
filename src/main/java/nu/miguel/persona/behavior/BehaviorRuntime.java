package nu.miguel.persona.behavior;

import nu.miguel.persona.behavior.BehaviorDefinition.BehaviorNode;
import nu.miguel.persona.content.Durations;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Stateful, single-threaded interpreter. Durable fields use absolute deadlines. */
public final class BehaviorRuntime {
    public interface Environment {
        long now();
        boolean condition(BehaviorNode node,BehaviorEvent event);
        CompletionStage<BehaviorStatus> action(BehaviorNode node);
        default void cancel(BehaviorNode node){}
        default void wake(){}
    }
    public static final class Budget {
        private int remaining;private final long deadlineNanos;
        public Budget(int evaluations,long nanos){remaining=evaluations;deadlineNanos=System.nanoTime()+nanos;}
        private boolean exhausted;boolean take(){if(remaining<=0||System.nanoTime()>=deadlineNanos){exhausted=true;return false;}remaining--;return true;}
        public int remaining(){return remaining;}
        public boolean exhausted(){return exhausted;}
    }
    public record Snapshot(Map<String,Integer> progress,Map<String,Long> deadlines,Map<String,Object> blackboard,String anchor,boolean visible,String checkpoint) {}
    private record Outcome(long at,String node,BehaviorStatus status,String detail) {}
    private final BehaviorDefinition definition;private final Map<String,BehaviorDefinition> definitions;private final Environment environment;
    private final Map<String,Integer> progress=new HashMap<>();private final Map<String,Long> deadlines=new HashMap<>();private final Map<String,Object> blackboard=new HashMap<>();
    private final Map<String,CompletionStage<BehaviorStatus>> running=new HashMap<>();private final Deque<BehaviorEvent> inbox=new ArrayDeque<>();private final Deque<Outcome> outcomes=new ArrayDeque<>();
    private final int inboxLimit;private final long eventTtlMillis;private String runningLeaf,checkpoint,anchor;private boolean visible=true,cancelled;
    public BehaviorRuntime(BehaviorDefinition definition,Environment environment){this(definition,Map.of(definition.id(),definition),environment,64,30_000);}
    public BehaviorRuntime(BehaviorDefinition definition,Map<String,BehaviorDefinition> definitions,Environment environment,int inboxLimit,long eventTtlMillis){this.definition=Objects.requireNonNull(definition);this.definitions=Map.copyOf(definitions);this.environment=Objects.requireNonNull(environment);this.inboxLimit=inboxLimit;this.eventTtlMillis=eventTtlMillis;}
    public BehaviorStatus tick(Budget budget){if(cancelled)return BehaviorStatus.FAILURE;expireEvents();return evaluate(definition.root(),budget,currentEvent());}
    public void signal(BehaviorEvent event){expireEvents();while(inbox.size()>=inboxLimit)inbox.removeFirst();inbox.addLast(event);environment.wake();}
    public void cancel(){cancelled=true;if(runningLeaf!=null){BehaviorNode n=definition.nodes().get(runningLeaf);if(n!=null)environment.cancel(n);}running.clear();runningLeaf=null;}
    public void restartTransient(){if(runningLeaf!=null){BehaviorNode n=definition.nodes().get(runningLeaf);if(n!=null)environment.cancel(n);}running.clear();runningLeaf=null;}
    public Snapshot snapshot(){return new Snapshot(Map.copyOf(progress),Map.copyOf(deadlines),Map.copyOf(blackboard),anchor,visible,checkpoint);}
    public void restore(Snapshot s){progress.clear();deadlines.clear();blackboard.clear();if(s==null)return;s.progress().forEach((k,v)->{if(known(k.contains("::")?k.substring(k.indexOf("::")+2):k))progress.put(k,v);});s.deadlines().forEach((k,v)->{if(known(k))deadlines.put(k,v);});blackboard.putAll(s.blackboard());anchor=s.anchor();visible=s.visible();checkpoint=known(s.checkpoint())?s.checkpoint():null;if(checkpoint==null)progress.clear();restartTransient();}
    public Map<String,Object> blackboard(){return blackboard;}public String anchor(){return anchor;}public void anchor(String value){anchor=value;}public boolean visible(){return visible;}public void visible(boolean value){visible=value;}public String checkpoint(){return checkpoint;}public String runningLeaf(){return runningLeaf;}
    public List<String> recentOutcomes(){return outcomes.stream().map(x->x.node+"="+x.status+(x.detail==null?"":" ("+x.detail+")")).toList();}

    private BehaviorStatus evaluate(BehaviorNode n,Budget budget,BehaviorEvent event){if(!budget.take())return BehaviorStatus.RUNNING;return switch(n.type()){
        case "sequence"->composite(n,budget,event,true);
        case "selector"->composite(n,budget,event,false);
        case "priority-selector"->priority(n,budget,event);
        case "parallel"->parallel(n,budget,event);
        case "invert"->invert(evaluate(n.child(),budget,event));
        case "repeat"->repeat(n,budget,event,false);
        case "retry"->repeat(n,budget,event,true);
        case "timeout"->timeout(n,budget,event);
        case "cooldown"->cooldown(n,budget,event);
        case "checkpoint"->checkpoint(n,budget,event);
        case "wait"->waitNode(n);
        case "condition"->condition(n,event);
        case "action"->action(n);
        case "subtree"->{BehaviorDefinition subtree=definitions.get(n.subtree());yield subtree==null?BehaviorStatus.FAILURE:evaluate(subtree.root(),budget,event);}
        default->BehaviorStatus.FAILURE;
    };}
    private BehaviorStatus composite(BehaviorNode n,Budget b,BehaviorEvent e,boolean sequence){int i=progress.getOrDefault(n.id(),0);while(i<n.children().size()){BehaviorStatus s=evaluate(n.children().get(i),b,e);if(s==BehaviorStatus.RUNNING){progress.put(n.id(),i);return s;}if(sequence&&s==BehaviorStatus.FAILURE||!sequence&&s==BehaviorStatus.SUCCESS){progress.remove(n.id());record(n,s,null);return s;}i++;}progress.remove(n.id());BehaviorStatus done=sequence?BehaviorStatus.SUCCESS:BehaviorStatus.FAILURE;record(n,done,null);return done;}
    private BehaviorStatus priority(BehaviorNode n,Budget b,BehaviorEvent e){int previous=progress.getOrDefault(n.id(),-1);for(int i=0;i<n.children().size();i++){BehaviorStatus s=evaluate(n.children().get(i),b,e);if(b.exhausted())return BehaviorStatus.RUNNING;if(s!=BehaviorStatus.FAILURE){if(previous>=0&&previous!=i)cancelTree(n.children().get(previous));if(s==BehaviorStatus.RUNNING)progress.put(n.id(),i);else progress.remove(n.id());return s;}}progress.remove(n.id());return BehaviorStatus.FAILURE;}
    private BehaviorStatus parallel(BehaviorNode n,Budget b,BehaviorEvent e){int success=0,failure=0;for(BehaviorNode c:n.children()){String key=n.id()+"::"+c.id();int saved=progress.getOrDefault(key,0);BehaviorStatus s=saved==1?BehaviorStatus.SUCCESS:saved==2?BehaviorStatus.FAILURE:evaluate(c,b,e);if(b.exhausted())return BehaviorStatus.RUNNING;if(s==BehaviorStatus.SUCCESS){success++;progress.put(key,1);}else if(s==BehaviorStatus.FAILURE){failure++;progress.put(key,2);}}int st=intOption(n,"success-threshold",n.children().size()),ft=intOption(n,"failure-threshold",1);if(success>=st){finishParallel(n);return BehaviorStatus.SUCCESS;}if(failure>=ft){finishParallel(n);return BehaviorStatus.FAILURE;}return BehaviorStatus.RUNNING;}
    private void finishParallel(BehaviorNode n){cancelChildren(n);for(BehaviorNode c:n.children())progress.remove(n.id()+"::"+c.id());}
    private BehaviorStatus repeat(BehaviorNode n,Budget b,BehaviorEvent e,boolean retry){int done=progress.getOrDefault(n.id(),0),times=intOption(n,"times",1);BehaviorStatus s=evaluate(n.child(),b,e);if(s==BehaviorStatus.RUNNING)return s;boolean target=retry?s==BehaviorStatus.FAILURE:s==BehaviorStatus.SUCCESS;if(!target){progress.remove(n.id());return s;}done++;if(done>=times){progress.remove(n.id());return retry?BehaviorStatus.FAILURE:BehaviorStatus.SUCCESS;}progress.put(n.id(),done);clearTree(n.child());return BehaviorStatus.RUNNING;}
    private BehaviorStatus timeout(BehaviorNode n,Budget b,BehaviorEvent e){long now=environment.now(),end=deadlines.computeIfAbsent(n.id(),x->now+duration(n).toMillis());if(now>=end){cancelTree(n.child());deadlines.remove(n.id());return BehaviorStatus.FAILURE;}BehaviorStatus s=evaluate(n.child(),b,e);if(s!=BehaviorStatus.RUNNING)deadlines.remove(n.id());return s;}
    private BehaviorStatus cooldown(BehaviorNode n,Budget b,BehaviorEvent e){long now=environment.now(),end=deadlines.getOrDefault(n.id(),0L);if(now<end)return BehaviorStatus.FAILURE;BehaviorStatus s=evaluate(n.child(),b,e);if(s==BehaviorStatus.SUCCESS)deadlines.put(n.id(),now+duration(n).toMillis());return s;}
    private BehaviorStatus checkpoint(BehaviorNode n,Budget b,BehaviorEvent e){checkpoint=n.id();BehaviorStatus s=evaluate(n.child(),b,e);if(s!=BehaviorStatus.RUNNING&&Objects.equals(checkpoint,n.id()))checkpoint=null;return s;}
    private BehaviorStatus waitNode(BehaviorNode n){long now=environment.now(),end=deadlines.computeIfAbsent(n.id(),x->now+duration(n).toMillis());if(now<end){runningLeaf=n.id();return BehaviorStatus.RUNNING;}deadlines.remove(n.id());if(Objects.equals(runningLeaf,n.id()))runningLeaf=null;return BehaviorStatus.SUCCESS;}
    private BehaviorStatus condition(BehaviorNode n,BehaviorEvent e){boolean ok=environment.condition(n,e);BehaviorStatus s=ok?BehaviorStatus.SUCCESS:BehaviorStatus.FAILURE;record(n,s,ok?null:"condition false");return s;}
    private BehaviorStatus action(BehaviorNode n){CompletionStage<BehaviorStatus> future=running.get(n.id());if(future==null){future=environment.action(n);running.put(n.id(),future);runningLeaf=n.id();future.whenComplete((x,t)->environment.wake());}var cf=future.toCompletableFuture();if(!cf.isDone())return BehaviorStatus.RUNNING;running.remove(n.id());if(Objects.equals(runningLeaf,n.id()))runningLeaf=null;try{return cf.join();}catch(RuntimeException ex){record(n,BehaviorStatus.FAILURE,ex.getMessage());return BehaviorStatus.FAILURE;}}
    private void cancelChildren(BehaviorNode n){n.children().forEach(this::cancelTree);}private void cancelTree(BehaviorNode n){if(running.remove(n.id())!=null)environment.cancel(n);clearTree(n);}private void clearTree(BehaviorNode n){progress.remove(n.id());deadlines.remove(n.id());if(n.child()!=null)clearTree(n.child());n.children().forEach(this::clearTree);}
    private BehaviorEvent currentEvent(){return inbox.peekLast();}private void expireEvents(){long cutoff=environment.now()-eventTtlMillis;while(!inbox.isEmpty()&&inbox.peekFirst().occurredAt().toEpochMilli()<=cutoff)inbox.removeFirst();}
    private void record(BehaviorNode n,BehaviorStatus s,String detail){outcomes.addLast(new Outcome(environment.now(),n.id(),s,detail));while(outcomes.size()>32)outcomes.removeFirst();}
    private static BehaviorStatus invert(BehaviorStatus s){return s==BehaviorStatus.RUNNING?s:s==BehaviorStatus.SUCCESS?BehaviorStatus.FAILURE:BehaviorStatus.SUCCESS;}
    private static int intOption(BehaviorNode n,String key,int fallback){Object v=n.options().get(key);return v==null?fallback:v instanceof Number x?x.intValue():Integer.parseInt(String.valueOf(v));}
    private static Duration duration(BehaviorNode n){return Durations.parse(n.options().get("duration"));}
    private boolean known(String id){return id!=null&&definitions.values().stream().anyMatch(d->d.nodes().containsKey(id));}
}
