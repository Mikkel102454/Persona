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
    @Test void loadsOrderedScriptsTypedConditionsHooksAndReusableScripts() throws Exception {
        dirs();Files.writeString(temp.resolve("scripts.yml"),"""
                scripts:
                  success:
                    - { type: play-sound, sound: minecraft:test }
                """);
        Files.writeString(temp.resolve("npcs/guide.yml"),"""
                id: test:guide
                dialogues:
                  - id: test:intro
                    when: { type: variable, name: rank, operator: greater-than-or-equal, value: 2 }
                """);
        Files.writeString(temp.resolve("dialogues/intro.yml"),"""
                id: test:intro
                start: hello
                nodes:
                  hello:
                    script:
                      - { type: play-sound, sound: minecraft:test }
                      - type: say
                        variants:
                          - { text: Hello, weight: 2 }
                          - { text: Greetings, weight: 1 }
                      - type: if
                        when: { type: permission, permission: persona.player.quests }
                        then: [ { type: run-script, script: success } ]
                      - { type: end-dialogue }
                """);
        Files.writeString(temp.resolve("quests/trial.yml"),"""
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
                          script: [ { type: action-bar, text: "<remaining>" } ]
                    branches:
                      - when: { type: flag, name: shortcut }
                        next-phase: end
                on-complete: [ { type: give-experience, amount: 5 } ]
                """);
        Registry registry=loader().load();assertEquals(1,registry.scripts().get("success").size());
        Node node=registry.dialogues().get("test:intro").nodes().get("hello");assertInstanceOf(Command.class,node.script().getFirst());
        Say say=(Say)node.script().get(1);assertEquals(2,say.variants().size());
        Quest q=registry.quests().get("test:trial");assertTrue(q.repeatable());assertEquals(Duration.ofHours(1),q.cooldown());assertEquals(1000,q.phases().getFirst().objectives().getFirst().onProgress().every());
    }
    @Test void rejectsLegacyDialogueWithMigrationGuidance() throws Exception {dirs();Files.writeString(temp.resolve("dialogues/old.yml"),"""
            id: test:old
            start: first
            nodes:
              first:
                lines: [ { text: old } ]
            """);ContentException e=assertThrows(ContentException.class,()->loader().load());assertTrue(e.errors().stream().anyMatch(x->x.contains("Persona 2.0")&&x.contains("lines")));}
    @Test void loadsLocalizedDialogueText() throws Exception {dirs();Files.writeString(temp.resolve("dialogues/localized.yml"),"""
            id: test:localized
            start: first
            nodes:
              first:
                script:
                  - type: say
                    text-key: dialogue.guide.hello
                    translations:
                      en: Hello
                      da-dk: Hej
                      default: Welcome
            """);Say say=(Say)loader().load().dialogues().get("test:localized").nodes().get("first").script().getFirst();assertEquals("dialogue.guide.hello",say.textKey());assertEquals("Hej",say.translations().get("da-dk"));}
    private void dirs() throws Exception {Files.createDirectories(temp.resolve("npcs"));Files.createDirectories(temp.resolve("dialogues"));Files.createDirectories(temp.resolve("quests"));}
    private ContentLoader loader(){return new ContentLoader(temp.toFile(),Duration.ofSeconds(2),x->Material.STONE,x->EntityType.ZOMBIE);}
}
