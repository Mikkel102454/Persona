package nu.miguel.persona.behavior;

import nu.miguel.persona.behavior.BehaviorDefinition.BehaviorNode;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorRuntimeTest {
    @Test void memorySequenceResumesItsRunningChild(){BehaviorNode first=node("first","condition",Map.of()),action=node("async","action",Map.of()),last=node("last","condition",Map.of()),root=composite("root","sequence",first,action,last);CompletableFuture<BehaviorStatus> future=new CompletableFuture<>();Env env=new Env(future);BehaviorRuntime runtime=runtime(root,env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(List.of("first"),env.conditions);assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(List.of("first"),env.conditions);future.complete(BehaviorStatus.SUCCESS);assertEquals(BehaviorStatus.SUCCESS,tick(runtime));assertEquals(List.of("first","last"),env.conditions);}
    @Test void reactivePriorityInterruptsLowerBranch(){BehaviorNode highCondition=node("high-condition","condition",Map.of()),highAction=node("high-action","action",Map.of()),high=composite("high","sequence",highCondition,highAction),low=node("low","action",Map.of()),root=composite("root","priority-selector",high,low);CompletableFuture<BehaviorStatus> pending=new CompletableFuture<>();Env env=new Env(pending);env.condition=false;BehaviorRuntime runtime=runtime(root,env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));env.condition=true;env.next=CompletableFuture.completedFuture(BehaviorStatus.SUCCESS);assertEquals(BehaviorStatus.SUCCESS,tick(runtime));assertTrue(env.cancelled.contains("low"));}
    @Test void parallelDoesNotRepeatCompletedChildren(){BehaviorNode done=node("done","action",Map.of()),pending=node("pending","action",Map.of()),root=new BehaviorNode("root","parallel",List.of(done,pending),null,null,Map.of("success-threshold",2,"failure-threshold",1));Env env=new Env(CompletableFuture.completedFuture(BehaviorStatus.SUCCESS));env.byId.put("pending",new CompletableFuture<>());BehaviorRuntime runtime=runtime(root,env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(1,env.calls.get("done"));assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(1,env.calls.get("done"));env.byId.get("pending").complete(BehaviorStatus.SUCCESS);assertEquals(BehaviorStatus.SUCCESS,tick(runtime));}

    @Test void checkpointSnapshotRestoresAbsoluteWait(){
        BehaviorNode wait=node("wait","wait",Map.of("duration","1s")),root=decorator("save","checkpoint",wait);
        Env env=new Env(null);BehaviorRuntime original=runtime(root,env);
        assertEquals(BehaviorStatus.RUNNING,tick(original));
        BehaviorRuntime.Snapshot snapshot=original.snapshot();
        assertEquals("test:tree/save",snapshot.checkpoint());assertEquals(1100,snapshot.wakeAt());
        env.now=1500;BehaviorRuntime restored=runtime(root,env);restored.restore(snapshot);
        assertEquals(BehaviorStatus.SUCCESS,tick(restored));
    }

    @Test void restoredRuntimeSleepsUntilItsPersistedWakeTime(){
        BehaviorNode root=node("wait","wait",Map.of("duration","1s"));Env env=new Env(null);BehaviorRuntime original=runtime(root,env);tick(original);
        BehaviorRuntime restored=runtime(root,env);restored.restore(original.snapshot());BehaviorScheduler scheduler=new BehaviorScheduler();scheduler.add(restored);
        assertEquals(0,scheduler.tick(100,1_000_000_000));
        env.now=1100;assertEquals(1,scheduler.tick(100,1_000_000_000));
    }

    @Test void subtreeNodeStateIsNamespacedByBehaviorId(){
        BehaviorNode sharedIdA=node("same","wait",Map.of("duration","1s")),sharedIdB=node("same","wait",Map.of("duration","2s"));
        BehaviorDefinition a=definition("demo:a",sharedIdA,"a"),b=definition("demo:b",sharedIdB,"b");
        BehaviorNode callA=new BehaviorNode("call-a","subtree",List.of(),null,"demo:a",Map.of()),callB=new BehaviorNode("call-b","subtree",List.of(),null,"demo:b",Map.of());
        BehaviorNode root=new BehaviorNode("root","parallel",List.of(callA,callB),null,null,Map.of("success-threshold",2,"failure-threshold",1));BehaviorDefinition main=definition("demo:main",root,"main");
        BehaviorRuntime runtime=new BehaviorRuntime(main,Map.of(main.id(),main,a.id(),a,b.id(),b),new Env(null),64,30_000);tick(runtime);
        assertEquals(Set.of("demo:a/same","demo:b/same"),runtime.snapshot().deadlines().keySet());
    }

    @Test void typeChangedAndRemovedNodeStateIsDiscarded(){
        BehaviorNode oldWait=node("durable","wait",Map.of("duration","1s"));BehaviorDefinition oldDefinition=definition("test:tree",oldWait,"old");Env env=new Env(null);BehaviorRuntime oldRuntime=new BehaviorRuntime(oldDefinition,env);tick(oldRuntime);BehaviorRuntime.Snapshot snapshot=oldRuntime.snapshot();
        BehaviorNode replacement=node("durable","action",Map.of("action","look"));BehaviorDefinition changed=definition("test:tree",replacement,"changed");BehaviorRuntime changedRuntime=new BehaviorRuntime(changed,env);changedRuntime.restore(snapshot);assertTrue(changedRuntime.snapshot().deadlines().isEmpty());assertTrue(changedRuntime.due());
        BehaviorNode other=node("other","wait",Map.of("duration","1s"));BehaviorDefinition removed=definition("test:tree",other,"removed");BehaviorRuntime removedRuntime=new BehaviorRuntime(removed,env);removedRuntime.restore(snapshot);assertTrue(removedRuntime.snapshot().deadlines().isEmpty());
    }

    @Test void changedCheckpointChildrenRestartOnlyRuntimeNodeState(){
        BehaviorNode oldChild=composite("sequence","sequence",node("wait","wait",Map.of("duration","1s")),node("after","condition",Map.of()));BehaviorDefinition oldDefinition=definition("test:tree",decorator("save","checkpoint",oldChild),"old");Env env=new Env(null);BehaviorRuntime oldRuntime=new BehaviorRuntime(oldDefinition,env);oldRuntime.blackboard().put("author-data","retained");tick(oldRuntime);BehaviorRuntime.Snapshot snapshot=oldRuntime.snapshot();
        BehaviorNode newChild=composite("sequence","sequence",node("wait","wait",Map.of("duration","1s")),node("inserted","condition",Map.of()),node("after","condition",Map.of()));BehaviorDefinition changed=definition("test:tree",decorator("save","checkpoint",newChild),"changed");BehaviorRuntime restored=new BehaviorRuntime(changed,env);restored.restore(snapshot);BehaviorRuntime.Snapshot migrated=restored.snapshot();
        assertNull(migrated.checkpoint());assertTrue(migrated.progress().isEmpty());assertTrue(migrated.deadlines().isEmpty());assertEquals("retained",migrated.blackboard().get("author-data"));
    }

    @Test void logicalTravelIsTypedAndContributesItsAbsoluteWake(){
        BehaviorNode root=node("travel","action",Map.of("action","logical-travel"));BehaviorDefinition definition=definition("test:tree",root,"hash");Env env=new Env(new CompletableFuture<>());BehaviorRuntime runtime=new BehaviorRuntime(definition,env);BehaviorRuntime.LogicalTravel travel=new BehaviorRuntime.LogicalTravel("test:tree","travel","home","market",100,900);runtime.logicalTravel(travel);
        BehaviorRuntime.Snapshot snapshot=runtime.snapshot();assertEquals(travel,snapshot.logicalTravel());assertEquals(1000,snapshot.wakeAt());assertFalse(snapshot.blackboard().containsKey("travel.started"));
        BehaviorRuntime restored=new BehaviorRuntime(definition,env);restored.restore(snapshot);assertEquals(travel,restored.logicalTravel());assertFalse(restored.due());env.now=1000;assertTrue(restored.due());
    }

    @Test void transientActionRestartsImmediatelyWhileCheckpointRemainsValid(){
        BehaviorNode root=decorator("save","checkpoint",node("async","action",Map.of("action","look")));Env originalEnv=new Env(new CompletableFuture<>());BehaviorRuntime original=runtime(root,originalEnv);assertEquals(BehaviorStatus.RUNNING,tick(original));BehaviorRuntime.Snapshot snapshot=original.snapshot();assertEquals(Long.MAX_VALUE,snapshot.wakeAt());
        Env restoredEnv=new Env(new CompletableFuture<>());BehaviorRuntime restored=runtime(root,restoredEnv);restored.restore(snapshot);assertTrue(restored.due());assertEquals("test:tree/save",restored.checkpoint());assertEquals(BehaviorStatus.RUNNING,tick(restored));assertEquals(1,restoredEnv.calls.get("async"));
    }

    @Test void cooldownDeadlineSurvivesRestart(){
        BehaviorNode child=node("done","action",Map.of("action","look")),root=new BehaviorNode("cool","cooldown",List.of(),child,null,Map.of("duration","1s"));Env env=new Env(CompletableFuture.completedFuture(BehaviorStatus.SUCCESS));BehaviorRuntime original=runtime(root,env);assertEquals(BehaviorStatus.SUCCESS,tick(original));BehaviorRuntime.Snapshot snapshot=original.snapshot();assertEquals(1100,snapshot.deadlines().get("test:tree/cool"));
        env.now=500;BehaviorRuntime restored=runtime(root,env);restored.restore(snapshot);assertEquals(BehaviorStatus.FAILURE,tick(restored));assertEquals(1,env.calls.get("done"));env.now=1100;assertEquals(BehaviorStatus.SUCCESS,tick(restored));assertEquals(2,env.calls.get("done"));
    }

    @Test void cancelStopsEveryParallelActionAndIgnoresOldCompletions(){BehaviorNode one=node("one","action",Map.of()),two=node("two","action",Map.of()),root=new BehaviorNode("root","parallel",List.of(one,two),null,null,Map.of("success-threshold",2,"failure-threshold",1));Env env=new Env(null);env.byId.put("one",new CompletableFuture<>());env.byId.put("two",new CompletableFuture<>());BehaviorRuntime runtime=runtime(root,env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));runtime.cancel();assertEquals(Set.of("one","two"),new HashSet<>(env.cancelled));env.byId.get("one").complete(BehaviorStatus.SUCCESS);env.byId.get("two").complete(BehaviorStatus.SUCCESS);assertEquals(0,env.wakes);}
    @Test void snapshotKeepsLogicalPositionSeparateFromAnchor(){BehaviorNode root=node("wait","wait",Map.of("duration","1s"));BehaviorRuntime runtime=runtime(root,new Env(null));var position=new BehaviorRuntime.LogicalPosition("world",1,2,3,4,5);runtime.anchor("destination");runtime.position(position);assertEquals("destination",runtime.snapshot().anchor());assertEquals(position,runtime.snapshot().position());}

    @Test void consumableEventTriggersOnlyOnceAndUsesStableId(){BehaviorNode root=node("event","condition",Map.of("condition","event","event","interaction"));Env env=new Env(null);BehaviorRuntime runtime=runtime(root,env);BehaviorEvent event=new BehaviorEvent("interaction",Instant.ofEpochMilli(100),Map.of());runtime.signal(event);assertEquals(event.id(),runtime.inbox().getFirst().id());assertEquals(BehaviorStatus.SUCCESS,tick(runtime));assertTrue(runtime.inbox().isEmpty());assertEquals(BehaviorStatus.FAILURE,tick(runtime));}
    @Test void consumeFalseAndObserveOnlyEventsRemainAvailable(){BehaviorNode observe=node("event","condition",Map.of("condition","event","event","interaction","consume",false));BehaviorRuntime first=runtime(observe,new Env(null));first.signal(BehaviorEvent.of("interaction"));assertEquals(BehaviorStatus.SUCCESS,tick(first));assertEquals(1,first.inbox().size());BehaviorNode consume=node("event","condition",Map.of("condition","event","event","interaction"));BehaviorRuntime second=runtime(consume,new Env(null));second.signal(BehaviorEvent.observe("interaction",Map.of()));assertEquals(BehaviorStatus.SUCCESS,tick(second));assertEquals(1,second.inbox().size());}
    @Test void eventMatchingIsOldestRelevantAndReportsDrops(){BehaviorNode root=node("event","condition",Map.of("condition","event","event","wanted"));BehaviorDefinition d=definition("test:tree",root,"hash");BehaviorRuntime runtime=new BehaviorRuntime(d,Map.of(d.id(),d),new Env(null),2,30_000);runtime.signal(BehaviorEvent.of("unrelated"));BehaviorEvent wanted=BehaviorEvent.of("wanted");runtime.signal(wanted);runtime.signal(BehaviorEvent.of("later"));assertEquals(1,runtime.droppedEvents());assertEquals(BehaviorStatus.SUCCESS,tick(runtime));assertFalse(runtime.inbox().stream().anyMatch(e->e.id().equals(wanted.id())));}
    @Test void infiniteRepeatYieldsEveryImmediateIteration(){BehaviorNode root=new BehaviorNode("forever","repeat",List.of(),node("done","condition",Map.of()),null,Map.of("forever",true));Env env=new Env(null);BehaviorRuntime runtime=runtime(root,env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(1,env.conditions.size());assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(2,env.conditions.size());}
    @Test void parallelSuccessWinsWhenBothThresholdsArriveTogether(){BehaviorNode yes=node("yes","action",Map.of()),no=node("no","action",Map.of()),root=new BehaviorNode("root","parallel",List.of(yes,no),null,null,Map.of("success-threshold",1,"failure-threshold",1));Env env=new Env(null);env.byId.put("yes",CompletableFuture.completedFuture(BehaviorStatus.SUCCESS));env.byId.put("no",CompletableFuture.completedFuture(BehaviorStatus.FAILURE));assertEquals(BehaviorStatus.SUCCESS,tick(runtime(root,env)));}
    @Test void parallelCancellationPolicyCanKeepRunningChildren(){BehaviorNode done=node("done","action",Map.of()),pending=node("pending","action",Map.of()),root=new BehaviorNode("root","parallel",List.of(done,pending),null,null,Map.of("success-threshold",1,"failure-threshold",2,"cancel-remaining","never"));Env env=new Env(null);env.byId.put("done",CompletableFuture.completedFuture(BehaviorStatus.SUCCESS));env.byId.put("pending",new CompletableFuture<>());assertEquals(BehaviorStatus.SUCCESS,tick(runtime(root,env)));assertFalse(env.cancelled.contains("pending"));}
    @Test void fullRunningPathIncludesCompositeSubtreeAndLeaf(){BehaviorNode leaf=node("leaf","action",Map.of()),subRoot=decorator("sub-root","checkpoint",leaf);BehaviorDefinition sub=definition("test:sub",subRoot,"sub");BehaviorNode call=new BehaviorNode("call","subtree",List.of(),null,"test:sub",Map.of()),root=composite("root","sequence",call);BehaviorDefinition main=definition("test:tree",root,"main");BehaviorRuntime runtime=new BehaviorRuntime(main,Map.of(main.id(),main,sub.id(),sub),new Env(new CompletableFuture<>()),64,30_000);assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(List.of("test:tree/root","test:tree/call","test:sub/sub-root","test:sub/leaf"),runtime.runningPath());}
    @Test void traceRedactsConditionValuesAndIncludesEventIdentity(){BehaviorNode root=node("secret","condition",Map.of("condition","event","event","signal:test","value","do-not-leak"));BehaviorRuntime runtime=runtime(root,new Env(null));BehaviorEvent event=BehaviorEvent.of("signal:test");runtime.signal(event);tick(runtime);BehaviorRuntime.TraceEntry trace=runtime.traceHistory().getLast();assertEquals("<redacted>",trace.inputs().get("value"));assertEquals(event.id().toString(),trace.inputs().get("event-id"));assertEquals(true,trace.output());}
    @Test void exhaustedBudgetDoesNotInterruptReactiveRunningBranch(){BehaviorNode high=node("high","condition",Map.of()),low=node("low","action",Map.of()),root=composite("root","priority-selector",high,low);Env env=new Env(new CompletableFuture<>());env.condition=false;BehaviorRuntime runtime=runtime(root,env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));env.condition=true;BehaviorRuntime.Budget exhaustedBeforeChild=new BehaviorRuntime.Budget(1,1_000_000_000);assertEquals(BehaviorStatus.RUNNING,runtime.tick(exhaustedBeforeChild));assertFalse(env.cancelled.contains("low"));}
    @Test void selectorInvertRetryAndTimeoutHaveDefinedTerminalSemantics(){Env selectorEnv=new Env(CompletableFuture.completedFuture(BehaviorStatus.SUCCESS));selectorEnv.condition=false;BehaviorNode selector=composite("selector","selector",node("no","condition",Map.of()),node("yes","action",Map.of()));assertEquals(BehaviorStatus.SUCCESS,tick(runtime(selector,selectorEnv)));BehaviorNode invert=decorator("invert","invert",node("false","condition",Map.of()));assertEquals(BehaviorStatus.SUCCESS,tick(runtime(invert,selectorEnv)));BehaviorNode retry=new BehaviorNode("retry","retry",List.of(),node("failure","condition",Map.of()),null,Map.of("times",2));BehaviorRuntime retries=runtime(retry,selectorEnv);assertEquals(BehaviorStatus.RUNNING,tick(retries));assertEquals(BehaviorStatus.FAILURE,tick(retries));CompletableFuture<BehaviorStatus> pending=new CompletableFuture<>();Env timeoutEnv=new Env(pending);BehaviorNode timeout=new BehaviorNode("timeout","timeout",List.of(),node("pending","action",Map.of()),null,Map.of("duration","1s"));BehaviorRuntime timed=runtime(timeout,timeoutEnv);assertEquals(BehaviorStatus.RUNNING,tick(timed));timeoutEnv.now=1100;assertEquals(BehaviorStatus.FAILURE,tick(timed));assertTrue(timeoutEnv.cancelled.contains("pending"));}
    @Test void everyCompositeDecoratorCombinationHasTerminalSemantics(){for(String composite:List.of("sequence","selector","priority-selector","parallel"))for(String decorator:List.of("invert","repeat","retry","timeout","cooldown","checkpoint")){BehaviorNode child=node("leaf","condition",Map.of());Map<String,Object> options=switch(decorator){case "repeat","retry"->Map.of("times",1);case "timeout","cooldown"->Map.of("duration","1s");default->Map.of();};BehaviorNode wrapped=new BehaviorNode("decorator",decorator,List.of(),child,null,options);Map<String,Object> compositeOptions=composite.equals("parallel")?Map.of("success-threshold",1,"failure-threshold",1):Map.of();BehaviorNode root=new BehaviorNode("root",composite,List.of(wrapped),null,null,compositeOptions);BehaviorStatus result=tick(runtime(root,new Env(null)));assertNotEquals(BehaviorStatus.RUNNING,result,composite+" + "+decorator);}}

    @Test void pauseResumeAndRestartControlRuntime(){CompletableFuture<BehaviorStatus> first=new CompletableFuture<>();Env env=new Env(first);BehaviorRuntime runtime=runtime(node("work","action",Map.of()),env);assertEquals(BehaviorStatus.RUNNING,tick(runtime));runtime.pause();assertTrue(runtime.paused());assertEquals(List.of("work"),env.cancelled);assertEquals(BehaviorStatus.RUNNING,tick(runtime));assertEquals(1,env.calls.get("work"));runtime.resume();assertFalse(runtime.paused());env.next=CompletableFuture.completedFuture(BehaviorStatus.SUCCESS);assertEquals(BehaviorStatus.SUCCESS,tick(runtime));runtime.blackboard().put("extension:durable",3);runtime.signal(BehaviorEvent.of("queued"));runtime.restart();assertTrue(runtime.blackboard().isEmpty());assertTrue(runtime.inbox().isEmpty());assertTrue(runtime.deadlines().isEmpty());}
    @Test void selectorTraceNamesEveryFailedBranch(){Env env=new Env(null);env.condition=false;BehaviorRuntime runtime=runtime(composite("choose","selector",node("one","condition",Map.of()),node("two","condition",Map.of())),env);assertEquals(BehaviorStatus.FAILURE,tick(runtime));assertEquals("branches failed: one, two",runtime.traceHistory().getLast().detail());}

    private static BehaviorRuntime runtime(BehaviorNode root,Env env){return new BehaviorRuntime(definition("test:tree",root,"hash"),env);}
    private static BehaviorDefinition definition(String id,BehaviorNode root,String hash){Map<String,BehaviorNode> nodes=new HashMap<>();collect(root,nodes);return new BehaviorDefinition(id,BehaviorScope.PLAYER,root,nodes,hash);}
    private static BehaviorStatus tick(BehaviorRuntime r){return r.tick(new BehaviorRuntime.Budget(100,1_000_000_000));}
    private static BehaviorNode node(String id,String type,Map<String,Object> options){return new BehaviorNode(id,type,List.of(),null,null,options);}
    private static BehaviorNode composite(String id,String type,BehaviorNode...children){return new BehaviorNode(id,type,List.of(children),null,null,Map.of());}
    private static BehaviorNode decorator(String id,String type,BehaviorNode child){return new BehaviorNode(id,type,List.of(),child,null,Map.of());}
    private static void collect(BehaviorNode n,Map<String,BehaviorNode> out){out.put(n.id(),n);if(n.child()!=null)collect(n.child(),out);n.children().forEach(x->collect(x,out));}

    private static final class Env implements BehaviorRuntime.Environment {
        long now=100;int wakes;boolean condition=true;CompletableFuture<BehaviorStatus> next;
        final Map<String,CompletableFuture<BehaviorStatus>> byId=new HashMap<>();final Map<String,Integer> calls=new HashMap<>();final List<String> conditions=new ArrayList<>(),cancelled=new ArrayList<>();
        Env(CompletableFuture<BehaviorStatus> next){this.next=next;}
        public long now(){return now;}
        public boolean condition(BehaviorNode n,BehaviorEvent e){conditions.add(n.id());return condition;}
        public java.util.concurrent.CompletionStage<BehaviorStatus> action(BehaviorNode n){calls.merge(n.id(),1,Integer::sum);return byId.getOrDefault(n.id(),next);}
        public void cancel(BehaviorNode n){cancelled.add(n.id());}
        public void wake(){wakes++;}
    }
}
