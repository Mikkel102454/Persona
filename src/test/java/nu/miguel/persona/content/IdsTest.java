package nu.miguel.persona.content;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdsTest {
    @Test void acceptsExplicitLowercaseNamespace(){assertEquals("village:builder/one",Ids.require("village:builder/one","id"));}
    @Test void rejectsImplicitOrUppercaseIds(){assertThrows(IllegalArgumentException.class,()->Ids.require("builder","id"));assertThrows(IllegalArgumentException.class,()->Ids.require("Village:builder","id"));}
}
