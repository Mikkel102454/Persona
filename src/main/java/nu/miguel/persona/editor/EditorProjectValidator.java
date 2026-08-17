package nu.miguel.persona.editor;

import nu.miguel.persona.api.PersonaApi;
import nu.miguel.persona.content.ContentFormat;
import nu.miguel.persona.content.ContentValidator;
import nu.miguel.persona.editor.protocol.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stages a complete candidate in an isolated directory and invokes Persona's normal content loader. */
public final class EditorProjectValidator {
    private static final Pattern LOCATION = Pattern.compile("^(.+):(\\d+):(\\d+):\\s*(.*)$");
    private static final Pattern SUGGESTION = Pattern.compile("did you mean '([^']+)'\\?");
    private static final Pattern REFERENCE = Pattern.compile("(?i)(?:references |has )?missing\\s+((?:shared |player |delivery |start )?(?:behavior|dialogue|quest|npc|script|phase|node|anchor|objective|subtree)(?: anchor)?)\\s+([^\\s]+)$");
    private static final Pattern ID = Pattern.compile("^(\\s*)(?:-\\s*)?id:\\s*['\"]?([^'\"#\\s]+)");

    private EditorProjectValidator() {}

    public static ValidationResult validate(Path liveRoot, ValidationProject project, Duration dialogueDelay, PersonaApi api) {
        try (StagedCandidate candidate = stage(liveRoot, project, dialogueDelay, api)) {
            ContentValidator.Report report = candidate.report();
            List<ValidationDiagnostic> diagnostics = report.errors().stream()
                    .map(error -> diagnostic(candidate.root(), error)).toList();
            return new ValidationResult(Protocol.VERSION, project.requestId(), project.draftId(), report.valid(), project.proposedRevision(),
                    ContentFormat.CURRENT, diagnostics);
        } catch (Exception error) {
            String message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            return new ValidationResult(Protocol.VERSION, project.requestId(), project.draftId(), false, project.proposedRevision(),
                    ContentFormat.CURRENT, List.of(new ValidationDiagnostic("project", 1, 1, null, null, null,
                    "Could not stage candidate: " + message, null, "ERROR")));
        }
    }

    public static StagedCandidate stage(Path liveRoot, ValidationProject project, Duration dialogueDelay, PersonaApi api)
            throws Exception {
        validateEnvelope(project); Path staging = null;
        try {
            Path parent = liveRoot.toAbsolutePath().normalize().resolve(".editor-validation");
            Files.createDirectories(parent); staging = Files.createTempDirectory(parent, project.requestId() + "-");
            for (ContentFile file : ContentSnapshotBuilder.read(liveRoot, EditorScope.ALL).files()) write(staging, file);
            clearScope(staging, project.scope());
            for (ContentFile file : project.files()) write(staging, file);
            return new StagedCandidate(staging, new ContentValidator(staging.toFile(), dialogueDelay, api).validate());
        } catch (Exception error) { if (staging != null) deleteTree(staging); throw error; }
    }

    private static void validateEnvelope(ValidationProject project) throws Exception {
        if (project == null || project.protocolVersion() != Protocol.VERSION || project.requestId() == null
                || project.sessionId() == null || project.draftId() == null || project.scope() == null
                || project.proposedRevision() == null || !project.proposedRevision().matches("[0-9a-f]{64}")
                || project.files().size() > 1_024) throw new IOException("invalid validation project envelope");
        long bytes = 0; Set<String> paths = new HashSet<>();
        for (ContentFile file : project.files()) {
            if (file == null || !validPath(file.path()) || file.content() == null || file.sha256() == null
                    || !paths.add(file.path()) || !allowed(project.scope(), file.path()))
                throw new IOException("invalid, duplicate, or out-of-scope candidate path");
            byte[] content = file.content().getBytes(StandardCharsets.UTF_8); bytes += content.length;
            if (bytes > 10L * 1_024 * 1_024 || !MessageDigest.isEqual(
                    HexFormat.of().formatHex(digest().digest(content)).getBytes(StandardCharsets.US_ASCII),
                    file.sha256().getBytes(StandardCharsets.US_ASCII)))
                throw new IOException(bytes > 10L * 1_024 * 1_024 ? "candidate exceeds 10 MiB" : "candidate digest mismatch");
        }
        if (!project.proposedRevision().equals(ContentProjectRevision.compute(project.files())))
            throw new IOException("candidate revision mismatch");
    }

