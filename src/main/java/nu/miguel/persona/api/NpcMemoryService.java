package nu.miguel.persona.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Typed memory shared by behavior trees and extensions. Null player denotes global NPC memory. */
public interface NpcMemoryService {
    enum Type { BOOLEAN, NUMBER, STRING, TIMESTAMP }
    record Value(Type type,Object value,Instant createdAt,Instant updatedAt,Instant expiresAt,String source) {
        public Value { if(type==null||value==null)throw new IllegalArgumentException("type and value are required"); }
        public boolean expired(Instant now){return expiresAt!=null&&!expiresAt.isAfter(now);}
        public boolean booleanValue(){return (Boolean)value;}
        public double numberValue(){return ((Number)value).doubleValue();}
        public String stringValue(){return (String)value;}
        public Instant timestampValue(){return (Instant)value;}
    }
    Optional<Value> get(UUID player,String npcDefinition,String instance,String key);
    Map<String,Value> entries(UUID player,String npcDefinition,String instance);
    Value set(UUID player,String npcDefinition,String instance,String key,Type type,Object value,Duration ttl,String source);
    Value adjust(UUID player,String npcDefinition,String instance,String key,double delta,Duration ttl,String source);
    boolean forget(UUID player,String npcDefinition,String instance,String key);
}
