# Persona 2.0 Authoring Guide

Persona 2.0 uses one ordered script language everywhere executable behavior is allowed. A script is a YAML list; every entry is a typed object whose `type` is lowercase kebab-case. Extension commands are namespaced, for example `assetchannel:play-sound`.

## Content format compatibility

Persona's YAML content format is version `1`. This version is independent of the
plugin version (`2.0.0`), the Java extension API (`2.1`), the editor protocol, and the
SQLite schema. A file may declare `content-version: 1` at its root. Existing files
without that field are interpreted as format 1; a future or malformed version is
rejected before activation. Persona will document migrations before incrementing the
format version.

Reload, command-line validation, and editor validation use the same `ContentValidator`
and return diagnostics in `path:line:column: message` form. Unknown keys are rejected
and include a likely replacement when one is close enough. Validation aggregates
independent errors across behaviors, NPCs, dialogues, quests, and reusable scripts.

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

## Extension API 2.1

Persona API 2.x evolves additively. The current level is `2.1`; previously compiled
2.0 extensions remain accepted. An extension may request any published 2.x level up
to the runtime's current level; a future minor is rejected rather than silently
running without APIs it may require.
Extensions register commands, conditions, placeholders, objectives, behavior
conditions, and behavior actions. A command parses YAML during atomic content loading,
validates against `PersonaContext` immediately before mutation, and returns a
`CompletionStage<CommandResult>`. Results may indicate success, a useful failure, node
jump, dialogue transfer, dialogue end, or stop. Persona awaits the stage before
advancing.

```java
registrar.command("play-sound", new ExpansionTypes.Command() {
    public Map<String,Object> parse(Map<String,Object> yaml) { return Map.copyOf(yaml); }
    public String validate(PersonaContext context, Map<String,Object> data) { return null; }
    public CompletionStage<ExpansionTypes.CommandResult> execute(PersonaContext context, Map<String,Object> data) {
        return playback(data).thenApply(ignored -> ExpansionTypes.CommandResult.success());
    }
});
```

Behavior handlers can return `BehaviorNodeMetadata` from `metadata()` to declare
shared/player scope support, event names that can wake the node, durable runtime field
names and Java types, and a JSON Schema fragment. Persona applies the scope and schema
during normal atomic loading. The same fragments are exposed by
`PersonaApi.behaviorSchemas()` for CLI and hosted editor consumers. Durable values use
`BehaviorContext.durable`; they are persisted transactionally with the runtime.

Parsing, validation, condition evaluation, action start, and cancellation run on the
Minecraft server thread. An action's `CompletionStage` may finish on any thread; the
result is observed and advances the tree only on a later server-thread evaluation.
`ExpansionServices.completeSync` is the supported way to perform a final Bukkit
operation and complete safely on the server thread.

API 2.1 passes a distinct `CancellationToken` to behavior actions. Its callbacks run
exactly once. Persona also invokes the legacy `cancel(BehaviorContext)` callback at
most once, on the server thread, so 2.0 actions retain their behavior. A reload,
restart, branch interruption, timeout, or shutdown cancels the execution. See
`ExampleBehaviorExpansion` in the published sources for a scoped condition and a
cancellable asynchronous action.

## Administration and support

`/persona npc trace` shows behavior/tree identity, full running and checkpoint paths,
deadlines, inbox event IDs, progress, redacted blackboard values, presentation state,
and recent node outcomes. Failed selectors record all failed child IDs. Runtime
controls are `/persona behavior pause|resume|restart|signal`; controls apply to the
selected NPC's shared runtime and, for a player sender, that player's runtime.

`/persona validate [--json]` and `/persona reload --dry-run [--json]` load the complete
candidate with the production validator but never activate it. `/persona debug`
accepts `npc=`, `player=`, `behavior=`, and `node=` filters; `/persona debug off`
disables the filter. Slow handler timing uses `behavior.diagnostics.slow-milliseconds`.
`/persona diagnostics` reports orphaned persisted rows and aggregate extension usage.
`/persona support` creates a ZIP containing versions, redacted configuration,
validation output, extension schemas, and runtime diagnostics without blackboard or
memory values.

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

## Event delivery and execution semantics

Every delivered event has a stable UUID, occurrence time, and an explicit policy:
normal gameplay and named-signal events are `CONSUMABLE`; observation events created
through the Java runtime API are `OBSERVE_ONLY`. Event conditions consume the oldest
matching consumable event after a successful match by default. Set `consume: false`
to observe it until its inbox TTL expires. This means one interaction cannot repeatedly
trigger a successful branch on later ticks. Inbox order is FIFO even while an
asynchronous action is running: arrivals queue in order, the action completes, and
subsequent event conditions see the oldest relevant arrival. A full inbox drops its
oldest entry; `/persona npc trace` exposes the cumulative dropped count.

```yaml
- id: clicked
  type: condition
  condition: event
  event: interaction
  consume: true
```

Quest-state, objective-progress, flag, variable, and NPC-memory mutations wake all
relevant player runtimes. Proximity is measured from that player's active private
presentation when one exists. Built-in events also include `navigation-success`,
`navigation-failure`, `navigation-cancelled`, `spawn`, `despawn`, `world-change`,
`projection-spawn`, `projection-despawn`, and `projection-lifecycle`. Administrators
can send `/persona behavior signal <name>` to the selected Citizens NPC; content
matches it as `signal:<name>`.

`repeat` and `retry` execute at most one completed iteration per behavior tick, so an
immediate child always yields before its next iteration. Use `forever: true` instead
of `times` for an infinite form; specifying both is invalid. The per-tick yield and
the global node/time budgets are its loop safeguards.

