package nu.miguel.persona.content;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ScriptGraphContentLoaderTest {
    @TempDir Path root;

    @Test void loadsTypedSignatureStableNodesConnectionsAndConverters() throws Exception {
        write("""
                content-version: 2
                scripts:
                  calculate:
                    inputs:
                      amount: { type: integer, default: 1 }
                    outputs:
                      total: { type: number, required: true }
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
        assertEquals(2,script.nodes().size());assertEquals(4,script.connections().size());
    }

    @Test void rejectsFormatOneAndListFormWithTargetedMigrationError() throws Exception {
        write("scripts:\n  old: [ { type: wait, duration: 1s } ]\n");
        ContentException error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("content-version: 2"));
        write("content-version: 2\nscripts:\n  old: [ { type: wait, duration: 1s } ]\n");
        error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("obsolete list form"));
    }

    @Test void rejectsMismatchedTypesCyclesDuplicateInputsAndUnreachableOutput() throws Exception {
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

    @Test void runScriptCallsRequireInputsAndExactLiteralTypes() throws Exception {
        write("""
                content-version: 2
                scripts:
                  target:
                    inputs: { amount: { type: integer, required: true } }
                    outputs: {}
                    nodes: { pause: { type: wait, duration: 1ms } }
                    connections:
                      enter: { from: $input.exec, to: pause.exec }
                      leave: { from: pause.success, to: $output.exec }
                """);
        Files.writeString(root.resolve("npcs/caller.yml"),"""
                id: test:caller
                on-interact:
                  - { type: run-script, script: target, inputs: { amount: wrong } }
                """);
        ContentException error=assertThrows(ContentException.class,()->loader().load());
        assertTrue(error.getMessage().contains("must be integer"));
    }

    @Test void behaviorScriptActionsRejectTargetsWithRequiredInputs() throws Exception {
        write("""
                content-version: 2
                scripts:
                  target:
                    inputs: { amount: { type: integer, required: true } }
                    outputs: {}
                    nodes: { pause: { type: wait, duration: 1ms } }
                    connections:
                      enter: { from: $input.exec, to: pause.exec }
                      leave: { from: pause.success, to: $output.exec }
                """);
        Files.createDirectories(root.resolve("behaviors"));
        Files.writeString(root.resolve("behaviors/caller.yml"), """
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
        write("""
                content-version: 2
                scripts:
                  target:
                    inputs: { amount: { type: integer, required: true } }
                    outputs: {}
                    nodes: { pause: { type: wait, duration: 1ms } }
                    connections:
                      enter: { from: $input.exec, to: pause.exec }
                      leave: { from: pause.success, to: $output.exec }
                  caller:
                    inputs: {}
                    outputs: {}
                    nodes:
                      call: { type: run-script, script: target }
                    connections:
                      enter: { from: $input.exec, to: call.exec }
                      leave: { from: call.success, to: $output.exec }
                """);
        ContentException missing=assertThrows(ContentException.class,()->loader().load());
        assertTrue(missing.getMessage().contains("inputs mapping"),missing.getMessage());
        write(Files.readString(root.resolve("scripts.yml")).replace(
                "call: { type: run-script, script: target }",
                "call: { type: run-script, script: target, inputs: { amount: wrong } }"));
        ContentException wrong=assertThrows(ContentException.class,()->loader().load());
        assertTrue(wrong.getMessage().contains("must be integer"),wrong.getMessage());
    }

    private String graph(String nodes,String connections){return """
            content-version: 2
            scripts:
              invalid:
                inputs: {}
                outputs: { result: { type: number, required: true } }
                nodes:
                  pause: { type: wait, duration: 1ms }
            """+indent(nodes,6)+"    connections:\n"+indent(connections,6);}
    private static String indent(String value,int spaces){return value.lines().map(line->" ".repeat(spaces)+line+"\n").reduce("",String::concat);}
    private void assertInvalid(String yaml,String message)throws Exception{write(yaml);ContentException error=assertThrows(ContentException.class,()->loader().load());assertTrue(error.getMessage().contains(message),error.getMessage());}
    private void write(String yaml)throws Exception{Files.createDirectories(root.resolve("npcs"));Files.createDirectories(root.resolve("dialogues"));Files.createDirectories(root.resolve("quests"));Files.writeString(root.resolve("scripts.yml"),yaml);}
    private ContentLoader loader(){return new ContentLoader(root.toFile(),Duration.ZERO,x->Material.STONE,x->EntityType.ZOMBIE);}
}
