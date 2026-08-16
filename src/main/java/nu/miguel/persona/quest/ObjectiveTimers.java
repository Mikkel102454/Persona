package nu.miguel.persona.quest;

import nu.miguel.persona.state.PlayerState;

public final class ObjectiveTimers {
    private ObjectiveTimers() {}

    public static long waitElapsed(long startedAt, long now) {
        return Math.max(0, now - startedAt);
    }

    public static long survivalElapsed(long accumulated, long onlineSince, long now) {
        long elapsed = Math.max(0, now - onlineSince);
        return accumulated > Long.MAX_VALUE - elapsed ? Long.MAX_VALUE : accumulated + elapsed;
    }

    public static void resetSurvival(PlayerState.ObjectiveProgress progress, long now) {
        progress.value(0);
        progress.startedAt(now);
        progress.onlineSince(now);
    }
}
