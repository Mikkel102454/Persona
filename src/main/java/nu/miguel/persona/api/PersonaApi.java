package nu.miguel.persona.api;

import nu.miguel.persona.Main;
import nu.miguel.persona.content.Content;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.quest.QuestService;
import nu.miguel.persona.script.EffectExecutor;
import nu.miguel.persona.script.ScriptEngine;
import nu.miguel.persona.state.PlayerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Stable entry point for Persona extensions. Obtain it with {@link #get()}. */
public final class PersonaApi {
    /** Latest additive API level. All 2.x extensions remain accepted. */
    public static final String API_VERSION="2.2";
    private static volatile PersonaApi instance;
    private final Main plugin;
    private final Map<String,PersonaExpansion> expansions=new LinkedHashMap<>();
    private final Map<Class<?>,Map<String,Entry<?>>> types=new ConcurrentHashMap<>();
    private final Map<String,SchemaEntry> editorSchemas=new LinkedHashMap<>();
    private final Map<String,CatalogEntry> editorCatalogs=new LinkedHashMap<>();
    private final Map<PersonaExpansion,Resources> resources=new IdentityHashMap<>();
    private boolean initialLoadComplete;

    public PersonaApi(Main plugin){this.plugin=plugin;instance=this;}
    public static PersonaApi get(){PersonaApi value=instance;if(value==null)throw new IllegalStateException("Persona is not enabled");return value;}
    public Main plugin(){return plugin;}
    public synchronized boolean register(PersonaExpansion expansion){
        requirePrimaryThread();String id=namespace(expansion.identifier());
        if(id.equals("persona")&&!(expansion instanceof BuiltinExpansion))throw new IllegalArgumentException("persona is reserved");
        if(!compatible(expansion.requiredApiVersion())||!expansion.canRegister())return false;
        if(expansions.containsKey(id))throw new IllegalArgumentException("duplicate expansion "+id);
        Registrar registrar=new Registrar(expansion,id);
        try{expansion.contribute(registrar);expansion.attach(this);expansions.put(id,expansion);if(plugin.memories()!=null)plugin.memories().claimNamespace(id,id);return true;}
        catch(RuntimeException e){registrar.rollback();throw e;}
    }
    public synchronized void unregister(PersonaExpansion expansion){
        requirePrimaryThread();if(expansions.remove(namespace(expansion.identifier()))!=expansion)return;
        types.values().forEach(map->map.entrySet().removeIf(e->e.getValue().owner==expansion));
        editorSchemas.entrySet().removeIf(e->e.getValue().owner==expansion);
        editorCatalogs.entrySet().removeIf(e->e.getValue().owner==expansion);
        Resources owned=resources.remove(expansion);if(owned!=null)owned.close();if(plugin.memories()!=null)plugin.memories().releaseNamespaces(expansion.identifier());
    }
    public synchronized void unregister(Plugin owner){new ArrayList<>(expansions.values()).stream().filter(x->x.owner()==owner).toList().forEach(this::unregister);}
    public synchronized void shutdown(){new ArrayList<>(expansions.values()).forEach(this::unregister);if(instance==this)instance=null;}
    public synchronized Optional<PersonaExpansion> expansion(String id){return Optional.ofNullable(expansions.get(namespace(id)));}
    public synchronized Set<String> expansions(){return Set.copyOf(expansions.keySet());}
    public boolean isInitialLoadComplete(){return initialLoadComplete;}
    public void initialLoadComplete(){initialLoadComplete=true;}
    public boolean reload(){return plugin.reloadPersona();}
    public NpcMemoryService memories(){return plugin.memories();}
    public void signal(net.citizensnpcs.api.npc.NPC npc,Player player,String name,Map<String,Object> data){signalAsync(npc,player,name,data);}
    /** Wakes behavior listeners and runs the matching typed NPC-local signal graph. */
    public CompletionStage<ScriptEngine.Control> signalAsync(net.citizensnpcs.api.npc.NPC npc,Player player,
                                                              String name,Map<String,Object> data){
        Objects.requireNonNull(npc,"npc");String signal=Objects.requireNonNull(name,"name").toLowerCase(Locale.ROOT);
        if(!signal.matches("[a-z][a-z0-9_.-]{0,63}"))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid NPC signal name"));
        Map<String,Object> supplied=data==null?Map.of():Map.copyOf(data);
        plugin.behaviors().wake(npc,player,"signal:"+signal,supplied);
        PersonaTrait trait=npc.getTraitNullable(PersonaTrait.class);
        if(trait==null||!trait.bound())return CompletableFuture.completedFuture(ScriptEngine.Control.next());
        Content.Npc definition=plugin.registry().npcs().get(trait.definitionId());
        Content.NpcSignal declared=definition==null?null:definition.signals().get(signal);
        if(declared==null||declared.graph()==null)return CompletableFuture.completedFuture(ScriptEngine.Control.next());
        Map<String,Object> values=new LinkedHashMap<>(supplied);
        values.put("npc",definition.id());
        values.put("npc-instance",Objects.toString(trait.instanceId(),npc.getUniqueId().toString()));
        if(player!=null)values.put("player",player);
        EffectExecutor.Context context=new EffectExecutor.Context(player,npc,definition,null,null,null,0,0);
        return plugin.scripts().runNpcEvent(declared.graph(),values,context);
    }

    public QuestService.Result startQuest(Player p,String id){return plugin.quests().start(p,id);}
    public QuestService.Result finishQuest(Player p,String id){return plugin.quests().finish(p,id);}
    public QuestService.Result resetQuest(Player p,String id){return plugin.quests().reset(p,id);}
    public List<ActiveObjective> activeObjectives(Player p,String type){return plugin.quests().activeObjectives(p,type);}
    public boolean incrementProgress(Player p,String quest,String objective,long amount){return plugin.quests().updateProgress(p,quest,objective,amount,true);}
    public boolean setProgress(Player p,String quest,String objective,long value){return plugin.quests().updateProgress(p,quest,objective,value,false);}
    public Optional<Boolean> flag(Player p,String name){PlayerState s=plugin.states().require(p);return s==null?Optional.empty():Optional.of(s.flags().getOrDefault(name,false));}
    public void flag(Player p,String name,boolean value){PlayerState s=requiredState(p);Boolean old=s.flags().put(name,value);plugin.states().save(s);plugin.behaviors().playerStateChanged(p,"flag-changed",Map.of("name",name,"old",old==null?false:old,"new",value));}
    public Optional<String> variable(Player p,String name){PlayerState s=plugin.states().require(p);return s==null?Optional.empty():Optional.ofNullable(s.variables().get(name));}
    public void variable(Player p,String name,String value){PlayerState s=requiredState(p);String old=value==null?s.variables().remove(name):s.variables().put(name,value);plugin.states().save(s);plugin.behaviors().playerStateChanged(p,"variable-changed",Map.of("name",name,"old",Objects.toString(old,"<unset>"),"new",Objects.toString(value,"<unset>")));}
    public String resolvePlaceholders(Player p,String value){return plugin.effects().replace(value,EffectExecutor.Context.player(p));}
    /** Resolves built-in and extension placeholders with the complete command context. */
    public String resolvePlaceholders(PersonaContext c,String value){
        Objects.requireNonNull(c,"context");
        EffectExecutor.Context source=new EffectExecutor.Context(c.player(),c.npc().orElse(null),c.npcDefinition().orElse(null),
                c.dialogue().orElse(null),c.quest().orElse(null),c.phase().orElse(null),c.objective().orElse(null),c.current(),c.required());
        return plugin.effects().replace(value,source);
    }

    public record ActiveObjective(String questId,String phaseId,String objectiveId,String type,long current,long required,Map<String,Object> options){}
    public <T> Optional<T> handler(Class<T> category,String raw){Entry<?> e=types.getOrDefault(category,Map.of()).get(canonical(raw));return e==null?Optional.empty():Optional.of(category.cast(e.handler));}
    public Set<String> registeredTypes(Class<?> category){return Set.copyOf(types.getOrDefault(category,Map.of()).keySet());}
    /** Namespaced script value types declared by registered command extensions. */
    public synchronized Set<String> scriptValueTypes(){Set<String> result=new LinkedHashSet<>();types.getOrDefault(ExpansionTypes.Command.class,Map.of()).values().forEach(entry->result.addAll(((ExpansionTypes.Command)entry.handler).nominalValueTypes().keySet()));return Set.copyOf(result);}
    /** JSON Schema fragments keyed by canonical extension behavior node type. */
    public synchronized Map<String,Map<String,Object>> behaviorSchemas(){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        types.getOrDefault(ExpansionTypes.BehaviorCondition.class,Map.of()).forEach((name,e)->result.put("condition:"+name,((ExpansionTypes.BehaviorCondition)e.handler).metadata().schema()));
        types.getOrDefault(ExpansionTypes.BehaviorAction.class,Map.of()).forEach((name,e)->result.put("action:"+name,((ExpansionTypes.BehaviorAction)e.handler).metadata().schema()));
        return Collections.unmodifiableMap(result);
    }
    /** Complete data-only schema inventory for built-in, extension, and future content types. */
    public synchronized List<EditorSchemaDescriptor> editorSchemas(){
        Map<String,EditorSchemaDescriptor> result=new TreeMap<>();
        types.forEach((category,entries)->entries.forEach((id,entry)->{
            if(entry.handler instanceof EditorSchemaProvider provider){String contentType=contentType(category);PersonaExpansion owner=entry.owner;Map<String,Object> schema=new LinkedHashMap<>(provider.editorSchema());if(entry.handler instanceof ExpansionTypes.Command command){Map<String,Map<String,Object>> nominal=command.nominalValueTypes();for(String valueType:nominal.keySet())if(!valueType.contains(":"))throw new IllegalArgumentException("extension nominal value type must be namespaced: "+valueType);for(ExpansionTypes.ScriptPin pin:java.util.stream.Stream.concat(command.inputPins().stream(),command.outputPins().stream()).toList())if(pin.valueType().contains(":")&&!nominal.containsKey(pin.valueType()))throw new IllegalArgumentException("extension pin "+pin.name()+" uses undeclared nominal value type "+pin.valueType());schema.put("x-persona-input-pins",pinMetadata(command.inputPins()));schema.put("x-persona-output-pins",pinMetadata(command.outputPins()));schema.put("x-persona-value-types",nominal);}
                result.put(contentType+":"+id,new EditorSchemaDescriptor(contentType,id,owner.identifier(),owner.version(),schema));}
        }));
        editorSchemas.forEach((key,entry)->result.put(key,new EditorSchemaDescriptor(entry.contentType,entry.typeId,
                entry.owner.identifier(),entry.owner.version(),entry.provider.editorSchema())));
        return List.copyOf(result.values());
    }
    private static List<Map<String,Object>> pinMetadata(List<ExpansionTypes.ScriptPin> pins){return pins.stream().map(pin->{Map<String,Object> value=new LinkedHashMap<>();value.put("name",pin.name());value.put("valueType",pin.valueType());value.put("required",pin.required());if(pin.defaultValue()!=null)value.put("default",pin.defaultValue());return Map.copyOf(value);}).toList();}
    public synchronized List<EditorCatalogDescriptor> editorCatalogs(){
        return editorCatalogs.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry->{CatalogEntry value=entry.getValue();
            return new EditorCatalogDescriptor(entry.getKey(),value.owner.identifier(),value.owner.version(),value.provider.metadata());}).toList();
    }
    /** Invokes extension catalog code only on the Minecraft server thread and enforces its declared revision/bounds. */
    public synchronized EditorCatalogProvider.CatalogPage queryEditorCatalog(String rawId,EditorCatalogProvider.CatalogQuery query){
        requirePrimaryThread();CatalogEntry entry=editorCatalogs.get(canonical(rawId));if(entry==null)throw new IllegalArgumentException("unknown editor catalog "+rawId);
        EditorCatalogProvider.CatalogMetadata metadata=entry.provider.metadata();
        if(!metadata.dependencyFields().containsAll(query.dependencies().keySet()))throw new IllegalArgumentException("catalog query contains undeclared dependencies");
        EditorCatalogProvider.CatalogPage page=Objects.requireNonNull(entry.provider.query(query),"catalog returned no page");
        if(!metadata.revision().equals(page.revision())||page.page()!=query.page()||page.values().size()>query.pageSize())
            throw new IllegalArgumentException("catalog returned a mismatched revision, page, or unbounded result");
        return page;
    }
    /** Authoritative load/publish validation for extension schemas and live catalog references. */
    public synchronized void validateEditorData(EditorSchemaProvider provider,Map<String,Object> value,String path){
        Map<String,Object> schema=provider==null?Map.of():provider.editorSchema();
        nu.miguel.persona.content.Validation.schema(schema,value,path);validateCatalogFields(schema,value,value,path);
    }
    private void validateCatalogFields(Map<?,?> schema,Object value,Map<String,Object> dependencies,String path){
        Object catalog=schema.get(EditorSchemaAnnotations.CATALOG);
        if(catalog instanceof String id&&!id.isBlank()){
            CatalogEntry entry=editorCatalogs.get(canonical(id));if(entry==null)throw new IllegalArgumentException(path+" references unavailable catalog "+id);
            Map<String,String> queryDependencies=new LinkedHashMap<>();for(String field:entry.provider.metadata().dependencyFields())if(dependencies.get(field)!=null)queryDependencies.put(field,String.valueOf(dependencies.get(field)));
            Collection<?> selected=value instanceof Collection<?> values?values:List.of(value);for(Object raw:selected)validateCatalogValue(canonical(id),entry,String.valueOf(raw),queryDependencies,path);
        }
        if(value instanceof Map<?,?> map&&schema.get("properties") instanceof Map<?,?> properties)for(var item:map.entrySet()){
            Object child=properties.get(item.getKey());if(child instanceof Map<?,?> rule)validateCatalogFields(rule,item.getValue(),stringObjectMap(map),path+"."+item.getKey());}
        if(value instanceof Collection<?> values&&schema.get("items") instanceof Map<?,?> items){int index=0;for(Object item:values)validateCatalogFields(items,item,dependencies,path+"["+(index++)+"]");}
        for(String combination:List.of("allOf","anyOf","oneOf"))if(schema.get(combination) instanceof Collection<?> choices)for(Object choice:choices)
            if(choice instanceof Map<?,?> rule&&matchesSchema(rule,value,path))validateCatalogFields(rule,value,dependencies,path);
    }
    private void validateCatalogValue(String id,CatalogEntry entry,String selected,Map<String,String> dependencies,String path){
        int page=0;boolean found=false,more;
        do{EditorCatalogProvider.CatalogPage result=queryEditorCatalog(id,new EditorCatalogProvider.CatalogQuery(selected,page,200,dependencies));
            found=result.values().stream().anyMatch(value->value.id().equals(selected)&&!value.deprecated());more=result.hasMore();page++;
        }while(!found&&more&&page<20);
        if(!found&&entry.provider.metadata().missingValuePolicy()==EditorCatalogProvider.MissingValuePolicy.REJECT)
            throw new IllegalArgumentException(path+" value "+selected+" is unavailable in catalog "+id);
        if(!found&&more)throw new IllegalArgumentException(path+" could not be verified against bounded catalog "+id);
    }
    private static Map<String,Object> stringObjectMap(Map<?,?> value){Map<String,Object> result=new LinkedHashMap<>();value.forEach((key,item)->result.put(String.valueOf(key),item));return result;}
    @SuppressWarnings("unchecked")private static Map<String,Object> castSchema(Map<?,?> value){return (Map<String,Object>)value;}
    private static boolean matchesSchema(Map<?,?> schema,Object value,String path){if(!(value instanceof Map<?,?> map))return true;
        try{nu.miguel.persona.content.Validation.schema(castSchema(schema),stringObjectMap(map),path);return true;}catch(IllegalArgumentException ignored){return false;}}
    public PersonaContext context(EffectExecutor.Context c,String type){
        Entry<?> entry=findEntry(type);PersonaExpansion owner=entry==null?expansions.get("persona"):entry.owner;
        File data=owner instanceof BuiltinExpansion?plugin.getDataFolder():new File(plugin.getDataFolder(),"extensions-data/"+owner.identifier());
        return new PersonaContext(c.player(),Optional.ofNullable(c.citizensNpc()),Optional.ofNullable(c.npc()),Optional.ofNullable(c.dialogue()),Optional.ofNullable(c.quest()),Optional.ofNullable(c.phase()),Optional.ofNullable(c.objective()),c.current(),c.required(),this,logger(owner),data,servicesFor(owner));
    }
    private Entry<?> findEntry(String type){String key=canonical(type);for(Map<String,Entry<?>> map:types.values()){Entry<?> e=map.get(key);if(e!=null)return e;}return null;}
    private Logger logger(PersonaExpansion expansion){Plugin owner=expansion==null?null:expansion.owner();return owner==null?plugin.getLogger():owner.getLogger();}
    ExpansionServices servicesFor(PersonaExpansion expansion){return resources.computeIfAbsent(expansion,x->new Resources(expansion));}
    private PlayerState requiredState(Player p){PlayerState s=plugin.states().require(p);if(s==null)throw new IllegalStateException("player state is loading");return s;}
    private boolean compatible(String requested){try{String[] wanted=Objects.toString(requested,"").split("\\."),current=API_VERSION.split("\\.");return wanted.length>=2&&Integer.parseInt(wanted[0])==Integer.parseInt(current[0])&&Integer.parseInt(wanted[1])<=Integer.parseInt(current[1]);}catch(NumberFormatException e){return false;}}
    private static String namespace(String id){String value=Objects.requireNonNull(id,"identifier").toLowerCase(Locale.ROOT);if(!value.matches("[a-z0-9][a-z0-9_.-]*"))throw new IllegalArgumentException("invalid expansion identifier "+id);return value;}
    public static String canonical(String raw){String value=Objects.requireNonNull(raw,"type").toLowerCase(Locale.ROOT).replace('_','-');return value.contains(":")?value:"persona:"+value;}
    private void requirePrimaryThread(){if(Bukkit.getServer()!=null&&!Bukkit.isPrimaryThread())throw new IllegalStateException("expansions must register on the server thread");}
    private record Entry<T>(PersonaExpansion owner,T handler){}
    private record SchemaEntry(PersonaExpansion owner,String contentType,String typeId,EditorSchemaProvider provider){}
    private record CatalogEntry(PersonaExpansion owner,EditorCatalogProvider provider){}
    private static String contentType(Class<?> category){
        if(category==ExpansionTypes.Condition.class)return "condition";if(category==ExpansionTypes.Command.class)return "command";
        if(category==ExpansionTypes.Placeholder.class)return "placeholder";if(category==ExpansionTypes.Objective.class)return "objective";
        if(category==ExpansionTypes.BehaviorCondition.class)return "behavior-condition";if(category==ExpansionTypes.BehaviorAction.class)return "behavior-action";
        return category.getSimpleName().toLowerCase(Locale.ROOT);
    }
    private final class Resources implements ExpansionServices{
        private final Plugin schedulerOwner;private final List<Listener> listeners=new ArrayList<>();private final List<BukkitTask> tasks=new ArrayList<>();
        Resources(PersonaExpansion expansion){this.schedulerOwner=expansion!=null&&expansion.owner()!=null?expansion.owner():plugin;}
        public void registerListener(Listener listener){requirePrimaryThread();plugin.getServer().getPluginManager().registerEvents(listener,schedulerOwner);listeners.add(listener);}
        public BukkitTask runSync(Runnable task){BukkitTask value=plugin.getServer().getScheduler().runTask(schedulerOwner,task);tasks.add(value);return value;}
        public BukkitTask runLater(Runnable task,long ticks){BukkitTask value=plugin.getServer().getScheduler().runTaskLater(schedulerOwner,task,ticks);tasks.add(value);return value;}
        public BukkitTask runAsync(Runnable task){BukkitTask value=plugin.getServer().getScheduler().runTaskAsynchronously(schedulerOwner,task);tasks.add(value);return value;}
        void close(){listeners.forEach(HandlerList::unregisterAll);tasks.forEach(BukkitTask::cancel);listeners.clear();tasks.clear();}
    }
    private final class Registrar implements ExpansionRegistrar{
        private final PersonaExpansion owner;private final String namespace;private final List<Map.Entry<Class<?>,String>> added=new ArrayList<>();
        Registrar(PersonaExpansion owner,String namespace){this.owner=owner;this.namespace=namespace;}
        private <T> void add(Class<T> category,String name,T handler){
            String local=namespace(name);String key=namespace+":"+local;Map<String,Entry<?>> map=types.computeIfAbsent(category,x->new LinkedHashMap<>());
            if(map.putIfAbsent(key,new Entry<>(owner,Objects.requireNonNull(handler)))!=null)throw new IllegalArgumentException("duplicate type "+key);
            added.add(Map.entry(category,key));
        }
        public void condition(String n,ExpansionTypes.Condition h){add(ExpansionTypes.Condition.class,n,h);}public void command(String n,ExpansionTypes.Command h){add(ExpansionTypes.Command.class,n,h);}
        public void placeholder(String n,ExpansionTypes.Placeholder h){add(ExpansionTypes.Placeholder.class,n,h);}public void objective(String n,ExpansionTypes.Objective h){add(ExpansionTypes.Objective.class,n,h);}
        public void behaviorCondition(String n,ExpansionTypes.BehaviorCondition h){add(ExpansionTypes.BehaviorCondition.class,n,h);}public void behaviorAction(String n,ExpansionTypes.BehaviorAction h){add(ExpansionTypes.BehaviorAction.class,n,h);}
        public void editorSchema(String contentType,String name,EditorSchemaProvider provider){
            String kind=namespace(contentType),id=namespace+":"+namespace(name),key=kind+":"+id;
            if(editorSchemas.putIfAbsent(key,new SchemaEntry(owner,kind,id,Objects.requireNonNull(provider)))!=null)throw new IllegalArgumentException("duplicate editor schema "+key);
            addedSchemas.add(key);
        }
        public void editorCatalog(String name,EditorCatalogProvider provider){String id=namespace+":"+namespace(name);
            if(editorCatalogs.putIfAbsent(id,new CatalogEntry(owner,Objects.requireNonNull(provider)))!=null)throw new IllegalArgumentException("duplicate editor catalog "+id);addedCatalogs.add(id);}
        private final List<String> addedSchemas=new ArrayList<>(),addedCatalogs=new ArrayList<>();
        void rollback(){added.forEach(x->types.get(x.getKey()).remove(x.getValue()));addedSchemas.forEach(editorSchemas::remove);addedCatalogs.forEach(editorCatalogs::remove);}
    }
}
