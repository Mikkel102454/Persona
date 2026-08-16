package nu.miguel.persona.dialogue;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DialogueTokenBookTest {
    private final DialogueTokenBook tokens = new DialogueTokenBook();
    private final UUID session = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final UUID npc = UUID.randomUUID();

    @Test void validTokenIsConsumedExactlyOnce() {
        var key = key(session, player, "intro", "greeting");
        String token = tokens.issue(key, 2);

        assertEquals(OptionalInt.of(2), tokens.consume(token, key));
        assertTrue(tokens.consume(token, key).isEmpty());
    }

    @Test void forgedTokenIsRejected() {
        assertTrue(tokens.consume("not-a-real-token", key(session, player, "intro", "greeting")).isEmpty());
    }

    @Test void wrongPlayerCannotConsumeRightfulPlayersToken() {
        var owner = key(session, player, "intro", "greeting");
        String token = tokens.issue(owner, 0);

        assertTrue(tokens.consume(token, key(session, UUID.randomUUID(), "intro", "greeting")).isEmpty());
        assertEquals(OptionalInt.of(0), tokens.consume(token, owner));
    }

    @Test void oldNodeAndOldSessionTokensAreStale() {
        var old = key(session, player, "intro", "greeting");
        String token = tokens.issue(old, 1);

        assertTrue(tokens.consume(token, key(session, player, "intro", "accepted")).isEmpty());
        assertTrue(tokens.consume(token, key(UUID.randomUUID(), player, "intro", "greeting")).isEmpty());
        assertEquals(OptionalInt.of(1), tokens.consume(token, old));
    }

    @Test void cancellingSessionInvalidatesAllItsChoicesOnly() {
        var cancelled = key(session, player, "intro", "greeting");
        var active = key(UUID.randomUUID(), player, "other", "start");
        String first = tokens.issue(cancelled, 0);
        String second = tokens.issue(cancelled, 1);
        String survivor = tokens.issue(active, 0);

        tokens.clearSession(session);

        assertTrue(tokens.consume(first, cancelled).isEmpty());
        assertTrue(tokens.consume(second, cancelled).isEmpty());
        assertEquals(OptionalInt.of(0), tokens.consume(survivor, active));
        assertEquals(0, tokens.size());
    }

    private DialogueTokenBook.SessionKey key(UUID sessionId, UUID playerId, String dialogue, String node) {
        return new DialogueTokenBook.SessionKey(sessionId, playerId, npc, "village:" + dialogue, node);
    }
}
