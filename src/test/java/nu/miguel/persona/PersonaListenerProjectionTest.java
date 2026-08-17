package nu.miguel.persona;

import net.citizensnpcs.api.event.NPCDamageByEntityEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import nu.miguel.persona.behavior.BehaviorService;
import nu.miguel.persona.citizens.ProjectionManager;
import nu.miguel.persona.dialogue.DialogueService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonaListenerProjectionTest {
    @Test void rightClickRoutesBehaviorAndDialogueToLogicalBase(){
        Fixture f=new Fixture();NPCRightClickEvent event=mock(NPCRightClickEvent.class);when(event.getNPC()).thenReturn(f.projection);when(event.getClicker()).thenReturn(f.player);when(f.manager.routed(f.projection,f.player)).thenReturn(f.base);
        f.listener.npcClick(event);
        verify(f.manager).markInteraction(f.player,f.base);verify(f.behaviors).wake(eq(f.base),eq(f.player),eq("interaction"),anyMap());verify(f.dialogues).begin(f.player,f.base);
    }

    @Test void leftClickAndDamageRouteToLogicalBase(){
        Fixture f=new Fixture();when(f.manager.routed(f.projection,f.player)).thenReturn(f.base);
        NPCLeftClickEvent left=mock(NPCLeftClickEvent.class);when(left.getNPC()).thenReturn(f.projection);when(left.getClicker()).thenReturn(f.player);f.listener.npcLeftClick(left);
        NPCDamageByEntityEvent damage=mock(NPCDamageByEntityEvent.class);when(damage.getNPC()).thenReturn(f.projection);when(damage.getDamager()).thenReturn(f.player);when(damage.getDamage()).thenReturn(2d);f.listener.npcDamage(damage);
        verify(f.behaviors).wake(eq(f.base),eq(f.player),eq("interaction"),anyMap());verify(f.behaviors).wake(eq(f.base),eq(f.player),eq("damage"),anyMap());
    }

    @Test void foreignProjectionInteractionIsRejected(){
        Fixture f=new Fixture();NPCRightClickEvent event=mock(NPCRightClickEvent.class);when(event.getNPC()).thenReturn(f.projection);when(event.getClicker()).thenReturn(f.player);when(f.manager.routed(f.projection,f.player)).thenReturn(null);
        f.listener.npcClick(event);verify(event).setCancelled(true);verifyNoInteractions(f.dialogues);
    }

    @Test void selectionOfProjectionSelectsLogicalBase(){
        Fixture f=new Fixture();when(f.manager.routed(f.projection,f.player)).thenReturn(f.base);@SuppressWarnings("unchecked") Consumer<NPC> selector=mock(Consumer.class);
        f.listener.routeSelection(f.projection,f.player,selector);verify(selector).accept(f.base);
    }

    private static final class Fixture {final Main plugin=mock(Main.class);final BehaviorService behaviors=mock(BehaviorService.class);final ProjectionManager manager=mock(ProjectionManager.class);final DialogueService dialogues=mock(DialogueService.class);final NPC projection=mock(NPC.class),base=mock(NPC.class);final Player player=mock(Player.class);final PersonaListener listener;Fixture(){when(plugin.behaviors()).thenReturn(behaviors);when(plugin.dialogues()).thenReturn(dialogues);when(behaviors.projections()).thenReturn(manager);listener=new PersonaListener(plugin);}}
}
