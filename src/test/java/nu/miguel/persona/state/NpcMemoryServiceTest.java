package nu.miguel.persona.state;

import nu.miguel.persona.api.NpcMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class NpcMemoryServiceTest {
    @TempDir Path temp;
    @Test void isolatesScopesPersistsAtomicAdjustmentsAndExpiresImmediately() throws Exception {UUID player=UUID.randomUUID();Path db=temp.resolve("persona.db");try(SqliteStore store=new SqliteStore(db.toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){memory.set(player,"test:npc","one","trust",NpcMemoryService.Type.NUMBER,2,null,"test");memory.adjust(player,"test:npc","one","trust",3,null,"test");memory.set(null,"test:npc","one","visible",NpcMemoryService.Type.BOOLEAN,true,null,"test");memory.set(player,"test:npc","two","short",NpcMemoryService.Type.STRING,"gone",Duration.ZERO,"test");assertEquals(5,memory.get(player,"test:npc","one","trust").orElseThrow().numberValue());assertTrue(memory.get(null,"test:npc","one","visible").orElseThrow().booleanValue());assertTrue(memory.get(player,"test:npc","two","short").isEmpty());memory.flush();}try(SqliteStore store=new SqliteStore(db.toFile(),Logger.getAnonymousLogger());PersistentNpcMemoryService memory=new PersistentNpcMemoryService(store)){assertEquals(5,memory.get(player,"test:npc","one","trust").orElseThrow().numberValue());assertTrue(memory.get(player,"test:npc","one","visible").isEmpty());assertTrue(memory.forget(player,"test:npc","one","trust"));memory.flush();}}
}
