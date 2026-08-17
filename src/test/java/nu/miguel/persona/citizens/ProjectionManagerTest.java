package nu.miguel.persona.citizens;

import net.citizensnpcs.api.npc.MetadataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import nu.miguel.persona.Main;
import nu.miguel.persona.behavior.BehaviorRuntime.LogicalPosition;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectionManagerTest {
    @Test void prioritiesFavorDialogueNavigationQuestAndRecentInteraction(){
        long now=1_000_000;
        double idle=ProjectionManager.priorityScore(new ProjectionManager.Priority(10,0,false,false,false),now);
        assertTrue(ProjectionManager.priorityScore(new ProjectionManager.Priority(10,now-1_000,true,false,false),now)>idle);
        assertTrue(ProjectionManager.priorityScore(new ProjectionManager.Priority(10,0,false,false,true),now)>idle);
        assertTrue(ProjectionManager.priorityScore(new ProjectionManager.Priority(10,0,false,true,false),now)>idle);
        assertTrue(ProjectionManager.priorityScore(new ProjectionManager.Priority(2,0,false,false,false),now)>ProjectionManager.priorityScore(new ProjectionManager.Priority(40,0,false,false,false),now));
    }

    @Test void twoPlayersKeepIndependentPresentationsAndOnlySeeTheirOwn(){
        Main plugin=mock(Main.class);FileConfiguration config=mock(FileConfiguration.class);Server server=mock(Server.class);World world=mock(World.class);NPCRegistry registry=mock(NPCRegistry.class);
        when(plugin.getConfig()).thenReturn(config);when(plugin.getServer()).thenReturn(server);when(world.getName()).thenReturn("world");when(server.getWorld("world")).thenReturn(world);
        Player first=player("first",world,0);Player second=player("second",world,20);when(server.getPlayer(first.getUniqueId())).thenReturn(first);when(server.getPlayer(second.getUniqueId())).thenReturn(second);doReturn(List.of(first,second)).when(server).getOnlinePlayers();
        NPC base=mock(NPC.class);when(base.getUniqueId()).thenReturn(UUID.randomUUID());when(base.getCosmeticEntityType()).thenReturn(EntityType.ARMOR_STAND);when(base.getRawName()).thenReturn("Guide");when(base.data()).thenReturn(mock(MetadataStore.class));
        PersonaTrait baseTrait=mock(PersonaTrait.class);when(baseTrait.definitionId()).thenReturn("demo:guide");when(base.getTraitNullable(PersonaTrait.class)).thenReturn(baseTrait);
        NPC one=projection();NPC two=projection();when(registry.createNPC(any(),anyString())).thenReturn(one,two);
        ProjectionManager manager=new ProjectionManager(plugin,registry);
        LogicalPosition a=new LogicalPosition("world",0,64,0,0,0),b=new LogicalPosition("world",20,64,0,0,0);
        manager.begin(first,base,"a",a);manager.begin(second,base,"b",b);
        assertSame(one,manager.inspect(first,base).projection());assertEquals(a,manager.inspect(first,base).position());
        assertSame(two,manager.inspect(second,base).projection());assertEquals(b,manager.inspect(second,base).position());
        verify(second).hideEntity(plugin,one.getEntity());verify(first).hideEntity(plugin,two.getEntity());
        verify(first).showEntity(plugin,one.getEntity());verify(second).showEntity(plugin,two.getEntity());
        assertEquals(2,manager.counts().server());
    }

    @Test void copiesCitizensSkinAsNameSignatureThenTexture(){
        Main plugin=mock(Main.class);FileConfiguration config=mock(FileConfiguration.class);Server server=mock(Server.class);World world=mock(World.class);NPCRegistry registry=mock(NPCRegistry.class);when(plugin.getConfig()).thenReturn(config);when(plugin.getServer()).thenReturn(server);when(world.getName()).thenReturn("world");when(server.getWorld("world")).thenReturn(world);when(server.getOnlinePlayers()).thenReturn(List.of());
        Player viewer=player("viewer",world,0);when(server.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
        NPC base=mock(NPC.class);when(base.getUniqueId()).thenReturn(UUID.randomUUID());when(base.getCosmeticEntityType()).thenReturn(EntityType.PLAYER);when(base.getRawName()).thenReturn("Guide");when(base.data()).thenReturn(mock(MetadataStore.class));PersonaTrait trait=mock(PersonaTrait.class);when(trait.definitionId()).thenReturn("demo:guide");when(base.getTraitNullable(PersonaTrait.class)).thenReturn(trait);
        SkinTrait source=mock(SkinTrait.class);when(source.getSkinName()).thenReturn("skin-key");when(source.getSignature()).thenReturn("signature");when(source.getTexture()).thenReturn("texture");when(base.getTraitNullable(SkinTrait.class)).thenReturn(source);
        NPC projection=projection();SkinTrait target=mock(SkinTrait.class);when(projection.getTraitNullable(SkinTrait.class)).thenReturn(target);when(projection.getOrAddTrait(SkinTrait.class)).thenReturn(target);when(registry.createNPC(any(),anyString())).thenReturn(projection);
        new ProjectionManager(plugin,registry).begin(viewer,base,null,new LogicalPosition("world",0,64,0,0,0));
        verify(target).setSkinPersistent("skin-key","signature","texture");
    }

    private static Player player(String name,World world,double x){Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getName()).thenReturn(name);when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(new org.bukkit.Location(world,x,64,0));return player;}
    private static NPC projection(){NPC npc=mock(NPC.class);Entity entity=mock(Entity.class);PersonaTrait trait=mock(PersonaTrait.class);MetadataStore data=mock(MetadataStore.class);AtomicBoolean spawned=new AtomicBoolean();when(npc.spawn(any())).thenAnswer(i->{spawned.set(true);return true;});when(npc.isSpawned()).thenAnswer(i->spawned.get());when(npc.getEntity()).thenReturn(entity);when(npc.getStoredLocation()).thenAnswer(i->null);when(npc.getOrAddTrait(PersonaTrait.class)).thenReturn(trait);when(npc.getTrait(PersonaTrait.class)).thenReturn(trait);when(npc.data()).thenReturn(data);return npc;}
}
