package nu.miguel.persona;

import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import nu.miguel.persona.citizens.PersonaTrait;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
    @EventHandler public void quit(PlayerQuitEvent e){plugin.behaviors().playerQuit(e.getPlayer());plugin.dialogues().cancel(e.getPlayer().getUniqueId(),null);plugin.quests().tickPlayer(e.getPlayer(),false);plugin.states().unload(e.getPlayer());lastMove.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void death(PlayerDeathEvent e){plugin.dialogues().cancel(e.getPlayer().getUniqueId(),"Conversation cancelled.");plugin.quests().death(e.getPlayer());}
    @EventHandler(ignoreCancelled=true) public void npcClick(NPCRightClickEvent e){var routed=plugin.behaviors().projections().routed(e.getNPC(),e.getClicker());if(routed==null){e.setCancelled(true);return;}plugin.behaviors().wake(routed,e.getClicker(),"interaction",Map.of());plugin.dialogues().begin(e.getClicker(),routed);}
    @EventHandler public void npcDespawn(NPCDespawnEvent e){plugin.dialogues().cancelNpc(e.getNPC());}
    @EventHandler(ignoreCancelled=true) public void entityDeath(EntityDeathEvent e){if(e.getEntity().getKiller()!=null)plugin.quests().kill(e.getEntity().getKiller(),e.getEntityType());}
    @EventHandler(ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){var npc=CitizensAPI.getNPCRegistry().getNPC(e.getEntity());if(npc!=null){org.bukkit.entity.Player player=e.getDamager() instanceof org.bukkit.entity.Player p?p:null;plugin.behaviors().wake(npc,player,"damage",Map.of("amount",e.getFinalDamage()));}}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void interact(PlayerInteractEvent e){if((e.getAction()==Action.RIGHT_CLICK_BLOCK||e.getAction()==Action.LEFT_CLICK_BLOCK)&&e.getClickedBlock()!=null)plugin.quests().interact(e.getPlayer(),e.getClickedBlock().getLocation(),e.getClickedBlock().getType());laterCollect(e.getPlayer());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void drop(PlayerDropItemEvent e){laterCollect(e.getPlayer());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void pickup(PlayerAttemptPickupItemEvent e){laterCollect(e.getPlayer());}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof org.bukkit.entity.Player p)laterCollect(p);}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof org.bukkit.entity.Player p)laterCollect(p);}
    @EventHandler(priority=EventPriority.MONITOR) public void close(InventoryCloseEvent e){if(e.getPlayer() instanceof org.bukkit.entity.Player p)laterCollect(p);}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void move(PlayerMoveEvent e){if(!e.hasChangedBlock())return;long now=System.currentTimeMillis();if(now-lastMove.getOrDefault(e.getPlayer().getUniqueId(),0L)<500)return;lastMove.put(e.getPlayer().getUniqueId(),now);plugin.quests().move(e.getPlayer(),e.getTo());}
    private void laterCollect(org.bukkit.entity.Player p){plugin.getServer().getScheduler().runTask(plugin,()->plugin.quests().collect(p));}
}
