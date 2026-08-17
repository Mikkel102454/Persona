package nu.miguel.persona.state;

import nu.miguel.persona.behavior.BehaviorRuntime.LogicalPosition;
import nu.miguel.persona.behavior.BehaviorRuntime.LogicalTravel;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public final class SqliteStore implements AutoCloseable {
    private static final int SCHEMA_VERSION=5;
    public static int schemaVersion(){return SCHEMA_VERSION;}
    private final Connection connection;
    private final ExecutorService executor;
    private final Logger logger;
    private final File databaseFile;

    public SqliteStore(File file,Logger logger)throws SQLException{
        this.logger=logger;databaseFile=file.getAbsoluteFile();
        File parent=databaseFile.getParentFile();
        if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new SQLException("Cannot create "+parent);
        new org.sqlite.JDBC();
        Connection opened=null;
        try{
            opened=open(databaseFile);verifyIntegrity(opened);
        }catch(SQLException failure){
            closeQuietly(opened);
            if(!isCorruption(failure)||!databaseFile.exists())throw failure;
            File quarantined=quarantine(databaseFile);
            logger.severe("Persona database was corrupt and was moved to "+quarantined.getAbsolutePath()+"; starting with a new database");
            opened=open(databaseFile);
        }
        connection=opened;
        try{migrate();}
        catch(SQLException e){closeQuietly(connection);throw e;}
        executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Persona persistence");t.setDaemon(true);return t;});
    }

    private static Connection open(File file)throws SQLException{return DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());}
    private static void verifyIntegrity(Connection connection)throws SQLException{
        try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("PRAGMA quick_check(1)")){
            if(!result.next()||!"ok".equalsIgnoreCase(result.getString(1)))throw new SQLException("SQLite integrity check failed: "+(result.isClosed()?"no result":result.getString(1)),"SQLITE_CORRUPT");
        }
    }
    private static boolean isCorruption(SQLException error){for(SQLException e=error;e!=null;e=e.getNextException()){String value=(e.getSQLState()+" "+e.getMessage()).toLowerCase(Locale.ROOT);if(value.contains("corrupt")||value.contains("malformed")||value.contains("not a database"))return true;}return false;}
    private static File quarantine(File database)throws SQLException{
        String suffix=".corrupt-"+System.currentTimeMillis();Path source=database.toPath(),target=Path.of(database.getAbsolutePath()+suffix);
        try{
            Files.move(source,target);
            for(String sidecar:List.of("-wal","-shm")){Path extra=Path.of(database.getAbsolutePath()+sidecar);if(Files.exists(extra))Files.move(extra,Path.of(target+sidecar));}
            return target.toFile();
        }catch(IOException e){throw new SQLException("Could not preserve corrupt database before recovery",e);}
    }
    private static void closeQuietly(Connection connection){if(connection!=null)try{connection.close();}catch(SQLException ignored){}}

    private void migrate()throws SQLException{
        try(Statement s=connection.createStatement()){
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
            try(ResultSet rs=s.executeQuery("SELECT count(*) FROM schema_version")){if(rs.next()&&rs.getInt(1)==0)s.execute("INSERT INTO schema_version VALUES(1)");}
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
        ensureColumn("behavior_runtime","logical_world","TEXT");ensureColumn("behavior_runtime","logical_x","REAL");ensureColumn("behavior_runtime","logical_y","REAL");ensureColumn("behavior_runtime","logical_z","REAL");ensureColumn("behavior_runtime","logical_yaw","REAL");ensureColumn("behavior_runtime","logical_pitch","REAL");
        ensureColumn("behavior_runtime","checkpoint_structure","TEXT");
        ensureColumn("behavior_runtime","travel_behavior","TEXT");ensureColumn("behavior_runtime","travel_node","TEXT");ensureColumn("behavior_runtime","travel_source","TEXT");ensureColumn("behavior_runtime","travel_destination","TEXT");ensureColumn("behavior_runtime","travel_started_at","INTEGER");ensureColumn("behavior_runtime","travel_duration","INTEGER");ensureColumn("behavior_runtime","updated_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("behavior_checkpoint","behavior_id","TEXT NOT NULL DEFAULT ''");
        ensureColumn("behavior_checkpoint","node_type","TEXT");
        try(Statement s=connection.createStatement()){
            s.execute("CREATE INDEX IF NOT EXISTS idx_objective_player ON objective(player)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_memory_expiry ON npc_memory(expires_at) WHERE expires_at IS NOT NULL");
            s.execute("CREATE INDEX IF NOT EXISTS idx_memory_player_npc ON npc_memory(player,npc_definition,instance)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_runtime_player_npc ON behavior_runtime(player,npc_definition,instance)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_runtime_behavior ON behavior_runtime(behavior)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_blackboard_runtime ON behavior_blackboard(scope,player,npc_definition,instance)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_checkpoint_runtime ON behavior_checkpoint(scope,player,npc_definition,instance)");
            s.execute("UPDATE schema_version SET version="+SCHEMA_VERSION);
            s.execute("PRAGMA optimize");
        }
    }

    private void ensureColumn(String table,String column,String declaration)throws SQLException{boolean found=false;try(Statement s=connection.createStatement();ResultSet rs=s.executeQuery("PRAGMA table_info("+table+")")){while(rs.next())if(rs.getString("name").equalsIgnoreCase(column)){found=true;break;}}if(!found)try(Statement s=connection.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+declaration);}}

    public CompletableFuture<PlayerState> load(UUID id){return CompletableFuture.supplyAsync(()->{PlayerState state=new PlayerState(id);String p=id.toString();try{try(PreparedStatement q=connection.prepareStatement("SELECT quest,phase,started_at FROM player_quest WHERE player=?")){q.setString(1,p);try(ResultSet rs=q.executeQuery()){while(rs.next()){long started=rs.getLong(3);state.quests().put(rs.getString(1),new PlayerState.QuestProgress(rs.getInt(2),started==0?System.currentTimeMillis():started));}}}try(PreparedStatement q=connection.prepareStatement("SELECT quest,objective,value,started_at,online_since FROM objective WHERE player=?")){q.setString(1,p);try(ResultSet rs=q.executeQuery()){while(rs.next()){var quest=state.quests().get(rs.getString(1));if(quest!=null)quest.objectives().put(rs.getString(2),new PlayerState.ObjectiveProgress(rs.getLong(3),rs.getLong(4),rs.getLong(5)));}}}try(PreparedStatement q=connection.prepareStatement("SELECT quest,completed_at,completion_count FROM completed_quest WHERE player=?")){q.setString(1,p);try(ResultSet rs=q.executeQuery()){while(rs.next()){String quest=rs.getString(1);if(!state.quests().containsKey(quest))state.completed().add(quest);state.completedAt().put(quest,rs.getLong(2));state.completions().put(quest,rs.getInt(3));}}}try(PreparedStatement q=connection.prepareStatement("SELECT name,value FROM flag WHERE player=?")){q.setString(1,p);try(ResultSet rs=q.executeQuery()){while(rs.next())state.flags().put(rs.getString(1),rs.getBoolean(2));}}try(PreparedStatement q=connection.prepareStatement("SELECT name,value FROM variable WHERE player=?")){q.setString(1,p);try(ResultSet rs=q.executeQuery()){while(rs.next())state.variables().put(rs.getString(1),rs.getString(2));}}return state;}catch(SQLException e){throw new CompletionException(e);}},executor);}

    public CompletableFuture<Void> save(PlayerState original){PlayerState state=original.snapshot();return CompletableFuture.runAsync(()->{String p=state.playerId().toString();try{connection.setAutoCommit(false);delete("DELETE FROM objective WHERE player=?",p);delete("DELETE FROM player_quest WHERE player=?",p);delete("DELETE FROM completed_quest WHERE player=?",p);delete("DELETE FROM flag WHERE player=?",p);delete("DELETE FROM variable WHERE player=?",p);try(PreparedStatement q=connection.prepareStatement("INSERT INTO player_quest(player,quest,phase,started_at) VALUES(?,?,?,?)")){for(var e:state.quests().entrySet()){q.setString(1,p);q.setString(2,e.getKey());q.setInt(3,e.getValue().phase());q.setLong(4,e.getValue().startedAt());q.addBatch();}q.executeBatch();}try(PreparedStatement q=connection.prepareStatement("INSERT INTO objective VALUES(?,?,?,?,?,?)")){for(var e:state.quests().entrySet())for(var o:e.getValue().objectives().entrySet()){q.setString(1,p);q.setString(2,e.getKey());q.setString(3,o.getKey());q.setLong(4,o.getValue().value());q.setLong(5,o.getValue().startedAt());q.setLong(6,o.getValue().onlineSince());q.addBatch();}q.executeBatch();}try(PreparedStatement q=connection.prepareStatement("INSERT INTO completed_quest(player,quest,completed_at,completion_count) VALUES(?,?,?,?)")){Set<String> history=new HashSet<>(state.completed());history.addAll(state.completions().keySet());for(String quest:history){q.setString(1,p);q.setString(2,quest);q.setLong(3,state.completedAt().getOrDefault(quest,System.currentTimeMillis()));q.setInt(4,state.completions().getOrDefault(quest,1));q.addBatch();}q.executeBatch();}try(PreparedStatement q=connection.prepareStatement("INSERT INTO flag VALUES(?,?,?)")){for(var e:state.flags().entrySet()){q.setString(1,p);q.setString(2,e.getKey());q.setBoolean(3,e.getValue());q.addBatch();}q.executeBatch();}try(PreparedStatement q=connection.prepareStatement("INSERT INTO variable VALUES(?,?,?)")){for(var e:state.variables().entrySet()){q.setString(1,p);q.setString(2,e.getKey());q.setString(3,e.getValue());q.addBatch();}q.executeBatch();}connection.commit();}catch(SQLException e){rollback();throw new CompletionException(e);}finally{autoCommit();}},executor).whenComplete((ignored,error)->{if(error!=null)logger.severe("Could not save Persona state: "+error.getMessage());});}
    private void delete(String sql,String player)throws SQLException{try(PreparedStatement q=connection.prepareStatement(sql)){q.setString(1,player);q.executeUpdate();}}

    public record MemoryRow(String scope,String player,String npcDefinition,String instance,String key,String type,String value,long createdAt,long updatedAt,Long expiresAt,String source) {}
    public CompletableFuture<List<MemoryRow>> loadMemories(){return CompletableFuture.supplyAsync(()->{List<MemoryRow> rows=new ArrayList<>();try(Statement q=connection.createStatement();ResultSet r=q.executeQuery("SELECT scope,player,npc_definition,instance,memory_key,value_type,value,created_at,updated_at,expires_at,source FROM npc_memory")){while(r.next())rows.add(new MemoryRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getLong(9),r.getObject(10)==null?null:r.getLong(10),r.getString(11)));return rows;}catch(SQLException e){throw new CompletionException(e);}},executor);}
    public CompletableFuture<Void> saveMemories(List<MemoryRow> rows,Set<String> deleted){return CompletableFuture.runAsync(()->{try{connection.setAutoCommit(false);try(PreparedStatement d=connection.prepareStatement("DELETE FROM npc_memory WHERE scope=? AND player=? AND npc_definition=? AND instance=? AND memory_key=?")){for(String packed:deleted){String[] p=packed.split(java.util.regex.Pattern.quote("\0"),-1);for(int i=0;i<5;i++)d.setString(i+1,p[i]);d.addBatch();}d.executeBatch();}try(PreparedStatement q=connection.prepareStatement("INSERT INTO npc_memory(scope,player,npc_definition,instance,memory_key,value_type,value,created_at,updated_at,expires_at,source) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scope,player,npc_definition,instance,memory_key) DO UPDATE SET value_type=excluded.value_type,value=excluded.value,updated_at=excluded.updated_at,expires_at=excluded.expires_at,source=excluded.source")){for(MemoryRow r:rows){q.setString(1,r.scope);q.setString(2,r.player);q.setString(3,r.npcDefinition);q.setString(4,r.instance);q.setString(5,r.key);q.setString(6,r.type);q.setString(7,r.value);q.setLong(8,r.createdAt);q.setLong(9,r.updatedAt);if(r.expiresAt==null)q.setNull(10,Types.BIGINT);else q.setLong(10,r.expiresAt);q.setString(11,r.source);q.addBatch();}q.executeBatch();}connection.commit();}catch(SQLException e){rollback();throw new CompletionException(e);}finally{autoCommit();}},executor).whenComplete((ignored,error)->{if(error!=null)logger.severe("Could not save NPC memories: "+error.getMessage());});}
    public CompletableFuture<Integer> sweepExpiredMemories(long now){return CompletableFuture.supplyAsync(()->{try(PreparedStatement q=connection.prepareStatement("DELETE FROM npc_memory WHERE expires_at IS NOT NULL AND expires_at<=?")){q.setLong(1,now);return q.executeUpdate();}catch(SQLException e){throw new CompletionException(e);}},executor);}

    public record BehaviorRow(String scope,String player,String npcDefinition,String instance,String behavior,String hash,
                              String anchor,LogicalPosition position,boolean visible,String checkpoint,long wakeAt,
                              Map<String,Integer> progress,Map<String,Long> deadlines,Map<String,Object> blackboard,
                              Map<String,String> nodeTypes,String checkpointStructure,LogicalTravel logicalTravel) {
        public BehaviorRow {progress=Map.copyOf(progress);deadlines=Map.copyOf(deadlines);blackboard=Map.copyOf(blackboard);nodeTypes=Map.copyOf(nodeTypes);}
        public BehaviorRow(String scope,String player,String npcDefinition,String instance,String behavior,String hash,String anchor,
                           LogicalPosition position,boolean visible,String checkpoint,Map<String,Integer> progress,
                           Map<String,Long> deadlines,Map<String,Object> blackboard){
            this(scope,player,npcDefinition,instance,behavior,hash,anchor,position,visible,checkpoint,0,progress,deadlines,blackboard,Map.of(),null,null);
        }
    }
    private record Identity(String scope,String player,String npc,String instance) {}

    /** Loads runtime tables in three indexed scans, avoiding one query per runtime. */
    public CompletableFuture<List<BehaviorRow>> loadBehaviorRuntimes(){return CompletableFuture.supplyAsync(()->{
        Map<Identity,Map<String,Integer>> progress=new HashMap<>();Map<Identity,Map<String,Long>> deadlines=new HashMap<>();Map<Identity,Map<String,String>> nodeTypes=new HashMap<>();Map<Identity,Map<String,Object>> boards=new HashMap<>();
        try{
            try(Statement q=connection.createStatement();ResultSet r=q.executeQuery("SELECT scope,player,npc_definition,instance,behavior_id,node_id,node_type,progress,deadline FROM behavior_checkpoint")){while(r.next()){Identity id=identity(r);String behavior=r.getString(5),node=r.getString(6);String key=behavior.isEmpty()||node.startsWith(behavior+"/")||node.startsWith(behavior+"::")?node:stateKey(behavior,node);progress.computeIfAbsent(id,x->new HashMap<>()).put(key,r.getInt(8));if(r.getObject(9)!=null)deadlines.computeIfAbsent(id,x->new HashMap<>()).put(key,r.getLong(9));if(r.getString(7)!=null)nodeTypes.computeIfAbsent(id,x->new HashMap<>()).put(baseKey(key),r.getString(7));}}
            try(Statement q=connection.createStatement();ResultSet r=q.executeQuery("SELECT scope,player,npc_definition,instance,value_key,value_type,value FROM behavior_blackboard")){while(r.next()){try{boards.computeIfAbsent(identity(r),x->new HashMap<>()).put(r.getString(5),decodeValue(r.getString(6),r.getString(7)));}catch(RuntimeException corrupt){logger.warning("Ignoring corrupt behavior blackboard value "+r.getString(5)+": "+corrupt.getMessage());}}}
            List<BehaviorRow> rows=new ArrayList<>();
            String sql="SELECT scope,player,npc_definition,instance,behavior,tree_hash,anchor,visible,checkpoint,wake_at,logical_world,logical_x,logical_y,logical_z,logical_yaw,logical_pitch,checkpoint_structure,travel_behavior,travel_node,travel_source,travel_destination,travel_started_at,travel_duration FROM behavior_runtime";
            try(Statement q=connection.createStatement();ResultSet r=q.executeQuery(sql)){while(r.next()){try{Identity id=identity(r);LogicalPosition position=r.getString(11)==null?null:new LogicalPosition(r.getString(11),r.getDouble(12),r.getDouble(13),r.getDouble(14),r.getFloat(15),r.getFloat(16));LogicalTravel travel=r.getString(18)==null||r.getString(19)==null||r.getString(21)==null?null:new LogicalTravel(r.getString(18),r.getString(19),r.getString(20),r.getString(21),r.getLong(22),r.getLong(23));rows.add(new BehaviorRow(id.scope,id.player,id.npc,id.instance,r.getString(5),r.getString(6),r.getString(7),position,r.getBoolean(8),r.getString(9),r.getLong(10),progress.getOrDefault(id,Map.of()),deadlines.getOrDefault(id,Map.of()),boards.getOrDefault(id,Map.of()),nodeTypes.getOrDefault(id,Map.of()),r.getString(17),travel));}catch(RuntimeException corrupt){logger.warning("Ignoring corrupt behavior runtime row: "+corrupt.getMessage());}}}
            return rows;
        }catch(SQLException e){throw new CompletionException(e);}
    },executor);}

    /** Incrementally upserts only dirty runtimes; each runtime and its child rows commit atomically. */
    public CompletableFuture<Void> saveBehaviorRuntimes(List<BehaviorRow> rows){List<BehaviorRow> copies=List.copyOf(rows);return CompletableFuture.runAsync(()->{for(BehaviorRow row:copies)saveBehaviorRuntime(row);},executor).whenComplete((ignored,error)->{if(error!=null)logger.severe("Could not save behavior runtimes: "+error.getMessage());});}
    private void saveBehaviorRuntime(BehaviorRow r){
        try{
            connection.setAutoCommit(false);
            String sql="INSERT INTO behavior_runtime(scope,player,npc_definition,instance,behavior,tree_hash,anchor,visible,wake_at,checkpoint,logical_world,logical_x,logical_y,logical_z,logical_yaw,logical_pitch,checkpoint_structure,travel_behavior,travel_node,travel_source,travel_destination,travel_started_at,travel_duration,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(scope,player,npc_definition,instance) DO UPDATE SET behavior=excluded.behavior,tree_hash=excluded.tree_hash,anchor=excluded.anchor,visible=excluded.visible,wake_at=excluded.wake_at,checkpoint=excluded.checkpoint,logical_world=excluded.logical_world,logical_x=excluded.logical_x,logical_y=excluded.logical_y,logical_z=excluded.logical_z,logical_yaw=excluded.logical_yaw,logical_pitch=excluded.logical_pitch,checkpoint_structure=excluded.checkpoint_structure,travel_behavior=excluded.travel_behavior,travel_node=excluded.travel_node,travel_source=excluded.travel_source,travel_destination=excluded.travel_destination,travel_started_at=excluded.travel_started_at,travel_duration=excluded.travel_duration,updated_at=excluded.updated_at";
            try(PreparedStatement runtime=connection.prepareStatement(sql)){identity(runtime,r.scope,r.player,r.npcDefinition,r.instance);runtime.setString(5,r.behavior);runtime.setString(6,r.hash);runtime.setString(7,r.anchor);runtime.setBoolean(8,r.visible);runtime.setLong(9,r.wakeAt);runtime.setString(10,r.checkpoint);LogicalPosition p=r.position;if(p==null){for(int i=11;i<=16;i++)runtime.setNull(i,i==11?Types.VARCHAR:Types.REAL);}else{runtime.setString(11,p.world());runtime.setDouble(12,p.x());runtime.setDouble(13,p.y());runtime.setDouble(14,p.z());runtime.setFloat(15,p.yaw());runtime.setFloat(16,p.pitch());}runtime.setString(17,r.checkpointStructure);LogicalTravel travel=r.logicalTravel;if(travel==null){for(int i=18;i<=23;i++)runtime.setNull(i,i<=21?Types.VARCHAR:Types.BIGINT);}else{runtime.setString(18,travel.behaviorId());runtime.setString(19,travel.nodeId());runtime.setString(20,travel.source());runtime.setString(21,travel.destination());runtime.setLong(22,travel.startedAt());runtime.setLong(23,travel.durationMillis());}runtime.setLong(24,System.currentTimeMillis());runtime.executeUpdate();}
            deleteRuntimeChildren(r);
            try(PreparedStatement board=connection.prepareStatement("INSERT INTO behavior_blackboard(scope,player,npc_definition,instance,value_key,value_type,value) VALUES(?,?,?,?,?,?,?)")){for(var e:r.blackboard.entrySet()){identity(board,r.scope,r.player,r.npcDefinition,r.instance);board.setString(5,e.getKey());board.setString(6,valueType(e.getValue()));board.setString(7,String.valueOf(e.getValue()));board.addBatch();}board.executeBatch();}
            Set<String> nodes=new HashSet<>(r.progress.keySet());nodes.addAll(r.deadlines.keySet());nodes.addAll(r.nodeTypes.keySet());
            try(PreparedStatement checkpoint=connection.prepareStatement("INSERT INTO behavior_checkpoint(scope,player,npc_definition,instance,behavior_id,node_id,node_type,progress,deadline) VALUES(?,?,?,?,?,?,?,?,?)")){for(String key:nodes){String base=baseKey(key);int split=base.indexOf('/');String behavior=split<=0?"":base.substring(0,split);identity(checkpoint,r.scope,r.player,r.npcDefinition,r.instance);checkpoint.setString(5,behavior);checkpoint.setString(6,key);checkpoint.setString(7,r.nodeTypes.get(base));checkpoint.setInt(8,r.progress.getOrDefault(key,0));Long deadline=r.deadlines.get(key);if(deadline==null)checkpoint.setNull(9,Types.BIGINT);else checkpoint.setLong(9,deadline);checkpoint.addBatch();}checkpoint.executeBatch();}
            connection.commit();
        }catch(SQLException e){rollback();throw new CompletionException(e);}finally{autoCommit();}
    }
    private void deleteRuntimeChildren(BehaviorRow r)throws SQLException{for(String table:List.of("behavior_blackboard","behavior_checkpoint"))try(PreparedStatement q=connection.prepareStatement("DELETE FROM "+table+" WHERE scope=? AND player=? AND npc_definition=? AND instance=?")){identity(q,r.scope,r.player,r.npcDefinition,r.instance);q.executeUpdate();}}

    /** Creates a consistent online SQLite backup using VACUUM INTO. The target must not exist. */
    public CompletableFuture<File> backup(File target){File destination=target.getAbsoluteFile();return CompletableFuture.supplyAsync(()->{if(destination.exists())throw new CompletionException(new IOException("Backup target already exists: "+destination));File parent=destination.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new CompletionException(new IOException("Cannot create "+parent));String escaped=destination.getAbsolutePath().replace("'","''");try(Statement s=connection.createStatement()){s.execute("VACUUM INTO '"+escaped+"'");return destination;}catch(SQLException e){throw new CompletionException(e);}},executor);}
    public CompletableFuture<String> integrityCheck(){return CompletableFuture.supplyAsync(()->{try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("PRAGMA integrity_check")){return r.next()?r.getString(1):"no result";}catch(SQLException e){throw new CompletionException(e);}},executor);}
    /** Returns the query plans used to verify the large runtime/memory indexes. */
    public CompletableFuture<Map<String,List<String>>> persistenceQueryPlans(){return CompletableFuture.supplyAsync(()->{try{Map<String,List<String>> plans=new LinkedHashMap<>();plans.put("runtime-player",queryPlan("SELECT * FROM behavior_runtime WHERE player=? AND npc_definition=?","player","npc"));plans.put("runtime-checkpoints",queryPlan("SELECT * FROM behavior_checkpoint WHERE scope=? AND player=? AND npc_definition=? AND instance=?","player","player","npc","instance"));plans.put("expired-memory",queryPlan("SELECT * FROM npc_memory WHERE expires_at IS NOT NULL AND expires_at<=?",System.currentTimeMillis()));return Map.copyOf(plans);}catch(SQLException e){throw new CompletionException(e);}},executor);}
    private List<String> queryPlan(String sql,Object...values)throws SQLException{List<String> result=new ArrayList<>();try(PreparedStatement q=connection.prepareStatement("EXPLAIN QUERY PLAN "+sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(ResultSet r=q.executeQuery()){while(r.next())result.add(r.getString("detail"));}}return List.copyOf(result);}

    private static Identity identity(ResultSet r)throws SQLException{return new Identity(r.getString(1),r.getString(2),r.getString(3),r.getString(4));}
    private static void identity(PreparedStatement q,String scope,String player,String npc,String instance)throws SQLException{q.setString(1,scope);q.setString(2,player);q.setString(3,npc);q.setString(4,instance);}
    private static String stateKey(String behavior,String node){return behavior+"/"+node;}
    private static String baseKey(String key){int marker=key.indexOf("#parallel:");return marker<0?key:key.substring(0,marker);}
    private static String valueType(Object v){return v instanceof Boolean?"boolean":v instanceof Number?"number":"string";}
    private static Object decodeValue(String type,String value){return switch(type){case "boolean"->Boolean.parseBoolean(value);case "number"->Double.parseDouble(value);default->value;};}
    private void rollback(){try{connection.rollback();}catch(SQLException ignored){}}
    private void autoCommit(){try{connection.setAutoCommit(true);}catch(SQLException ignored){}}

    @Override public void close(){executor.shutdown();try{if(!executor.awaitTermination(15,TimeUnit.SECONDS))logger.warning("Timed out flushing Persona persistence");connection.close();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(SQLException e){logger.severe("Could not close Persona database: "+e.getMessage());}}
}
