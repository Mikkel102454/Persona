# Persona

Persona is a dialogue and quest engine for [Paper](https://papermc.io/) servers. It connects YAML-authored characters, conversations, and quests to [Citizens](https://citizensnpcs.co/) NPCs, while keeping player progress in a local SQLite database.

The plugin is designed for server owners who want to build story-driven content without compiling a plugin for every quest. Developers can add new commands, conditions, placeholders, and objective types through the additive Persona 2.x extension API.

## Features

- Branching, timed dialogue with choices and conditional paths
- Multi-phase quests with eight built-in objective types
- Typed node graphs shared by dialogues, quests, NPC events, and reusable scripts
- Conditions based on quest state, items, flags, variables, permissions, worlds, and chance
- Built-in effects for messages, titles, sounds, particles, items, entities, blocks, movement, and more
- Citizens NPC bindings with conditional dialogue selection
- Stable-ID YAML behavior trees with shared and per-player runtimes
- Typed global and player/NPC memory with expiry and SQLite persistence
- Viewer-private Citizens projections at named NPC anchors
- Per-player progress persisted in SQLite
- Atomic content reloads: invalid content is rejected while the previous registry remains active
- A Java extension API for custom namespaced content types

## Requirements

- Java 25
- Paper 26.2
- Citizens 2.0.43 or a compatible release

## Installation

1. Install Paper and Citizens on the server.
2. Download or build Persona and place its JAR in the server's `plugins` directory.
3. Start the server once. Persona creates `plugins/Persona`, its configuration, example content, and `persona.db`.
4. Add content to the directories described below, then run `/persona reload` or restart the server.

## Quick start

Persona ships a small builder quest in `plugins/Persona/examples`. Copy these files into their corresponding live content directories and remove the `.example` suffix:

```text
plugins/Persona/
|-- npcs/
|   `-- builder.yml
|-- behaviors/
|   `-- builder-routine.yml
|-- dialogues/
|   |-- builder.yml
|   |-- builder_delivery.yml
|   `-- builder_thanks.yml
|-- quests/
|   `-- supplies.yml
`-- scripts/
    `-- quest-success.yml
```

Reload Persona, create or select an NPC with Citizens, and bind that selected NPC to the definition:

```text
/persona reload
/persona npc bind village:builder
```

Interacting with the NPC now selects the appropriate conversation based on the player's quest state.

For a fuller behavior-tree example, copy `harbor_keeper.yml.example` plus the three
`keeper-*.yml.example` behavior files. They demonstrate a shared patrol, private
anchors and visibility, typed memory, named signals, logical travel, checkpoints,
parallel nodes, retries, cooldowns, and a reusable subtree.

## Content layout

Persona reads YAML from the following locations:

| Path | Purpose |
| --- | --- |
| `plugins/Persona/npcs/**/*.yml` | NPC definitions, dialogue registrations, signals, and event graphs |
| `plugins/Persona/dialogues/**/*.yml` | Dialogue nodes and their event graphs |
| `plugins/Persona/quests/**/*.yml` | Quests, phases, objectives, and lifecycle graphs |
| `plugins/Persona/scripts/**/*.yml` | One typed reusable graph per file |
| `plugins/Persona/behaviors/**/*.yml` | Shared and per-player NPC behavior trees |
| `plugins/Persona/extensions/*.jar` | Optional standalone Persona extensions |

The five content roots may contain up to eight nested folder levels. IDs are namespaced and independent from paths, so moving `npcs/village/builder.yml` does not change `village:builder` or any reference to it. Script-bearing fields in NPC, dialogue, and quest content are explicit version-2 graphs with stable node and connection keys:

```yaml
content-version: 2
id: village:welcome
start: greeting
nodes:
  greeting:
    graph:
      variables: {}
      nodes:
        welcome: { type: say, text: "Welcome, traveler." }
        finish: { type: end-dialogue }
      connections:
        enter: { from: $event.exec, to: welcome.exec }
        leave: { from: welcome.success, to: finish.exec }
```

Old list hooks, `on-interact`, and monolithic `scripts.yml` are intentionally rejected with migration errors. Use `on-click` and individual files under `scripts/`.

For the complete schema, built-in commands and conditions, quest objective types, extension API, and migration notes, see [AUTHORING.md](AUTHORING.md).

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/persona quests [page]` | List the player's active quests | `persona.player.quests` |
| `/persona quest show <quest-id>` | Show quest and objective progress | `persona.player.quests` |
| `/persona dialogue cancel` | Leave the current conversation | `persona.player.dialogue.cancel` |
| `/persona npc info` | Inspect the selected NPC's behavior and presentation | `persona.admin.npc` |
| `/persona memory ...` | Inspect, mutate, or migrate selected-NPC memory | `persona.admin.memory` |
| `/persona behavior signal <name>` | Send a named signal to the selected NPC runtimes | `persona.admin.behavior` |
| `/persona backup` | Create a consistent online SQLite backup | `persona.admin.backup` |
| `/persona quest start <player> <quest-id>` | Start a quest for an online player | `persona.admin.quest` |
| `/persona quest finish <player> <quest-id>` | Finish a quest for an online player | `persona.admin.quest` |
| `/persona quest reset <player> <quest-id>` | Reset a quest for an online player | `persona.admin.quest` |
| `/persona npc bind <npc-id> [instance-id]` | Bind the selected Citizens NPC | `persona.admin.npc` |
| `/persona npc unbind` | Remove the selected NPC's binding | `persona.admin.npc` |
| `/persona npc info` | Inspect the selected NPC's binding | `persona.admin.npc` |
| `/persona reload` | Atomically reload configuration and content | `persona.admin.reload` |

Player permissions are granted by default. Administrative permissions default to server operators.

Memory commands operate on the selected bound Citizens NPC. Player scope accepts an
online/offline player name, UUID, or `self`; global scope omits the player argument:

```text
/persona memory get <global|player> [player] <key>
/persona memory list <global|player> [player] [page]
/persona memory set <global|player> [player] <key> <boolean|number|string|timestamp> <value> [expiry]
/persona memory adjust <global|player> [player] <key> <amount> [minimum] [maximum] [expiry]
/persona memory cas <global|player> [player] <key> <type> <expected|unset> <value> [expiry]
/persona memory expire <global|player> [player] <key> <now|ISO-8601|duration>
/persona memory delete <global|player> [player] <key>
/persona memory export|import [file]
/persona memory metrics
```

Durations such as `30s`, `10m`, and `2h` mean that far from now. Timestamp values and
expiry accept `now` and ISO-8601 instants as well. Lists include type, source, update,
and expiry metadata and show eight entries per page. Migration files are versioned
YAML under `plugins/Persona/memory-transfer/`; imports overwrite matching identities.

Player/global inspection, player/global mutation, and migration are separately
controlled by `persona.admin.memory.inspect.*`, `persona.admin.memory.modify.*`, and
`persona.admin.memory.migrate`. This prevents an administrator who may edit shared
story state from automatically reading private player memories.

## Database durability and backups

Persona persists player state, NPC memories, and behavior runtimes in
`plugins/Persona/persona.db`. Runtime flushes update only changed logical runtimes;
offline player runtimes remain untouched. Runtime metadata, blackboard values, and
checkpoint state for one NPC/player runtime are committed as one SQLite transaction.

Run `/persona backup` from the console or as an authorized operator to create a
consistent online backup under `plugins/Persona/backups/`. The command queues the
backup after pending state flushes and uses SQLite's online `VACUUM INTO` facility, so
the server does not need to stop. Keep copies of this directory outside the Minecraft
host according to your normal retention policy. To restore, stop the server, move the
current `persona.db` plus its `-wal` and `-shm` files out of the data directory, copy
the chosen backup to `persona.db`, and start the server.

Persona runs an integrity check when opening the database. If SQLite reports physical
corruption, Persona moves the unreadable database and its sidecars to a timestamped
`persona.db.corrupt-*` file before creating a fresh database. Do not delete that
quarantined copy; restore a known-good backup or provide it to a SQLite recovery tool.

## Configuration

The generated `config.yml` controls dialogue timing and player-facing messages:

```yaml
dialogue:
  default-line-delay: 2s
  inactivity-timeout: 120s
  maximum-distance: 8.0
```

Durations accept values such as `500ms`, `2s`, and `1m`. Messages and authored text support MiniMessage formatting.

## Building and testing

The Gradle wrapper handles the required Gradle version. A local Java 25 toolchain must be available.

Persona currently publishes YAML content-format version `1`, independently of the
plugin, Java API, editor protocol, and SQLite schema versions. See
[`AUTHORING.md`](AUTHORING.md#content-format-compatibility) for the compatibility and
validation contract.

```shell
# Windows
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat runServer

# macOS or Linux
./gradlew build
./gradlew test
./gradlew runServer
```

The shaded plugin JAR is written to `build/libs/Persona-2.0.0.jar`. The development server uses the `run` directory; install Citizens there before testing NPC integration.

## Extensions

Standalone extensions are loaded at server startup from `plugins/Persona/extensions`. Each extension JAR must contain a `persona-extension.yml` manifest and an implementation of `PersonaExpansion` targeting API version `2.x` (current: `2.2`). Extensions can register namespaced commands, conditions, placeholders, objectives, behavior conditions, cancellable behavior actions, data-only editor schemas, and bounded live catalogs. API evolution within 2.x is additive and previously compiled 2.0 extensions remain compatible.

Persona also publishes a standard Java component and a shaded artifact through Gradle's `maven-publish` configuration for local or repository-based API consumption.
