# Persona agent guide

Paper plugin providing YAML-authored dialogue, quests, reusable scripts, NPC behavior, SQLite state, and hosted-editor integration. Java 25; Paper 26.2; Citizens is compile-only.

## Find things

- Bootstrap/commands/events: `src/main/java/nu/miguel/persona/{Main,PersonaCommand,PersonaListener}.java`
- Public extension API and loader: `api/`; API events: `api/event/`
- YAML loading, validation, IDs, atomic reload: `content/`
- Ordered script runtime/effects: `script/`; quests/conditions: `quest/`; conversations: `dialogue/`
- Behavior trees/runtime/scheduler: `behavior/`; Citizens traits/projections: `citizens/`
- SQLite/player/NPC state: `state/`; hosted-editor client/snapshots/publish: `editor/`
- Defaults, plugin metadata, schemas, bundled examples: `src/main/resources/`
- Tests mirror packages under `src/test/java/`.
- User overview/build: `README.md`; complete YAML/API contract: `AUTHORING.md`; script migration: `SCRIPT_FORMAT_2_MIGRATION.md`.

## Work rules

- Preserve atomic content reload: reject invalid new content without replacing the active registry.
- Keep the content format, plugin version, extension API, editor protocol, and DB schema version conceptually separate.
- Update schemas/examples/docs and validation tests when changing authored YAML.
- Keep Paper/Citizens work off-thread unless their API explicitly permits it; preserve async script ordering and cancellation.
- Never edit `build/`, `.gradle/`, `.idea/`, or `run/` as source.

## Verify

```sh
./gradlew test                 # unit suite
./gradlew build                # tests + shaded plugin JAR
./gradlew runServer            # manual Paper server in run/
```

For one test: `./gradlew test --tests 'fully.qualified.TestName'`. Output: `build/libs/Persona-2.0.0.jar`. The composite build expects sibling `../PersonaBackend`.

