package nu.miguel.persona.behavior;

import java.util.*;

/** Round-robin scheduler with a shared node/time budget. */
public final class BehaviorScheduler {
    private final List<BehaviorRuntime> runtimes=new ArrayList<>();private int cursor;
    public synchronized void add(BehaviorRuntime runtime){if(!runtimes.contains(runtime))runtimes.add(runtime);}
    public synchronized void remove(BehaviorRuntime runtime){int i=runtimes.indexOf(runtime);if(i>=0){runtimes.remove(i);runtime.cancel();if(cursor>i)cursor--;}}
    public synchronized int tick(int evaluations,long nanos){if(runtimes.isEmpty())return 0;BehaviorRuntime.Budget budget=new BehaviorRuntime.Budget(evaluations,nanos);int visited=0,limit=runtimes.size();while(visited<limit&&budget.remaining()>0){if(cursor>=runtimes.size())cursor=0;runtimes.get(cursor).tick(budget);cursor=(cursor+1)%runtimes.size();visited++;}return visited;}
    public synchronized List<BehaviorRuntime> runtimes(){return List.copyOf(runtimes);}
}
