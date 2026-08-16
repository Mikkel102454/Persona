# Persona 2.0 Authoring Guide

Persona 2.0 uses one ordered script language everywhere executable behavior is allowed. A script is a YAML list; every entry is a typed object whose `type` is lowercase kebab-case. Extension commands are namespaced, for example `assetchannel:play-sound`.

## Dialogue

```yaml
id: village:vander-completed
start: intro
nodes:
  intro:
    script:
      - type: if
        when: { type: quest-state, quest: village:vander-bread, state: active }
        else:
          - { type: goto, node: already-finished }
      - type: say
        text: "Let me see whether you brought the bread."
        delay: 1.5s
      - type: deliver-items
        quest: village:vander-bread
        objective: deliver_bread
        on-success: [ { type: goto, node: completed } ]
        on-failure: [ { type: goto, node: not-completed } ]
  completed:
    script:
      - { type: say, text: "Thank you so much for helping me." }
      - { type: give-item, material: minecraft:gold_ingot, amount: 5 }
      - { type: say, text: "Here, take these gold ingots." }
      - { type: end-dialogue }
  not-completed:
    script:
      - { type: say, text: "You still need ten loaves of bread." }
      - { type: end-dialogue }
  already-finished:
    script:
      - { type: say, text: "Thank you again." }
      - { type: end-dialogue }
```

`say` accepts either `text` or weighted `variants`, and an optional `delay`. The delay is awaited before the next step. Reaching the end of a node script ends the conversation.

## Control steps

- `if`: `when`, `then`, and optional `else` scripts.
- `choice`: an `options` list. Each option has `text`, optional `when`, and `script`. The selected script returns to the step after the choice unless it changes control flow.
- `goto`: `node`, or `dialogue` plus optional `node` for a transfer.
- `end-dialogue`: ends a conversation.
- `stop`: stops the current script in any context.
- `wait`: awaits `duration`, such as `500ms`, `2s`, or `1m`.
- `random`: weighted `options`, each containing `weight` and `script`.
- `run-script`: runs a reusable entry from `scripts.yml`.

If no choice option is eligible, execution continues. Runtime nesting is capped at 32 and automatic dialogue transitions at 64. Recursive reusable scripts are rejected at load time.

## Conditions

`when` accepts one typed condition or a list (treated as `all`):

```yaml
when:
  - { type: quest-state, quest: village:bread, state: active }
  - type: any
    conditions:
      - { type: item-count, material: minecraft:bread, amount: 10 }
      - { type: permission, permission: persona.admin.quest }
```

Built-ins are `all`, `any`, `not`, `quest-state`, `item-count`, `flag`, `variable`, `permission`, `world`, and `chance`. `not` uses a nested `when`. Variable operators are `equals`, `not-equals`, `greater-than`, `greater-than-or-equal`, `less-than`, `less-than-or-equal`, and `contains`. Quest states are `not-started`, `active`, and `completed`.

## Commands and failures

Built-in commands include `start-quest`, `finish-quest`, `deliver-items`, `message`, `action-bar`, `title`, `play-sound`, `particle`, `give-item`, `take-item`, `give-experience`, `set-flag`, `set-variable`, `run-command`, `teleport`, `lightning-effect`, `potion-effect`, `broadcast`, `spawn-entity`, `set-block`, `npc-animation`, `npc-speak`, and `npc-move`.

Every command may define `on-success` and `on-failure`. Persona validates immediately before mutation. A handled outcome resumes the parent script unless its handler jumps, stops, or ends the dialogue. An unhandled failure shows its useful message, logs technical errors, and stops safely.

## NPCs

```yaml
id: village:vander
display-name: "<gold>Vander"
on-interact:
  - { type: play-sound, sound: minecraft:entity.villager.ambient }
dialogues:
  - id: village:vander-completed
    priority: 100
    when: { type: quest-state, quest: village:vander-bread, state: active }
on-no-dialogue:
  - { type: message, text: "Vander has nothing to say." }
```

NPC hooks are ordinary scripts. Dialogue registrations use typed `when` conditions.

## Quests and objectives

