# Persona 2 Authoring Guide

Persona loads YAML atomically: the complete candidate project is validated before any live registry changes. NPCs, dialogues, quests, and reusable scripts use content version 2. Behaviour trees retain their structural format and separate runtime.

## Project layout

```text
plugins/Persona/
|-- .persona/project.yml
|-- npcs/**/*.yml
|-- dialogues/**/*.yml
|-- quests/**/*.yml
|-- behaviors/**/*.yml
`-- scripts/**/*.yml
```

Each reusable script has its own file. Content roots permit eight subfolder levels; folder segments use lowercase letters, digits, dots, underscores, and hyphens. A resource ID is stored inside YAML and does not change when its file moves. `.persona/project.yml` records empty folders and is editor metadata, not runtime content.

Old list-form hooks, `on-interact`, and monolithic `scripts.yml` are deliberately rejected. Persona does not infer or rewrite them.

## Graph descriptors

Every executable NPC, dialogue, or quest field is a host event graph:

```yaml
variables:
  visits: { type: integer, default: 0 }
nodes:
  greet: { type: say, text: "Welcome back." }
  remember: { type: set-variable, variable: visits }
connections:
  enter: { from: $event.exec, to: greet.exec }
  continue: { from: greet.success, to: remember.exec }
```

`$event` exposes execution plus typed values supplied by the host. A reusable script instead declares `inputs`, `outputs`, and the `$input`/`$output` boundaries:

```yaml
content-version: 2
id: village:celebration
inputs:
  amount: { type: integer, default: 20 }
  player: { type: player, required: true }
outputs:
  shown: { type: boolean, default: false }
variables: {}
nodes:
  sound: { type: play-sound, sound: minecraft:ui.toast.challenge_complete }
  particles: { type: particle, particle: minecraft:happy_villager }
  yes: { type: value, value-type: boolean, value: true }
connections:
  enter: { from: $input.exec, to: sound.exec }
  sound-player: { from: $input.player, to: sound.player }
  after-sound: { from: sound.success, to: particles.exec }
  particles-player: { from: $input.player, to: particles.player }
  particle-count: { from: $input.amount, to: particles.amount }
  leave: { from: particles.success, to: $output.exec }
  result: { from: yes.value, to: $output.shown }
```

Node and connection keys are stable lowercase identifiers. Data connections require exact nominal types. Safe conversion is explicit through `integer-to-number`, `string-to-text`, or `to-string`; the editor inserts visible converter nodes rather than hiding a cast.

Supported scalar types include `boolean`, `integer`, `number`, `string`, `text`, `duration`, `player`, `npc`, `npc-instance`, `behavior`, `dialogue`, `dialogue-registration`, `quest`, `quest-objective`, `script`, `condition`, `anchor`, `world`, `location`, `material`, `entity-type`, `sound`, and `particle`. Prefix any value type with `list:` for a typed list.

## Flow, state, and variables

Execution graphs support Sequence, Branch, Switch, Random, Gate, Do Once, Do N, For, For Each, and While nodes. Execution cycles are rejected; bounded loop nodes provide iteration without graph cycles. A loop node may perform at most 10,000 iterations and one execution may traverse at most 100,000 nodes.

Execution-local variables are declared under `variables` and accessed with `get-variable` and `set-variable`. Persistent nodes cover player flags, player strings, and typed global or player NPC memory. Gate, Do Once, and Do N state is scoped to the NPC instance and optional player, and is cleared on reload or despawn.

Pure value/getter/converter nodes have data pins only. Impure nodes expose execution pins. Required inputs must be connected or have a valid authored default.

## NPCs and events

```yaml
content-version: 2
id: village:vander
display-name: "<gold>Vander"
dialogues:
  - id: village:vander-intro
    priority: 100
    when: { type: quest-state, quest: village:bread, state: not-started }
anchors:
  counter: { world: world, x: 12, y: 64, z: -4, yaw: 90, pitch: 0 }
on-click:
  variables: {}
  nodes:
    sound: { type: play-sound, sound: minecraft:entity.villager.ambient }
  connections:
    enter: { from: $event.exec, to: sound.exec }
    sound-player: { from: $event.player, to: sound.player }
on-no-dialogue:
  variables: {}
  nodes:
    empty: { type: message, text: "Vander has nothing to say." }
  connections:
    enter: { from: $event.exec, to: empty.exec }
    empty-player: { from: $event.player, to: empty.player }
```

Permanent built-in events are `on-click`, `on-damage`, `on-spawn`, `on-despawn`, and `on-no-dialogue`. On Click exposes player, NPC definition, NPC instance, and left/right button values. On Damage also exposes damage. Spawn and Despawn do not expose a player; player-required nodes are invalid there.

A right click runs On Click and selects dialogue only if execution continues. If no registration matches, On No Dialogue runs. A left click runs On Click without dialogue selection. A second trigger of the same event for the same NPC/player is ignored while the first execution remains active.

NPC-local signals declare typed parameters:

```yaml
signals:
  festival-started:
    parameters:
      host: { type: player }
    graph:
      variables: {}
      nodes:
        announce: { type: broadcast, text: "The festival has begun." }
      connections:
        enter: { from: $event.exec, to: announce.exec }
