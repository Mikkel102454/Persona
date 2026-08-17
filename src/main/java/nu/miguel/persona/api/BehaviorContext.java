package nu.miguel.persona.api;

import net.citizensnpcs.api.npc.NPC;
import nu.miguel.persona.behavior.BehaviorEvent;
import nu.miguel.persona.behavior.BehaviorScope;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Execution context for extension-provided behavior conditions and actions. */
public record BehaviorContext(BehaviorScope scope,Optional<Player> player,String npcDefinition,
                              String instance,Optional<NPC> actor,Optional<BehaviorEvent> event,
                              NpcMemoryService memories,Map<String,Object> blackboard) {
    public BehaviorContext { player=player==null?Optional.empty():player;actor=actor==null?Optional.empty():actor;event=event==null?Optional.empty():event; }
    public UUID playerId(){return player.map(Player::getUniqueId).orElse(null);}
    /** Typed access to durable extension state (persisted as part of this runtime). */
    public <T> T durable(String key,Class<T> type,T fallback){Object value=blackboard.get(key);return value==null?fallback:type.cast(value);}
    public void durable(String key,Object value){if(value==null)blackboard.remove(key);else blackboard.put(key,value);}
}