Quest, phase, and objective hooks (`on-start`, `on-complete`, `on-fail`, and `on-reset` where applicable) are scripts. Rewards belong in completion scripts.

```yaml
id: village:bread
title: "Bread Delivery"
when: { type: permission, permission: persona.player.quests }
phases:
  - id: delivery
    objectives:
      - id: deliver_bread
        type: deliver-item
        material: minecraft:bread
        amount: 10
        on-progress:
          every: 1
          script:
            - { type: action-bar, text: "<current>/<required>" }
        on-complete:
          - { type: play-sound, sound: minecraft:block.note_block.pling }
    on-complete:
      - { type: give-experience, amount: 10 }
    branches:
      - when: { type: flag, name: skip-finale }
        next-phase: end
on-complete:
  - { type: set-flag, flag: bread-complete, value: true }
```

Objective structural types are `collect-item`, `deliver-item`, `talk-to-npc`, `kill-entity`, `go-to-location`, `interact-block`, `wait`, and `survive`, plus namespaced extension types. Phase destinations are lowercase phase IDs or `end`.

## Reusable scripts

```yaml
scripts:
  celebration:
    - { type: play-sound, sound: minecraft:ui.toast.challenge_complete }
    - { type: wait, duration: 500ms }
    - { type: particle, particle: minecraft:happy_villager, count: 20 }
```

Invoke it with `{ type: run-script, script: celebration }`.

## Extension API 2.0

Extensions require API `2.0` and register `command`, `condition`, `placeholder`, and `objective` types. A command parses YAML during atomic content loading, validates against `PersonaContext` immediately before mutation, and returns a `CompletionStage<CommandResult>`. Results may indicate success, a useful failure, node jump, dialogue transfer, dialogue end, or stop. Persona awaits the stage before advancing.

```java
registrar.command("play-sound", new ExpansionTypes.Command() {
    public Map<String,Object> parse(Map<String,Object> yaml) { return Map.copyOf(yaml); }
    public String validate(PersonaContext context, Map<String,Object> data) { return null; }
    public CompletionStage<ExpansionTypes.CommandResult> execute(PersonaContext context, Map<String,Object> data) {
        return playback(data).thenApply(ignored -> ExpansionTypes.CommandResult.success());
    }
});
```

## Migration from 1.x

| 1.x | 2.0 |
|---|---|
| node `lines` | ordered `type: say` steps |
| choice `actions` / `effects` | option `script` |
| `on-enter` / `on-exit` | place steps in the node `script` |
| action/effect operator maps and uppercase types | lowercase typed commands |
| quest or phase `rewards` | commands in completion scripts |
| `effects.yml` and `use` | `scripts.yml` and `type: run-script` |
| legacy condition operator maps | typed `when` objects |
| extension action/effect/flow/reward registrations | one extension `command` registration |

Persona does not translate 1.x content. Obsolete keys and `effects.yml` reject the entire atomic load with a migration message. Player flags, variables, quest progress storage, placeholders, `config.yml`, plugin manifests, and the SQLite schema are unchanged.
# Behavior trees and NPC memory

Behavior definitions live in `behaviors/*.yml`. Every tree and every node requires a stable lowercase ID. A tree has `scope: shared` or `scope: player`; NPC definitions attach them with `shared-behavior` and `player-behavior`. NPC anchors are named locations under `anchors`.

Built-in composites are `sequence`, `selector`, `priority-selector`, and threshold-based `parallel`. Decorators are `invert`, `repeat`, `retry`, `timeout`, `cooldown`, and `checkpoint`; leaves are `condition`, `action`, `wait`, and `subtree`. Shared trees may only use player-independent conditions and actions. Reload is atomic, so a duplicate node ID, recursive subtree, missing anchor/reference, invalid threshold, or illegal scope leaves the active registry unchanged.

Memory actions are `remember`, `adjust-memory`, and `forget`. Player trees default to player/NPC memory; use `scope: global` for global NPC memory. Values are available in scripts as `<memory:key>` and `<npc-memory:key>`. Persona-bound actors must not use independent Citizens player-filter traits because Persona owns their visibility.

The editor compatibility contract is bundled at `schema/behaviors.schema.json`.
