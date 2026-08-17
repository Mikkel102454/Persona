package nu.miguel.persona.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Typed memory shared by behavior trees and extensions. Null player denotes global NPC memory. */
public interface NpcMemoryService {
    enum Type { BOOLEAN, NUMBER, STRING, TIMESTAMP }
    enum Scope { GLOBAL, PLAYER }
    record Value(Type type,Object value,Instant createdAt,Instant updatedAt,Instant expiresAt,String source) {
        public Value { if(type==null||value==null)throw new IllegalArgumentException("type and value are required"); }
        public boolean expired(Instant now){return expiresAt!=null&&!expiresAt.isAfter(now);}
        public boolean booleanValue(){return (Boolean)value;}
        public double numberValue(){return ((Number)value).doubleValue();}
        public String stringValue(){return (String)value;}
        public Instant timestampValue(){return (Instant)value;}
    }
    /** A fully qualified memory entry, suitable for migration and diagnostics. */
    record Entry(UUID player,String npcDefinition,String instance,String key,Value value) {
        public Scope scope(){return player==null?Scope.GLOBAL:Scope.PLAYER;}
    }
    /** Result of an atomic conditional mutation. */
    record Mutation(boolean applied,Value value) {}
    Optional<Value> get(UUID player,String npcDefinition,String instance,String key);
    Map<String,Value> entries(UUID player,String npcDefinition,String instance);
    List<Entry> entries();
    Value set(UUID player,String npcDefinition,String instance,String key,Type type,Object value,Duration ttl,String source);
    Value adjust(UUID player,String npcDefinition,String instance,String key,double delta,Duration ttl,String source);
    Value adjust(UUID player,String npcDefinition,String instance,String key,double delta,double minimum,double maximum,Duration ttl,String source);
    Mutation compareAndSet(UUID player,String npcDefinition,String instance,String key,Object expected,Type type,Object value,Duration ttl,String source);
    boolean expire(UUID player,String npcDefinition,String instance,String key,Instant expiresAt,String source);
    boolean forget(UUID player,String npcDefinition,String instance,String key);
}
