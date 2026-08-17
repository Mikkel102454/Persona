package nu.miguel.persona.citizens;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.trait.SkinTrait;
import nu.miguel.persona.Main;
import nu.miguel.persona.behavior.BehaviorRuntime.LogicalPosition;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Lazily materializes viewer-only Citizens actors while retaining logical presentation. */
public final class ProjectionManager implements AutoCloseable {
    public record Presentation(String anchor,LogicalPosition position,boolean visible,NPC projection,String reason) {}
    public record Counts(int player,int server,int playerLimit,int serverLimit) {}
    public record Priority(double distance,long interacted,boolean dialogue,boolean quest,boolean navigating) {}
    private record Key(UUID player,UUID base) {}
    private record Skin(String name,String signature,String texture) {}
    private record Snapshot(EntityType type,String name,Skin skin,Map<Equipment.EquipmentSlot,ItemStack> equipment,
                            Integer age,Boolean ageLock,org.bukkit.entity.Pose pose,boolean glowing,
                            boolean protectedNpc,boolean sneaking,Map<String,Object> metadata) {}
    // String keys are deliberately used: these keys are stable across supported
    // Citizens releases while the Metadata enum's binary shape is not.
    private static final Set<String> SUPPORTED_METADATA=Set.of(
            "collidable","flyable","fluid-pushable","glowing",
            "nameplate-visible","silent-sounds","swim","minecraft-ai");
    private static final class State {
        String anchor; LogicalPosition position; boolean visible=true; NPC projection;
        long lastNear,interacted; boolean dialogue,quest,navigation; Snapshot snapshot;
        String reason="inherits shared actor";
    }

    private final Main plugin;
    private final NPCRegistry registry;
    private final Map<Key,State> states=new HashMap<>();
    private final Map<String,Long> diagnostics=new HashMap<>();
    private final int perPlayer,serverLimit,transitionCount,transitionDuration,debugInterval;
    private final double rangeSquared;
    private final long suspendMillis,diagnosticMillis;
    private final Particle spawnParticle,despawnParticle,debugParticle;
    private final Sound spawnSound,despawnSound;
    private long ticks;

    public ProjectionManager(Main plugin){this(plugin,CitizensAPI.createInMemoryNPCRegistry("persona-projections"));}
    ProjectionManager(Main plugin,NPCRegistry registry){
        this.plugin=plugin;
        this.registry=registry;
        perPlayer=positive(plugin.getConfig().getInt("behavior.projections.per-player",25),25);
        serverLimit=positive(plugin.getConfig().getInt("behavior.projections.server",500),500);
        double range=plugin.getConfig().getDouble("behavior.projections.activation-range",48);rangeSquared=range*range;
        suspendMillis=Math.max(0,plugin.getConfig().getLong("behavior.projections.suspend-seconds",30))*1000;
        diagnosticMillis=Math.max(1,plugin.getConfig().getLong("behavior.projections.diagnostics-seconds",30))*1000;
        transitionCount=Math.max(0,plugin.getConfig().getInt("behavior.projections.transitions.particle-count",12));
        transitionDuration=Math.max(0,plugin.getConfig().getInt("behavior.projections.transitions.duration-ticks",8));
        spawnParticle=particle("behavior.projections.transitions.spawn-particle");
        despawnParticle=particle("behavior.projections.transitions.despawn-particle");
        spawnSound=sound("behavior.projections.transitions.spawn-sound");
        despawnSound=sound("behavior.projections.transitions.despawn-sound");
        debugParticle=particle("behavior.projections.debug.particle");
        debugInterval=Math.max(1,plugin.getConfig().getInt("behavior.projections.debug.interval-ticks",10));
    }

    public void apply(Player viewer,NPC base,String anchor,LogicalPosition position,boolean visible,boolean interacted){
        if(anchor==null&&position==null&&visible){inherit(viewer,base);return;}
        Key key=new Key(viewer.getUniqueId(),base.getUniqueId());
        State s=states.computeIfAbsent(key,ignored->{State created=new State();created.anchor=anchor;created.position=position;return created;});
        s.anchor=anchor;s.visible=visible;if(position!=null)s.position=position;if(interacted)s.interacted=System.currentTimeMillis();
        update(viewer,base,s);
    }

