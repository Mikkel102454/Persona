# Migrating reusable scripts to format 2

Persona does not rewrite or interpret legacy reusable scripts. A `scripts.yml` without `content-version: 2`, or a script whose value is a YAML list, is rejected with a targeted migration error. Other Persona content files remain at content format 1.

## Script definitions

Before:

```yaml
scripts:
  start-introduction:
    - type: start-quest
      quest: story:introduction
```

After:

```yaml
content-version: 2
scripts:
  start-introduction:
    inputs:
      quest: { type: quest, required: true }
    outputs:
      started: { type: boolean, default: false }
    nodes:
      start: { type: start-quest, quest: story:introduction }
      yes: { type: value, value-type: boolean, value: true }
    connections:
      enter: { from: $input.exec, to: start.exec }
      selected: { from: $input.quest, to: start.quest }
      leave: { from: start.success, to: $output.exec }
      result: { from: yes.value, to: $output.started }
```

Every node and connection needs a stable mapping key. `$input` owns the execution entry and declared input data outputs; `$output` owns the execution result and declared output data inputs. Pure value and converter nodes have data pins only.

## Calls from list hooks

Every NPC, dialogue, quest, objective, phase, and nested command hook changes from:

```yaml
- { type: run-script, script: start-introduction }
```

to:

```yaml
- type: run-script
  script: start-introduction
  inputs:
    quest: story:introduction
```

Use `inputs: {}` for a zero-parameter script. Hook calls execute asynchronously as before and discard return values.

## Calls from behavior graphs

Behavior `action: script` nodes keep their script ID. They invoke the graph with an empty input map and discard outputs, so a behavior-callable graph must make every input optional (with defaults where needed). To consume typed return values, use an explicit `run-script` node inside a reusable script graph.

## Explicit graph call nodes

An explicit call is a normal node:

```yaml
nodes:
  call:
    type: run-script
    script: start-introduction
    inputs: {}
connections:
  argument: { from: quest.value, to: call.quest }
  continue: { from: call.success, to: $output.exec }
  return-value: { from: call.started, to: $output.started }
```

Its data pins come from the target signature. Recursive call graphs, execution/data cycles, incompatible nominal types, missing endpoints, duplicate input wires, unreachable required outputs, and reads from impure outputs before their execution are rejected atomically during content loading.
