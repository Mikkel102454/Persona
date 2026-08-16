package nu.miguel.persona.api;

import nu.miguel.persona.Main;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExpansionRegistryTest {
    @Test void ownsNamespacedTypesRejectsDuplicatesAndUnregisters() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        PersonaExpansion first=expansion("example","2.0",r->r.condition("ready",(c,d)->true));
        assertTrue(api.register(first));
        assertTrue(api.handler(ExpansionTypes.Condition.class,"example:ready").isPresent());
        assertThrows(IllegalArgumentException.class,()->api.register(expansion("example","2.0",r->{})));
        first.unregister();
        assertTrue(api.handler(ExpansionTypes.Condition.class,"example:ready").isEmpty());
    }

    @Test void validatesNamespaceAndApiCompatibility() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        assertThrows(IllegalArgumentException.class,()->api.register(expansion("Bad Namespace","1.0",r->{})));
        assertFalse(api.register(expansion("legacy","1.0",r->{})));
        assertThrows(IllegalArgumentException.class,()->api.register(expansion("persona","2.0",r->{})));
    }

    @Test void builtinVocabularyUsesThePublicRegistry() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        assertTrue(api.register(new BuiltinExpansion()));
        assertTrue(api.registeredTypes(ExpansionTypes.Condition.class).contains("persona:quest-state"));
        assertTrue(api.registeredTypes(ExpansionTypes.Command.class).contains("persona:start-quest"));
        assertTrue(api.registeredTypes(ExpansionTypes.Command.class).contains("persona:play-sound"));
        assertTrue(api.registeredTypes(ExpansionTypes.Objective.class).contains("persona:survive"));
        assertTrue(api.registeredTypes(ExpansionTypes.Command.class).contains("persona:give-item"));
        assertTrue(api.registeredTypes(ExpansionTypes.Placeholder.class).contains("persona:variable"));
    }

    private static PersonaExpansion expansion(String id,String apiVersion,java.util.function.Consumer<ExpansionRegistrar> registrations){
        return new PersonaExpansion(){public String identifier(){return id;}public String author(){return "test";}public String version(){return "1";}public String requiredApiVersion(){return apiVersion;}protected void registerTypes(ExpansionRegistrar r){registrations.accept(r);}};
    }
}