    /** Replaces the current private presentation position immediately. */
    public void begin(Player viewer,NPC base,String anchor,LogicalPosition position){
        Key key=new Key(viewer.getUniqueId(),base.getUniqueId());State old=states.remove(key);if(old!=null)destroy(old,false);
        State s=new State();s.anchor=anchor;s.position=position;s.lastNear=System.currentTimeMillis();if(old!=null){s.interacted=old.interacted;s.dialogue=old.dialogue;s.quest=old.quest;s.navigation=old.navigation;}states.put(key,s);update(viewer,base,s);
    }

    public void inherit(Player viewer,NPC base){State s=states.remove(new Key(viewer.getUniqueId(),base.getUniqueId()));if(s!=null)destroy(s,true);show(viewer,base);}
    public Presentation inspect(Player viewer,NPC base){State s=states.get(new Key(viewer.getUniqueId(),base.getUniqueId()));if(s==null)return new Presentation(null,null,true,null,"inherits shared actor");syncPosition(s);return new Presentation(s.anchor,s.position,s.visible,s.projection,s.reason);}
    public NPC routed(NPC clicked,Player viewer){PersonaTrait t=clicked.getTraitNullable(PersonaTrait.class);if(t==null||!t.projection())return clicked;if(!viewer.getUniqueId().equals(t.projectionViewer()))return null;return CitizensAPI.getNPCRegistry().getByUniqueId(t.baseNpc());}
    public void markInteraction(Player viewer,NPC base){priorityState(viewer,base).interacted=System.currentTimeMillis();}
    public void markDialogue(Player viewer,NPC base,boolean active){State s=active?priorityState(viewer,base):states.get(new Key(viewer.getUniqueId(),base.getUniqueId()));if(s!=null)s.dialogue=active;}
    public void markQuest(Player viewer,NPC base,boolean relevant){State s=states.get(new Key(viewer.getUniqueId(),base.getUniqueId()));if(s!=null)s.quest=relevant;}
    public void markNavigation(Player viewer,NPC base,boolean active){State s=active?priorityState(viewer,base):states.get(new Key(viewer.getUniqueId(),base.getUniqueId()));if(s!=null)s.navigation=active;}
    public Counts counts(Player viewer){int player=(int)states.entrySet().stream().filter(e->e.getKey().player.equals(viewer.getUniqueId())&&spawned(e.getValue())).count();return new Counts(player,(int)states.values().stream().filter(ProjectionManager::spawned).count(),perPlayer,serverLimit);}
    public Counts counts(){return new Counts(0,(int)states.values().stream().filter(ProjectionManager::spawned).count(),perPlayer,serverLimit);}

    public boolean validateBase(NPC base,boolean binding){
        PlayerFilter filter=base.getTraitNullable(PlayerFilter.class);if(filter==null)return true;
        String detail="players="+filter.getPlayerUUIDs().size()+", groups="+filter.getGroups().size()+", permissions="+filter.getPermissions().size();
        diagnose("filter:"+base.getUniqueId(),"Citizens NPC "+base.getId()+" is Persona-bound but also has PlayerFilter ("+detail+"). Remove it; Persona owns per-player visibility.");
        return !binding;
    }

    public void tick(){
        long now=System.currentTimeMillis();ticks++;
        for(var e:new ArrayList<>(states.entrySet())){
            Player p=plugin.getServer().getPlayer(e.getKey().player);NPC base=CitizensAPI.getNPCRegistry().getByUniqueId(e.getKey().base);
            if(p==null||base==null){destroy(e.getValue(),false);states.remove(e.getKey());continue;}
            validateBase(base,false);State s=e.getValue();syncPosition(s);update(p,base,s);
            if(s.projection!=null&&s.projection.isSpawned()&&now-s.lastNear>=suspendMillis)suspend(s,"suspended outside activation range");
            if(debugParticle!=null&&ticks%debugInterval==0)debug(p,base,s);
        }
    }

    public void playerJoined(Player joined){for(State s:states.values())if(spawned(s))hideFromOtherViewer(joined,s);}
    public void playerQuit(UUID id){states.entrySet().removeIf(e->{if(!e.getKey().player.equals(id))return false;destroy(e.getValue(),false);return true;});}
    public void cancelNavigations(){for(State s:states.values())if(s.projection!=null)s.projection.getNavigator().cancelNavigation();}

