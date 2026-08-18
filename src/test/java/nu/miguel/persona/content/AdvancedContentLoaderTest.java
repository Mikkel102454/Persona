package nu.miguel.persona.content;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static nu.miguel.persona.content.Content.*;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedContentLoaderTest {
    @TempDir Path temp;

    @Test void loadsV2GraphsTypedConditionsHooksAndIndividualReusableScripts() throws Exception {
        dirs();Files.writeString(temp.resolve("scripts/success.yml"),"""
                content-version: 2
                id: success
                inputs:
                  player: { type: player, required: true }
                outputs: {}
                variables: {}
                nodes:
                  sound: { type: play-sound, sound: minecraft:test }
                connections:
                  enter: { from: $input.exec, to: sound.exec }
                  sound-player: { from: $input.player, to: sound.player }
                  leave: { from: sound.success, to: $output.exec }
                """);
        Files.writeString(temp.resolve("npcs/guide.yml"),"""
                content-version: 2
                id: test:guide
                dialogues:
                  - id: test:intro
                    when: { type: variable, name: rank, operator: greater-than-or-equal, value: 2 }
                """);
        Files.writeString(temp.resolve("dialogues/intro.yml"),"""
                content-version: 2
                id: test:intro
                start: hello
                nodes:
                  hello:
                    graph:
                      variables: {}
                      nodes:
                        sound: { type: play-sound, sound: minecraft:test }
                        line:
                          type: say
                          variants:
                            - { text: Hello, weight: 2 }
                            - { text: Greetings, weight: 1 }
                        reusable: { type: run-script, script: success, inputs: {} }
                        end: { type: end-dialogue }
                      connections:
                        enter: { from: $event.exec, to: sound.exec }
                        sound-player: { from: $event.player, to: sound.player }
                        line: { from: sound.success, to: line.exec }
                        reusable: { from: line.success, to: reusable.exec }
                        reusable-player: { from: $event.player, to: reusable.player }
                        finish: { from: reusable.success, to: end.exec }
                """);
        Files.writeString(temp.resolve("quests/trial.yml"),"""
                content-version: 2
                id: test:trial
                repeatable: true
                cooldown: 1h
                maximum-completions: 3
                when:
                  - { type: flag, name: allowed }
                  - { type: world, world: world }
                phases:
                  - id: first
                    objectives:
                      - id: wait
                        type: wait
                        duration: 5s
                        on-progress:
                          every: 1s
                          graph:
                            variables: {}
                            nodes:
                              status: { type: action-bar, text: "<remaining>" }
                            connections:
                              enter: { from: $event.exec, to: status.exec }
                              status-player: { from: $event.player, to: status.player }
                    branches:
                      - when: { type: flag, name: shortcut }
                        next-phase: end
                on-complete:
                  variables: {}
                  nodes:
                    reward: { type: give-experience, amount: 5 }
                  connections:
                    enter: { from: $event.exec, to: reward.exec }
                    reward-player: { from: $event.player, to: reward.player }
                """);
        Registry registry=loader().load();assertEquals(1,registry.scripts().get("success").nodes().size());
        Node node=registry.dialogues().get("test:intro").nodes().get("hello");assertEquals("play-sound",node.graph().nodes().get("sound").type());
        assertEquals(2,((java.util.List<?>)node.graph().nodes().get("line").options().get("variants")).size());
        Quest q=registry.quests().get("test:trial");assertTrue(q.repeatable());assertEquals(Duration.ofHours(1),q.cooldown());assertEquals(1000,q.phases().getFirst().objectives().getFirst().onProgress().every());
    }

    @Test void rejectsLegacyDialogueWithMigrationGuidance() throws Exception {dirs();Files.writeString(temp.resolve("dialogues/old.yml"),"""
            content-version: 2
            id: test:old
            start: first
            nodes:
              first:
                script: [ { type: say, text: old } ]
            """);ContentException e=assertThrows(ContentException.class,()->loader().load());assertTrue(e.errors().stream().anyMatch(x->x.contains("script")||x.contains("graph")));}

    @Test void loadsLocalizedDialogueText() throws Exception {dirs();Files.writeString(temp.resolve("dialogues/localized.yml"),"""
            content-version: 2
            id: test:localized
            start: first
            nodes:
              first:
                graph:
                  variables: {}
                  nodes:
                    line:
                      type: say
                      text-key: dialogue.guide.hello
                      translations: { en: Hello, da-dk: Hej, default: Welcome }
                    end: { type: end-dialogue }
                  connections:
                    enter: { from: $event.exec, to: line.exec }
                    finish: { from: line.success, to: end.exec }
            """);var say=loader().load().dialogues().get("test:localized").nodes().get("first").graph().nodes().get("line");assertEquals("dialogue.guide.hello",say.options().get("text-key"));assertEquals("Hej",((java.util.Map<?,?>)say.options().get("translations")).get("da-dk"));}

    private void dirs() throws Exception {Files.createDirectories(temp.resolve("npcs"));Files.createDirectories(temp.resolve("dialogues"));Files.createDirectories(temp.resolve("quests"));Files.createDirectories(temp.resolve("scripts"));}
    private ContentLoader loader(){return new ContentLoader(temp.toFile(),Duration.ofSeconds(2),x->Material.STONE,x->EntityType.ZOMBIE);}
}
