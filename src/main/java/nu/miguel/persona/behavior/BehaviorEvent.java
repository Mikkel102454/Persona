package nu.miguel.persona.behavior;

import java.time.Instant;
import java.util.Map;

public record BehaviorEvent(String type,Instant occurredAt,Map<String,Object> data) {
    public BehaviorEvent { data=Map.copyOf(data); }
    public static BehaviorEvent of(String type){return new BehaviorEvent(type,Instant.now(),Map.of());}
}
