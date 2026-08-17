package nu.miguel.persona.state;

import nu.miguel.persona.api.NpcMemoryService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe write-behind memory cache. Expiry is enforced on reads, independently of DB cleanup. */
public final class PersistentNpcMemoryService implements NpcMemoryService,AutoCloseable {
    public record Change(UUID player,String npc,String instance,String key,Value oldValue,Value newValue,String source) {}
    public record SweepMetrics(long runs,long rowsRemoved,long lastRowsRemoved,long lastRunEpochMillis) {}
    private record Key(UUID player,String npc,String instance,String key){
        String packed(){return (player==null?"global":"player")+'\0'+(player==null?"":player)+'\0'+npc+'\0'+instance+'\0'+key;}
    }

    private final SqliteStore store;
    private final Map<Key,Value> values=new ConcurrentHashMap<>();
    private final Map<Key,Value> expiredWrites=new ConcurrentHashMap<>();
    private final Set<Key> dirty=ConcurrentHashMap.newKeySet(),deleted=ConcurrentHashMap.newKeySet();
    private final Map<String,String> namespaceOwners=new ConcurrentHashMap<>();
    private final AtomicLong sweepRuns=new AtomicLong(),sweptRows=new AtomicLong();
    private volatile long lastSweepRows,lastSweepAt;
    private volatile Duration expiredRetention=Duration.ZERO;
    private volatile Consumer<Change> changeListener=change->{};

    public PersistentNpcMemoryService(SqliteStore store){
        this.store=store;
        for(SqliteStore.MemoryRow r:store.loadMemories().join()){
            Key k=new Key(r.player().isBlank()?null:UUID.fromString(r.player()),r.npcDefinition(),r.instance(),r.key());
            Value v=decode(r);if(!v.expired(Instant.now()))values.put(k,v);
        }
    }

    public void expiredRetention(Duration retention){expiredRetention=retention==null||retention.isNegative()?Duration.ZERO:retention;}
    public SweepMetrics sweepMetrics(){return new SweepMetrics(sweepRuns.get(),sweptRows.get(),lastSweepRows,lastSweepAt);}
    public void claimNamespace(String namespace,String owner){
        String n=namespace(namespace),o=namespace(owner);String previous=namespaceOwners.putIfAbsent(n,o);
        if(previous!=null&&!previous.equals(o))throw new IllegalArgumentException("memory namespace "+n+" is owned by "+previous);
    }
    public void releaseNamespaces(String owner){String o=namespace(owner);namespaceOwners.entrySet().removeIf(e->e.getValue().equals(o));}
    public Map<String,String> namespaceOwners(){return Map.copyOf(namespaceOwners);}

    @Override public Optional<Value> get(UUID player,String npc,String instance,String key){
        Key k=key(player,npc,instance,key);Value v=values.get(k);
        if(v!=null&&v.expired(Instant.now())&&values.remove(k,v)){markExpired(k,v);changed(k,v,null,"expiry");v=null;}
        return Optional.ofNullable(v);
    }
    @Override public Map<String,Value> entries(UUID player,String npc,String instance){
        Map<String,Value> out=new TreeMap<>();
        for(Key k:new ArrayList<>(values.keySet()))if(Objects.equals(k.player,player)&&k.npc.equals(npc)&&k.instance.equals(normalize(instance)))get(player,npc,instance,k.key).ifPresent(v->out.put(k.key,v));
        return Map.copyOf(out);
    }
    @Override public List<Entry> entries(){
        List<Entry> out=new ArrayList<>();
        for(Key k:new ArrayList<>(values.keySet()))get(k.player,k.npc,k.instance,k.key).ifPresent(v->out.add(new Entry(k.player,k.npc,k.instance,k.key,v)));
        out.sort(Comparator.comparing((Entry e)->e.scope().name()).thenComparing(e->Objects.toString(e.player(),""))
                .thenComparing(Entry::npcDefinition).thenComparing(Entry::instance).thenComparing(Entry::key));
        return List.copyOf(out);
    }
    public void onChange(Consumer<Change> listener){changeListener=listener==null?change->{}:listener;}
    public synchronized void restore(Entry entry,String source){
        Key k=key(entry.player(),entry.npcDefinition(),entry.instance(),entry.key());Value old=current(k,Instant.now()),next=entry.value();
        values.put(k,next);expiredWrites.remove(k);deleted.remove(k);dirty.add(k);changed(k,old,next,source);
    }

