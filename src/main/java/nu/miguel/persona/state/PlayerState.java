package nu.miguel.persona.state;

import nu.miguel.persona.content.Content.QuestState;

import java.util.*;

public final class PlayerState {
    private final UUID playerId;
    private final Map<String, QuestProgress> quests = new HashMap<>();
    private final Set<String> completed = new HashSet<>();
    private final Map<String, Boolean> flags = new HashMap<>();
    private final Map<String, String> variables = new HashMap<>();
    private final Map<String, Integer> completions = new HashMap<>();
    private final Map<String, Long> completedAt = new HashMap<>();

    public PlayerState(UUID playerId) { this.playerId = playerId; }
    public UUID playerId() { return playerId; }
    public Map<String, QuestProgress> quests() { return quests; }
    public Set<String> completed() { return completed; }
    public Map<String, Boolean> flags() { return flags; }
    public Map<String, String> variables() { return variables; }
    public Map<String, Integer> completions() { return completions; }
    public Map<String, Long> completedAt() { return completedAt; }
    public QuestState questState(String id) { return completed.contains(id) ? QuestState.COMPLETED : quests.containsKey(id) ? QuestState.ACTIVE : QuestState.NOT_STARTED; }

    public static final class QuestProgress {
        private int phase;
        private final Map<String, ObjectiveProgress> objectives = new HashMap<>();
        private long revision;
        private final long startedAt;
        public QuestProgress(int phase) { this(phase, System.currentTimeMillis()); }
        public QuestProgress(int phase, long startedAt) { this.phase = phase; this.startedAt = startedAt; }
        public int phase() { return phase; }
        public void phase(int value) { phase = value; revision++; objectives.clear(); }
        public Map<String, ObjectiveProgress> objectives() { return objectives; }
        public long revision() { return revision; }
        public long startedAt() { return startedAt; }
    }

    public static final class ObjectiveProgress {
        private long value;
        private long startedAt;
        private long onlineSince;
        public ObjectiveProgress(long value, long startedAt, long onlineSince) { this.value=value; this.startedAt=startedAt; this.onlineSince=onlineSince; }
        public long value() { return value; }
        public void value(long value) { this.value=value; }
        public long startedAt() { return startedAt; }
        public void startedAt(long value) { startedAt=value; }
        public long onlineSince() { return onlineSince; }
        public void onlineSince(long value) { onlineSince=value; }
        public ObjectiveProgress copy() { return new ObjectiveProgress(value, startedAt, onlineSince); }
    }

    public synchronized PlayerState snapshot() {
        PlayerState copy = new PlayerState(playerId);
        copy.completed.addAll(completed); copy.flags.putAll(flags); copy.variables.putAll(variables);
        copy.completions.putAll(completions); copy.completedAt.putAll(completedAt);
        quests.forEach((id,q) -> { QuestProgress qc=new QuestProgress(q.phase,q.startedAt); q.objectives.forEach((k,v)->qc.objectives.put(k,v.copy())); copy.quests.put(id,qc); });
        return copy;
    }
}