    private void update(Player viewer,NPC base,State s){
        if(!s.visible){hide(viewer,base);destroy(s,true);s.reason="private presentation is hidden";return;}
        Location current=location(base,s.position,s.anchor);if(current==null){hide(viewer,base);destroy(s,true);s.reason="presentation world is unavailable";return;}
        hide(viewer,base);
        if(!near(viewer,current)){if(spawned(s)&&s.projection.getNavigator().isNavigating())suspend(s,"navigation suspended outside activation range");else s.reason=viewer.getWorld().equals(current.getWorld())?"outside activation range":"viewer is in another world";return;}
        s.lastNear=System.currentTimeMillis();Snapshot latest=snapshot(base);
        if(s.projection!=null&&s.snapshot!=null&&s.snapshot.type()!=latest.type()){destroy(s,false);s.projection=null;s.snapshot=null;}
        if(!spawned(s))materialize(viewer,base,current,s,latest);else{if(!latest.equals(s.snapshot))copy(latest,s.projection);s.snapshot=latest;s.reason=s.projection.getNavigator().isNavigating()?"active private navigation":"active private projection";}
    }

    private void materialize(Player viewer,NPC base,Location target,State s,Snapshot latest){
        if(!reserve(viewer,base,target,s)){s.reason="projection limit reached";diagnose("limit:"+viewer.getUniqueId(),"Projection denied for "+viewer.getName()+" / NPC "+base.getId()+": "+counts(viewer)+". Move closer, finish dialogue/navigation, or raise behavior.projections limits.");return;}
        if(s.projection==null){s.projection=registry.createNPC(latest.type(),latest.name());copy(latest,s.projection);PersonaTrait bt=base.getTraitNullable(PersonaTrait.class);if(bt==null){s.reason="base actor is not Persona-bound";s.projection.destroy();s.projection=null;return;}s.projection.getOrAddTrait(PersonaTrait.class).bindProjection(bt.definitionId(),bt.instanceId(),viewer.getUniqueId(),base.getUniqueId());}
        if(!s.projection.spawn(target)){s.reason="Citizens rejected projection spawn";diagnose("spawn:"+base.getUniqueId(),"Citizens rejected a Persona projection spawn for NPC "+base.getId()+" at "+shortLocation(target)+".");return;}
        s.snapshot=latest;s.position=position(target);applyEntity(latest,s.projection.getEntity());
        for(Player online:plugin.getServer().getOnlinePlayers())if(!online.getUniqueId().equals(viewer.getUniqueId()))online.hideEntity(plugin,s.projection.getEntity());viewer.showEntity(plugin,s.projection.getEntity());effect(viewer,target,spawnParticle,spawnSound);s.reason="active private projection";
    }

    private boolean reserve(Player viewer,NPC base,Location target,State incoming){
        Counts counts=counts(viewer);if(counts.player()<perPlayer&&counts.server()<serverLimit)return true;
        double incomingScore=priorityScore(new Priority(viewer.getLocation().distance(target),incoming.interacted,incoming.dialogue,incoming.quest,incoming.navigation),System.currentTimeMillis());
        Map.Entry<Key,State> victim=states.entrySet().stream().filter(e->spawned(e.getValue())&&(counts.server()>=serverLimit||e.getKey().player.equals(viewer.getUniqueId())))
                .min(Comparator.comparingDouble(e->score(e.getKey(),e.getValue()))).orElse(null);
        if(victim==null||score(victim.getKey(),victim.getValue())>=incomingScore)return false;
        destroy(victim.getValue(),true);victim.getValue().reason="preempted by a higher-priority projection";return true;
    }

    static double priorityScore(Priority p,long now){double recency=p.interacted()==0?0:Math.max(0,120_000-(now-p.interacted()))/1000d;return Math.max(0,100-p.distance())+recency+(p.dialogue()?400:0)+(p.quest()?200:0)+(p.navigating()?300:0);}
    private double score(Key key,State s){Player p=plugin.getServer().getPlayer(key.player);Location l=s.projection==null?null:s.projection.getStoredLocation();double d=p==null||l==null||!p.getWorld().equals(l.getWorld())?Double.MAX_VALUE:p.getLocation().distance(l);return priorityScore(new Priority(d,s.interacted,s.dialogue,s.quest,s.navigation||s.projection.getNavigator().isNavigating()),System.currentTimeMillis());}