    @Override public synchronized Value set(UUID player,String npc,String instance,String key,Type type,Object raw,Duration ttl,String source){
        Key k=key(player,npc,instance,key);authorize(k.key,source);Object value=normalize(type,raw);Instant now=Instant.now();Value old=current(k,now);
        Value next=new Value(type,value,old==null?now:old.createdAt(),now,ttl==null?null:now.plus(ttl),source);
        values.put(k,next);expiredWrites.remove(k);deleted.remove(k);dirty.add(k);changed(k,old,next,source);return next;
    }
    @Override public synchronized Value adjust(UUID player,String npc,String instance,String key,double delta,Duration ttl,String source){
        return adjust(player,npc,instance,key,delta,-Double.MAX_VALUE,Double.MAX_VALUE,ttl,source);
    }
    @Override public synchronized Value adjust(UUID player,String npc,String instance,String key,double delta,double minimum,double maximum,Duration ttl,String source){
        if(!Double.isFinite(delta)||!Double.isFinite(minimum)||!Double.isFinite(maximum)||minimum>maximum)throw new IllegalArgumentException("invalid numeric adjustment bounds");
        Value old=get(player,npc,instance,key).orElse(null);
        if(old!=null&&old.type()!=Type.NUMBER)throw new IllegalStateException("memory is not numeric");
        double result=Math.max(minimum,Math.min(maximum,(old==null?0d:old.numberValue())+delta));
        return set(player,npc,instance,key,Type.NUMBER,result,ttl,source);
    }
    @Override public synchronized Mutation compareAndSet(UUID player,String npc,String instance,String key,Object expected,Type type,Object raw,Duration ttl,String source){
        Key k=key(player,npc,instance,key);authorize(k.key,source);Value old=current(k,Instant.now());
        Object normalizedExpected=expected==null?null:normalize(old==null?type:old.type(),expected);
        if(old==null?normalizedExpected!=null:!Objects.equals(old.value(),normalizedExpected))return new Mutation(false,old);
        return new Mutation(true,set(player,npc,instance,key,type,raw,ttl,source));
    }
    @Override public synchronized boolean expire(UUID player,String npc,String instance,String key,Instant expiresAt,String source){
        Key k=key(player,npc,instance,key);authorize(k.key,source);Value old=current(k,Instant.now());if(old==null)return false;
        Instant now=Instant.now();Value next=new Value(old.type(),old.value(),old.createdAt(),now,Objects.requireNonNull(expiresAt,"expiresAt"),source);
        if(!expiresAt.isAfter(now)){values.remove(k);markExpired(k,next);changed(k,old,null,source);}
        else{values.put(k,next);deleted.remove(k);dirty.add(k);changed(k,old,next,source);}return true;
    }
    @Override public synchronized boolean forget(UUID player,String npc,String instance,String key){return forget(player,npc,instance,key,"forget");}
    public synchronized boolean forget(UUID player,String npc,String instance,String key,String source){
        Key k=key(player,npc,instance,key);authorize(k.key,source);dirty.remove(k);expiredWrites.remove(k);deleted.add(k);Value old=values.remove(k);if(old!=null)changed(k,old,null,source);return old!=null;
    }

