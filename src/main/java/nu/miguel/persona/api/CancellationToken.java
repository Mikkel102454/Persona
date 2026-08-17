package nu.miguel.persona.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Thread-safe cancellation signal for a single extension action execution. */
public final class CancellationToken {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final List<Runnable> callbacks=new ArrayList<>();
    private final Consumer<Runnable> dispatcher;

    public CancellationToken(){this(Runnable::run);}
    /** Runtime constructor allowing Persona to preserve its server-thread guarantee. */
    public CancellationToken(Consumer<Runnable> dispatcher){this.dispatcher=java.util.Objects.requireNonNull(dispatcher,"dispatcher");}

    public boolean isCancelled(){return cancelled.get();}

    /**
     * Registers a callback which is invoked exactly once. If cancellation already
     * happened, it is handed to the token's dispatcher immediately.
     */
    public void onCancel(Runnable callback){
        if(callback==null)throw new NullPointerException("callback");
        synchronized(callbacks){
            if(!cancelled.get()){callbacks.add(callback);return;}
        }
        dispatcher.accept(callback);
    }

    /** Persona runtime entry point. Extensions should only observe the token. */
    public boolean cancel(){
        if(!cancelled.compareAndSet(false,true))return false;
        List<Runnable> pending;
        synchronized(callbacks){pending=List.copyOf(callbacks);callbacks.clear();}
        for(Runnable callback:pending)dispatcher.accept(callback);
        return true;
    }
}
