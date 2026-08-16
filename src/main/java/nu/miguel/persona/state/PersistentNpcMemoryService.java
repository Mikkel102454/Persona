package nu.miguel.persona.state;

import nu.miguel.persona.api.NpcMemoryService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe write-behind memory cache. Expiry is enforced on reads, independently of DB cleanup. */
public final class PersistentNpcMemoryService implements NpcMemoryService,AutoCloseable {
    private record Key(UUID player,String npc,String instance,String key){String packed(){return (player==null?"global":"player")+'\0'+(player==null?"":player)+'\0'+npc+'\0'+instance+'\0'+key;}}
    private final SqliteStore store;
    private final Map<Key,Value> values=new ConcurrentHashMap<>();
    private final Set<Key> dirty=ConcurrentHashMap.newKeySet(),deleted=ConcurrentHashMap.newKeySet();
    public PersistentNpcMemoryService(SqliteStore store){this.store=store;for(SqliteStore.MemoryRow r:store.loadMemories().join()){Key k=new Key(r.player().isBlank()?null:UUID.fromString(r.player()),r.npcDefinition(),r.instance(),r.key());Value v=decode(r);if(!v.expired(Instant.now()))values.put(k,v);}}
    @Override public Optional<Value> get(UUID player,String npc,String instance,String key){Key k=key(player,npc,instance,key);Value v=values.get(k);if(v!=null&&v.expired(Instant.now())){values.remove(k,v);dirty.remove(k);deleted.add(k);v=null;}return Optional.ofNullable(v);}
    @Override public Map<String,Value> entries(UUID player,String npc,String instance){Map<String,Value> out=new TreeMap<>();for(var e:values.entrySet())if(Objects.equals(e.getKey().player,player)&&e.getKey().npc.equals(npc)&&e.getKey().instance.equals(normalize(instance)))get(player,npc,instance,e.getKey().key).ifPresent(v->out.put(e.getKey().key,v));return Map.copyOf(out);}
    @Override public Value set(UUID player,String npc,String instance,String key,Type type,Object raw,Duration ttl,String source){Key k=key(player,npc,instance,key);Object value=normalize(type,raw);Instant now=Instant.now();Value old=values.get(k);Value next=new Value(type,value,old==null?now:old.createdAt(),now,ttl==null?null:now.plus(ttl),source);values.put(k,next);deleted.remove(k);dirty.add(k);return next;}
    @Override public synchronized Value adjust(UUID player,String npc,String instance,String key,double delta,Duration ttl,String source){double current=get(player,npc,instance,key).map(v->{if(v.type()!=Type.NUMBER)throw new IllegalStateException("memory is not numeric");return v.numberValue();}).orElse(0d);return set(player,npc,instance,key,Type.NUMBER,current+delta,ttl,source);}
    @Override public boolean forget(UUID player,String npc,String instance,String key){Key k=key(player,npc,instance,key);dirty.remove(k);deleted.add(k);return values.remove(k)!=null;}
    public synchronized void flush(){if(dirty.isEmpty()&&deleted.isEmpty())return;Set<Key> writes=new HashSet<>(dirty),removes=new HashSet<>(deleted);dirty.removeAll(writes);deleted.removeAll(removes);List<SqliteStore.MemoryRow> rows=new ArrayList<>();for(Key k:writes){Value v=values.get(k);if(v!=null)rows.add(encode(k,v));}store.saveMemories(rows,removes.stream().map(Key::packed).collect(java.util.stream.Collectors.toSet())).join();}
    public void sweep(){Instant now=Instant.now();for(var e:values.entrySet())if(e.getValue().expired(now)){values.remove(e.getKey(),e.getValue());dirty.remove(e.getKey());deleted.add(e.getKey());}store.sweepExpiredMemories(now.toEpochMilli());}
    @Override public void close(){flush();}
    private static Key key(UUID player,String npc,String instance,String key){if(npc==null||npc.isBlank()||key==null||!key.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid memory identity");return new Key(player,npc,normalize(instance),key);}
    private static String normalize(String v){return v==null?"":v;}
    private static Object normalize(Type type,Object value){return switch(type){case BOOLEAN->value instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(value));case NUMBER->value instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(value));case STRING->String.valueOf(value);case TIMESTAMP->value instanceof Instant i?i:Instant.parse(String.valueOf(value));};}
    private static SqliteStore.MemoryRow encode(Key k,Value v){String raw=v.type()==Type.TIMESTAMP?((Instant)v.value()).toString():String.valueOf(v.value());return new SqliteStore.MemoryRow(k.player==null?"global":"player",k.player==null?"":k.player.toString(),k.npc,k.instance,k.key,v.type().name(),raw,v.createdAt().toEpochMilli(),v.updatedAt().toEpochMilli(),v.expiresAt()==null?null:v.expiresAt().toEpochMilli(),v.source());}
    private static Value decode(SqliteStore.MemoryRow r){Type t=Type.valueOf(r.type());Object v=normalize(t,r.value());return new Value(t,v,Instant.ofEpochMilli(r.createdAt()),Instant.ofEpochMilli(r.updatedAt()),r.expiresAt()==null?null:Instant.ofEpochMilli(r.expiresAt()),r.source());}
}
