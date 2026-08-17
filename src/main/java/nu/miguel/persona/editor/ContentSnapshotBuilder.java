package nu.miguel.persona.editor;

import nu.miguel.persona.editor.protocol.ContentFile;
import nu.miguel.persona.editor.protocol.EditorScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

final class ContentSnapshotBuilder {
    private static final int MAX_FILES = 1_024;
    private static final long MAX_BYTES = 10L * 1_024 * 1_024;

    private ContentSnapshotBuilder() {}

    static Project read(Path dataFolder, EditorScope scope) throws IOException {
        Path root = dataFolder.toAbsolutePath().normalize();
        List<Path> candidates = candidates(root, scope);
        List<ContentFile> files = new ArrayList<>();
        long bytes = 0;
        for (Path file : candidates) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            byte[] content = Files.readAllBytes(file);
            bytes += content.length;
            if (files.size() >= MAX_FILES || bytes > MAX_BYTES)
                throw new IOException("Persona content snapshot exceeds the 1024-file or 10 MiB limit");
            String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            files.add(new ContentFile(relative, sha256(content), utf8(content, relative)));
        }
        files.sort(Comparator.comparing(ContentFile::path));
        MessageDigest revision = digest();
        for (ContentFile file : files) {
            revision.update(file.path().getBytes(StandardCharsets.UTF_8));
            revision.update((byte) 0);
            revision.update(file.sha256().getBytes(StandardCharsets.US_ASCII));
            revision.update((byte) 0);
        }
        return new Project(hex(revision.digest()), files);
    }

    private static List<Path> candidates(Path root, EditorScope scope) throws IOException {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        if (scope == EditorScope.ALL || scope == EditorScope.CONTENT || scope == EditorScope.SCRIPTS)
            result.add(root.resolve("scripts.yml"));
        Map<EditorScope, String> directories = Map.of(
                EditorScope.BEHAVIORS, "behaviors",
                EditorScope.NPCS, "npcs",
                EditorScope.DIALOGUES, "dialogues",
                EditorScope.QUESTS, "quests");
        for (var entry : directories.entrySet()) {
            if (scope != EditorScope.ALL && scope != EditorScope.CONTENT && scope != entry.getKey()) continue;
            Path directory = root.resolve(entry.getValue());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.walk(directory)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(ContentSnapshotBuilder::yaml).forEach(result::add);
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean yaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static String sha256(byte[] value) { return hex(digest().digest(value)); }
    private static String utf8(byte[] value, String path) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Persona content file is not valid UTF-8: " + path, error);
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }

    record Project(String revision, List<ContentFile> files) {
        Project { files = List.copyOf(files); }
    }
}