```

## Dialogues

```yaml
content-version: 2
id: village:vander-intro
start: greeting
nodes:
  greeting:
    graph:
      variables: {}
      nodes:
        hello: { type: say, text: "Welcome, traveler.", delay: 1.5s }
        answer:
          type: choice
          options:
            - { text: "I can help." }
            - { text: "Goodbye." }
        start: { type: start-quest, quest: village:bread }
        next: { type: goto, node: accepted }
        leave: { type: end-dialogue }
      connections:
        enter: { from: $event.exec, to: hello.exec }
        choose: { from: hello.success, to: answer.exec }
        accept: { from: answer.option-0, to: start.exec }
        start-player: { from: $event.player, to: start.player }
        advance: { from: start.success, to: next.exec }
        decline: { from: answer.option-1, to: leave.exec }
  accepted:
    graph:
      variables: {}
      nodes:
        thanks: { type: say, text: "Thank you." }
        end: { type: end-dialogue }
      connections:
        enter: { from: $event.exec, to: thanks.exec }
        finish: { from: thanks.success, to: end.exec }
```

Dialogue graphs receive `$event.player`, `$event.npc`, `$event.npc-instance`, `$event.dialogue`, and `$event.dialogue-node`. `goto` transfers within the current dialogue or to another dialogue; `end-dialogue` ends the conversation.

## Quests

```yaml
content-version: 2
id: village:bread
title: "Bread Delivery"
when: { type: permission, permission: persona.player.quests }
phases:
  - id: delivery
    objectives:
      - id: deliver-bread
        type: deliver-item
        material: minecraft:bread
        amount: 10
        on-progress:
          every: 1
          graph:
            variables: {}
            nodes:
              status: { type: action-bar, text: "<current>/<required>" }
            connections:
              enter: { from: $event.exec, to: status.exec }
              status-player: { from: $event.player, to: status.player }
    on-complete:
      variables: {}
      nodes:
        experience: { type: give-experience, amount: 10 }
      connections:
        enter: { from: $event.exec, to: experience.exec }
        experience-player: { from: $event.player, to: experience.player }
on-complete:
  variables: {}
  nodes:
    remember: { type: set-flag, flag: bread-complete, value: true }
  connections:
    enter: { from: $event.exec, to: remember.exec }
    remember-player: { from: $event.player, to: remember.player }
```

Quest and phase lifecycle fields are event graphs. Objective types are `collect-item`, `deliver-item`, `talk-to-npc`, `kill-entity`, `go-to-location`, `interact-block`, `wait`, and `survive`, plus signed namespaced extension types. Objective `on-progress` contains `every` and `graph`.

Typed `when` conditions include `all`, `any`, `not`, `quest-state`, `item-count`, `flag`, `variable`, `permission`, `world`, `chance`, and registered extension conditions.

## Commands and calls

Built-in actions include quest start/finish/delivery, messages, action bars, titles, sound, particles, item and experience changes, flags and strings, commands, teleport, effects, entity/block changes, and NPC speech, animation, and movement. Namespaced extension actions are accepted only when their signed schema and runtime handler are available.

Player-targeted nodes (quest actions, player state, messages, titles, sounds, particles, inventory/experience, commands, teleport, potion effects, and NPC speech) expose a required `player` data input. It must be wired from a typed boundary or node output; Persona never infers a target from ambient event context. Runtime-only values such as `player` cannot be entered as inline YAML literals.

A `run-script` node gets data pins from the target reusable script signature. Reusable-call cycles and missing required inputs/outputs are rejected during atomic loading. Behaviour `action: script` nodes may call only graphs whose inputs are optional; they discard outputs.

## Behaviour trees

Behaviour definitions live under `behaviors/` and use the separate status-based tree runtime. Every tree node has a stable ID. Composites are `sequence`, `selector`, `priority-selector`, and `parallel`; decorators include `invert`, `repeat`, `retry`, `timeout`, `cooldown`, and `checkpoint`; leaves include `condition`, `action`, `wait`, and `subtree`.

Trees have `scope: shared` or `scope: player`. Shared trees cannot read player-only state. NPC definitions attach them with `shared-behavior` and `player-behavior`. Runtime state and checkpoints are persisted compatibly across reloads when IDs and node shapes still match.

## Validation, extensions, and operations

Run `/persona validate [--json]` or `/persona reload --dry-run [--json]` to validate without activation. `/persona reload` activates only a completely valid candidate. Diagnostics include source paths and actionable graph endpoint/type failures.

The additive Java extension API can register namespaced commands, conditions, placeholders, objectives, behaviour conditions, and behaviour actions. Editor schemas must be signed by the active extension catalog; unsigned extension graph data remains visible as custom YAML but cannot be mutated as a trusted typed node.

Use `/persona diagnostics`, `/persona debug`, `/persona support`, and `/persona backup` for operational inspection and recovery. See [SCRIPT_FORMAT_2_MIGRATION.md](SCRIPT_FORMAT_2_MIGRATION.md) for the intentionally manual format migration and the packaged `examples/` tree for complete content.

Versioned JSON Schemas are packaged under `schema/` and published in the schema artifact alongside the plugin/API artifacts.
