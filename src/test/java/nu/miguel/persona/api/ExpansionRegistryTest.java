package nu.miguel.persona.api;

import nu.miguel.persona.Main;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import nu.miguel.persona.behavior.BehaviorStatus;

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
        assertTrue(api.register(expansion("minor-old","2.0",r->{})));
        assertTrue(api.register(expansion("minor-current","2.1",r->{})));
        assertTrue(api.register(expansion("minor-latest","2.2",r->{})));
        assertFalse(api.register(expansion("minor-future","2.9",r->{})));
    }

    @Test void publishesExtensionBehaviorSchemaMetadata(){
        PersonaApi api=new PersonaApi(mock(Main.class));
        assertTrue(api.register(expansion("weather","2.0",r->r.behaviorCondition("raining",new ExpansionTypes.BehaviorCondition(){
            public boolean test(BehaviorContext context,Map<String,Object> data){return true;}
            public BehaviorNodeMetadata metadata(){return new BehaviorNodeMetadata(Set.of(nu.miguel.persona.behavior.BehaviorScope.SHARED),Set.of("weather-change"),Map.of(),Map.of("type","object"));}
        }))));
        assertEquals("object",api.behaviorSchemas().get("condition:weather:raining").get("type"));
    }

    @Test void api20StyleBehaviorActionUsesAdditive21Defaults(){
        // This class implements only the methods present in 2.0. Invoking the 2.1
        // overload proves the additive default dispatch used by old class files.
        ExpansionTypes.BehaviorAction oldStyle=new ExpansionTypes.BehaviorAction(){
            public java.util.concurrent.CompletionStage<BehaviorStatus> execute(BehaviorContext context,Map<String,Object> data){return CompletableFuture.completedFuture(BehaviorStatus.SUCCESS);}
        };
        assertEquals(BehaviorStatus.SUCCESS,oldStyle.execute(null,Map.of(),new CancellationToken()).toCompletableFuture().join());
        assertEquals(Set.of(nu.miguel.persona.behavior.BehaviorScope.SHARED,nu.miguel.persona.behavior.BehaviorScope.PLAYER),oldStyle.metadata().scopes());
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

    @Test void publishesTypedSchemasForEveryExtensionCategoryAndFutureTypes() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        assertTrue(api.register(expansion("forms","2.2",r->{
            r.command("announce",new ExpansionTypes.Command(){
                public java.util.concurrent.CompletionStage<ExpansionTypes.CommandResult> execute(PersonaContext c,Map<String,Object> d){return CompletableFuture.completedFuture(ExpansionTypes.CommandResult.success());}
                public java.util.List<ExpansionTypes.ScriptPin> inputPins(){return java.util.List.of(new ExpansionTypes.ScriptPin("channel","string",true,null));}
                public java.util.List<ExpansionTypes.ScriptPin> outputPins(){return java.util.List.of(new ExpansionTypes.ScriptPin("receipt","forms:receipt",true,null));}
                public Map<String,Map<String,Object>> nominalValueTypes(){return Map.of("forms:receipt",Map.of("label","Receipt"));}
                public Map<String,Object> editorSchema(){return Map.of("type","object","required",java.util.List.of("channel"),"properties",Map.of("channel",Map.of("type","string","enum",java.util.List.of("local","global"),EditorSchemaAnnotations.WIDGET,"radio-group")));}
            });
            r.editorSchema("future-widget","panel",()->Map.of("type","object",EditorSchemaAnnotations.ORDER,3));
        })));
        var schemas=api.editorSchemas();
        assertEquals(Set.of("command:forms:announce","future-widget:forms:panel"),schemas.stream().map(x->x.contentType()+":"+x.typeId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("1",schemas.getFirst().extensionVersion());
        var commandSchema=schemas.stream().filter(x->x.contentType().equals("command")).findFirst().orElseThrow().schema();
        assertEquals("channel",((java.util.List<Map<String,Object>>)commandSchema.get("x-persona-input-pins")).getFirst().get("name"));
        assertEquals("forms:receipt",((java.util.List<Map<String,Object>>)commandSchema.get("x-persona-output-pins")).getFirst().get("valueType"));
        assertTrue(((Map<?,?>)commandSchema.get("x-persona-value-types")).containsKey("forms:receipt"));
        assertThrows(UnsupportedOperationException.class,()->schemas.stream().filter(x->x.contentType().equals("command")).findFirst().orElseThrow().schema().put("bad",true));
    }

    @Test void validatesPersonaSchemaAnnotations() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        assertTrue(api.register(expansion("invalid","2.2",r->r.editorSchema("command","bad",()->Map.of(EditorSchemaAnnotations.WIDGET,"javascript-component")))));
        assertThrows(IllegalArgumentException.class,api::editorSchemas);
    }

    @Test void catalogsAreNamespacedBoundedAndDependencyAware() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        PersonaExpansion expansion=expansion("assets","2.2",r->r.editorCatalog("sounds",new EditorCatalogProvider(){
            public CatalogMetadata metadata(){return new CatalogMetadata("rev-7",Map.of("type","string"),"persona.editor.assets",CachePolicy.REVISION,Set.of("namespace"),MissingValuePolicy.WARN);}
            public CatalogPage query(CatalogQuery q){return new CatalogPage("rev-7",java.util.List.of(new CatalogValue("village:bell","Village bell","A bronze bell","village","BELL",false)),q.page(),false);}
        }));
        assertTrue(api.register(expansion));
        assertEquals("assets:sounds",api.editorCatalogs().getFirst().catalogId());
        var page=api.queryEditorCatalog("assets:sounds",new EditorCatalogProvider.CatalogQuery("bell",0,25,Map.of("namespace","village")));
        assertEquals("village:bell",page.values().getFirst().id());
        assertThrows(IllegalArgumentException.class,()->api.queryEditorCatalog("assets:sounds",new EditorCatalogProvider.CatalogQuery("",0,25,Map.of("world","nether"))));
        expansion.unregister();
        assertTrue(api.editorCatalogs().isEmpty());
    }

    @Test void catalogReferencesAreAuthoritativelyRevalidated() {
        PersonaApi api=new PersonaApi(mock(Main.class));
        assertTrue(api.register(expansion("assets","2.2",r->{
            r.editorCatalog("items",new EditorCatalogProvider(){
                public CatalogMetadata metadata(){return new CatalogMetadata("r1",Map.of("type","string"),"",CachePolicy.REVISION,Set.of("namespace"),MissingValuePolicy.REJECT);}
                public CatalogPage query(CatalogQuery query){return new CatalogPage("r1",query.search().equals("village:bell")?java.util.List.of(new CatalogValue("village:bell","Bell","","","",false)):java.util.List.of(),query.page(),false);}
            });
            r.editorSchema("future","asset",()->Map.of("type","object","properties",Map.of("namespace",Map.of("type","string"),"asset-id",Map.of("type","string",EditorSchemaAnnotations.CATALOG,"assets:items"))));
        })));
        EditorSchemaProvider schema=()->api.editorSchemas().stream().filter(value->value.typeId().equals("assets:asset")).findFirst().orElseThrow().schema();
        assertDoesNotThrow(()->api.validateEditorData(schema,Map.of("namespace","village","asset-id","village:bell"),"asset"));
        assertThrows(IllegalArgumentException.class,()->api.validateEditorData(schema,Map.of("namespace","village","asset-id","village:missing"),"asset"));
    }

    private static PersonaExpansion expansion(String id,String apiVersion,java.util.function.Consumer<ExpansionRegistrar> registrations){
        return new PersonaExpansion(){public String identifier(){return id;}public String author(){return "test";}public String version(){return "1";}public String requiredApiVersion(){return apiVersion;}protected void registerTypes(ExpansionRegistrar r){registrations.accept(r);}};
    }
}
