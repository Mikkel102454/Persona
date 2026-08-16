package nu.miguel.persona.state;

import nu.miguel.persona.content.Content.QuestState;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStateTest {
    @Test void completedHistoryTakesPrecedenceOverActiveRecord(){PlayerState s=new PlayerState(UUID.randomUUID());s.quests().put("demo:q",new PlayerState.QuestProgress(0));assertEquals(QuestState.ACTIVE,s.questState("demo:q"));s.completed().add("demo:q");assertEquals(QuestState.COMPLETED,s.questState("demo:q"));}
    @Test void snapshotDoesNotShareObjectiveMutation(){PlayerState s=new PlayerState(UUID.randomUUID());var q=new PlayerState.QuestProgress(0);q.objectives().put("o",new PlayerState.ObjectiveProgress(1,2,3));s.quests().put("demo:q",q);PlayerState copy=s.snapshot();q.objectives().get("o").value(9);assertEquals(1,copy.quests().get("demo:q").objectives().get("o").value());}
}
