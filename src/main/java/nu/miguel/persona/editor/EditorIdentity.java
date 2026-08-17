package nu.miguel.persona.editor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

public final class EditorIdentity {
    private final UUID installationId;
    private final KeyPair keys;

    private EditorIdentity(UUID installationId, KeyPair keys) {
        this.installationId = installationId; this.keys = keys;
    }

    public static EditorIdentity loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        if (Files.isRegularFile(file)) {
            Properties values = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return new EditorIdentity(UUID.fromString(values.getProperty("installation-id")), new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(values.getProperty("public-key")))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(values.getProperty("private-key"))))));
        }
        Files.createDirectories(file.getParent());
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        EditorIdentity identity = new EditorIdentity(UUID.randomUUID(), keys);
        Properties values = new Properties();
        values.setProperty("installation-id", identity.installationId.toString());
        values.setProperty("public-key", identity.publicKey());
        values.setProperty("private-key", Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            values.store(writer, "Persona editor signing identity - keep private");
        }
        try { Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) {}
        return identity;
    }

    public UUID installationId() { return installationId; }
    public String publicKey() { return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()); }
    public String sign(String value) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.getPrivate()); signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Could not sign editor message", e); }
    }
}
