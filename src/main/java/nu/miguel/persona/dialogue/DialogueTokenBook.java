package nu.miguel.persona.dialogue;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/** Issues opaque, one-use choice tokens and validates their full conversation context. */
public final class DialogueTokenBook {
    public record SessionKey(UUID session, UUID player, UUID npc, String dialogue, String node) {}

    private final SecureRandom random;
    private final Map<String, IssuedToken> issued = new HashMap<>();

    public DialogueTokenBook() { this(new SecureRandom()); }
    DialogueTokenBook(SecureRandom random) { this.random = random; }

    public String issue(SessionKey key, int choice) {
        byte[] bytes = new byte[18];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (issued.containsKey(token));
        issued.put(token, new IssuedToken(key, choice));
        return token;
    }

    public OptionalInt consume(String token, SessionKey expected) {
        IssuedToken value = issued.get(token);
        if (value == null || !value.key.equals(expected)) return OptionalInt.empty();
        issued.remove(token);
        return OptionalInt.of(value.choice);
    }

    public void clearSession(UUID session) {
        issued.entrySet().removeIf(entry -> entry.getValue().key.session().equals(session));
    }

    int size() { return issued.size(); }
    private record IssuedToken(SessionKey key, int choice) {}
}
