package nu.miguel.persona.api;

import nu.miguel.persona.Main;
import nu.miguel.persona.content.Content;
import nu.miguel.persona.quest.QuestService;
import nu.miguel.persona.script.EffectExecutor;
import nu.miguel.persona.state.PlayerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Stable entry point for Persona extensions. Obtain it with {@link #get()}. */
public final class PersonaApi {
    /** Latest additive API level. All 2.x extensions remain accepted. */
    public static final String API_VERSION="2.1";
    private static volatile PersonaApi instance;
    private final Main plugin;
    private final Map<String,PersonaExpansion> expansions=new LinkedHashMap<>();
    private final Map<Class<?>,Map<String,Entry<?>>> types=new ConcurrentHashMap<>();
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
    public void signal(net.citizensnpcs.api.npc.NPC npc,Player player,String name,Map<String,Object> data){plugin.behaviors().wake(npc,player,"signal:"+Objects.requireNonNull(name),data==null?Map.of():data);}

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
    /** JSON Schema fragments keyed by canonical extension behavior node type. */
    public synchronized Map<String,Map<String,Object>> behaviorSchemas(){
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        types.getOrDefault(ExpansionTypes.BehaviorCondition.class,Map.of()).forEach((name,e)->result.put("condition:"+name,((ExpansionTypes.BehaviorCondition)e.handler).metadata().schema()));
        types.getOrDefault(ExpansionTypes.BehaviorAction.class,Map.of()).forEach((name,e)->result.put("action:"+name,((ExpansionTypes.BehaviorAction)e.handler).metadata().schema()));
        return Collections.unmodifiableMap(result);
    }
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
        void rollback(){added.forEach(x->types.get(x.getKey()).remove(x.getValue()));}
    }
}
