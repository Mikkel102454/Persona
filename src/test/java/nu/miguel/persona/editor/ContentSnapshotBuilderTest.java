package nu.miguel.persona.editor;

import nu.miguel.persona.editor.protocol.EditorScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContentSnapshotBuilderTest {
    @TempDir Path root;

    @Test void readsOnlyYamlForRequestedScopeWithStableRevision() throws Exception {
        Files.createDirectories(root.resolve("behaviors/nested"));
        Files.createDirectories(root.resolve("npcs"));
        Files.writeString(root.resolve("behaviors/tree.yml"), "id: test:tree\n");
        Files.writeString(root.resolve("behaviors/nested/tree.yaml"), "id: test:nested\n");
        Files.writeString(root.resolve("behaviors/ignored.txt"), "secret");
        Files.writeString(root.resolve("npcs/actor.yml"), "id: test:actor\n");
        Files.writeString(root.resolve("scripts.yml"), "scripts: {}\n");

        var first = ContentSnapshotBuilder.read(root, EditorScope.BEHAVIORS);
        var second = ContentSnapshotBuilder.read(root, EditorScope.BEHAVIORS);

        assertEquals(first.revision(), second.revision());
        assertEquals(java.util.List.of("behaviors/nested/tree.yaml", "behaviors/tree.yml"),
                first.files().stream().map(file -> file.path()).toList());
        assertTrue(first.files().stream().allMatch(file -> file.sha256().matches("[0-9a-f]{64}")));
    }

    @Test void allScopeIncludesScriptsAndEveryContentDirectory() throws Exception {
        Files.createDirectories(root.resolve("quests"));
        Files.writeString(root.resolve("quests/story.yml"), "id: test:story\n");
        Files.writeString(root.resolve("scripts.yml"), "scripts: {}\n");

        var snapshot = ContentSnapshotBuilder.read(root, EditorScope.ALL);

        assertEquals(java.util.List.of("quests/story.yml", "scripts.yml"),
                snapshot.files().stream().map(file -> file.path()).toList());
    }

    @Test void preservesCommentsOrderingAliasesAndExtensionDataByteForByte() throws Exception {
        Files.createDirectories(root.resolve("npcs"));
        String yaml = "# authored comment\ndefaults: &defaults\n  visible: true\n"
                + "extension-owned:\n  future-field: [one, two]\nactor:\n  <<: *defaults\n";
        Files.writeString(root.resolve("npcs/actor.yml"), yaml);

        var snapshot = ContentSnapshotBuilder.read(root, EditorScope.NPCS);

        assertEquals(yaml, snapshot.files().getFirst().content());
    }

    @Test void rejectsMalformedUtf8InsteadOfSilentlyReplacingSourceBytes() throws Exception {
        Files.createDirectories(root.resolve("scripts"));
        Files.write(root.resolve("scripts.yml"), new byte[]{(byte) 0xc3, 0x28});

        var error = assertThrows(java.io.IOException.class,
                () -> ContentSnapshotBuilder.read(root, EditorScope.SCRIPTS));
        assertTrue(error.getMessage().contains("not valid UTF-8"));
    }
}
