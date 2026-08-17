package nu.miguel.persona.state;

import nu.miguel.persona.behavior.BehaviorRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreTest {
    @TempDir Path temp;

    @Test void restoresTypedQuestProgressFlagsAndHistory()throws Exception{
        UUID id=UUID.randomUUID();
        try(SqliteStore store=new SqliteStore(temp.resolve("persona.db").toFile(),Logger.getAnonymousLogger())){
            PlayerState state=new PlayerState(id);var quest=new PlayerState.QuestProgress(2,1234);quest.objectives().put("target",new PlayerState.ObjectiveProgress(7,100,200));state.quests().put("demo:quest",quest);state.completions().put("demo:quest",2);state.completedAt().put("demo:quest",4000L);state.completed().add("demo:old");state.completedAt().put("demo:old",4567L);state.completions().put("demo:old",3);state.flags().put("met_builder",true);state.variables().put("reputation","12");store.save(state).get(5,TimeUnit.SECONDS);
        }
        try(SqliteStore store=new SqliteStore(temp.resolve("persona.db").toFile(),Logger.getAnonymousLogger())){
            PlayerState restored=store.load(id).get(5,TimeUnit.SECONDS);assertEquals(2,restored.quests().get("demo:quest").phase());assertEquals(1234,restored.quests().get("demo:quest").startedAt());assertEquals(7,restored.quests().get("demo:quest").objectives().get("target").value());assertFalse(restored.completed().contains("demo:quest"));assertEquals(2,restored.completions().get("demo:quest"));assertTrue(restored.completed().contains("demo:old"));assertEquals(3,restored.completions().get("demo:old"));assertEquals(4567,restored.completedAt().get("demo:old"));assertTrue(restored.flags().get("met_builder"));assertEquals("12",restored.variables().get("reputation"));
        }
    }

    @Test void persistsNormalizedBehaviorRuntimeWithTypedTravelAndWake()throws Exception{
        Path file=temp.resolve("runtime.db");var position=new BehaviorRuntime.LogicalPosition("world",1.25,64,9.5,90,12);var travel=new BehaviorRuntime.LogicalTravel("demo:tree","travel","home","market",1000,5000);
        var row=new SqliteStore.BehaviorRow("player",UUID.randomUUID().toString(),"demo:npc","instance","demo:tree","hash","home",position,false,"demo:tree/checkpoint",6000,Map.of("demo:tree/sequence",2),Map.of("demo:tree/wait",1234L),Map.of("source","home","started",12d),Map.of("demo:tree/sequence","sequence","demo:tree/wait","wait","demo:tree/checkpoint","checkpoint","demo:tree/travel","action:logical-travel"),"structure",travel);
        try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){store.saveBehaviorRuntimes(List.of(row)).get(5,TimeUnit.SECONDS);}
        try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){var restored=store.loadBehaviorRuntimes().get(5,TimeUnit.SECONDS).getFirst();assertEquals("demo:tree/checkpoint",restored.checkpoint());assertEquals(position,restored.position());assertEquals(6000,restored.wakeAt());assertEquals(2,restored.progress().get("demo:tree/sequence"));assertEquals(1234L,restored.deadlines().get("demo:tree/wait"));assertEquals("wait",restored.nodeTypes().get("demo:tree/wait"));assertEquals("home",restored.blackboard().get("source"));assertEquals(travel,restored.logicalTravel());}
    }

    @Test void incrementalWritesPreserveOfflinePlayerRows()throws Exception{
        Path file=temp.resolve("incremental.db");SqliteStore.BehaviorRow online=row("online","home"),offline=row("offline","harbor");
        try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){
            store.saveBehaviorRuntimes(List.of(offline)).get(5,TimeUnit.SECONDS);
            store.saveBehaviorRuntimes(List.of(online)).get(5,TimeUnit.SECONDS);
            assertEquals(Set.of("online","offline"),store.loadBehaviorRuntimes().get(5,TimeUnit.SECONDS).stream().map(SqliteStore.BehaviorRow::player).collect(java.util.stream.Collectors.toSet()));
            SqliteStore.BehaviorRow updated=row("online","market");store.saveBehaviorRuntimes(List.of(updated)).get(5,TimeUnit.SECONDS);
            Map<String,String> anchors=new HashMap<>();store.loadBehaviorRuntimes().get(5,TimeUnit.SECONDS).forEach(x->anchors.put(x.player(),x.anchor()));assertEquals(Map.of("online","market","offline","harbor"),anchors);
        }
    }

    @Test void createsConsistentOnlineBackupAndReportsIntegrity()throws Exception{
        Path file=temp.resolve("live.db"),backup=temp.resolve("backup/persona.db");
        try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){store.saveBehaviorRuntimes(List.of(row("player","home"))).get(5,TimeUnit.SECONDS);assertEquals(backup.toFile().getAbsoluteFile(),store.backup(backup.toFile()).get(5,TimeUnit.SECONDS));assertEquals("ok",store.integrityCheck().get(5,TimeUnit.SECONDS));}
        try(SqliteStore restored=new SqliteStore(backup.toFile(),Logger.getAnonymousLogger())){assertEquals("home",restored.loadBehaviorRuntimes().get(5,TimeUnit.SECONDS).getFirst().anchor());}
    }

    @Test void queryPlansUsePersistenceIndexes()throws Exception{
        try(SqliteStore store=new SqliteStore(temp.resolve("plans.db").toFile(),Logger.getAnonymousLogger())){
            Map<String,List<String>> plans=store.persistenceQueryPlans().get(5,TimeUnit.SECONDS);
            assertTrue(plans.values().stream().flatMap(Collection::stream).allMatch(detail->detail.contains("INDEX")),plans.toString());
        }
    }

    @Test void corruptDatabaseIsQuarantinedBeforeFreshRecovery()throws Exception{
        Path file=temp.resolve("corrupt.db");Files.write(file,new byte[]{1,2,3,4,5,6,7});
        try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){assertEquals("ok",store.integrityCheck().get(5,TimeUnit.SECONDS));}
        try(var files=Files.list(temp)){assertTrue(files.anyMatch(path->path.getFileName().toString().startsWith("corrupt.db.corrupt-")));}
    }

    @Test void migratesLegacyUnqualifiedRuntimeRows()throws Exception{
        Path file=temp.resolve("legacy.db");new org.sqlite.JDBC();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());var statement=connection.createStatement()){
            statement.execute("CREATE TABLE behavior_runtime(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', behavior TEXT NOT NULL, tree_hash TEXT NOT NULL, anchor TEXT, visible INTEGER NOT NULL DEFAULT 1, wake_at INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, PRIMARY KEY(scope,player,npc_definition,instance))");
            statement.execute("CREATE TABLE behavior_blackboard(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', value_key TEXT NOT NULL, value_type TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(scope,player,npc_definition,instance,value_key))");
            statement.execute("CREATE TABLE behavior_checkpoint(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', node_id TEXT NOT NULL, progress INTEGER NOT NULL, deadline INTEGER, PRIMARY KEY(scope,player,npc_definition,instance,node_id))");
            statement.execute("INSERT INTO behavior_runtime(scope,player,npc_definition,instance,behavior,tree_hash,anchor,visible,wake_at,checkpoint) VALUES('player','legacy','demo:npc','one','demo:tree','hash','home',1,1234,'save')");
            statement.execute("INSERT INTO behavior_checkpoint VALUES('player','legacy','demo:npc','one','sequence',2,NULL)");
        }
        try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){SqliteStore.BehaviorRow restored=store.loadBehaviorRuntimes().get(5,TimeUnit.SECONDS).getFirst();assertEquals(1234,restored.wakeAt());assertEquals(2,restored.progress().get("sequence"));assertTrue(restored.nodeTypes().isEmpty());}
    }

    @Test void migratesEveryReleasedSchemaVersionWithoutLosingState()throws Exception{
        new org.sqlite.JDBC();UUID player=UUID.randomUUID();
        for(int version=1;version<=5;version++){
            Path file=temp.resolve("schema-v"+version+".db");
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());var statement=connection.createStatement()){
                statement.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)");statement.execute("INSERT INTO schema_version VALUES("+version+")");
                statement.execute("CREATE TABLE flag(player TEXT NOT NULL, name TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(player,name))");
                statement.execute("INSERT INTO flag VALUES('"+player+"','from-v"+version+"',1)");
            }
            try(SqliteStore store=new SqliteStore(file.toFile(),Logger.getAnonymousLogger())){assertTrue(store.load(player).get(5,TimeUnit.SECONDS).flags().get("from-v"+version));}
            try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());var statement=connection.createStatement();var result=statement.executeQuery("SELECT version FROM schema_version")){assertTrue(result.next());assertEquals(5,result.getInt(1));}
        }
    }

    private static SqliteStore.BehaviorRow row(String player,String anchor){return new SqliteStore.BehaviorRow("player",player,"demo:npc","instance","demo:tree","hash",anchor,null,true,null,0,Map.of(),Map.of(),Map.of(),Map.of(),null,null);}
}
