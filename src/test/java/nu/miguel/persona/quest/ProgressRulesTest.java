package nu.miguel.persona.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressRulesTest {
    @Test void duplicateEventsCannotAdvancePastCompletion() {
        long completed = ProgressRules.update(9, 1, 10, true);
        assertEquals(10, completed);
        assertEquals(completed, ProgressRules.update(completed, 1, 10, true));
        assertEquals(completed, ProgressRules.update(completed, 100, 10, true));
    }

    @Test void incrementCannotOverflowIntoNegativeProgress() {
        assertEquals(100, ProgressRules.update(Long.MAX_VALUE - 2, 10, 100, true));
    }

    @Test void inventoryDerivedProgressCanMoveBothDirections() {
        assertEquals(3, ProgressRules.update(7, 3, 10, false));
        assertEquals(10, ProgressRules.update(3, 40, 10, false));
        assertEquals(0, ProgressRules.update(3, -1, 10, false));
    }
}
