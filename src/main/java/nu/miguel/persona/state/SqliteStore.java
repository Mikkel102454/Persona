package nu.miguel.persona.state;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;

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
        }
        ensureColumn("player_quest","started_at","INTEGER NOT NULL DEFAULT 0");
        ensureColumn("completed_quest","completion_count","INTEGER NOT NULL DEFAULT 1");
        try(Statement s=connection.createStatement()){s.execute("UPDATE schema_version SET version=2");}
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

    @Override public void close() {
        executor.shutdown();
        try { if(!executor.awaitTermination(15, TimeUnit.SECONDS)) logger.warning("Timed out flushing Persona persistence"); connection.close(); }
        catch(InterruptedException e){Thread.currentThread().interrupt();} catch(SQLException e){logger.severe("Could not close Persona database: "+e.getMessage());}
    }
}
