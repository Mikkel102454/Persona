package nu.miguel.persona.content;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationSchemaTest {
    @Test void enforcesStringsNumbersListsAndNestedObjects(){
        Map<String,Object> schema=Map.of("type","object","required",List.of("name","delay"),"additionalProperties",false,"properties",Map.of(
                "name",Map.of("type","string","pattern","[a-z]+:[a-z-]+","minLength",3,"maxLength",30),
                "delay",Map.of("type","string","format","duration"),
                "weights",Map.of("type","array","minItems",2,"maxItems",3,"uniqueItems",true,"items",Map.of("type","integer","minimum",1,"maximum",5)),
                "nested",Map.of("type","object","required",List.of("enabled"),"properties",Map.of("enabled",Map.of("type","boolean")))));
        assertDoesNotThrow(()->Validation.schema(schema,Map.of("name","demo:bell","delay","2s","weights",List.of(1,3),"nested",Map.of("enabled",true)),"action"));
        assertThrows(IllegalArgumentException.class,()->Validation.schema(schema,Map.of("name","BAD","delay","soon"),"action"));
        assertThrows(IllegalArgumentException.class,()->Validation.schema(schema,Map.of("name","demo:bell","delay","2s","weights",List.of(1,1)),"action"));
    }

    @Test void enforcesConditionalAndMutuallyExclusiveFields(){
        Map<String,Object> schema=Map.of("type","object","oneOf",List.of(
                Map.of("required",List.of("kind","sound"),"properties",Map.of("kind",Map.of("const","sound"))),
                Map.of("required",List.of("kind","particle"),"properties",Map.of("kind",Map.of("const","particle")))),
                "not",Map.of("required",List.of("sound","particle")),
                "dependentRequired",Map.of("sound",List.of("volume")));
        assertDoesNotThrow(()->Validation.schema(schema,Map.of("kind","sound","sound","bell","volume",1),"action"));
        assertThrows(IllegalArgumentException.class,()->Validation.schema(schema,Map.of("kind","sound","sound","bell"),"action"));
        assertThrows(IllegalArgumentException.class,()->Validation.schema(schema,Map.of("kind","sound","sound","bell","particle","happy","volume",1),"action"));
    }

    @Test void surfacesExtensionCrossFieldMessage(){
        Map<String,Object> schema=Map.of("type","object","not",Map.of("required",List.of("from","at")),
                "x-persona-validation-message","Choose either from or at, not both");
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->Validation.schema(schema,Map.of("from","npc","at","anchor"),"move"));
        assertTrue(failure.getMessage().contains("Choose either"));
    }
}
