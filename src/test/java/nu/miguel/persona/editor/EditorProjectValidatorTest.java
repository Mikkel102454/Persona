package nu.miguel.persona.editor;

import nu.miguel.persona.editor.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EditorProjectValidatorTest {
    @TempDir Path live;

    @Test void validatesThroughNormalLoaderWithoutChangingLiveFilesAndReturnsStructuredFixes() throws Exception {
        Files.createDirectories(live.resolve("behaviors"));
        String original = "# live comment\nid: test:live\nscope: player\nroot: { id: wait, type: wait, duration: 1s }\n";
        Files.writeString(live.resolve("behaviors/tree.yml"), original);
        String candidate = "# candidate comment\nid: test:live\nscope: player\nroot:\n  id: root\n  type: wait\n  duration: 1s\n  duraton: 1s\n";
        UUID requestId = UUID.randomUUID(), draftId = UUID.randomUUID();
        List<ContentFile> files = List.of(file("behaviors/tree.yml", candidate));
        ValidationProject project = new ValidationProject(Protocol.VERSION, requestId, UUID.randomUUID(), draftId,
                EditorScope.BEHAVIORS, "a".repeat(64), ContentProjectRevision.compute(files), files);

        ValidationResult result = EditorProjectValidator.validate(live, project, Duration.ZERO, null);

        assertFalse(result.valid());
        assertEquals(requestId, result.requestId()); assertEquals(draftId, result.draftId());
        ValidationDiagnostic issue = result.diagnostics().stream()
                .filter(item -> item.path().equals("behaviors/tree.yml") && item.message().contains("duraton"))
                .findFirst().orElseThrow(() -> new AssertionError(result.diagnostics().toString()));
        assertEquals(8, issue.line()); assertEquals("root", issue.nodeId());
        assertEquals("Replace with 'duration'.", issue.suggestion());
        assertEquals(original, Files.readString(live.resolve("behaviors/tree.yml")));
        Path temporary = live.resolve(".editor-validation");
        if (Files.exists(temporary)) try (var entries = Files.list(temporary)) { assertEquals(0, entries.count()); }
    }

    @Test void structuresMissingContentReferences() throws Exception {
        Files.createDirectories(live.resolve("npcs"));
        String npc = "id: test:guide\ndialogues:\n  - id: test:absent\n";
        List<ContentFile> files = List.of(file("npcs/guide.yml", npc));
        ValidationProject project = new ValidationProject(Protocol.VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), EditorScope.NPCS, "b".repeat(64), ContentProjectRevision.compute(files), files);

        ValidationResult result = EditorProjectValidator.validate(live, project, Duration.ZERO, null);

        ValidationDiagnostic issue = result.diagnostics().stream()
                .filter(item -> "test:absent".equals(item.referenceId())).findFirst().orElseThrow();
        assertEquals("dialogue", issue.referenceType());
        assertEquals("test:guide", issue.nodeId());
    }

    private static ContentFile file(String path, String content) throws Exception {
        return new ContentFile(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))), content);
    }
}
