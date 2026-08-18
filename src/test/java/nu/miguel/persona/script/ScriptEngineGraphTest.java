package nu.miguel.persona.script;

import nu.miguel.persona.Main;
import nu.miguel.persona.content.Content.Say;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static nu.miguel.persona.script.ScriptDefinition.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScriptEngineGraphTest {
    private static final EffectExecutor.Context EMPTY = new EffectExecutor.Context(null, null, null,
            null, null, null, null, 0, 0);

    @Test void executesTypedLocalVariablesAndReturnsExactOutputs() {
        ScriptDefinition graph = new ScriptDefinition("variable-test", Boundary.REUSABLE, Map.of(),
                Map.of("answer", new Parameter(ValueType.INTEGER, true, null)),
                Map.of("answer", new Variable(ValueType.INTEGER, 0L)),
                Map.of("five", new Node("value", Map.of("value-type", "integer", "value", 5L)),
                        "set", new Node("set-variable", Map.of("variable", "answer"))),
                connections(
                        "enter", INPUT, "exec", "set", "exec",
                        "value", "five", "value", "set", "value",
                        "result", "set", "result", OUTPUT, "answer",
                        "leave", "set", "success", OUTPUT, "exec"));

        ScriptEngine.ScriptResult result = new ScriptEngine(mock(Main.class)).run(graph, Map.of(), EMPTY)
                .toCompletableFuture().join();

        assertEquals(ScriptEngine.Kind.CONTINUE, result.control().kind());
        assertEquals(5L, result.outputs().get("answer"));
    }

    @Test void enforcesLoopBudgetAndRecordsLimitWithoutExecutingTheBody() {
        ScriptDefinition graph = new ScriptDefinition("loop-limit", Boundary.REUSABLE, Map.of(), Map.of(), Map.of(),
                Map.of("loop", new Node("for", Map.of("first", 0L, "last", 10_000L, "step", 1L))),
                connections("enter", INPUT, "exec", "loop", "exec",
                        "leave", "loop", "completed", OUTPUT, "exec"));
        ScriptEngine engine = new ScriptEngine(mock(Main.class));

        ScriptEngine.ScriptResult result = engine.run(graph, Map.of(), EMPTY).toCompletableFuture().join();

        assertEquals(ScriptEngine.Kind.STOP, result.control().kind());
        assertTrue(engine.graphTraceHistory().stream().anyMatch(trace -> trace.status().equals("LIMIT")));
    }

    @Test void scopesDoOnceStateUntilClearAndBoundsTheTraceRing() {
        ScriptDefinition graph = new ScriptDefinition("once", Boundary.REUSABLE, Map.of(), Map.of(), Map.of(),
                Map.of("once", new Node("do-once", Map.of()),
                        "first", new Node("stop", Map.of()), "skipped", new Node("stop", Map.of())),
                connections("enter", INPUT, "exec", "once", "exec",
                        "first", "once", "completed", "first", "exec",
                        "skipped", "once", "skipped", "skipped", "exec"));
        ScriptEngine engine = new ScriptEngine(mock(Main.class));

        engine.run(graph, Map.of(), EMPTY).toCompletableFuture().join();
        assertEquals("first", engine.graphTraceHistory().getLast().node());
        engine.run(graph, Map.of(), EMPTY).toCompletableFuture().join();
        assertEquals("skipped", engine.graphTraceHistory().getLast().node());
        engine.clearState();
        engine.run(graph, Map.of(), EMPTY).toCompletableFuture().join();
        assertEquals("first", engine.graphTraceHistory().getLast().node());

        for (int index = 0; index < 1_100; index++) engine.run(graph, Map.of(), EMPTY).toCompletableFuture().join();
        assertEquals(1_000, engine.graphTraceHistory().size());
        assertTrue(engine.graphTraceHistory().getFirst().sequence() > 1);
    }

    @Test void suppressesARepeatedNpcEventUntilTheFirstExecutionCompletes() {
        ScriptDefinition graph = new ScriptDefinition("click", Boundary.EVENT,
                Map.of("npc-instance", new Parameter(ValueType.NPC_INSTANCE, true, null)), Map.of(), Map.of(),
                Map.of("line", new Node("say", Map.of("text", "Hello"))),
                connections("enter", EVENT, "exec", "line", "exec",
                        "leave", "line", "success", OUTPUT, "exec"));
        ScriptEngine engine = new ScriptEngine(mock(Main.class));
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        ScriptEngine.Host host = new ScriptEngine.Host() {
            @Override public java.util.concurrent.CompletionStage<Void> say(Say say, EffectExecutor.Context context) {
                calls.incrementAndGet(); return release;
            }
        };

        CompletableFuture<ScriptEngine.Control> first = engine.runNpcEvent(graph,
                Map.of("npc-instance", "actor-1"), EMPTY, host).toCompletableFuture();
        assertFalse(first.isDone());
        assertEquals(ScriptEngine.Kind.STOP, engine.runNpcEvent(graph,
                Map.of("npc-instance", "actor-1"), EMPTY, host).toCompletableFuture().join().kind());
        assertEquals(1, calls.get());
        release.complete(null);
        assertEquals(ScriptEngine.Kind.CONTINUE, first.join().kind());
        assertEquals(ScriptEngine.Kind.CONTINUE, engine.runNpcEvent(graph,
                Map.of("npc-instance", "actor-1"), EMPTY, host).toCompletableFuture().join().kind());
        assertEquals(2, calls.get());
    }

    @Test void wiredPlayerInputOverridesTheEventPlayerForTargetedCommands() {
        Main plugin = mock(Main.class);
        EffectExecutor effects = mock(EffectExecutor.class);
        Player eventPlayer = mock(Player.class), targetPlayer = mock(Player.class);
        when(plugin.effects()).thenReturn(effects);
        when(eventPlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(targetPlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        ScriptDefinition graph = new ScriptDefinition("targeted", Boundary.REUSABLE,
                Map.of("target", new Parameter(ValueType.PLAYER, true, null)), Map.of(), Map.of(),
                Map.of("give", new Node("give-item", Map.of("material", "minecraft:stone", "amount", 2L))),
                connections("enter", INPUT, "exec", "give", "exec",
                        "target", INPUT, "target", "give", "player",
                        "leave", "give", "success", OUTPUT, "exec"));

        ScriptEngine.ScriptResult result = new ScriptEngine(plugin)
                .run(graph, Map.of("target", targetPlayer), EffectExecutor.Context.player(eventPlayer))
                .toCompletableFuture().join();

        assertEquals(ScriptEngine.Kind.CONTINUE, result.control().kind());
        verify(effects).executeCommand(eq("persona:give-item"), anyMap(),
                argThat(context -> context.player() == targetPlayer));
    }

    @Test void targetedCommandsNeverFallBackToTheEventPlayer() {
        Main plugin = mock(Main.class);
        Player eventPlayer = mock(Player.class);
        when(eventPlayer.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ScriptDefinition graph = new ScriptDefinition("targeted", Boundary.REUSABLE,
                Map.of(), Map.of(), Map.of(),
                Map.of("give", new Node("give-item", Map.of("material", "minecraft:stone", "amount", 2L))),
                connections("enter", INPUT, "exec", "give", "exec"));

        ScriptEngine.ScriptResult result = new ScriptEngine(plugin)
                .run(graph, Map.of(), EffectExecutor.Context.player(eventPlayer)).toCompletableFuture().join();

        assertEquals(ScriptEngine.Kind.STOP, result.control().kind());
        verify(plugin, never()).effects();
    }

    private static Map<String, Connection> connections(String... values) {
        if (values.length % 5 != 0) throw new IllegalArgumentException("Connections require key/from-node/from-pin/to-node/to-pin");
        Map<String, Connection> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 5)
            result.put(values[index], new Connection(new Endpoint(values[index + 1], values[index + 2]),
                    new Endpoint(values[index + 3], values[index + 4])));
        return result;
    }
}
