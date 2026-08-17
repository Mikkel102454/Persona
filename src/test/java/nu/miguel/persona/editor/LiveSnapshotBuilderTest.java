package nu.miguel.persona.editor;

import nu.miguel.persona.Main;
import nu.miguel.persona.api.NpcMemoryService;
import nu.miguel.persona.behavior.BehaviorService;
import nu.miguel.persona.citizens.ProjectionManager;
import nu.miguel.persona.editor.protocol.*;
import nu.miguel.persona.state.*;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveSnapshotBuilderTest {
    @Test void capturesOnlyUuidWorldAndScopedQuestDataForOnlinePlayers()throws Exception{
        UUID playerId=UUID.randomUUID();Main plugin=base();Server server=plugin.getServer();Player player=mock(Player.class);World world=mock(World.class);
        when(player.getUniqueId()).thenReturn(playerId);when(player.getWorld()).thenReturn(world);when(world.getName()).thenReturn("world");doReturn(List.of(player)).when(server).getOnlinePlayers();
        PlayerState state=new PlayerState(playerId);state.quests().put("story:quest",new PlayerState.QuestProgress(0));StateManager states=mock(StateManager.class);when(states.require(player)).thenReturn(state);when(plugin.states()).thenReturn(states);
        LiveSubscribeRequest request=new LiveSubscribeRequest(Protocol.VERSION,UUID.randomUUID(),Set.of(LiveTopic.PLAYERS),LiveFilter.ALL,500);
        LiveStateSnapshot snapshot=LiveSnapshotBuilder.capture(plugin,request,SessionRestrictions.UNRESTRICTED,1,true);
        assertEquals(playerId,snapshot.players().getFirst().playerId());assertEquals("world",snapshot.players().getFirst().world());assertEquals(List.of("story:quest"),snapshot.players().getFirst().activeQuests());
        String json=EditorClient.jsonMapper().writeValueAsString(snapshot);assertFalse(json.contains("address")||json.contains("inventory")||json.contains("chat"));
    }

    @Test void redactsMemoryKeysAndValuesUnlessNamespaceIsConfigured(){
        Main plugin=base();PersistentNpcMemoryService memories=mock(PersistentNpcMemoryService.class);Instant now=Instant.now();when(memories.entries()).thenReturn(List.of(new NpcMemoryService.Entry(null,"story:keeper","one","secret.code",new NpcMemoryService.Value(NpcMemoryService.Type.STRING,"rose",now,now,null,"quest"))));when(plugin.memories()).thenReturn(memories);
        FileConfiguration config=mock(FileConfiguration.class);when(config.getStringList("editor.memory-visible-namespaces")).thenReturn(List.of());when(plugin.getConfig()).thenReturn(config);
        LiveSubscribeRequest request=new LiveSubscribeRequest(Protocol.VERSION,UUID.randomUUID(),Set.of(LiveTopic.MEMORIES),LiveFilter.ALL,500);LiveStateSnapshot snapshot=LiveSnapshotBuilder.capture(plugin,request,SessionRestrictions.UNRESTRICTED,1,true);
        assertTrue(snapshot.memories().getFirst().redacted());assertEquals("<redacted>",snapshot.memories().getFirst().value());assertFalse(snapshot.memories().getFirst().key().contains("secret"));
    }

    private static Main base(){Main plugin=mock(Main.class);Server server=mock(Server.class);when(server.isPrimaryThread()).thenReturn(true);when(server.getOnlinePlayers()).thenReturn(List.of());when(plugin.getServer()).thenReturn(server);BehaviorService behaviors=mock(BehaviorService.class);ProjectionManager projections=mock(ProjectionManager.class);when(behaviors.runtimeSummaries()).thenReturn(List.of());when(behaviors.projections()).thenReturn(projections);when(behaviors.liveMetrics()).thenReturn(new BehaviorService.LiveMetrics(0,0,0,0));when(projections.counts()).thenReturn(new ProjectionManager.Counts(0,0,25,500));when(plugin.behaviors()).thenReturn(behaviors);return plugin;}
}
