package nu.miguel.persona.content;

import org.junit.jupiter.api.Test;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;

import static nu.miguel.persona.content.Content.ObjectiveType.*;
import static org.junit.jupiter.api.Assertions.*;

class PackagedSamplesTest {
    @TempDir Path temp;
    @Test void activeSamplesFormAValidGraphAndCoverEveryObjectiveType() throws Exception {
        Path examples=Path.of("src/main/resources/examples");
        for(String directory:Set.of("npcs","dialogues","quests","behaviors","scripts")){Files.createDirectories(temp.resolve(directory));try(var files=Files.list(examples.resolve(directory))){for(Path source:files.toList()){String name=source.getFileName().toString().replace(".example","");Files.copy(source,temp.resolve(directory).resolve(name));}}}
        File content = temp.toFile();
        Content.Registry registry = new ContentLoader(content, Duration.ofSeconds(2),
                raw -> switch (raw) {
                    case "minecraft:bread" -> Material.BREAD;
                    case "minecraft:crafting_table" -> Material.CRAFTING_TABLE;
                    case "minecraft:diamond" -> Material.DIAMOND;
                    case "minecraft:oak_log" -> Material.OAK_LOG;
                    case "minecraft:emerald" -> Material.EMERALD;
                    default -> null;
                },
                raw -> raw.equals("minecraft:zombie") ? EntityType.ZOMBIE : null).load();

        assertTrue(registry.npcs().containsKey("guild:trial_master"));
        assertTrue(registry.dialogues().containsKey("guild:trial_intro"));
        Content.Quest trial = registry.quests().get("guild:adventurers_trial");
        assertNotNull(trial);

        Set<Content.ObjectiveType> types = trial.phases().stream()
                .flatMap(phase -> phase.objectives().stream())
                .map(Content.Objective::type)
                .collect(Collectors.toSet());
        assertEquals(Set.of(COLLECT_ITEM, TALK_TO_NPC, KILL_ENTITY, GO_TO_LOCATION,
                INTERACT_BLOCK, WAIT, SURVIVE), types);
    }

}
