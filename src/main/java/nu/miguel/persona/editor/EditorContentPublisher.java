package nu.miguel.persona.editor;

import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.Content;
import nu.miguel.persona.editor.protocol.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Persona-authoritative, recoverable publication transaction. Must run on the server thread. */
public final class EditorContentPublisher {
    private EditorContentPublisher() {}

    @FunctionalInterface public interface Activator { void activate(Content.Registry candidate) throws Exception; }

    public static PublishApplyResult publish(Path liveRoot, PublishProject project, Duration dialogueDelay,
                                             PersonaApi api, Activator activator) {
        String backupId = null;
        try {
            validateEnvelope(project);
            ContentSnapshotBuilder.Project live = ContentSnapshotBuilder.read(liveRoot, project.scope());
            if (!live.revision().equals(project.baseRevision()))
                return failure(project, live.revision(), null, "Live content changed after publish confirmation");
            ValidationProject validation = new ValidationProject(Protocol.VERSION, project.publishId(), project.sessionId(),
                    project.draftId(), project.scope(), project.baseRevision(), project.proposedRevision(), project.files());
            try (EditorProjectValidator.StagedCandidate staged = EditorProjectValidator.stage(
                    liveRoot, validation, dialogueDelay, api)) {
                if (!staged.report().valid())
                    return failure(project, live.revision(), null, "Authoritative validation failed: "
                            + String.join(" | ", staged.report().errors()));
                Path backup = backupRoot(liveRoot, project.publishId()); backupId = liveRoot.relativize(backup).toString().replace('\\', '/');
                backup(liveRoot, project.scope(), backup, live.files(), project);
                try {
                    replaceScope(liveRoot, project.scope(), project.files());
                    activator.activate(staged.report().candidate());
                } catch (Exception applyFailure) {
                    restore(liveRoot, project.scope(), backup);
                    return failure(project, project.baseRevision(), backupId,
                            "Publication rolled back: " + Objects.toString(applyFailure.getMessage(), applyFailure.getClass().getSimpleName()));
                }
            }
            return new PublishApplyResult(Protocol.VERSION, project.publishId(), true,
                    project.proposedRevision(), backupId, null);
        } catch (Exception failure) {
            return failure(project, safeRevision(liveRoot, project), backupId,
                    Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }

    public static RollbackApplyResult rollback(Path liveRoot, RollbackProject rollback, Duration dialogueDelay,
                                               PersonaApi api, Activator activator) {
        try {
            if (rollback == null || rollback.protocolVersion() != Protocol.VERSION || rollback.rollbackId() == null
                    || rollback.publishId() == null || rollback.sessionId() == null || rollback.scope() == null
                    || rollback.currentRevision() == null || rollback.targetRevision() == null || rollback.backupId() == null)
                throw new IOException("Invalid rollback project envelope");
            String expectedBackup = "backups/editor-content/" + rollback.publishId();
            if (!expectedBackup.equals(rollback.backupId())) throw new IOException("Rollback backup identity does not match publication");
            Path backup = safe(liveRoot, rollback.backupId());
            Properties manifest = new Properties();
            try (var reader = Files.newBufferedReader(backup.resolve("publish.properties"), StandardCharsets.UTF_8)) {
                manifest.load(reader);
            }
            if (!rollback.publishId().toString().equals(manifest.getProperty("publish-id"))
                    || !rollback.targetRevision().equals(manifest.getProperty("base-revision"))
                    || !rollback.currentRevision().equals(manifest.getProperty("proposed-revision"))
                    || !rollback.scope().name().equals(manifest.getProperty("scope")))
                throw new IOException("Rollback manifest does not match durable publication metadata");
            ContentSnapshotBuilder.Project target = ContentSnapshotBuilder.read(backup, rollback.scope());
            if (!target.revision().equals(rollback.targetRevision())) throw new IOException("Rollback backup digest mismatch");
            PublishProject transaction = new PublishProject(Protocol.VERSION, rollback.rollbackId(), rollback.sessionId(),
                    rollback.publishId(), rollback.scope(), rollback.currentRevision(), rollback.targetRevision(), target.files());
            PublishApplyResult result = publish(liveRoot, transaction, dialogueDelay, api, activator);
            return new RollbackApplyResult(Protocol.VERSION, rollback.rollbackId(), rollback.publishId(), result.success(),
                    result.activeRevision(), result.backupId(), result.error());
        } catch (Exception error) {
            String current;
            try { current = ContentSnapshotBuilder.read(liveRoot, rollback.scope()).revision(); }
            catch (Exception ignored) { current = rollback == null ? "0".repeat(64) : rollback.currentRevision(); }
            return new RollbackApplyResult(Protocol.VERSION, rollback == null ? new UUID(0, 0) : rollback.rollbackId(),
                    rollback == null ? new UUID(0, 0) : rollback.publishId(), false, current, null,
                    bounded(Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
        }
    }

    private static void validateEnvelope(PublishProject project) throws IOException {
        if (project == null || project.protocolVersion() != Protocol.VERSION || project.publishId() == null
                || project.sessionId() == null || project.draftId() == null || project.scope() == null
                || project.baseRevision() == null || !project.baseRevision().matches("[0-9a-f]{64}")
                || project.proposedRevision() == null || !project.proposedRevision().matches("[0-9a-f]{64}")
                || !project.proposedRevision().equals(ContentProjectRevision.compute(project.files())))
            throw new IOException("Invalid publish project envelope");
    }

    private static void backup(Path liveRoot, EditorScope scope, Path backup, List<ContentFile> current,
                               PublishProject project) throws IOException {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Publish backup already exists");
        Files.createDirectories(backup);
        for (ContentFile file : current) write(backup, file, false);
        String manifest = "publish-id=" + project.publishId() + "\nbase-revision=" + project.baseRevision()
                + "\nproposed-revision=" + project.proposedRevision() + "\nscope=" + scope.name()
                + "\ncreated-at=" + Instant.now() + "\n";
        Files.writeString(backup.resolve("publish.properties"), manifest, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void replaceScope(Path root, EditorScope scope, List<ContentFile> replacement) throws IOException {
        Set<String> retained = new HashSet<>();
        for (ContentFile file : replacement) { write(root, file, true); retained.add(file.path()); }
        for (ContentFile file : ContentSnapshotBuilder.read(root, scope).files())
            if (!retained.contains(file.path())) Files.deleteIfExists(safe(root, file.path()));
    }

    private static void restore(Path root, EditorScope scope, Path backup) throws IOException {
        for (ContentFile file : ContentSnapshotBuilder.read(root, scope).files()) Files.deleteIfExists(safe(root, file.path()));
        ContentSnapshotBuilder.Project saved = ContentSnapshotBuilder.read(backup, scope);
        for (ContentFile file : saved.files()) write(root, file, true);
    }

    private static void write(Path root, ContentFile file, boolean atomic) throws IOException {
        Path target = safe(root, file.path()); Files.createDirectories(target.getParent());
        if (!atomic) {
            Files.writeString(target, file.content(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return;
        }
        Path temporary = target.resolveSibling("." + target.getFileName() + ".persona-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, file.content(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Path safe(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize(), target = normalizedRoot.resolve(relative).normalize();
        if (!target.startsWith(normalizedRoot)) throw new IOException("Content path escapes Persona data folder");
        return target;
    }
    private static Path backupRoot(Path liveRoot, UUID publishId) {
        return liveRoot.toAbsolutePath().normalize().resolve("backups/editor-content").resolve(publishId.toString());
    }
    private static String safeRevision(Path root, PublishProject project) {
        try { return project == null || project.scope() == null ? "0".repeat(64)
                : ContentSnapshotBuilder.read(root, project.scope()).revision(); }
        catch (Exception ignored) { return project != null && project.baseRevision() != null ? project.baseRevision() : "0".repeat(64); }
    }
    private static PublishApplyResult failure(PublishProject project, String revision, String backup, String error) {
        return new PublishApplyResult(Protocol.VERSION, project == null ? new UUID(0, 0) : project.publishId(), false,
                revision, backup, bounded(error));
    }
    private static String bounded(String value) {
        String text = Objects.toString(value, "Publication failed"); return text.substring(0, Math.min(2_048, text.length()));
    }
}
