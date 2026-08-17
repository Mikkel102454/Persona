package nu.miguel.persona.api.event;

import nu.miguel.persona.api.NpcMemoryService;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired on the server thread after a Persona memory is changed or expires. */
public final class NpcMemoryChangeEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final UUID player;
    private final String npcDefinition,instance,key,source;
    private final NpcMemoryService.Value oldValue,newValue;

    public NpcMemoryChangeEvent(UUID player,String npcDefinition,String instance,String key,
                                NpcMemoryService.Value oldValue,NpcMemoryService.Value newValue,String source){
        this.player=player;this.npcDefinition=npcDefinition;this.instance=instance;this.key=key;
        this.oldValue=oldValue;this.newValue=newValue;this.source=source;
    }
    public NpcMemoryService.Scope scope(){return player==null?NpcMemoryService.Scope.GLOBAL:NpcMemoryService.Scope.PLAYER;}
    public UUID player(){return player;}
    public String npcDefinition(){return npcDefinition;}
    public String instance(){return instance;}
    public String key(){return key;}
    public NpcMemoryService.Value oldValue(){return oldValue;}
    public NpcMemoryService.Value newValue(){return newValue;}
    public String source(){return source;}
    @Override public @NotNull HandlerList getHandlers(){return HANDLERS;}
    public static @NotNull HandlerList getHandlerList(){return HANDLERS;}
}
