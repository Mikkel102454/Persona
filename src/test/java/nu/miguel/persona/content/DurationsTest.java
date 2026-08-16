package nu.miguel.persona.content;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class DurationsTest {
    @Test void parsesSupportedUnitsAndDecimals(){
        assertEquals(Duration.ofMillis(2500),Durations.parse("2.5s"));
        assertEquals(Duration.ofMinutes(3),Durations.parse("3m"));
        assertEquals(Duration.ofMillis(125),Durations.parse("125ms"));
        assertEquals(Duration.ofSeconds(2),Durations.parse(2));
    }
    @Test void rejectsMalformedDurations(){assertThrows(IllegalArgumentException.class,()->Durations.parse("soon"));}
}
