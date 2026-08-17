package nu.miguel.persona.content;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ContentValidationCompatibilityTest {
    @TempDir Path root;

    @Test void aggregatesCategoriesWithLocationsAndSuggestions()throws Exception{
        dirs();Files.writeString(root.resolve("behaviors/a.yml"),"""
                id: test:tree
                scope: player
                root:
                  id: root
                  type: sequence
                  duraton: 1s
                  children: [ { id: wait, type: wait, duration: 1s } ]
                """);
        Files.writeString(root.resolve("npcs/a.yml"),"""
                id: test:npc
                display-nam: Typo
                """);
        Files.writeString(root.resolve("dialogues/a.yml"),"""
                id: test:dialogue
                start: missing
                nodes: {}
                """);
        Files.writeString(root.resolve("quests/a.yml"),"""
                id: test:quest
                phases: []
                """);
        Files.writeString(root.resolve("scripts.yml"),"""
                scripts:
                  broken: [ { type: say } ]
                """);
        ContentException failure=assertThrows(ContentException.class,()->loader().load());
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("behaviors/a.yml")&&x.contains("duraton")),failure.errors().toString());
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("npcs/a.yml")&&x.contains("display-name")));
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("dialogues/a.yml")));
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("quests/a.yml")));
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("scripts.yml")));
        assertTrue(failure.errors().stream().allMatch(x->x.matches(".*:\\d+:\\d+: .*")),failure.errors().toString());
    }

    @Test void validatesBehaviorScriptsAndCommandsThroughNormalParser()throws Exception{
        dirs();Files.writeString(root.resolve("behaviors/a.yml"),"""
                id: test:tree
                scope: player
                root:
                  id: root
                  type: sequence
                  children:
                    - { id: script, type: action, action: script, script: absent }
                    - { id: command, type: action, action: command, command: give-item, material: minecraft:stone, amount: 0 }
                """);
        ContentException failure=assertThrows(ContentException.class,()->loader().load());
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("missing script absent")),failure.errors().toString());
        assertTrue(failure.errors().stream().anyMatch(x->x.contains("give-item amount must be positive")),failure.errors().toString());
    }

    @Test void conflictingBehaviorIdsNameBothFiles()throws Exception{
        dirs();String yaml="id: test:same\nscope: player\nroot: { id: wait, type: wait, duration: 1s }\n";Files.writeString(root.resolve("behaviors/first.yml"),yaml);Files.writeString(root.resolve("behaviors/second.yml"),yaml);
        ContentException failure=assertThrows(ContentException.class,()->loader().load());String message=String.join("\n",failure.errors());assertTrue(message.contains("second.yml"));assertTrue(message.contains("first.yml"));
    }

    @Test void contentFormatIsIndependentAndRejectsFutureVersion()throws Exception{
        dirs();Files.writeString(root.resolve("npcs/a.yml"),"content-version: 2\nid: test:npc\n");ContentException failure=assertThrows(ContentException.class,()->loader().load());assertTrue(failure.getMessage().contains("supported version is 1"));
    }

    private void dirs()throws Exception{for(String name:new String[]{"behaviors","npcs","dialogues","quests"})Files.createDirectories(root.resolve(name));}
    private ContentLoader loader(){return new ContentLoader(root.toFile(),Duration.ZERO,x->Material.STONE,x->EntityType.ZOMBIE);}
}
