package nu.miguel.persona.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AtomicContentRegistryTest {
    @Test void successfulReloadPublishesCompleteReplacement() throws Exception {
        var oldRegistry = registry("demo:old");
        var replacement = registry("demo:new");
        var holder = new AtomicContentRegistry(oldRegistry);

        holder.loadAndReplace(() -> replacement);

        assertSame(replacement, holder.get());
    }

    @Test void validationFailureRetainsPreviousRegistry() {
        var oldRegistry = registry("demo:old");
        var holder = new AtomicContentRegistry(oldRegistry);

        assertThrows(ContentException.class,
                () -> holder.loadAndReplace(() -> { throw new ContentException(List.of("broken reference")); }));
        assertSame(oldRegistry, holder.get());
    }

    @Test void unexpectedLoaderFailureAlsoRetainsPreviousRegistry() {
        var oldRegistry = registry("demo:old");
        var holder = new AtomicContentRegistry(oldRegistry);

        assertThrows(IllegalStateException.class,
                () -> holder.loadAndReplace(() -> { throw new IllegalStateException("disk read failed"); }));
        assertSame(oldRegistry, holder.get());
    }

    private Content.Registry registry(String npcId) {
        Content.Npc npc = new Content.Npc(npcId, npcId, List.of());
        return new Content.Registry(Map.of(npcId, npc), Map.of(), Map.of());
    }
}
