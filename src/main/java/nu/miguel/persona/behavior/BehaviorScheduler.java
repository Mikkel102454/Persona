package nu.miguel.persona.behavior;

import java.util.*;

/** Round-robin scheduler with a shared node/time budget and durable sleeping. */
public final class BehaviorScheduler {
    private final List<BehaviorRuntime> runtimes=new ArrayList<>();private final Deque<BehaviorRuntime> woken=new ArrayDeque<>();private int cursor;
    public synchronized void add(BehaviorRuntime runtime){if(!runtimes.contains(runtime))runtimes.add(runtime);}
    public synchronized void wake(BehaviorRuntime runtime){if(runtimes.contains(runtime)&&!woken.contains(runtime))woken.addLast(runtime);}
    public synchronized void remove(BehaviorRuntime runtime){int i=runtimes.indexOf(runtime);woken.remove(runtime);if(i>=0){runtimes.remove(i);runtime.cancel();if(cursor>i)cursor--;}}
    public synchronized int tick(int evaluations,long nanos){if(runtimes.isEmpty())return 0;BehaviorRuntime.Budget budget=new BehaviorRuntime.Budget(evaluations,nanos);int visited=0;Set<BehaviorRuntime> evaluated=new HashSet<>();while(!woken.isEmpty()&&budget.remaining()>0){BehaviorRuntime runtime=woken.removeFirst();if(runtimes.contains(runtime)&&evaluated.add(runtime)){runtime.tick(budget);visited++;}}int considered=0,limit=runtimes.size();while(considered<limit&&budget.remaining()>0){if(cursor>=runtimes.size())cursor=0;BehaviorRuntime runtime=runtimes.get(cursor);cursor=(cursor+1)%runtimes.size();considered++;if(evaluated.contains(runtime)||!runtime.due())continue;evaluated.add(runtime);runtime.tick(budget);visited++;}return visited;}
    public synchronized List<BehaviorRuntime> runtimes(){return List.copyOf(runtimes);}
}
