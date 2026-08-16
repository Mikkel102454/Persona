package nu.miguel.persona.content;

import java.util.Objects;

/** Replaces content only after a complete candidate registry has loaded successfully. */
public final class AtomicContentRegistry {
    @FunctionalInterface public interface Loader { Content.Registry load() throws ContentException; }
    private volatile Content.Registry current;

    public AtomicContentRegistry(Content.Registry initial) { current = Objects.requireNonNull(initial); }
    public Content.Registry get() { return current; }
    public void loadAndReplace(Loader loader) throws ContentException {
        Content.Registry candidate = Objects.requireNonNull(loader.load(), "loaded registry");
        current = candidate;
    }
}
