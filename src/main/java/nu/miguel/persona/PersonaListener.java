package nu.miguel.persona;

import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCSelectEvent;
import net.citizensnpcs.api.event.NPCDamageByEntityEvent;
import nu.miguel.persona.citizens.PersonaTrait;
import nu.miguel.persona.content.Content.Npc;
import nu.miguel.persona.script.EffectExecutor;
import nu.miguel.persona.script.ScriptDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import net.citizensnpcs.api.CitizensAPI;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PersonaListener implements Listener {
    private final Main plugin;
    private final Map<UUID,Long> lastMove=new HashMap<>();
    public PersonaListener(Main plugin){this.plugin=plugin;}
    @EventHandler public void join(PlayerJoinEvent e){plugin.states().load(e.getPlayer());plugin.behaviors().playerJoined(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){if(plugin.editor()!=null)plugin.editor().playerQuit(e.getPlayer().getUniqueId());plugin.behaviors().playerQuit(e.getPlayer());plugin.dialogues().cancel(e.getPlayer().getUniqueId(),null);plugin.quests().tickPlayer(e.getPlayer(),false);plugin.states().unload(e.getPlayer());lastMove.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void death(PlayerDeathEvent e){plugin.dialogues().cancel(e.getPlayer().getUniqueId(),"Conversation cancelled.");plugin.quests().death(e.getPlayer());}
    @EventHandler(ignoreCancelled=true) public void npcClick(NPCRightClickEvent e){var routed=plugin.behaviors().projections().routed(e.getNPC(),e.getClicker());if(routed==null){e.setCancelled(true);return;}plugin.behaviors().projections().markInteraction(e.getClicker(),routed);plugin.behaviors().wake(routed,e.getClicker(),"interaction",Map.of());plugin.dialogues().begin(e.getClicker(),routed);}
    @EventHandler(ignoreCancelled=true) public void npcLeftClick(NPCLeftClickEvent e){var routed=plugin.behaviors().projections().routed(e.getNPC(),e.getClicker());if(routed==null){e.setCancelled(true);return;}plugin.behaviors().projections().markInteraction(e.getClicker(),routed);plugin.behaviors().wake(routed,e.getClicker(),"interaction",Map.of("button","left"));plugin.dialogues().begin(e.getClicker(),routed,false);}
    @EventHandler public void npcSelect(NPCSelectEvent e){if(e.getSelector() instanceof org.bukkit.entity.Player player)routeSelection(e.getNPC(),player,npc->CitizensAPI.getDefaultNPCSelector().select(e.getSelector(),npc));}
    void routeSelection(net.citizensnpcs.api.npc.NPC selected,org.bukkit.entity.Player player,java.util.function.Consumer<net.citizensnpcs.api.npc.NPC> select){var routed=plugin.behaviors().projections().routed(selected,player);if(routed!=null&&routed!=selected)select.accept(routed);}
    @EventHandler(ignoreCancelled=true) public void npcDamage(NPCDamageByEntityEvent e){if(!(e.getDamager() instanceof org.bukkit.entity.Player player))return;var routed=plugin.behaviors().projections().routed(e.getNPC(),player);if(routed==null){e.setCancelled(true);return;}plugin.behaviors().wake(routed,player,"damage",Map.of("amount",e.getDamage()));runNpcGraph(routed,player,"damage",Map.of("damage",e.getDamage()));}
    @EventHandler public void npcSpawn(NPCSpawnEvent e){PersonaTrait t=e.getNPC().getTraitNullable(PersonaTrait.class);if(t!=null&&t.bound()&&!t.projection()){plugin.behaviors().actorLifecycle(e.getNPC(),"spawn",Map.of());runNpcGraph(e.getNPC(),null,"spawn",Map.of());}}
    @EventHandler public void npcDespawn(NPCDespawnEvent e){plugin.dialogues().cancelNpc(e.getNPC());PersonaTrait t=e.getNPC().getTraitNullable(PersonaTrait.class);if(t!=null&&t.bound()&&!t.projection()){plugin.behaviors().actorLifecycle(e.getNPC(),"despawn",Map.of("reason",e.getReason().name()));runNpcGraph(e.getNPC(),null,"despawn",Map.of("reason",e.getReason().name())).whenComplete((ignored,error)->plugin.scripts().clearStateForNpc(instance(e.getNPC(),t)));}}
    @EventHandler public void changedWorld(PlayerChangedWorldEvent e){plugin.behaviors().playerStateChanged(e.getPlayer(),"world-change",Map.of("from",e.getFrom().getName(),"to",e.getPlayer().getWorld().getName()));}
    @EventHandler(ignoreCancelled=true) public void entityDeath(EntityDeathEvent e){if(e.getEntity().getKiller()!=null)plugin.quests().kill(e.getEntity().getKiller(),e.getEntityType());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void interact(PlayerInteractEvent e){if((e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_BLOCK)&&e.getClickedBlock()!=null)plugin.quests().interact(e.getPlayer(),e.getClickedBlock().getLocation(),e.getClickedBlock().getType());laterCollect(e.getPlayer());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void drop(PlayerDropItemEvent e){laterCollect(e.getPlayer());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void pickup(PlayerAttemptPickupItemEvent e){laterCollect(e.getPlayer());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof org.bukkit.entity.Player p)laterCollect(p);}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof org.bukkit.entity.Player p)laterCollect(p);}
    @EventHandler(priority=EventPriority.MONITOR) public void close(InventoryCloseEvent e){if(e.getPlayer() instanceof org.bukkit.entity.Player p)laterCollect(p);}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void move(PlayerMoveEvent e){if(!e.hasChangedBlock())return;long now=System.currentTimeMillis();if(now-lastMove.getOrDefault(e.getPlayer().getUniqueId(),0L)<500)return;lastMove.put(e.getPlayer().getUniqueId(),now);plugin.quests().move(e.getPlayer(),e.getTo());}
    private void laterCollect(org.bukkit.entity.Player p){plugin.getServer().getScheduler().runTask(plugin,()->plugin.quests().collect(p));}
    private java.util.concurrent.CompletionStage<nu.miguel.persona.script.ScriptEngine.Control> runNpcGraph(net.citizensnpcs.api.npc.NPC citizensNpc,Player player,String event,Map<String,Object> extra){PersonaTrait trait=citizensNpc.getTraitNullable(PersonaTrait.class);if(trait==null||!trait.bound())return java.util.concurrent.CompletableFuture.completedFuture(nu.miguel.persona.script.ScriptEngine.Control.next());Npc npc=plugin.registry().npcs().get(trait.definitionId());if(npc==null)return java.util.concurrent.CompletableFuture.completedFuture(nu.miguel.persona.script.ScriptEngine.Control.stop());ScriptDefinition graph=switch(event){case "damage"->npc.onDamage();case "spawn"->npc.onSpawn();case "despawn"->npc.onDespawn();default->null;};Map<String,Object> values=new HashMap<>();values.put("npc",npc.id());values.put("npc-instance",instance(citizensNpc,trait));if(player!=null)values.put("player",player);values.putAll(extra);return plugin.scripts().runNpcEvent(graph,values,new EffectExecutor.Context(player,citizensNpc,npc,null,null,null,0,0));}
    private static String instance(net.citizensnpcs.api.npc.NPC npc,PersonaTrait trait){return trait.instanceId()==null?npc.getUniqueId().toString():trait.instanceId();}
}
