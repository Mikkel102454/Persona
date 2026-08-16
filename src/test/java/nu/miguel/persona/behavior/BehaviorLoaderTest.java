package nu.miguel.persona.behavior;

import nu.miguel.persona.content.ContentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
}