    public synchronized void flush(){
        if(dirty.isEmpty()&&deleted.isEmpty())return;Set<Key> writes=new HashSet<>(dirty),removes=new HashSet<>(deleted);dirty.removeAll(writes);deleted.removeAll(removes);
        List<SqliteStore.MemoryRow> rows=new ArrayList<>();for(Key k:writes){Value v=values.get(k);if(v==null)v=expiredWrites.get(k);if(v!=null)rows.add(encode(k,v));}
        try{store.saveMemories(rows,removes.stream().map(Key::packed).collect(java.util.stream.Collectors.toSet())).join();writes.forEach(expiredWrites::remove);}
        catch(RuntimeException e){dirty.addAll(writes);deleted.addAll(removes);throw e;}
    }
    public void sweep(){
        Instant now=Instant.now();for(var e:new ArrayList<>(values.entrySet()))if(e.getValue().expired(now)&&values.remove(e.getKey(),e.getValue())){markExpired(e.getKey(),e.getValue());changed(e.getKey(),e.getValue(),null,"expiry");}
        int removed=store.sweepExpiredMemories(now.minus(expiredRetention).toEpochMilli()).join();lastSweepRows=removed;lastSweepAt=System.currentTimeMillis();sweepRuns.incrementAndGet();sweptRows.addAndGet(removed);
    }
    @Override public void close(){flush();}

    private Value current(Key k,Instant now){Value old=values.get(k);if(old!=null&&old.expired(now)){values.remove(k);markExpired(k,old);changed(k,old,null,"expiry");return null;}return old;}
    private void markExpired(Key key,Value value){if(expiredRetention.isZero()){dirty.remove(key);expiredWrites.remove(key);deleted.add(key);}else{expiredWrites.put(key,value);deleted.remove(key);dirty.add(key);}}
    private void changed(Key k,Value old,Value next,String source){changeListener.accept(new Change(k.player,k.npc,k.instance,k.key,old,next,Objects.toString(source,"unknown")));}
    private void authorize(String key,String source){
        int colon=key.indexOf(':');if(colon<1||source==null||!source.startsWith("extension:"))return;
        String namespace=key.substring(0,colon),caller=source.substring("extension:".length()).split("[/ ]",2)[0];String owner=namespaceOwners.get(namespace);
        if(owner!=null&&!owner.equals(caller))throw new SecurityException("memory namespace "+namespace+" is owned by "+owner);
    }
    private static Key key(UUID player,String npc,String instance,String key){if(npc==null||npc.isBlank()||key==null||!key.matches("[a-z0-9][a-z0-9_.:-]*"))throw new IllegalArgumentException("invalid memory identity");return new Key(player,npc,normalize(instance),key);}
    private static String namespace(String value){String n=Objects.requireNonNull(value,"namespace").toLowerCase(Locale.ROOT);if(!n.matches("[a-z0-9][a-z0-9_.-]*"))throw new IllegalArgumentException("invalid namespace "+value);return n;}
    private static String normalize(String v){return v==null?"":v;}
    private static Object normalize(Type type,Object value){return switch(Objects.requireNonNull(type,"type")){case BOOLEAN->value instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(value));case NUMBER->value instanceof Number n?n.doubleValue():Double.parseDouble(String.valueOf(value));case STRING->String.valueOf(value);case TIMESTAMP->value instanceof Instant i?i:Instant.parse(String.valueOf(value));};}
    private static SqliteStore.MemoryRow encode(Key k,Value v){String raw=v.type()==Type.TIMESTAMP?v.timestampValue().toString():String.valueOf(v.value());return new SqliteStore.MemoryRow(k.player==null?"global":"player",k.player==null?"":k.player.toString(),k.npc,k.instance,k.key,v.type().name(),raw,v.createdAt().toEpochMilli(),v.updatedAt().toEpochMilli(),v.expiresAt()==null?null:v.expiresAt().toEpochMilli(),v.source());}
    private static Value decode(SqliteStore.MemoryRow r){Type t=Type.valueOf(r.type());Object v=normalize(t,r.value());return new Value(t,v,Instant.ofEpochMilli(r.createdAt()),Instant.ofEpochMilli(r.updatedAt()),r.expiresAt()==null?null:Instant.ofEpochMilli(r.expiresAt()),r.source());}
}