    private Snapshot snapshot(NPC base){
        SkinTrait t=base.getTraitNullable(SkinTrait.class);Skin skin=t==null||t.getTexture()==null||t.getSignature()==null?null:new Skin(Objects.requireNonNullElse(t.getSkinName(),base.getRawName()),t.getSignature(),t.getTexture());
        Map<Equipment.EquipmentSlot,ItemStack> eq=new EnumMap<>(Equipment.EquipmentSlot.class);Equipment equipment=base.getTraitNullable(Equipment.class);if(equipment!=null)equipment.getEquipmentBySlot().forEach((slot,item)->eq.put(slot,item==null?null:item.clone()));
        Entity entity=base.isSpawned()?base.getEntity():null;Integer age=entity instanceof Ageable a?a.getAge():null;Boolean ageLock=entity instanceof Ageable a?a.getAgeLock():null;
        Map<String,Object> metadata=new LinkedHashMap<>();for(String key:SUPPORTED_METADATA)if(base.data().has(key)){Object value=base.data().get(key);if(value!=null)metadata.put(key,value);}
        return new Snapshot(base.getCosmeticEntityType(),base.getRawName(),skin,Collections.unmodifiableMap(eq),age,ageLock,entity==null?null:entity.getPose(),entity!=null&&entity.isGlowing(),base.isProtected(),entity!=null&&entity.isSneaking(),Map.copyOf(metadata));
    }

    private void copy(Snapshot from,NPC to){
        to.setName(from.name());to.setProtected(from.protectedNpc());to.setSneaking(from.sneaking());
        for(String key:SUPPORTED_METADATA){if(from.metadata().containsKey(key))to.data().set(key,from.metadata().get(key));else to.data().remove(key);}
        SkinTrait targetSkin=to.getTraitNullable(SkinTrait.class);if(from.skin()==null){if(targetSkin!=null&&targetSkin.getTexture()!=null)targetSkin.clearTexture();}else{Skin skin=from.skin();if(targetSkin==null||!Objects.equals(targetSkin.getSkinName(),skin.name())||!Objects.equals(targetSkin.getSignature(),skin.signature())||!Objects.equals(targetSkin.getTexture(),skin.texture()))to.getOrAddTrait(SkinTrait.class).setSkinPersistent(skin.name(),skin.signature(),skin.texture());}
        Equipment target=to.getOrAddTrait(Equipment.class);if(target!=null)for(Equipment.EquipmentSlot slot:Equipment.EquipmentSlot.values()){ItemStack item=from.equipment().get(slot);target.set(slot,item==null?null:item.clone());}
        if(to.isSpawned())applyEntity(from,to.getEntity());
    }