Parallel children are evaluated in YAML order. If success and failure thresholds are
both reached in one evaluation, success deterministically wins. `cancel-remaining`
is `always` by default and may be `on-success`, `on-failure`, or `never`. Budget
exhaustion returns `RUNNING` without allowing a reactive priority selector to replace
its previously running branch. Runtime paths use `behavior-id/node-id` at every level,
including subtree nodes, and traces expose the full running path plus redacted
condition inputs and safe outputs. `behavior.tick-cadence` is the number of server
ticks between scheduler evaluations.

Memory actions are `remember`, `adjust-memory`, and `forget`. Player trees default to player/NPC memory; use `scope: global` for global NPC memory. Values are available in scripts as `<memory:key>` and `<npc-memory:key>`. Persona-bound actors must not use independent Citizens player-filter traits because Persona owns their visibility.

Memory keys may use a namespace prefix such as `fishing:rank`. Each registered
expansion automatically owns its identifier's namespace, and additional ownership can
be declared under `memory.namespaces` in `config.yml`. Extension writes should use a
source beginning with `extension:<identifier>/`; Persona rejects writes by one
extension into another extension's claimed namespace. Existing unnamespaced keys stay
compatible.

The public `NpcMemoryService` supplies atomic compare-and-set, bounded numeric adjust,
explicit expiry, and fully qualified entry enumeration. Every set, adjustment,
conditional update, deletion, and expiry fires `NpcMemoryChangeEvent` on the server
thread with typed old/new values, global/player scope, player UUID, NPC identity, key,
and source. `memory.expiry.retention` controls how long physically expired rows remain
in SQLite after they become invisible to reads; `memory.expiry.sweep-interval`
controls cleanup cadence, and `/persona memory metrics` reports sweep totals.

## Private presentation and walking

Player-scoped trees can switch from the shared actor to a private projection without a visible teleport:

```yaml
- { id: become-private, type: action, action: begin-private-presentation }
- id: walk-to-overlook
  type: action
  action: private-navigate
  destination: overlook
  arrival-distance: 1.5
  speed: 1.0
  pathfinding-range: 64
  stuck-seconds: 10
  stuck-action: fail
```

`begin-private-presentation` creates the projection at the shared actor's exact current position. `private-navigate` combines that transition with walking and durably selects the destination anchor. The projection's current logical world, coordinates, yaw, and pitch are persisted independently from the selected anchor, so reconnecting or restarting resumes from the last stored walking position.

Navigation options inherit defaults from `behavior.navigation` in `config.yml`. `stuck-action` is `fail`, `retry`, or `teleport`; `stuck-retries` limits retries. Completion emits `navigation-success`, cancellation emits `navigation-cancelled`, and failures emit `navigation-failure` with a distinct `reason` value.

When the viewer leaves activation range or changes world, private walking is suspended at its current logical position and its Citizens navigator is cancelled. It resumes when the viewer returns to that position's world and activation range. A missing or unloaded destination world fails the action with `destination-world-unavailable`; merely visiting another world does not fail or move the actor.

Active projections follow Citizens-side presentation edits without being recreated
unless the entity type changes. Persona synchronizes the raw name, skin, equipment,
age/age-lock, pose, glowing state, protection and sneaking, plus these stable Citizens
metadata keys: `collidable`, `flyable`, `fluid-pushable`, `glowing`,
`nameplate-visible`, `silent-sounds`, `swim`, and `minecraft-ai`. Skin data is passed to
Citizens in name, signature, texture order.

Projection limits prefer nearby actors, recent interactions, active dialogue, active
talk-to-NPC quest objectives, and private navigation. A higher-priority presentation
can temporarily preempt a lower-priority one. `/persona npc info` reports the selected
player's count and the server total. Limit and spawn failures are rate-limited in the
server log with their cause. Do not add Citizens' `playerfilter` trait to a bound base
actor: new bindings reject it and existing conflicts are reported clearly.

`behavior.projections.transitions` controls owner-only spawn/despawn particles,
sounds, and effect duration. `behavior.projections.debug.enabled` shows owner-only
markers for the selected anchor and current logical/navigation position.

The editor compatibility contract is bundled at `schema/behaviors.schema.json`.

## Behavior runtime persistence and reload migration

Wait, timeout, cooldown, and logical-travel timing uses absolute epoch deadlines, so
server downtime does not extend a timer. A restored sleeping runtime remains out of
the scheduler until its saved wake time or a relevant event wakes it. Logical travel
stores its behavior/node owner, source, destination, start time, and duration as typed
runtime columns; these values are not exposed as convention-based blackboard keys.

Durable node keys contain both the behavior ID and node ID. This also applies to nodes
inside reusable subtrees, allowing two subtrees to use the same local node IDs safely.
On content reload, Persona applies these migration rules:

- A persisted node survives only if the same behavior and node still exist and its
  node kind is unchanged. Action and condition kinds include their native or extension
  type, so changing `logical-travel` to another action discards the travel state.
- Asynchronous action and navigation handles are transient and always restart. Valid
  absolute wait/cooldown deadlines remain durable.
- Checkpoint progress resumes only when the checkpoint still exists and the IDs,
  kinds, order, and subtree structure below its child have the same fingerprint.
- A removed, moved, or structurally changed active checkpoint restarts runtime node
  progress and deadlines for that behavior tree. Its runtime blackboard is retained,
  and NPC memories are never deleted by behavior migration.
- Older rows without node-kind metadata are retained only when the complete behavior
  tree hash is unchanged. Legacy checkpoints without a child fingerprint restart once
  so future reloads can use the structural migration rules.

See the README's database section for the supported online backup and corruption
recovery procedure.
