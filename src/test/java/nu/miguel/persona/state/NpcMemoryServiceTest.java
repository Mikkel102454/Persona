package nu.miguel.persona.state;

import nu.miguel.persona.api.NpcMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.io.File;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class NpcMemoryServiceTest {
    @TempDir Path temp;
    @Test void isolatesScopesPersistsAtomicAdjustmentsAndExpiresImmediately() throws Exception {UUID player=UUID.randomUUID();Path db=temp.resolve("persona.db");try(SqliteStore store=new SqliteStore(db.toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){memory.set(player,"test:npc","one","trust",NpcMemoryService.Type.NUMBER,2,null,"test");memory.adjust(player,"test:npc","one","trust",3,null,"test");memory.set(null,"test:npc","one","visible",NpcMemoryService.Type.BOOLEAN,true,null,"test");memory.set(player,"test:npc","two","short",NpcMemoryService.Type.STRING,"gone",Duration.ZERO,"test");assertEquals(5,memory.get(player,"test:npc","one","trust").orElseThrow().numberValue());assertTrue(memory.get(null,"test:npc","one","visible").orElseThrow().booleanValue());assertTrue(memory.get(player,"test:npc","two","short").isEmpty());memory.flush();}try(SqliteStore store=new SqliteStore(db.toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){assertEquals(5,memory.get(player,"test:npc","one","trust").orElseThrow().numberValue());assertTrue(memory.get(player,"test:npc","one","visible").isEmpty());assertTrue(memory.forget(player,"test:npc","one","trust"));memory.flush();}}

    @Test void compareAndSetAndBoundedAdjustmentsAreAtomic()throws Exception{
        try(SqliteStore store=new SqliteStore(temp.resolve("atomic.db").toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){
            var first=memory.compareAndSet(null,"test:npc","one","score",null,NpcMemoryService.Type.NUMBER,8,null,"test");assertTrue(first.applied());
            assertFalse(memory.compareAndSet(null,"test:npc","one","score",7,NpcMemoryService.Type.NUMBER,9,null,"test").applied());
            assertEquals(10,memory.adjust(null,"test:npc","one","score",5,0,10,null,"test").numberValue());
            assertEquals(0,memory.adjust(null,"test:npc","one","score",-20,0,10,null,"test").numberValue());
        }
    }

    @Test void changesContainTypedOldAndNewValuesAndExpirySource()throws Exception{
        try(SqliteStore store=new SqliteStore(temp.resolve("events.db").toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){
            var changes=new ArrayList<PersistentNpcMemoryService.Change>();memory.onChange(changes::add);
            memory.set(null,"test:npc","one","key",NpcMemoryService.Type.STRING,"old",null,"script");
            memory.set(null,"test:npc","one","key",NpcMemoryService.Type.STRING,"new",null,"admin");
            memory.expire(null,"test:npc","one","key",Instant.now(),"admin-expire");
            assertEquals(3,changes.size());assertNull(changes.getFirst().oldValue());assertEquals("old",changes.get(1).oldValue().value());assertNull(changes.getLast().newValue());assertEquals("admin-expire",changes.getLast().source());
        }
    }

    @Test void enforcesClaimedExtensionNamespaces()throws Exception{
        try(SqliteStore store=new SqliteStore(temp.resolve("namespace.db").toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){
            memory.claimNamespace("fishing","fishing");memory.set(null,"test:npc","one","fishing:rank",NpcMemoryService.Type.NUMBER,1,null,"extension:fishing/action");
            assertThrows(SecurityException.class,()->memory.set(null,"test:npc","one","fishing:rank",NpcMemoryService.Type.NUMBER,2,null,"extension:other/action"));
        }
    }

    @Test void exportsAndImportsTypedMemoryForMigration()throws Exception{
        File transfer=temp.resolve("memories.yml").toFile();UUID player=UUID.randomUUID();
        try(SqliteStore store=new SqliteStore(temp.resolve("source.db").toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){
            memory.set(player,"test:npc","one","met-at",NpcMemoryService.Type.TIMESTAMP,"2026-08-17T10:15:30Z",Duration.ofHours(2),"test");assertEquals(1,MemoryTransfer.exportTo(memory,transfer));
        }
        try(SqliteStore store=new SqliteStore(temp.resolve("target.db").toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){
            assertEquals(1,MemoryTransfer.importFrom(memory,transfer));var value=memory.get(player,"test:npc","one","met-at").orElseThrow();assertEquals(NpcMemoryService.Type.TIMESTAMP,value.type());assertEquals(Instant.parse("2026-08-17T10:15:30Z"),value.timestampValue());
        }
    }

    @Test void retentionDelaysDatabaseSweepAndReportsMetrics()throws Exception{
        try(SqliteStore store=new SqliteStore(temp.resolve("retention.db").toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){
            memory.set(null,"test:npc","one","short",NpcMemoryService.Type.STRING,"value",null,"test");memory.flush();memory.expiredRetention(Duration.ofHours(1));memory.expire(null,"test:npc","one","short",Instant.now(),"test");memory.flush();memory.sweep();assertEquals(0,memory.sweepMetrics().rowsRemoved());
            memory.expiredRetention(Duration.ZERO);memory.sweep();assertEquals(1,memory.sweepMetrics().rowsRemoved());assertEquals(2,memory.sweepMetrics().runs());
        }
    }

    @Test void parsesFriendlyMemoryTimes(){assertTrue(MemoryTimes.parse("now").isBefore(Instant.now().plusSeconds(1)));assertTrue(MemoryTimes.parse("+5m").isAfter(Instant.now().plusSeconds(290)));assertEquals(Instant.parse("2026-08-17T10:15:30Z"),MemoryTimes.parse("2026-08-17T10:15:30Z"));}
}
