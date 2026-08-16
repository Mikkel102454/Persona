package nu.miguel.persona.state;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

public final class SqliteStore implements AutoCloseable {
    private final Connection connection;
    private final ExecutorService executor;
    private final Logger logger;

    public SqliteStore(File file, Logger logger) throws SQLException {
        this.logger = logger;
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new SQLException("Cannot create " + parent);
        new org.sqlite.JDBC();
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        executor = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"Persona persistence"); t.setDaemon(true); return t; });
        migrate();
    }

    private void migrate() throws SQLException {
        try (Statement s=connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
            try (ResultSet rs=s.executeQuery("SELECT count(*) FROM schema_version")) { if(rs.next() && rs.getInt(1)==0) s.execute("INSERT INTO schema_version VALUES(1)"); }
            s.execute("CREATE TABLE IF NOT EXISTS player_quest(player TEXT NOT NULL, quest TEXT NOT NULL, phase INTEGER NOT NULL, PRIMARY KEY(player,quest))");
            s.execute("CREATE TABLE IF NOT EXISTS objective(player TEXT NOT NULL, quest TEXT NOT NULL, objective TEXT NOT NULL, value INTEGER NOT NULL, started_at INTEGER NOT NULL, online_since INTEGER NOT NULL, PRIMARY KEY(player,quest,objective), FOREIGN KEY(player,quest) REFERENCES player_quest(player,quest) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS completed_quest(player TEXT NOT NULL, quest TEXT NOT NULL, completed_at INTEGER NOT NULL, PRIMARY KEY(player,quest))");
            s.execute("CREATE TABLE IF NOT EXISTS flag(player TEXT NOT NULL, name TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(player,name))");
            s.execute("CREATE TABLE IF NOT EXISTS variable(player TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(player,name))");
            s.execute("CREATE TABLE IF NOT EXISTS npc_memory(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', memory_key TEXT NOT NULL, value_type TEXT NOT NULL, value TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER, source TEXT, PRIMARY KEY(scope,player,npc_definition,instance,memory_key))");
            s.execute("CREATE TABLE IF NOT EXISTS behavior_runtime(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', behavior TEXT NOT NULL, tree_hash TEXT NOT NULL, anchor TEXT, visible INTEGER NOT NULL DEFAULT 1, wake_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,player,npc_definition,instance))");
            s.execute("CREATE TABLE IF NOT EXISTS behavior_blackboard(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', value_key TEXT NOT NULL, value_type TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(scope,player,npc_definition,instance,value_key))");
            s.execute("CREATE TABLE IF NOT EXISTS behavior_checkpoint(scope TEXT NOT NULL, player TEXT NOT NULL DEFAULT '', npc_definition TEXT NOT NULL, instance TEXT NOT NULL DEFAULT '', node_id TEXT NOT NULL, progress INTEGER NOT NULL, deadline INTEGER, PRIMARY KEY(scope,player,npc_definition,instance,node_id))");
        }
        ensureColumn("player_quest","started_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("completed_quest","completion_count","INTEGER NOT NULL DEFAULT 1");
        ensureColumn("behavior_runtime","checkpoint","TEXT");
        try(Statement s=connection.createStatement()){s.execute("UPDATE schema_version SET version=3");}
    }

    private void ensureColumn(String table,String column,String declaration)throws SQLException{
        boolean found=false;try(Statement s=connection.createStatement();ResultSet rs=s.executeQuery("PRAGMA table_info("+table+")")){while(rs.next())if(rs.getString("name").equalsIgnoreCase(column)){found=true;break;}}
        if(!found)try(Statement s=connection.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+declaration);}
    }

    public CompletableFuture<PlayerState> load(UUID id) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerState state=new PlayerState(id); String p=id.toString();
            try {
                try (PreparedStatement q=connection.prepareStatement("SELECT quest,phase,started_at FROM player_quest WHERE player=?")) { q.setString(1,p); try(ResultSet rs=q.executeQuery()){ while(rs.next()){long started=rs.getLong(3);state.quests().put(rs.getString(1),new PlayerState.QuestProgress(rs.getInt(2),started==0?System.currentTimeMillis():started));} } }
                try (PreparedStatement q=connection.prepareStatement("SELECT quest,objective,value,started_at,online_since FROM objective WHERE player=?")) { q.setString(1,p); try(ResultSet rs=q.executeQuery()){ while(rs.next()){ var quest=state.quests().get(rs.getString(1)); if(quest!=null) quest.objectives().put(rs.getString(2),new PlayerState.ObjectiveProgress(rs.getLong(3),rs.getLong(4),rs.getLong(5))); } } }
                try (PreparedStatement q=connection.prepareStatement("SELECT quest,completed_at,completion_count FROM completed_quest WHERE player=?")) { q.setString(1,p); try(ResultSet rs=q.executeQuery()){ while(rs.next()){String quest=rs.getString(1);if(!state.quests().containsKey(quest))state.completed().add(quest);state.completedAt().put(quest,rs.getLong(2));state.completions().put(quest,rs.getInt(3));} } }
                try (PreparedStatement q=connection.prepareStatement("SELECT name,value FROM flag WHERE player=?")) { q.setString(1,p); try(ResultSet rs=q.executeQuery()){ while(rs.next()) state.flags().put(rs.getString(1),rs.getBoolean(2)); } }
                try (PreparedStatement q=connection.prepareStatement("SELECT name,value FROM variable WHERE player=?")) { q.setString(1,p); try(ResultSet rs=q.executeQuery()){ while(rs.next()) state.variables().put(rs.getString(1),rs.getString(2)); } }
                return state;
            } catch(SQLException e) { throw new CompletionException(e); }
        }, executor);
    }

    public CompletableFuture<Void> save(PlayerState original) {
        PlayerState state=original.snapshot();
        return CompletableFuture.runAsync(() -> {
            String p=state.playerId().toString();
            try {
                connection.setAutoCommit(false);
                delete("DELETE FROM objective WHERE player=?",p); delete("DELETE FROM player_quest WHERE player=?",p);
                delete("DELETE FROM completed_quest WHERE player=?",p); delete("DELETE FROM flag WHERE player=?",p);delete("DELETE FROM variable WHERE player=?",p);
                try(PreparedStatement q=connection.prepareStatement("INSERT INTO player_quest(player,quest,phase,started_at) VALUES(?,?,?,?)")) { for(var e:state.quests().entrySet()){ q.setString(1,p);q.setString(2,e.getKey());q.setInt(3,e.getValue().phase());q.setLong(4,e.getValue().startedAt());q.addBatch(); } q.executeBatch(); }
                try(PreparedStatement q=connection.prepareStatement("INSERT INTO objective VALUES(?,?,?,?,?,?)")) { for(var e:state.quests().entrySet()) for(var o:e.getValue().objectives().entrySet()){ q.setString(1,p);q.setString(2,e.getKey());q.setString(3,o.getKey());q.setLong(4,o.getValue().value());q.setLong(5,o.getValue().startedAt());q.setLong(6,o.getValue().onlineSince());q.addBatch(); } q.executeBatch(); }
                try(PreparedStatement q=connection.prepareStatement("INSERT INTO completed_quest(player,quest,completed_at,completion_count) VALUES(?,?,?,?)")) { Set<String> history=new java.util.HashSet<>(state.completed());history.addAll(state.completions().keySet());for(String quest:history){q.setString(1,p);q.setString(2,quest);q.setLong(3,state.completedAt().getOrDefault(quest,System.currentTimeMillis()));q.setInt(4,state.completions().getOrDefault(quest,1));q.addBatch();}q.executeBatch();}
                try(PreparedStatement q=connection.prepareStatement("INSERT INTO flag VALUES(?,?,?)")) { for(var e:state.flags().entrySet()){q.setString(1,p);q.setString(2,e.getKey());q.setBoolean(3,e.getValue());q.addBatch();}q.executeBatch();}
                try(PreparedStatement q=connection.prepareStatement("INSERT INTO variable VALUES(?,?,?)")) { for(var e:state.variables().entrySet()){q.setString(1,p);q.setString(2,e.getKey());q.setString(3,e.getValue());q.addBatch();}q.executeBatch();}
                connection.commit(); connection.setAutoCommit(true);
            } catch(SQLException e) { try { connection.rollback(); connection.setAutoCommit(true); } catch(SQLException ignored){} throw new CompletionException(e); }
        },executor).exceptionally(e->{logger.severe("Could not save Persona state: "+e.getMessage());return null;});
    }
    private void delete(String sql,String player)throws SQLException{try(PreparedStatement q=connection.prepareStatement(sql)){q.setString(1,player);q.executeUpdate();}}

    public record MemoryRow(String scope,String player,String npcDefinition,String instance,String key,String type,String value,long createdAt,long updatedAt,Long expiresAt,String source) {}
    public CompletableFuture<List<MemoryRow>> loadMemories(){return CompletableFuture.supplyAsync(()->{List<MemoryRow> rows=new ArrayList<>();try(Statement q=connection.createStatement();ResultSet r=q.executeQuery("SELECT scope,player,npc_definition,instance,memory_key,value_type,value,created_at,updated_at,expires_at,source FROM npc_memory")){while(r.next())rows.add(new MemoryRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getLong(9),r.getObject(10)==null?null:r.getLong(10),r.getString(11)));return rows;}catch(SQLException e){throw new CompletionException(e);}},executor);}
    public CompletableFuture<Void> saveMemories(List<MemoryRow> rows,Set<String> deleted){return CompletableFuture.runAsync(()->{try{connection.setAutoCommit(false);try(PreparedStatement d=connection.prepareStatement("DELETE FROM npc_memory WHERE scope=? AND player=? AND npc_definition=? AND instance=? AND memory_key=?")){for(String packed:deleted){String[] p=packed.split(java.util.regex.Pattern.quote("\0"),-1);for(int i=0;i<5;i++)d.setString(i+1,p[i]);d.addBatch();}d.executeBatch();}try(PreparedStatement q=connection.prepareStatement("INSERT INTO npc_memory(scope,player,npc_definition,instance,memory_key,value_type,value,created_at,updated_at,expires_at,source) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scope,player,npc_definition,instance,memory_key) DO UPDATE SET value_type=excluded.value_type,value=excluded.value,updated_at=excluded.updated_at,expires_at=excluded.expires_at,source=excluded.source")){for(MemoryRow r:rows){q.setString(1,r.scope);q.setString(2,r.player);q.setString(3,r.npcDefinition);q.setString(4,r.instance);q.setString(5,r.key);q.setString(6,r.type);q.setString(7,r.value);q.setLong(8,r.createdAt);q.setLong(9,r.updatedAt);if(r.expiresAt==null)q.setNull(10,Types.BIGINT);else q.setLong(10,r.expiresAt);q.setString(11,r.source);q.addBatch();}q.executeBatch();}connection.commit();connection.setAutoCommit(true);}catch(SQLException e){try{connection.rollback();connection.setAutoCommit(true);}catch(SQLException ignored){}throw new CompletionException(e);}},executor).exceptionally(e->{logger.severe("Could not save NPC memories: "+e.getMessage());return null;});}
    public CompletableFuture<Integer> sweepExpiredMemories(long now){return CompletableFuture.supplyAsync(()->{try(PreparedStatement q=connection.prepareStatement("DELETE FROM npc_memory WHERE expires_at IS NOT NULL AND expires_at<=?")){q.setLong(1,now);return q.executeUpdate();}catch(SQLException e){throw new CompletionException(e);}},executor);}
    public record BehaviorRow(String scope,String player,String npcDefinition,String instance,String behavior,String hash,String anchor,boolean visible,String checkpoint,java.util.Map<String,Integer> progress,java.util.Map<String,Long> deadlines,java.util.Map<String,Object> blackboard) {}
    public CompletableFuture<List<BehaviorRow>> loadBehaviorRuntimes(){return CompletableFuture.supplyAsync(()->{List<BehaviorRow> rows=new ArrayList<>();try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT scope,player,npc_definition,instance,behavior,tree_hash,anchor,visible,checkpoint FROM behavior_runtime")){while(r.next()){String scope=r.getString(1),player=r.getString(2),npc=r.getString(3),instance=r.getString(4);Map<String,Integer> progress=new HashMap<>();Map<String,Long> deadlines=new HashMap<>();try(PreparedStatement q=connection.prepareStatement("SELECT node_id,progress,deadline FROM behavior_checkpoint WHERE scope=? AND player=? AND npc_definition=? AND instance=?")){identity(q,scope,player,npc,instance);try(ResultSet x=q.executeQuery()){while(x.next()){progress.put(x.getString(1),x.getInt(2));if(x.getObject(3)!=null)deadlines.put(x.getString(1),x.getLong(3));}}}Map<String,Object> blackboard=new HashMap<>();try(PreparedStatement q=connection.prepareStatement("SELECT value_key,value_type,value FROM behavior_blackboard WHERE scope=? AND player=? AND npc_definition=? AND instance=?")){identity(q,scope,player,npc,instance);try(ResultSet x=q.executeQuery()){while(x.next())blackboard.put(x.getString(1),decodeValue(x.getString(2),x.getString(3)));}}rows.add(new BehaviorRow(scope,player,npc,instance,r.getString(5),r.getString(6),r.getString(7),r.getBoolean(8),r.getString(9),progress,deadlines,blackboard));}return rows;}catch(SQLException e){throw new CompletionException(e);}},executor);}
    public CompletableFuture<Void> saveBehaviorRuntimes(List<BehaviorRow> rows){return CompletableFuture.runAsync(()->{try{connection.setAutoCommit(false);try(Statement s=connection.createStatement()){s.executeUpdate("DELETE FROM behavior_runtime");s.executeUpdate("DELETE FROM behavior_blackboard");s.executeUpdate("DELETE FROM behavior_checkpoint");}try(PreparedStatement runtime=connection.prepareStatement("INSERT INTO behavior_runtime(scope,player,npc_definition,instance,behavior,tree_hash,anchor,visible,wake_at,checkpoint) VALUES(?,?,?,?,?,?,?,?,0,?)");PreparedStatement board=connection.prepareStatement("INSERT INTO behavior_blackboard(scope,player,npc_definition,instance,value_key,value_type,value) VALUES(?,?,?,?,?,?,?)");PreparedStatement checkpoints=connection.prepareStatement("INSERT INTO behavior_checkpoint(scope,player,npc_definition,instance,node_id,progress,deadline) VALUES(?,?,?,?,?,?,?)")){for(BehaviorRow r:rows){runtime.setString(1,r.scope);runtime.setString(2,r.player);runtime.setString(3,r.npcDefinition);runtime.setString(4,r.instance);runtime.setString(5,r.behavior);runtime.setString(6,r.hash);runtime.setString(7,r.anchor);runtime.setBoolean(8,r.visible);runtime.setString(9,r.checkpoint);runtime.addBatch();for(var e:r.blackboard.entrySet()){board.setString(1,r.scope);board.setString(2,r.player);board.setString(3,r.npcDefinition);board.setString(4,r.instance);board.setString(5,e.getKey());board.setString(6,valueType(e.getValue()));board.setString(7,String.valueOf(e.getValue()));board.addBatch();}Set<String> nodes=new HashSet<>(r.progress.keySet());nodes.addAll(r.deadlines.keySet());for(String node:nodes){checkpoints.setString(1,r.scope);checkpoints.setString(2,r.player);checkpoints.setString(3,r.npcDefinition);checkpoints.setString(4,r.instance);checkpoints.setString(5,node);checkpoints.setInt(6,r.progress.getOrDefault(node,0));Long deadline=r.deadlines.get(node);if(deadline==null)checkpoints.setNull(7,Types.BIGINT);else checkpoints.setLong(7,deadline);checkpoints.addBatch();}}runtime.executeBatch();board.executeBatch();checkpoints.executeBatch();}connection.commit();connection.setAutoCommit(true);}catch(SQLException e){try{connection.rollback();connection.setAutoCommit(true);}catch(SQLException ignored){}throw new CompletionException(e);}},executor).exceptionally(e->{logger.severe("Could not save behavior runtimes: "+e.getMessage());return null;});}
    private static void identity(PreparedStatement q,String scope,String player,String npc,String instance)throws SQLException{q.setString(1,scope);q.setString(2,player);q.setString(3,npc);q.setString(4,instance);}private static String valueType(Object v){return v instanceof Boolean?"boolean":v instanceof Number?"number":"string";}private static Object decodeValue(String type,String value){return switch(type){case "boolean"->Boolean.parseBoolean(value);case "number"->Double.parseDouble(value);default->value;};}

    @Override public void close() {
        executor.shutdown();
        try { if(!executor.awaitTermination(15, TimeUnit.SECONDS)) logger.warning("Timed out flushing Persona persistence"); connection.close(); }
        catch(InterruptedException e){Thread.currentThread().interrupt();} catch(SQLException e){logger.severe("Could not close Persona database: "+e.getMessage());}
    }
}