    private void applyEntity(Snapshot from,Entity entity){entity.setGlowing(from.glowing());if(from.pose()!=null)entity.setPose(from.pose());if(entity instanceof Ageable age&&from.age()!=null){age.setAge(from.age());age.setAgeLock(Boolean.TRUE.equals(from.ageLock()));}}
    private Location location(NPC base,LogicalPosition position,String anchor){if(position!=null){var world=plugin.getServer().getWorld(position.world());return world==null?null:new Location(world,position.x(),position.y(),position.z(),position.yaw(),position.pitch());}return anchor(base,anchor);}
    private Location anchor(NPC base,String name){if(name==null)return base.getStoredLocation();PersonaTrait t=base.getTraitNullable(PersonaTrait.class);var definition=t==null?null:plugin.registry().npcs().get(t.definitionId());var a=definition==null?null:definition.anchors().get(name);var world=a==null?null:plugin.getServer().getWorld(a.world());return world==null?null:new Location(world,a.x(),a.y(),a.z(),a.yaw(),a.pitch());}
    private void syncPosition(State s){if(spawned(s)&&s.projection.getStoredLocation()!=null)s.position=position(s.projection.getStoredLocation());}
    private void suspend(State s,String reason){syncPosition(s);destroy(s,true);s.reason=reason;}
    private boolean near(Player p,Location l){return p.getWorld().equals(l.getWorld())&&p.getLocation().distanceSquared(l)<=rangeSquared;}
    private static boolean spawned(State s){return s.projection!=null&&s.projection.isSpawned();}
    private void hide(Player p,NPC npc){if(npc.isSpawned())p.hideEntity(plugin,npc.getEntity());}
    private void show(Player p,NPC npc){if(npc.isSpawned())p.showEntity(plugin,npc.getEntity());}
    private void hideFromOtherViewer(Player joined,State s){PersonaTrait t=s.projection.getTraitNullable(PersonaTrait.class);if(t!=null&&!joined.getUniqueId().equals(t.projectionViewer()))joined.hideEntity(plugin,s.projection.getEntity());}
    private void destroy(State s,boolean transition){if(spawned(s)){Location at=s.projection.getStoredLocation();s.projection.getNavigator().cancelNavigation();if(transition&&at!=null){Player viewer=plugin.getServer().getPlayer(s.projection.getTrait(PersonaTrait.class).projectionViewer());if(viewer!=null)effect(viewer,at,despawnParticle,despawnSound);}s.projection.despawn(DespawnReason.REMOVAL);}}
    private void debug(Player viewer,NPC base,State s){if(!plugin.getConfig().getBoolean("behavior.projections.debug.enabled",false))return;Location selected=anchor(base,s.anchor);if(selected!=null&&viewer.getWorld().equals(selected.getWorld()))viewer.spawnParticle(debugParticle,selected.clone().add(0,0.15,0),1,0,0,0,0);if(s.position!=null){Location pos=location(base,s.position,null);if(pos!=null&&viewer.getWorld().equals(pos.getWorld()))viewer.spawnParticle(debugParticle,pos.clone().add(0,1,0),1,0,0,0,0);}}
    private void effect(Player viewer,Location at,Particle particle,Sound sound){if(sound!=null)viewer.playSound(at,sound,(float)plugin.getConfig().getDouble("behavior.projections.transitions.volume",0.7),(float)plugin.getConfig().getDouble("behavior.projections.transitions.pitch",1));if(particle==null||transitionCount<=0)return;int pulses=Math.max(1,transitionDuration/2+1),amount=Math.max(1,(int)Math.ceil((double)transitionCount/pulses));for(int delay=0;delay<=transitionDuration;delay+=2){int atTick=delay;plugin.getServer().getScheduler().runTaskLater(plugin,()->{if(viewer.isOnline()&&viewer.getWorld().equals(at.getWorld()))viewer.spawnParticle(particle,at.clone().add(0,1,0),amount,.35,.7,.35,.01);},atTick);}}
    private State priorityState(Player viewer,NPC base){return states.computeIfAbsent(new Key(viewer.getUniqueId(),base.getUniqueId()),ignored->new State());}
    private void diagnose(String key,String message){long now=System.currentTimeMillis();if(now-diagnostics.getOrDefault(key,0L)<diagnosticMillis)return;diagnostics.put(key,now);plugin.getLogger().warning(message);}
    private Particle particle(String path){String value=plugin.getConfig().getString(path,"");if(value==null||value.isBlank()||value.equalsIgnoreCase("none"))return null;try{return Registry.PARTICLE_TYPE.get(org.bukkit.NamespacedKey.minecraft(value.toLowerCase(Locale.ROOT).replace("minecraft:","")));}catch(RuntimeException e){plugin.getLogger().warning("Invalid particle at "+path+": "+value);return null;}}
    private Sound sound(String path){String value=plugin.getConfig().getString(path,"");if(value==null||value.isBlank()||value.equalsIgnoreCase("none"))return null;try{return Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(value.toLowerCase(Locale.ROOT).replace("minecraft:","")));}catch(RuntimeException e){plugin.getLogger().warning("Invalid sound at "+path+": "+value);return null;}}
    private static int positive(int value,int fallback){return value>0?value:fallback;}
    private static String shortLocation(Location l){return l.getWorld().getName()+" "+Math.round(l.getX())+","+Math.round(l.getY())+","+Math.round(l.getZ());}
    public static LogicalPosition position(Location l){return new LogicalPosition(l.getWorld().getName(),l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());}
    @Override public void close(){states.values().forEach(s->destroy(s,false));states.clear();registry.deregisterAll();}
}
