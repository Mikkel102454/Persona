package nu.miguel.persona.content;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.Set;

import static nu.miguel.persona.script.ScriptDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptGraphContentLoaderTest {
    @TempDir Path root;

    @Test void loadsTypedSignatureVariablesStableNodesConnectionsListsAndConverters() throws Exception {
        write("calculate", """
                content-version: 2
                id: calculate
                inputs:
                  amount: { type: integer, default: 1 }
                  labels: { type: list:string, default: [one, two] }
                outputs:
                  total: { type: number, required: true }
                variables:
                  running-total: { type: number, default: 0.0 }
                nodes:
                  convert: { type: integer-to-number }
                  pause: { type: wait, duration: 1ms }
                connections:
                  enter: { from: $input.exec, to: pause.exec }
                  leave: { from: pause.success, to: $output.exec }
                  source: { from: $input.amount, to: convert.value }
                  result: { from: convert.result, to: $output.total }
                """);
        var script=loader().load().scripts().get("calculate");
        assertEquals("integer",script.inputs().get("amount").type().id());
        assertEquals("list:string",script.inputs().get("labels").type().id());
        assertEquals("number",script.variables().get("running-total").type().id());
        assertEquals(2,script.nodes().size());assertEquals(4,script.connections().size());
    }

    @Test void rejectsMonolithicScriptsAndLegacyListHooksWithTargetedMigrationErrors() throws Exception {
        dirs();Files.writeString(root.resolve("scripts.yml"),"content-version: 2\nscripts: {}\n");
        ContentException error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("monolithic scripts.yml"),error.getMessage());
        Files.delete(root.resolve("scripts.yml"));Files.writeString(root.resolve("npcs/old.yml"),"""
                content-version: 2
                id: test:old
                on-click: [ { type: wait, duration: 1s } ]
                """);
        error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("obsolete list hook"),error.getMessage());
    }

    @Test void rejectsMismatchedTypesCyclesAndDuplicateInputs() throws Exception {
        assertInvalid(graph("""
                bad: { type: value, value-type: string, value: nope }
                ""","""
                enter: { from: $input.exec, to: pause.exec }
                leave: { from: pause.success, to: $output.exec }
                wrong: { from: bad.value, to: $output.result }
                """),"requires exact type");
        assertInvalid(graph("""
                result: { type: value, value-type: number, value: 1.0 }
                a: { type: wait, duration: 1ms }
                b: { type: wait, duration: 1ms }
                ""","""
                enter: { from: $input.exec, to: pause.exec }
                leave: { from: pause.success, to: $output.exec }
                cycle-a: { from: a.success, to: b.exec }
                cycle-b: { from: b.success, to: a.exec }
                out: { from: result.value, to: $output.result }
                """),"execution cycle");
        assertInvalid(graph("""
                one: { type: value, value-type: number, value: 1.0 }
                two: { type: value, value-type: number, value: 2.0 }
                ""","""
                enter: { from: $input.exec, to: pause.exec }
                leave: { from: pause.success, to: $output.exec }
                a: { from: one.value, to: $output.result }
                b: { from: two.value, to: $output.result }
                """),"more than one connection");
    }

    @Test void loadsEveryPermanentNpcEventAndTypedLocalSignalBoundary() throws Exception {
        dirs();
        Files.writeString(root.resolve("npcs/events.yml"), "content-version: 2\nid: test:events\n"
                + "on-click:\n" + indent(eventGraph(), 2)
                + "on-damage:\n" + indent(eventGraph(), 2)
                + "on-spawn:\n" + indent(eventGraph(), 2)
                + "on-despawn:\n" + indent(eventGraph(), 2)
                + "on-no-dialogue:\n" + indent(eventGraph(), 2)
                + "signals:\n  alert:\n    parameters:\n      mood: { type: string, required: true }\n"
                + "    graph:\n" + indent(eventGraph(), 6));
        var npc = loader().load().npcs().get("test:events");
        assertEquals(Set.of("npc", "npc-instance", "player", "left-button", "right-button"),
                npc.onClick().inputs().keySet());
        assertEquals(Set.of("npc", "npc-instance", "player", "damage"), npc.onDamage().inputs().keySet());
        assertEquals(Set.of("npc", "npc-instance"), npc.onSpawn().inputs().keySet());
        assertEquals(Set.of("npc", "npc-instance", "reason"), npc.onDespawn().inputs().keySet());
        assertEquals(Set.of("npc", "npc-instance", "player"), npc.onNoDialogue().inputs().keySet());
        assertEquals(Set.of("npc", "npc-instance", "mood"), npc.signals().get("alert").graph().inputs().keySet());
    }

    @Test void eventRunScriptCallsRequireExactLiteralInputs() throws Exception {
        target();Files.writeString(root.resolve("npcs/caller.yml"),"""
                content-version: 2
                id: test:caller
                on-click:
                  variables: {}
                  nodes:
                    call: { type: run-script, script: target, inputs: { amount: wrong } }
                  connections:
                    enter: { from: $event.exec, to: call.exec }
                """);
        ContentException error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("must be integer"),error.getMessage());
    }

    @Test void behaviorScriptActionsRejectTargetsWithRequiredInputs() throws Exception {
        target();Files.writeString(root.resolve("behaviors/caller.yml"), """
                content-version: 2
                id: test:caller
                scope: player
                root:
                  id: call
                  type: action
                  action: script
                  script: target
                """);
        ContentException error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("required input amount"),error.getMessage());
    }

    @Test void explicitCallNodesRequireAndValidateTheirInputsMapping() throws Exception {
        target();write("caller",caller("call: { type: run-script, script: target }"));
        ContentException missing=assertThrows(ContentException.class,()->loader().load());
        assertTrue(missing.getMessage().contains("inputs mapping"),missing.getMessage());
        Files.writeString(root.resolve("scripts/caller.yml"),caller("call: { type: run-script, script: target, inputs: { amount: wrong } }"));
        ContentException wrong=assertThrows(ContentException.class,()->loader().load());
        assertTrue(wrong.getMessage().contains("must be integer"),wrong.getMessage());
    }

    @Test void playerTargetedCommandsRequireATypedPlayerConnection() throws Exception {
        write("targeted", """
                content-version: 2
                id: targeted
                inputs:
                  target: { type: player, required: true }
                outputs: {}
                variables: {}
                nodes:
                  give: { type: give-item, material: minecraft:stone, amount: 2 }
                connections:
                  enter: { from: $input.exec, to: give.exec }
                  target: { from: $input.target, to: give.player }
                  leave: { from: give.success, to: $output.exec }
                """);

        var script = loader().load().scripts().get("targeted");
        assertEquals(ValueType.PLAYER, script.inputs().get("target").type());
        assertEquals(new Endpoint("give", "player"), script.connections().get("target").to());
    }

    @Test void playerTargetedCommandsRejectAnUnwiredPlayerInput() throws Exception {
        write("untargeted", """
                content-version: 2
                id: untargeted
                inputs: {}
                outputs: {}
                variables: {}
                nodes:
                  give: { type: give-item, material: minecraft:stone, amount: 2 }
                connections:
                  enter: { from: $input.exec, to: give.exec }
                  leave: { from: give.success, to: $output.exec }
                """);

        ContentException error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("required input give.player is unwired"),error.getMessage());
    }

    private void target() throws Exception {write("target","""
            content-version: 2
            id: target
            inputs: { amount: { type: integer, required: true } }
            outputs: {}
            variables: {}
            nodes: { pause: { type: wait, duration: 1ms } }
            connections:
              enter: { from: $input.exec, to: pause.exec }
              leave: { from: pause.success, to: $output.exec }
            """);}
    private String caller(String node){return """
            content-version: 2
            id: caller
            inputs: {}
            outputs: {}
            variables: {}
            nodes:
              %s
            connections:
              enter: { from: $input.exec, to: call.exec }
              leave: { from: call.success, to: $output.exec }
            """.formatted(node);}
    private String graph(String nodes,String connections){return """
            content-version: 2
            id: invalid
            inputs: {}
            outputs: { result: { type: number, required: true } }
            variables: {}
            nodes:
              pause: { type: wait, duration: 1ms }
            """+indent(nodes,2)+"connections:\n"+indent(connections,2);}
    private static String eventGraph(){return "variables: {}\nnodes: { stop: { type: stop } }\n"
            + "connections: { enter: { from: $event.exec, to: stop.exec } }\n";}
    private static String indent(String value,int spaces){return value.lines().map(line->" ".repeat(spaces)+line+"\n").reduce("",String::concat);}
    private void assertInvalid(String yaml,String message)throws Exception{write("invalid",yaml);ContentException error=assertThrows(ContentException.class,()->loader().load());assertTrue(error.getMessage().contains(message),error.getMessage());}
    private void write(String id,String yaml)throws Exception{dirs();Files.writeString(root.resolve("scripts/"+id+".yml"),yaml);}
    private void dirs()throws Exception{Files.createDirectories(root.resolve("npcs"));Files.createDirectories(root.resolve("dialogues"));Files.createDirectories(root.resolve("quests"));Files.createDirectories(root.resolve("behaviors"));Files.createDirectories(root.resolve("scripts"));}
    private ContentLoader loader(){return new ContentLoader(root.toFile(),Duration.ZERO,x->Material.STONE,x->EntityType.ZOMBIE);}
}
