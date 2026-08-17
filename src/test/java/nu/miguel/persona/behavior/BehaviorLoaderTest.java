package nu.miguel.persona.behavior;

import nu.miguel.persona.content.ContentException;
import nu.miguel.persona.Main;
import nu.miguel.persona.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BehaviorLoaderTest {
    @TempDir Path temp;
    @Test void loadsAllBuiltinShapesAndStableIds() throws Exception {Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("tree.yml"),"""
            id: test:tree
            scope: player
            root:
              id: root
              type: sequence
              children:
                - { id: check, type: condition, condition: memory, key: met }
                - id: retry
                  type: retry
                  times: 2
                  child: { id: remember, type: action, action: remember, key: met, value: true }
                - id: together
                  type: parallel
                  success-threshold: 2
                  failure-threshold: 1
                  children:
                    - { id: wait, type: wait, duration: 1s }
                    - { id: hide, type: action, action: set-visible, visible: false }
            """);var loaded=new BehaviorLoader(temp.toFile(),null).load();assertEquals(7,loaded.get("test:tree").nodes().size());assertFalse(loaded.get("test:tree").hash().isBlank());}
    @Test void rejectsDuplicateIdsIllegalSharedActionsAndRecursion() throws Exception {Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("a.yml"),"""
            id: test:a
            scope: shared
            root:
              id: duplicate
              type: sequence
              children:
                - { id: duplicate, type: action, action: command }
                - { id: recurse, type: subtree, behavior: test:a }
            """);ContentException e=assertThrows(ContentException.class,()->new BehaviorLoader(temp.toFile(),null).load());assertTrue(e.errors().stream().anyMatch(x->x.contains("duplicate node ID")));}
    @Test void validatesPrivateNavigationContract() throws Exception {Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("private.yml"),"""
            id: test:private
            scope: player
            root:
              id: walk
              type: action
              action: private-navigate
              destination: overlook
              arrival-distance: 1.25
              speed: 1.1
              pathfinding-range: 80
              stuck-seconds: 12
              stuck-action: retry
              stuck-retries: 2
            """);assertEquals("private-navigate",new BehaviorLoader(temp.toFile(),null).load().get("test:private").root().options().get("action"));}
    @Test void validatesEventInfiniteRepeatAndParallelPolicy() throws Exception {Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("semantics.yml"),"""
            id: test:semantics
            scope: player
            root:
              id: parallel
              type: parallel
              success-threshold: 1
              failure-threshold: 1
              cancel-remaining: on-failure
              children:
                - id: forever
                  type: repeat
                  forever: true
                  child: { id: event, type: condition, condition: event, event: interaction, consume: false }
                - { id: fallback, type: condition, condition: chance, chance: 1 }
            """);assertEquals("on-failure",new BehaviorLoader(temp.toFile(),null).load().get("test:semantics").root().options().get("cancel-remaining"));}
    @Test void rejectsAmbiguousInfiniteRepeatAndBadConsume() throws Exception {Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("bad.yml"),"""
            id: test:bad
            scope: player
            root:
              id: repeat
              type: repeat
              forever: true
              times: 2
              child: { id: event, type: condition, condition: event, event: interaction, consume: nope }
            """);ContentException error=assertThrows(ContentException.class,()->new BehaviorLoader(temp.toFile(),null).load());assertTrue(error.errors().stream().anyMatch(x->x.contains("consume must be true or false")||x.contains("both forever and times")));}

    @Test void validatesExtensionSchemaTypesRangesAndUnknownKeys()throws Exception{Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("unknown.yml"),"""
            id: test:unknown
            scope: player
            root: { id: custom, type: action, action: test:move, distance: 2, distnace: 2 }
            """);Files.writeString(dir.resolve("range.yml"),"""
            id: test:range
            scope: player
            root: { id: custom, type: action, action: test:move, distance: 0 }
            """);Files.writeString(dir.resolve("type.yml"),"""
            id: test:type
            scope: player
            root: { id: custom, type: action, action: test:move, distance: far }
            """);PersonaApi api=new PersonaApi(mock(Main.class));api.register(new PersonaExpansion(){public String identifier(){return "test";}public String author(){return "test";}public String version(){return "1";}protected void registerTypes(ExpansionRegistrar registrar){registrar.behaviorAction("move",new ExpansionTypes.BehaviorAction(){public java.util.concurrent.CompletionStage<BehaviorStatus> execute(BehaviorContext c,java.util.Map<String,Object> d){return java.util.concurrent.CompletableFuture.completedFuture(BehaviorStatus.SUCCESS);}public java.util.Map<String,Object> schema(){return java.util.Map.of("additionalProperties",false,"required",java.util.List.of("distance"),"properties",java.util.Map.of("distance",java.util.Map.of("type","number","exclusiveMinimum",0)));}});}});ContentException failure=assertThrows(ContentException.class,()->new BehaviorLoader(temp.toFile(),api).load());assertTrue(failure.getMessage().contains("distnace"));assertTrue(failure.getMessage().contains("greater than"));assertTrue(failure.getMessage().contains("must be a number"));}

    @Test void enforcesExtensionScopeMetadata()throws Exception{Path dir=Files.createDirectories(temp.resolve("behaviors"));Files.writeString(dir.resolve("shared.yml"),"""
            id: test:shared
            scope: shared
            root: { id: custom, type: condition, condition: test:player-only }
            """);PersonaApi api=new PersonaApi(mock(Main.class));api.register(new PersonaExpansion(){public String identifier(){return "test";}public String author(){return "test";}public String version(){return "1";}protected void registerTypes(ExpansionRegistrar registrar){registrar.behaviorCondition("player-only",new ExpansionTypes.BehaviorCondition(){public boolean test(BehaviorContext c,java.util.Map<String,Object> d){return true;}public BehaviorNodeMetadata metadata(){return new BehaviorNodeMetadata(java.util.Set.of(BehaviorScope.PLAYER),java.util.Set.of("player-join"),java.util.Map.of("counter",Long.class),java.util.Map.of("type","object","additionalProperties",false));}});}});ContentException failure=assertThrows(ContentException.class,()->new BehaviorLoader(temp.toFile(),api).load());assertTrue(failure.getMessage().contains("not compatible with shared scope"));}
}
