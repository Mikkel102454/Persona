package nu.miguel.persona.content;

import nu.miguel.persona.Main;
import nu.miguel.persona.api.*;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExtensionContentLoaderTest {
    @TempDir Path root;
    @Test void parsesNamespacedCommandsConditionsAndObjectives() throws Exception {PersonaApi api=new PersonaApi(mock(Main.class));api.register(extension());dirs();
        Files.writeString(root.resolve("npcs/example.yml"),"""
                content-version: 2
                id: example:npc
                dialogues:
                  - id: example:dialogue
                    when: { type: test:allowed, value: yes }
                """);
        Files.writeString(root.resolve("dialogues/example.yml"),"""
                content-version: 2
                id: example:dialogue
                start: first
                nodes:
                  first:
                    graph:
                      variables: {}
                      nodes:
                        mark: { type: test:mark, value: yes }
                        end: { type: end-dialogue }
                      connections:
                        enter: { from: $event.exec, to: mark.exec }
                        finish: { from: mark.success, to: end.exec }
                """);
        Files.writeString(root.resolve("quests/example.yml"),"""
                content-version: 2
                id: example:quest
                when: { type: test:allowed, value: yes }
                phases:
                  - id: first
                    objectives: [ { id: custom, type: test:event, amount: 3 } ]
                """);
        Content.Registry registry=loader(api).load();assertEquals("test:mark",registry.dialogues().get("example:dialogue").nodes().get("first").graph().nodes().get("mark").type());assertInstanceOf(Content.CustomCondition.class,registry.quests().get("example:quest").requirements());assertEquals(Content.ObjectiveType.CUSTOM,registry.quests().get("example:quest").phases().getFirst().objectives().getFirst().type());}
    @Test void unavailableTypeIncludesNamespacedKey() throws Exception {dirs();Files.writeString(root.resolve("quests/bad.yml"),"""
            content-version: 2
            id: example:bad
            phases:
              - id: one
                objectives: [ { id: missing, type: missing:event } ]
            """);PersonaApi api=new PersonaApi(mock(Main.class));ContentException e=assertThrows(ContentException.class,()->loader(api).load());assertTrue(e.errors().stream().anyMatch(x->x.contains("missing:event")));}
    private void dirs() throws Exception {Files.createDirectories(root.resolve("npcs"));Files.createDirectories(root.resolve("dialogues"));Files.createDirectories(root.resolve("quests"));Files.createDirectories(root.resolve("scripts"));}
    private ContentLoader loader(PersonaApi api){return new ContentLoader(root.toFile(),Duration.ZERO,x->Material.STONE,x->EntityType.ZOMBIE,api);}
    private static PersonaExpansion extension(){return new PersonaExpansion(){public String identifier(){return "test";}public String author(){return "test";}public String version(){return "2";}protected void registerTypes(ExpansionRegistrar r){r.condition("allowed",(c,d)->true);r.command("mark",(c,d)->CompletableFuture.completedFuture(ExpansionTypes.CommandResult.success()));r.objective("event",yaml->new ExpansionTypes.ObjectiveDefinition(((Number)yaml.get("amount")).longValue(),yaml));}};}
}
