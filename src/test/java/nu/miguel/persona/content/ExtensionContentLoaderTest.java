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
                id: example:npc
                dialogues:
                  - id: example:dialogue
                    when: { type: test:allowed, value: yes }
                """);
        Files.writeString(root.resolve("dialogues/example.yml"),"""
                id: example:dialogue
                start: first
                nodes:
                  first:
                    script:
                      - { type: test:mark, value: yes }
                      - { type: end-dialogue }
                """);
        Files.writeString(root.resolve("quests/example.yml"),"""
                id: example:quest
                when: { type: test:allowed, value: yes }
                phases:
                  - id: first
                    objectives: [ { id: custom, type: test:event, amount: 3 } ]
                """);
        Content.Registry registry=loader(api).load();assertInstanceOf(Content.Command.class,registry.dialogues().get("example:dialogue").nodes().get("first").script().getFirst());assertInstanceOf(Content.CustomCondition.class,registry.quests().get("example:quest").requirements());assertEquals(Content.ObjectiveType.CUSTOM,registry.quests().get("example:quest").phases().getFirst().objectives().getFirst().type());}
    @Test void unavailableTypeIncludesNamespacedKey() throws Exception {dirs();Files.writeString(root.resolve("quests/bad.yml"),"""
            id: example:bad
            phases:
              - id: one
                objectives: [ { id: missing, type: missing:event } ]
            """);PersonaApi api=new PersonaApi(mock(Main.class));ContentException e=assertThrows(ContentException.class,()->loader(api).load());assertTrue(e.errors().stream().anyMatch(x->x.contains("missing:event")));}
    private void dirs() throws Exception {Files.createDirectories(root.resolve("npcs"));Files.createDirectories(root.resolve("dialogues"));Files.createDirectories(root.resolve("quests"));}
    private ContentLoader loader(PersonaApi api){return new ContentLoader(root.toFile(),Duration.ZERO,x->Material.STONE,x->EntityType.ZOMBIE,api);}
    private static PersonaExpansion extension(){return new PersonaExpansion(){public String identifier(){return "test";}public String author(){return "test";}public String version(){return "2";}protected void registerTypes(ExpansionRegistrar r){r.condition("allowed",(c,d)->true);r.command("mark",(c,d)->CompletableFuture.completedFuture(ExpansionTypes.CommandResult.success()));r.objective("event",yaml->new ExpansionTypes.ObjectiveDefinition(((Number)yaml.get("amount")).longValue(),yaml));}};}
}
