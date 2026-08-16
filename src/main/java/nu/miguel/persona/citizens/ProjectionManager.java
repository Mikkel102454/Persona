package nu.miguel.persona.citizens;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import nu.miguel.persona.Main;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ageable;

import java.util.*;

/** Lazily materializes viewer-only Citizens actors while retaining logical presentation. */
public final class ProjectionManager implements AutoCloseable {
    public record Presentation(String anchor,boolean visible,NPC projection,String reason) {}
    private record Key(UUID player,UUID base) {}
    private static final class State {String anchor;boolean visible=true;NPC projection;long lastNear;long interacted;String reason="inherits shared actor";}
    private final Main plugin;private final NPCRegistry registry=CitizensAPI.createInMemoryNPCRegistry("persona-projections");private final Map<Key,State> states=new HashMap<>();
    private final int perPlayer,serverLimit;private final double rangeSquared;private final long suspendMillis;
    public ProjectionManager(Main plugin){this.plugin=plugin;perPlayer=plugin.getConfig().getInt("behavior.projections.per-player",25);serverLimit=plugin.getConfig().getInt("behavior.projections.server",500);double range=plugin.getConfig().getDouble("behavior.projections.activation-range",48);rangeSquared=range*range;suspendMillis=plugin.getConfig().getLong("behavior.projections.suspend-seconds",30)*1000;}
    public void apply(Player viewer,NPC base,String anchor,boolean visible,boolean interacted){Key key=new Key(viewer.getUniqueId(),base.getUniqueId());State s=states.computeIfAbsent(key,x->new State());s.anchor=anchor;s.visible=visible;if(interacted)s.interacted=System.currentTimeMillis();update(viewer,base,s);}
    public void inherit(Player viewer,NPC base){State s=states.remove(new Key(viewer.getUniqueId(),base.getUniqueId()));if(s!=null)destroy(s);show(viewer,base);}
    public Presentation inspect(Player viewer,NPC base){State s=states.get(new Key(viewer.getUniqueId(),base.getUniqueId()));return s==null?new Presentation(null,true,null,"inherits shared actor"):new Presentation(s.anchor,s.visible,s.projection,s.reason);}
    public NPC routed(NPC clicked,Player viewer){PersonaTrait t=clicked.getTraitNullable(PersonaTrait.class);return t!=null&&t.projection()&&!viewer.getUniqueId().equals(t.projectionViewer())?null:clicked;}
    public void tick(){long now=System.currentTimeMillis();for(var e:new ArrayList<>(states.entrySet())){Player p=plugin.getServer().getPlayer(e.getKey().player);NPC base=CitizensAPI.getNPCRegistry().getByUniqueId(e.getKey().base);if(p==null||base==null){destroy(e.getValue());states.remove(e.getKey());continue;}update(p,base,e.getValue());if(e.getValue().projection!=null&&now-e.getValue().lastNear>=suspendMillis){e.getValue().projection.despawn(DespawnReason.PENDING_RESPAWN);e.getValue().reason="suspended outside activation range";}}}
    public void playerJoined(Player joined){for(State s:states.values())if(s.projection!=null&&s.projection.isSpawned()){PersonaTrait t=s.projection.getTraitNullable(PersonaTrait.class);if(t!=null&&!joined.getUniqueId().equals(t.projectionViewer()))joined.hideEntity(plugin,s.projection.getEntity());}}
    public void playerQuit(UUID id){states.entrySet().removeIf(e->{if(!e.getKey().player.equals(id))return false;destroy(e.getValue());return true;});}
    private void update(Player viewer,NPC base,State s){if(!s.visible){hide(viewer,base);destroy(s);s.reason="private presentation is hidden";return;}Location target=anchor(base,s.anchor);if(target==null){hide(viewer,base);destroy(s);s.reason="anchor world is unavailable";return;}boolean differs=base.getStoredLocation()==null||!same(base.getStoredLocation(),target);if(!differs){show(viewer,base);destroy(s);s.reason="anchor matches shared actor";return;}hide(viewer,base);if(!near(viewer,target)){s.reason="outside activation range";return;}s.lastNear=System.currentTimeMillis();if(s.projection==null||!s.projection.isSpawned())materialize(viewer,base,target,s);}
    private void materialize(Player viewer,NPC base,Location target,State s){long playerCount=states.entrySet().stream().filter(e->e.getKey().player.equals(viewer.getUniqueId())&&e.getValue().projection!=null&&e.getValue().projection.isSpawned()).count(),total=states.values().stream().filter(x->x.projection!=null&&x.projection.isSpawned()).count();if(playerCount>=perPlayer||total>=serverLimit){s.reason="projection limit reached";return;}if(s.projection==null){s.projection=registry.createNPC(base.getCosmeticEntityType(),base.getRawName());copy(base,s.projection);PersonaTrait bt=base.getTraitNullable(PersonaTrait.class);s.projection.getOrAddTrait(PersonaTrait.class).bindProjection(bt.definitionId(),bt.instanceId(),viewer.getUniqueId(),base.getUniqueId());}if(!s.projection.spawn(target)){s.reason="Citizens rejected projection spawn";return;}if(base.isSpawned()){s.projection.getEntity().setPose(base.getEntity().getPose());if(base.getEntity() instanceof Ageable from&&s.projection.getEntity() instanceof Ageable to){to.setAge(from.getAge());to.setAgeLock(from.getAgeLock());}}for(Player online:plugin.getServer().getOnlinePlayers())if(!online.getUniqueId().equals(viewer.getUniqueId()))online.hideEntity(plugin,s.projection.getEntity());viewer.showEntity(plugin,s.projection.getEntity());s.reason="active private projection";}
    private void copy(NPC base,NPC projection){projection.setProtected(base.isProtected());SkinTrait skin=base.getTraitNullable(SkinTrait.class);if(skin!=null&&skin.getTexture()!=null)projection.getOrAddTrait(SkinTrait.class).setSkinPersistent(skin.getSkinName(),skin.getSignature(),skin.getTexture());Equipment equipment=base.getTraitNullable(Equipment.class);if(equipment!=null){Equipment target=projection.getOrAddTrait(Equipment.class);for(var e:equipment.getEquipmentBySlot().entrySet())target.set(e.getKey(),e.getValue()==null?null:e.getValue().clone());}}
    private Location anchor(NPC base,String name){if(name==null)return base.getStoredLocation();PersonaTrait t=base.getTraitNullable(PersonaTrait.class);var definition=t==null?null:plugin.registry().npcs().get(t.definitionId());var a=definition==null?null:definition.anchors().get(name);var world=a==null?null:plugin.getServer().getWorld(a.world());return world==null?null:new Location(world,a.x(),a.y(),a.z(),a.yaw(),a.pitch());}
    private boolean near(Player p,Location l){return p.getWorld().equals(l.getWorld())&&p.getLocation().distanceSquared(l)<=rangeSquared;}private static boolean same(Location a,Location b){return Objects.equals(a.getWorld(),b.getWorld())&&a.distanceSquared(b)<0.01;}
    private void hide(Player p,NPC npc){if(npc.isSpawned())p.hideEntity(plugin,npc.getEntity());}private void show(Player p,NPC npc){if(npc.isSpawned())p.showEntity(plugin,npc.getEntity());}
    private void destroy(State s){if(s.projection!=null&&s.projection.isSpawned())s.projection.despawn(DespawnReason.REMOVAL);}
    @Override public void close(){states.values().forEach(this::destroy);states.clear();registry.deregisterAll();}
}
