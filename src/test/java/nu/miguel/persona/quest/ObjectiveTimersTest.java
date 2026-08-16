package nu.miguel.persona.quest;

import nu.miguel.persona.state.PlayerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjectiveTimersTest {
    @Test void waitIncludesTimeSpentOffline() {
        long questStarted = 1_000;
        long nextLogin = questStarted + 3_600_000;
        assertEquals(3_600_000, ObjectiveTimers.waitElapsed(questStarted, nextLogin));
    }

    @Test void clockGoingBackwardsDoesNotCreateNegativeProgress() {
        assertEquals(0, ObjectiveTimers.waitElapsed(2_000, 1_000));
        assertEquals(20, ObjectiveTimers.survivalElapsed(20, 2_000, 1_000));
    }

    @Test void survivalAddsOnlyTheProvidedOnlineInterval() {
        assertEquals(4_500, ObjectiveTimers.survivalElapsed(1_500, 10_000, 13_000));
    }

    @Test void deathResetsSurvivalValueAndBothAnchors() {
        var progress = new PlayerState.ObjectiveProgress(9_000, 100, 200);
        ObjectiveTimers.resetSurvival(progress, 12_345);
        assertEquals(0, progress.value());
        assertEquals(12_345, progress.startedAt());
        assertEquals(12_345, progress.onlineSince());
    }
}