    private static void write(Path root, ContentFile file) throws IOException {
        Path target = root.resolve(file.path()).normalize();
        if (!target.startsWith(root)) throw new IOException("candidate path escapes staging root");
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.content(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void clearScope(Path root, EditorScope scope) throws IOException {
        if (scope == EditorScope.ALL || scope == EditorScope.CONTENT || scope == EditorScope.SCRIPTS)
            Files.deleteIfExists(root.resolve("scripts.yml"));
        Map<EditorScope, String> directories = Map.of(EditorScope.BEHAVIORS, "behaviors", EditorScope.NPCS, "npcs",
                EditorScope.DIALOGUES, "dialogues", EditorScope.QUESTS, "quests");
        for (var entry : directories.entrySet())
            if (scope == EditorScope.ALL || scope == EditorScope.CONTENT || scope == entry.getKey())
                deleteTree(root.resolve(entry.getValue()));
    }

    private static ValidationDiagnostic diagnostic(Path root, String raw) {
        Matcher location = LOCATION.matcher(raw);
        String path = "content"; int line = 1, column = 1; String message = raw;
        if (location.matches()) {
            path = location.group(1); line = Integer.parseInt(location.group(2));
            column = Integer.parseInt(location.group(3)); message = location.group(4);
        }
        Matcher suggestion = SUGGESTION.matcher(message);
        String fix = suggestion.find() ? "Replace with '" + suggestion.group(1) + "'." : null;
        Matcher reference = REFERENCE.matcher(message);
        String referenceType = reference.find() ? reference.group(1).toLowerCase(Locale.ROOT).replace(' ', '-') : null;
        String referenceId = referenceType == null ? null : reference.group(2).replaceAll("[.;,]+$", "");
        return new ValidationDiagnostic(path, line, column, nearestNode(root.resolve(path).normalize(), line),
                referenceType, referenceId,
                message, fix, "ERROR");
    }

    private static String nearestNode(Path file, int errorLine) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = Math.min(errorLine, lines.size()) - 1; index >= 0; index--) {
                Matcher match = ID.matcher(lines.get(index));
                if (match.find()) return match.group(2);
            }
            return null;
        } catch (IOException ignored) { return null; }
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("\0")) return false;
        return Arrays.stream(path.split("/")).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
                && (path.toLowerCase(Locale.ROOT).endsWith(".yml") || path.toLowerCase(Locale.ROOT).endsWith(".yaml"));
    }
    private static boolean allowed(EditorScope scope, String path) {
        return switch (scope) {
            case ALL, CONTENT -> path.equals("scripts.yml") || path.startsWith("behaviors/") || path.startsWith("npcs/")
                    || path.startsWith("dialogues/") || path.startsWith("quests/");
            case SCRIPTS -> path.equals("scripts.yml"); case BEHAVIORS -> path.startsWith("behaviors/");
            case NPCS -> path.startsWith("npcs/"); case DIALOGUES -> path.startsWith("dialogues/");
            case QUESTS -> path.startsWith("quests/");
        };
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    public static final class StagedCandidate implements AutoCloseable {
        private final Path root; private final ContentValidator.Report report;
        private StagedCandidate(Path root, ContentValidator.Report report) { this.root = root; this.report = report; }
        public Path root() { return root; }
        public ContentValidator.Report report() { return report; }
        @Override public void close() { deleteTree(root); }
    }
}
