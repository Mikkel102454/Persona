package nu.miguel.persona.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTokenTest {
    @Test void cancellationAndCallbacksAreExactlyOnce(){
        CancellationToken token=new CancellationToken();AtomicInteger calls=new AtomicInteger();token.onCancel(calls::incrementAndGet);
        assertTrue(token.cancel());assertFalse(token.cancel());assertTrue(token.isCancelled());assertEquals(1,calls.get());
        token.onCancel(calls::incrementAndGet);assertEquals(2,calls.get());assertFalse(token.cancel());assertEquals(2,calls.get());
    }
}
