package nu.miguel.persona.api;

import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Persona-owned lifecycle facilities, especially useful to standalone extensions. */
public interface ExpansionServices {
    void registerListener(Listener listener);
    BukkitTask runSync(Runnable task);
    BukkitTask runLater(Runnable task,long ticks);
    BukkitTask runAsync(Runnable task);
    /** Completes on Persona's server thread, even when called by an async worker. */
    default <T> CompletionStage<T> completeSync(Supplier<T> completion){
        CompletableFuture<T> result=new CompletableFuture<>();
        runSync(()->{try{result.complete(completion.get());}catch(Throwable error){result.completeExceptionally(error);}});
        return result;
    }
    default CompletionStage<Void> completeSync(Runnable completion){return completeSync(()->{completion.run();return null;});}
}
