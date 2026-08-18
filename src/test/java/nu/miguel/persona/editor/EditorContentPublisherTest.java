package nu.miguel.persona.editor;

import nu.miguel.persona.editor.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EditorContentPublisherTest {
    @TempDir Path live;

    @Test void revalidatesBacksUpAndActivatesWithoutReserializingYaml() throws Exception {
        String original = graph("# original comment\n", "original");
        Files.createDirectories(live.resolve("scripts"));Files.writeString(live.resolve("scripts/demo.yml"), original);
        String candidate = graph("# candidate comment\n", "hello");
        PublishProject project = project(original, candidate);
        AtomicBoolean activated = new AtomicBoolean();

        PublishApplyResult result = EditorContentPublisher.publish(live, project, Duration.ZERO, null, registry -> {
            assertTrue(registry.scripts().containsKey("hello")); activated.set(true);
        });

        assertTrue(result.success(), result.error()); assertTrue(activated.get());
        assertEquals(candidate, Files.readString(live.resolve("scripts/demo.yml")));
        assertEquals(original, Files.readString(live.resolve(result.backupId()).resolve("scripts/demo.yml")));
        assertTrue(Files.readString(live.resolve(result.backupId()).resolve("publish.properties"))
                .contains("publish-id=" + project.publishId()));
    }

    @Test void publishesWhenPaperSuppliesARelativeDataFolder() throws Exception {
        String original = graph("", "original"), candidate = graph("", "changed");
        Files.createDirectories(live.resolve("scripts"));
        Files.writeString(live.resolve("scripts/demo.yml"), original);
        Path relativeLive = Path.of("").toAbsolutePath().normalize().relativize(live.toAbsolutePath().normalize());

        PublishApplyResult result = EditorContentPublisher.publish(relativeLive, project(original, candidate),
                Duration.ZERO, null, registry -> {});

        assertFalse(relativeLive.isAbsolute());
        assertTrue(result.success(), result.error());
        assertEquals(candidate, Files.readString(live.resolve("scripts/demo.yml")));
        assertTrue(result.backupId().startsWith("backups/editor-content/"));
        assertTrue(Files.isRegularFile(live.resolve(result.backupId()).resolve("publish.properties")));
    }

    @Test void restoresExactFilesWhenRuntimeActivationFails() throws Exception {
        String original = graph("# retained verbatim\n", "original");
        Files.createDirectories(live.resolve("scripts"));Files.writeString(live.resolve("scripts/demo.yml"), original);
        PublishProject project = project(original, graph("", "changed"));

        PublishApplyResult result = EditorContentPublisher.publish(live, project, Duration.ZERO, null,
                registry -> { throw new IllegalStateException("runtime swap failed"); });

        assertFalse(result.success()); assertTrue(result.error().contains("rolled back"));
        assertEquals(original, Files.readString(live.resolve("scripts/demo.yml")));
        assertNotNull(result.backupId());
    }

    @Test void explicitRollbackVerifiesManifestRevalidatesAndCreatesSafetyBackup() throws Exception {
        String original = graph("# original\n", "original"), changed = graph("# published\n", "new");
        Files.createDirectories(live.resolve("scripts"));Files.writeString(live.resolve("scripts/demo.yml"), original);
        PublishProject publish = project(original, changed);
        PublishApplyResult applied = EditorContentPublisher.publish(live, publish, Duration.ZERO, null, registry -> {});
        assertTrue(applied.success()); UUID rollbackId = UUID.randomUUID();
        RollbackProject rollback = new RollbackProject(Protocol.VERSION, rollbackId, publish.publishId(),
                publish.sessionId(), publish.scope(), publish.proposedRevision(), publish.baseRevision(), applied.backupId());

        RollbackApplyResult result = EditorContentPublisher.rollback(live, rollback, Duration.ZERO, null,
                registry -> assertTrue(registry.scripts().containsKey("original")));

        assertTrue(result.success(), result.error()); assertEquals(publish.baseRevision(), result.activeRevision());
        assertEquals(original, Files.readString(live.resolve("scripts/demo.yml")));
        assertEquals(changed, Files.readString(live.resolve(result.safetyBackupId()).resolve("scripts/demo.yml")));
    }

    @Test void rejectsStaleBaseBeforeCreatingBackup() throws Exception {
        Files.createDirectories(live.resolve("scripts"));Files.writeString(live.resolve("scripts/demo.yml"), graph("", "old"));
        ContentFile candidate = file("scripts/demo.yml", graph("", "new"));
        PublishProject project = new PublishProject(Protocol.VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EditorScope.SCRIPTS, "0".repeat(64), ContentProjectRevision.compute(List.of(candidate)), List.of(candidate));

        PublishApplyResult result = EditorContentPublisher.publish(live, project, Duration.ZERO, null, registry -> fail());

        assertFalse(result.success()); assertNull(result.backupId());
        assertFalse(Files.exists(live.resolve("backups/editor-content").resolve(project.publishId().toString())));
    }

    private PublishProject project(String original, String candidate) throws Exception {
        ContentFile old = file("scripts/demo.yml", original), changed = file("scripts/demo.yml", candidate);
        return new PublishProject(Protocol.VERSION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EditorScope.SCRIPTS, ContentProjectRevision.compute(List.of(old)),
                ContentProjectRevision.compute(List.of(changed)), List.of(changed));
    }
    private static ContentFile file(String path, String content) throws Exception {
        return new ContentFile(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))), content);
    }
    private static String graph(String comment,String id) {
        return comment+"content-version: 2\nid: "+id+"\ninputs: {}\noutputs: {}\nvariables: {}\nnodes:\n  pause: { type: wait, duration: 1ms }\nconnections:\n  enter: { from: $input.exec, to: pause.exec }\n  leave: { from: pause.success, to: $output.exec }\n";
    }
}
