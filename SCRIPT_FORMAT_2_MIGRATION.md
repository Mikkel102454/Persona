# Migrating all script-bearing content to format 2

Persona intentionally does not interpret legacy list hooks or monolithic reusable scripts. Validation identifies the file and obsolete field so migration can be completed before activation.

## Reusable scripts

Move every entry from `scripts.yml` into an individual `scripts/<folders>/<name>.yml` file. Add the resource ID and the complete typed graph boundaries:

```yaml
content-version: 2
id: story:start-introduction
inputs:
  quest: { type: quest, required: true }
  player: { type: player, required: true }
outputs:
  started: { type: boolean, default: false }
variables: {}
nodes:
  start: { type: start-quest, quest: story:introduction }
  yes: { type: value, value-type: boolean, value: true }
connections:
  enter: { from: $input.exec, to: start.exec }
  selected: { from: $input.quest, to: start.quest }
  player: { from: $input.player, to: start.player }
  leave: { from: start.success, to: $output.exec }
  result: { from: yes.value, to: $output.started }
```

Every node and connection needs a stable lowercase mapping key. `$input` owns execution entry and declared input data outputs. `$output` owns execution completion and declared output data inputs.

## Host hooks

NPCs, dialogues, and quests require `content-version: 2`. Replace every list-form hook with a descriptor containing `variables`, `nodes`, and `connections` and connect execution from `$event.exec`:

```yaml
on-complete:
  variables: {}
  nodes:
    call:
      type: run-script
      script: story:start-introduction
      inputs: {}
  connections:
    enter: { from: $event.exec, to: call.exec }
    quest: { from: $event.quest, to: call.quest }
    player: { from: $event.player, to: call.player }
```

For dialogue nodes, rename `script` to `graph`. For objective progress hooks, keep the cadence and nest the descriptor under `graph`:

```yaml
on-progress:
  every: 5
  graph:
    variables: {}
    nodes:
      status: { type: action-bar, text: "<current>/<required>" }
    connections:
      enter: { from: $event.exec, to: status.exec }
      player: { from: $event.player, to: status.player }
```

Nested list handlers such as `on-success`, `on-failure`, choice option scripts, and random option scripts become explicit execution outputs and connections on their corresponding graph nodes.

## NPC interaction

Rename `on-interact` to `on-click` and convert it to a graph descriptor. On Click exposes `left-button` and `right-button` boolean data pins. Preserve dialogue registrations under `dialogues`; right-click selection occurs only when On Click continues.

## Verification

1. Remove the old root `scripts.yml`.
2. Ensure every NPC, dialogue, quest, and individual reusable script declares `content-version: 2`.
3. Ensure every graph has keyed `variables`, `nodes`, and `connections` mappings, even when empty.
4. Run `/persona validate --json` or `/persona reload --dry-run --json`.
5. Resolve missing endpoints, exact-type mismatches, execution cycles, and required-output reachability errors before reloading.

The visual editor performs these mutations against authoritative YAML and can insert declared safe converters. It never silently accepts the old shapes.
