package nu.miguel.persona.api;

import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

/** Persona-owned lifecycle facilities, especially useful to standalone extensions. */
public interface ExpansionServices {
    void registerListener(Listener listener);
    BukkitTask runSync(Runnable task);
    BukkitTask runLater(Runnable task,long ticks);
    BukkitTask runAsync(Runnable task);
}
