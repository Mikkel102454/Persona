package nu.miguel.persona.quest;

public final class ProgressRules {
    private ProgressRules() {}

    public static long update(long current, long value, long required, boolean increment) {
        if (required < 0) throw new IllegalArgumentException("required progress cannot be negative");
        long candidate;
        if (!increment) candidate = value;
        else if (value > 0 && current > Long.MAX_VALUE - value) candidate = Long.MAX_VALUE;
        else candidate = current + value;
        return Math.clamp(candidate, 0, required);
    }
}
