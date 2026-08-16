package nu.miguel.persona.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreTest {
    @TempDir Path temp;
    @Test void restoresTypedQuestProgressFlagsAndHistory() throws Exception {
        UUID id=UUID.randomUUID();
        try(SqliteStore store=new SqliteStore(temp.resolve("persona.db").toFile(),Logger.getAnonymousLogger())){
            PlayerState state=new PlayerState(id);var quest=new PlayerState.QuestProgress(2,1234);quest.objectives().put("target",new PlayerState.ObjectiveProgress(7,100,200));state.quests().put("demo:quest",quest);state.completions().put("demo:quest",2);state.completedAt().put("demo:quest",4000L);state.completed().add("demo:old");state.completedAt().put("demo:old",4567L);state.completions().put("demo:old",3);state.flags().put("met_builder",true);state.variables().put("reputation","12");store.save(state).get(5,TimeUnit.SECONDS);
        }
        try(SqliteStore store=new SqliteStore(temp.resolve("persona.db").toFile(),Logger.getAnonymousLogger())){
            PlayerState restored=store.load(id).get(5,TimeUnit.SECONDS);assertEquals(2,restored.quests().get("demo:quest").phase());assertEquals(1234,restored.quests().get("demo:quest").startedAt());assertEquals(7,restored.quests().get("demo:quest").objectives().get("target").value());assertFalse(restored.completed().contains("demo:quest"));assertEquals(2,restored.completions().get("demo:quest"));assertTrue(restored.completed().contains("demo:old"));assertEquals(3,restored.completions().get("demo:old"));assertEquals(4567,restored.completedAt().get("demo:old"));assertTrue(restored.flags().get("met_builder"));assertEquals("12",restored.variables().get("reputation"));
        }
    }
    @Test void persistsNormalizedBehaviorRuntime() throws Exception {Path file=temp.resolve("runtime.db");var row=new SqliteStore.BehaviorRow("player",UUID.randomUUID().toString(),"demo:npc","instance","demo:tree","hash","home",false,"checkpoint",java.util.Map.of("sequence",2),java.util.Map.of("wait",1234L),java.util.Map.of("source","home","started",12d));try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){store.saveBehaviorRuntimes(java.util.List.of(row)).get(5,TimeUnit.SECONDS);}try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){var restored=store.loadBehaviorRuntimes().get(5,TimeUnit.SECONDS).getFirst();assertEquals("checkpoint",restored.checkpoint());assertEquals(2,restored.progress().get("sequence"));assertEquals(1234L,restored.deadlines().get("wait"));assertEquals("home",restored.blackboard().get("source"));}}
}
