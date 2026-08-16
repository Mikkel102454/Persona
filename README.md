# Persona

Persona is a dialogue and quest engine for [Paper](https://papermc.io/) servers. It connects YAML-authored characters, conversations, and quests to [Citizens](https://citizensnpcs.co/) NPCs, while keeping player progress in a local SQLite database.

The plugin is designed for server owners who want to build story-driven content without compiling a plugin for every quest. Developers can add new commands, conditions, placeholders, and objective types through the Persona 2.0 extension API.

## Features

- Branching, timed dialogue with choices and conditional paths
- Multi-phase quests with eight built-in objective types
- Ordered scripts shared by dialogues, quests, and NPC hooks
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
`-- quests/
    `-- supplies.yml
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
| `plugins/Persona/npcs/*.yml` | NPC definitions and dialogue selection rules |
| `plugins/Persona/dialogues/*.yml` | Dialogue nodes, speech, choices, and flow |
| `plugins/Persona/quests/*.yml` | Quests, phases, objectives, and lifecycle scripts |
| `plugins/Persona/scripts.yml` | Reusable named scripts |
| `plugins/Persona/behaviors/*.yml` | Shared and per-player NPC behavior trees |
| `plugins/Persona/extensions/*.jar` | Optional standalone Persona extensions |

IDs are namespaced, such as `village:builder` or `guild:adventurers_trial`. Script entries are executed in order and use lowercase kebab-case types:

```yaml
id: village:welcome
start: greeting
nodes:
  greeting:
    script:
      - type: say
        text: "Welcome, traveler."
      - type: choice
        options:
          - text: "Do you need help?"
            script:
              - type: start-quest
                quest: village:supplies
          - text: "Goodbye."
            script:
              - type: end-dialogue
```

For the complete schema, built-in commands and conditions, quest objective types, extension API, and migration notes, see [AUTHORING.md](AUTHORING.md).

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/persona quests [page]` | List the player's active quests | `persona.player.quests` |
| `/persona quest show <quest-id>` | Show quest and objective progress | `persona.player.quests` |
| `/persona dialogue cancel` | Leave the current conversation | `persona.player.dialogue.cancel` |
| `/persona npc info` | Inspect the selected NPC's behavior and presentation | `persona.admin.npc` |
| `/persona memory ...` | Inspect or mutate selected-NPC memory | `persona.admin.memory` |
| `/persona quest start <player> <quest-id>` | Start a quest for an online player | `persona.admin.quest` |
| `/persona quest finish <player> <quest-id>` | Finish a quest for an online player | `persona.admin.quest` |
| `/persona quest reset <player> <quest-id>` | Reset a quest for an online player | `persona.admin.quest` |
| `/persona npc bind <npc-id> [instance-id]` | Bind the selected Citizens NPC | `persona.admin.npc` |
| `/persona npc unbind` | Remove the selected NPC's binding | `persona.admin.npc` |
| `/persona npc info` | Inspect the selected NPC's binding | `persona.admin.npc` |
| `/persona reload` | Atomically reload configuration and content | `persona.admin.reload` |

Player permissions are granted by default. Administrative permissions default to server operators.

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

Standalone extensions are loaded at server startup from `plugins/Persona/extensions`. Each extension JAR must contain a `persona-extension.yml` manifest and an implementation of `PersonaExpansion` targeting API version `2.0`. Extensions can register namespaced commands, conditions, placeholders, and objectives.

Persona also publishes a standard Java component and a shaded artifact through Gradle's `maven-publish` configuration for local or repository-based API consumption.
