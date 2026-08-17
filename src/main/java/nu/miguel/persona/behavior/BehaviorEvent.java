package nu.miguel.persona.behavior;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An immutable inbox item. IDs remain stable while an event is queued or traced. */
public record BehaviorEvent(UUID id,String type,Instant occurredAt,ConsumptionPolicy policy,Map<String,Object> data) {
    public enum ConsumptionPolicy { CONSUMABLE, OBSERVE_ONLY }
    public BehaviorEvent {
        Objects.requireNonNull(id,"id");Objects.requireNonNull(type,"type");Objects.requireNonNull(occurredAt,"occurredAt");Objects.requireNonNull(policy,"policy");
        data=Map.copyOf(data);
    }
    /** Compatibility constructor: ordinary runtime events are consumable. */
    public BehaviorEvent(String type,Instant occurredAt,Map<String,Object> data){this(UUID.randomUUID(),type,occurredAt,ConsumptionPolicy.CONSUMABLE,data);}
    public static BehaviorEvent of(String type){return new BehaviorEvent(type,Instant.now(),Map.of());}
    public static BehaviorEvent observe(String type,Map<String,Object> data){return new BehaviorEvent(UUID.randomUUID(),type,Instant.now(),ConsumptionPolicy.OBSERVE_ONLY,data);}
}
