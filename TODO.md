# Persona Roadmap

This document tracks the work needed to turn Persona's current behavior, memory,
dialogue, quest, and Citizens integration into a polished authoring platform for
story-driven Minecraft worlds.

The checkboxes below describe unfinished work. Existing functionality should remain
backward compatible unless a migration is explicitly documented and tested.

## Product goals

- A server owner can build a living story world without writing Java.
- The same logical NPC can have shared behavior and private player-specific story state.
- Content is safe to reload while a production server is running.
- Invalid content is explained precisely and never partially activated.
- Runtime work remains predictable with approximately 100 concurrent players.
- YAML remains the compatibility format, even when content is created visually.
- Java extensions can add behavior nodes without forking Persona or the editor.

## P0: Runtime correctness and durability

### Private walking and presentation transitions

- [ ] Add a native `begin-private-presentation` action that creates a projection at
      the shared actor's exact current position.
- [ ] Add a combined `private-navigate` action that materializes, walks, persists the
      destination anchor, and handles failure as one cancellable operation.
- [ ] Persist a projection's current logical position independently of its selected
      named anchor.
- [ ] Resume private navigation after projection suspension when the player returns.
- [ ] Allow a private actor to walk across activation-boundary changes without being
      incorrectly suspended relative to its old anchor.
- [ ] Decide and document behavior when the viewer changes world during navigation.
- [ ] Add configurable arrival distance, speed, pathfinding range, and stuck handling.
- [ ] Wake the behavior immediately for navigation success, cancellation, and each
      distinct failure reason.
- [ ] Ensure reload and shutdown cancel every Citizens navigator and extension action.
- [ ] Prevent an old asynchronous completion from modifying a newly reloaded runtime.

Acceptance criteria:

- A player-specific NPC can transition from the shared actor to a walking projection
  without visually teleporting.
- Disconnecting, leaving activation range, restarting, and reloading never duplicates
  the NPC or loses the durable destination.
- Other players continue seeing the correct shared or private presentation.

### Runtime persistence

- [ ] Persist and restore the scheduler wake time rather than evaluating every restored
      runtime immediately.
- [ ] Persist logical-travel source, destination, start time, and duration as typed
      runtime fields instead of convention-based blackboard entries.
- [ ] Namespace runtime node state by behavior ID so subtree node IDs cannot collide.
- [ ] Filter all removed or type-changed node state during reload migration.
- [ ] Define migration rules for checkpoint nodes whose child structure changed.
- [ ] Make runtime, blackboard, and checkpoint writes transactional per logical runtime.
- [ ] Preserve offline player runtimes without rewriting the entire runtime table.
- [ ] Add incremental dirty-row writes instead of delete-and-reinsert persistence.
- [ ] Add database indexes and measure query plans with large memory/runtime datasets.
- [ ] Add corruption recovery and a supported database backup procedure.

Acceptance criteria:

- Waits and cooldowns use absolute deadlines across restart.
- Transient leaves restart safely, while valid checkpoint progress resumes.
- Removed nodes restart only the affected tree and never remove NPC memories.

### Event delivery

- [ ] Give every event a stable ID and explicit consumption policy.
- [ ] Support `consume: true|false` on event conditions.
- [ ] Prevent one interaction event from retriggering a successful branch every tick.
- [ ] Wake trees when relevant quest state, objective progress, flags, variables, or
      memories change.
- [ ] Track proximity relative to the player's active private presentation, not only
      the shared Citizens actor.
- [ ] Add navigation, spawn, despawn, world-change, and projection lifecycle events.
- [ ] Add an administrator command for sending named signals without a Java extension.
- [ ] Document ordering when several events arrive during a running asynchronous leaf.
- [ ] Expose dropped-event counters when an inbox reaches its configured bound.

### Behavior semantics

- [ ] Add exhaustive tests for every composite/decorator combination.
- [ ] Define whether `repeat` and `retry` yield between immediate iterations.
- [ ] Add optional infinite repeat with explicit safeguards and schema validation.
- [ ] Define deterministic parallel completion when success and failure thresholds are
      reached during the same evaluation.
- [ ] Add configurable cancellation policies for parallel children.
- [ ] Make subtree runtime paths include both behavior ID and node ID.
- [ ] Expose the complete running path rather than only the running leaf.
- [ ] Record condition inputs and safe redacted outputs in trace history.
- [ ] Ensure budget exhaustion never causes a reactive selector to interrupt a branch.
- [ ] Respect the configured behavior tick cadence.

## P0: Content validation and compatibility

- [ ] Validate every built-in behavior option, type, range, and required combination.
- [ ] Reject unknown keys with a suggested replacement when possible.
- [ ] Validate `source`, `destination`, and every other anchor-bearing option.
- [ ] Reuse the normal Persona condition and command parsers inside behavior leaves so
      validation and runtime semantics cannot drift apart.
- [ ] Validate named scripts used by behavior actions during atomic content loading.
- [ ] Validate extension node options using extension-provided schema metadata.
- [ ] Detect conflicting behavior IDs across files with both source locations.
- [ ] Include YAML line and column numbers in every validation error.
- [ ] Aggregate independent behavior, NPC, dialogue, quest, and script errors into one
      reload report.
- [ ] Add schema fixtures proving that all packaged examples remain valid.
- [ ] Add migration tests from every released SQLite schema version.
- [ ] Publish a documented content-format version independently of the plugin version.

Acceptance criteria:

- A failed reload leaves content, runtimes, navigation, and presentations unchanged.
- CLI validation and the visual editor return the same errors for the same project.

## P1: NPC presentation quality

- [ ] Copy and update all supported presentation properties: entity type, name, skin,
      equipment, age, pose, glowing state, and explicitly supported metadata.
- [ ] Detect base presentation changes and synchronize active projections.
- [ ] Confirm skin texture/signature ordering across supported Citizens versions.
- [ ] Add smooth spawn/despawn options and configurable transition effects.
- [ ] Add projection prioritization based on distance, recent interaction, dialogue,
      quest relevance, and navigation activity.
- [ ] Add rate-limited projection-limit diagnostics with actionable reasons.
- [ ] Expose current per-player and server-wide projection counts.
- [ ] Prevent independently configured Citizens player filters on Persona-bound actors,
      or detect and report them clearly.
- [ ] Test clicks, dialogue, damage, and selection routing for projections.
- [ ] Test two players seeing one logical NPC at different anchors simultaneously.
- [ ] Add optional debug particles or markers for anchors and navigation destinations.

## P1: Memory tools

- [ ] Support selecting another online or offline player in memory admin commands.
- [ ] Add `expire` and `list` commands with pagination and type/source metadata.
- [ ] Add compare-and-set and bounded numeric adjustment operations.
- [ ] Add memory-change events containing old value, new value, scope, and source.
- [ ] Add configurable key namespaces and extension ownership.
- [ ] Add memory import/export for debugging and server migration.
- [ ] Add retention policies and metrics for expired-row database sweeps.
- [ ] Add timestamp-friendly parsing such as `now`, ISO-8601, and durations from now.
- [ ] Add privacy-safe inspection permissions for player-specific memories.

## P1: Administration and debugging

- [ ] Expand `/persona npc trace` to show behavior ID, tree hash, full running path,
      checkpoint path, deadlines, inbox, blackboard, and recent outcomes.
- [ ] Explain why every selector branch failed.
- [ ] Explain why a projection inherited, spawned, suspended, failed, or hit a limit.
- [ ] Add `/persona behavior pause|resume|restart|signal` commands.
- [ ] Add `/persona validate` without activating content.
- [ ] Add `/persona reload --dry-run` and a machine-readable validation report.
- [ ] Add scoped debug logging per NPC instance, player, behavior, or node.
- [ ] Add timings for slow conditions/actions and per-extension runtime usage.
- [ ] Add diagnostics for orphaned persisted runtimes and unknown NPC definitions.
- [ ] Add a support bundle containing configuration, validation errors, schema versions,
      and redacted runtime diagnostics.

## P1: Extension API completion

- [ ] Version the behavior extension API additively within API 2.x.
- [ ] Add cancellation tokens and guarantee exactly one cancellation callback.
- [ ] Define server-thread rules for parsing, conditions, action starts, completions, and
      cancellation.
- [ ] Provide extension utilities for safely completing actions on the server thread.
- [ ] Allow extensions to declare player/shared scope compatibility.
- [ ] Allow extensions to declare wake events and durable runtime fields.
- [ ] Publish JSON Schema fragments for extension nodes.
- [ ] Merge extension schemas into the editor and CLI validator.
- [ ] Add example extensions for a custom condition and cancellable asynchronous action.
- [ ] Add binary/source compatibility tests for previously compiled 2.x extensions.

## P2: Visual editor

The editor must be a centrally hosted web application, not a separately installed
desktop application and not a web server exposed from the Minecraft machine. Persona
should use a LuckPerms-style session flow: an authorized user runs a command, Persona
opens an outbound secure session to the hosted service, and the user receives a link
plus a short-lived verification code. The hosted editor can then edit content and,
after explicit trust is established, subscribe to live server state.

Design references:

- [LuckPerms Web Editor](https://luckperms.net/wiki/Web-Editor)
- [LuckPerms Web Editor Technical Details](https://luckperms.net/wiki/Web-Editor-Technical-Details)

### Hosted service architecture

- [ ] Implement the hosted backend in Java with Spring Boot.
- [ ] Use Spring WebSocket for authenticated live plugin/browser channels and Spring
      MVC/WebFlux endpoints for sessions, snapshots, drafts, validation, and publishing.
- [ ] Keep the Minecraft plugin as a separate lightweight Java client that initiates
      one outbound TLS WebSocket connection per trusted editor session or multiplexes
      sessions over one authenticated server connection.
- [ ] Define shared protocol DTOs and JSON serialization in a small versioned Java
      module usable by both Persona and the Spring Boot backend.
- [ ] Do not share Bukkit, Paper, Citizens, runtime service, or database implementation
      classes with the hosted backend.
- [ ] Build a multi-tenant Persona editor hosted at a configurable HTTPS URL.
- [ ] Keep all Minecraft connections outbound; require no port forwarding or inbound
      access to the Paper server.
- [ ] Create `/persona editor [scope]` to start an editor session from the server.
- [ ] Return a clickable editor URL and a separate one-time verification code to the
      authorized command sender.
- [ ] Associate an in-game session with the initiating player's UUID, or with a clearly
      identified console session.
- [ ] Give session codes at least 40 bits of effective entropy, limit attempts, make
      them single-use, and expire unused codes within a few minutes.
- [ ] Never place reusable server credentials or private signing keys in the URL.
- [ ] Give every Persona installation a locally generated signing identity and use
      signed protocol messages so the browser and relay cannot forge server data.
- [ ] Give each browser session an ephemeral key and bind it during verification.
- [ ] Use TLS plus an authenticated WebSocket connection for live messages.
- [ ] Support reconnect with sequence numbers, bounded replay, and full resynchronization
      when deltas were missed.
- [ ] Add heartbeats, idle timeout, absolute session expiry, explicit revoke, and clean
      shutdown handling.
- [ ] Make the relay incapable of silently applying content; the Minecraft plugin must
      validate and authorize every requested operation.
- [ ] Version the editor protocol independently from plugin and content versions.
- [ ] Allow the hosted URL, relay URL, TLS requirements, and feature availability to be
      configured without weakening secure defaults.
- [ ] Publish the hosted web application and relay source so operators can audit or
      self-host compatible deployments if desired.

### Spring Boot backend

- [ ] Create modules for session/authentication, relay, content snapshots, drafts,
      publishing, live subscriptions, audit history, and administration.
- [ ] Model server installation, editor session, browser identity, capability grant,
      content revision, draft, publish request, subscription, and audit event explicitly.
- [ ] Use PostgreSQL for durable hosted metadata, drafts, content revisions, and audit
      records; do not store Minecraft runtime state as an authoritative hosted copy.
- [ ] Use Redis or an equivalent expiring store for verification codes, session leases,
      rate limits, presence, and cross-instance WebSocket routing when horizontally
      scaling the backend.
- [ ] Keep live quest/NPC/runtime data ephemeral and subscription-scoped unless an
      administrator explicitly enables bounded trace recording.
- [ ] Add database migrations through Flyway or Liquibase.
- [ ] Use Spring Security for capability checks on every HTTP and WebSocket operation.
- [ ] Authenticate plugin installations with asymmetric challenge/response rather than
      a permanent bearer token copied into configuration.
- [ ] Authenticate browsers through the Persona one-time-code flow; optionally add
      normal hosted accounts later for draft ownership and team workflows.
- [ ] Validate WebSocket message type, protocol version, payload size, sequence number,
      session, capability, and signature before dispatch.
- [ ] Separate plugin-to-backend and browser-to-backend message handlers so a browser
      can never impersonate a Minecraft server.
- [ ] Apply per-installation and per-session quotas for connections, messages, snapshot
      size, trace rate, drafts, and retained revisions.
- [ ] Add bounded queues, backpressure, timeouts, circuit breakers, and slow-consumer
      disconnection policies.
- [ ] Expose health, readiness, metrics, and structured audit logging through secured
      Spring Boot Actuator endpoints.
- [ ] Add OpenTelemetry traces and metrics without including memory values or private
      player data in telemetry.
- [ ] Support horizontal deployment behind a reverse proxy with TLS termination and
      WebSocket upgrade support.
- [ ] Provide Docker images, Docker Compose for development, and documented environment
      variables/secrets for production deployment.
- [ ] Add integration tests with Testcontainers for PostgreSQL, Redis, HTTP, WebSocket,
      reconnect, expiry, rate limiting, and multi-instance message routing.

### Session trust and permissions

- [ ] Add separate permissions for opening the editor, viewing content, viewing live
      players, viewing memories, editing drafts, publishing content, and issuing live
      mutations.
- [ ] Show the verification code, browser description, session scope, requested
      capabilities, and expiry in-game before trust is granted.
- [ ] Require the one-time code to be entered on the hosted website and require an
      explicit in-game/console confirmation for publish or live-mutation capability.
- [ ] Start every session read-only until its requested capabilities are trusted.
- [ ] Scope sessions to selected worlds, players, NPC definitions/instances, or content
      namespaces when requested.
- [ ] Add `/persona editor sessions`, `trust`, `revoke`, and `close` commands.
- [ ] Revoke sessions automatically when the initiating player loses permission,
      leaves the server if configured, or the plugin disables.
- [ ] Never transmit IP addresses, chat, inventory contents, or unrelated player data.
- [ ] Redact memory keys or values according to permissions and configurable namespaces.
- [ ] Record an audit event for connection, trust, snapshot access, draft upload,
      validation, publish, rollback, signal, memory mutation, and session revocation.
- [ ] Rate-limit authentication attempts, subscriptions, snapshots, mutations, and
      publish requests per session and server.

### Editor foundation

- [ ] Choose and document the browser frontend stack and its boundary with the required
      Java Spring Boot backend.
- [ ] Load a signed content snapshot from the connected Persona server as one project.
- [ ] Allow offline YAML files or ZIP projects to be imported into the hosted editor
      without granting that browser access to a live server.
- [ ] Import existing YAML without discarding comments or unsupported extension data.
- [ ] Save deterministic, readable YAML with stable ordering.
- [ ] Give every content editor two first-class modes: drag-and-drop visual editing and
      raw YAML source editing.
- [ ] Keep visual and YAML modes synchronized through one typed document model rather
      than maintaining two divergent copies.
- [ ] Parse and validate YAML continuously, but keep the last valid visual model visible
      while raw YAML contains temporary syntax errors.
- [ ] Highlight which visual node and form field correspond to the current YAML cursor,
      and select the matching YAML range when a visual element is selected.
- [ ] Preserve comments, key ordering where possible, extension-owned fields, aliases,
      and unknown future data when switching modes.
- [ ] Never silently delete YAML that the current editor or schema does not understand;
      show it as preserved custom data with a warning.
- [ ] Support split view so visual structure and raw YAML can be edited side by side.
- [ ] Add visual/YAML round-trip fixtures for behaviors, NPCs, quests, dialogues,
      scripts, and extension-provided content.
- [ ] Validate continuously using the same compatibility contract as the plugin.
- [ ] Display file, line, node, and suggested fixes for every validation error.
- [ ] Support undo/redo, copy/paste, autosave recovery, and unsaved-change warnings.
- [ ] Provide searchable command palettes and keyboard navigation.
- [ ] Show a project-wide reference graph and safe rename preview.
- [ ] Download the complete project or only changed files as YAML/ZIP.
- [ ] Add Git-friendly textual and visual diffs.
- [ ] Keep drafts isolated by server, session, author, and base content revision.
- [ ] Detect stale drafts when live server content was reloaded or changed elsewhere.

### Behavior-tree editor

- [ ] Add a graph/tree canvas with drag-and-drop nodes and automatic layout.
- [ ] Allow nodes and complete branches to be reordered, nested, duplicated, extracted
      into subtrees, or moved between compatible parents by dragging.
- [ ] Provide palettes for composites, decorators, leaves, and extension node types.
- [ ] Require and generate stable node IDs.
- [ ] Edit node-specific options with schema-driven forms.
- [ ] Show scope errors immediately when player-only nodes enter shared trees.
- [ ] Visualize sequence order, selector priority, and parallel thresholds.
- [ ] Expand and navigate subtree references without recursively embedding them.
- [ ] Highlight duplicate IDs, recursive references, and missing anchors/scripts.
- [ ] Show checkpoint boundaries and durable versus transient state.
- [ ] Include a deterministic step-through simulator with mock memories and events.
- [ ] Overlay live node status, running path, checkpoint, and selector failure reasons
      for a selected NPC/player runtime.

### NPC editor

- [ ] Create/edit NPC IDs, display names, dialogue registrations, hooks, and behavior
      references.
- [ ] Provide drag-and-drop ordering for dialogue registrations and NPC hook scripts.
- [ ] Manage named anchors in a table and visual coordinate view.
- [ ] Import coordinates copied from Minecraft commands or debug output.
- [ ] Preview shared versus selected-player presentation.
- [ ] Preview entity name, type, skin, equipment, age, and pose.
- [ ] Show every quest, dialogue, behavior, and script referencing an NPC.
- [ ] Warn when an anchor is too far from likely activation areas.
- [ ] Overlay the shared actor, private projections, logical anchors, current navigation,
      viewer range, and projection-limit reason from live state.

### Dialogue editor

- [ ] Add a node graph for dialogue flow and transfers.
- [ ] Allow dialogue nodes, choices, transitions, and script blocks to be created and
      connected through drag-and-drop interactions.
- [ ] Add ordered script editing within each dialogue node.
- [ ] Preview formatted messages, choices, placeholders, and delays.
- [ ] Simulate dialogue using mock flags, variables, quests, and memories.
- [ ] Detect unreachable nodes, dead ends, transfer loops, and missing destinations.
- [ ] Provide localization keys and translation previews in a later iteration.
- [ ] Show active live conversations, current node, eligible choices, wait deadline,
      distance, and cancellation reason for an authorized selected player.

### Quest editor

- [ ] Add a phase graph with branches and completion paths.
- [ ] Allow phases, objectives, branches, and lifecycle scripts to be arranged through
      drag-and-drop interactions.
- [ ] Add forms for every built-in objective type.
- [ ] Edit start, progress, completion, failure, and reset scripts.
- [ ] Preview requirements, timers, optional/hidden objectives, and repeat settings.
- [ ] Detect unreachable phases, impossible branches, and invalid objective references.
- [ ] Simulate quest progression and show resulting scripts/memory changes.
- [ ] Show live quest state per selected player, including phase, active objectives,
      progress, timers, requirements, completion history, and recent transitions.

### Script editor

- [ ] Add an ordered block editor for reusable and inline scripts.
- [ ] Allow script blocks and nested branches to be dragged between all compatible
      script containers without losing their typed options.
- [ ] Provide schema-driven forms for commands and conditions.
- [ ] Support nested `if`, `choice`, `random`, and success/failure scripts.
- [ ] Show placeholder availability for the current execution context.
- [ ] Find references and safely rename reusable scripts.
- [ ] Offer YAML source and visual block views without losing data between them.

### Extension-defined editor metadata

- [ ] Extend the Persona API so extensions can publish editor metadata and JSON Schema
      fragments for commands, conditions, objectives, behavior actions, behavior
      conditions, placeholders, and any future content type.
- [ ] Standardize optional schema annotations such as `x-persona-widget`,
      `x-persona-catalog`, `x-persona-reference-type`, and `x-persona-order` while
      retaining ordinary JSON Schema as the validation contract.
- [ ] Add typed Java contracts such as `EditorSchemaProvider` and
      `EditorCatalogProvider`; extension authors must not construct frontend components.
- [ ] Let every extension field declare its scalar/object/list type, label, help text,
      examples, default, required state, deprecation state, and validation constraints.
- [ ] Support constrained string inputs with a fixed `enum` so the editor renders a
      select box instead of unrestricted text.
- [ ] Support searchable large catalogs with stable IDs and optional display labels,
      descriptions, grouping, icons, and deprecation markers.
- [ ] Support dependent inputs where the valid values for one field depend on another
      field, such as channel, asset type, namespace, or selected NPC definition.
- [ ] Support conditional form sections using schema discriminators and `oneOf`/`anyOf`
      without requiring custom frontend code for each extension.
- [ ] Support numeric ranges, duration formats, regex patterns, minimum/maximum list
      sizes, mutually exclusive fields, and cross-field validation messages.
- [ ] Allow metadata to specify the preferred widget: searchable select, multi-select,
      radio group, checkbox, slider, duration input, color input, location/anchor picker,
      material/entity picker, script reference, or content-ID reference.
- [ ] Keep raw YAML available for every extension node even when no visual metadata is
      installed or the hosted editor is older than the extension.

### Live extension catalogs

- [ ] Allow extensions to register read-only catalog providers for values that are only
      known on the connected Minecraft server.
- [ ] Represent each catalog with a namespaced catalog ID, revision, value schema,
      permission requirement, cache policy, and optional dependency fields.
- [ ] Have Persona snapshot registered catalogs on the server thread and transmit signed
      immutable catalog data asynchronously through the trusted editor session.
- [ ] Never allow the hosted backend or browser to invoke arbitrary extension code;
      catalogs are requested through bounded typed protocol messages handled by Persona.
- [ ] Add pagination/search for large catalogs without sending an unbounded dataset on
      the Minecraft tick thread.
- [ ] Cache catalogs by installation, extension version, catalog revision, and dependency
      values, and invalidate them when the extension reports a change.
- [ ] Show whether a constrained value is live, cached, stale, unavailable, or no longer
      installed on the connected server.
- [ ] Preserve a previously valid ID in YAML when it disappears from a catalog, but mark
      it invalid/deprecated and prevent publication unless the extension permits it.
- [ ] Revalidate constrained values inside the Minecraft plugin during dry-run and
      publication; browser validation alone is never authoritative.
- [ ] Include extension and catalog versions in content validation and publish audit
      records so a changed catalog cannot silently alter a draft.

AssetChannel acceptance example:

- AssetChannel registers a catalog such as `assetchannel:assets` containing the fixed
  set of asset IDs available on that server.
- Its action/command schema marks the `asset-id` input as a reference to that catalog.
- The visual editor renders `asset-id` as a searchable picker with only valid IDs.
- Raw YAML still stores the stable ID, for example `asset-id: village:bell`.
- If `village:bell` is removed, the editor shows the existing value as unavailable and
  Persona rejects or warns on publish according to AssetChannel's declared policy.

### Live runtime observability

- [ ] Provide an online-player browser with UUID, current world, relevant active quests,
      and counts of active NPC runtimes; hide fields the session cannot view.
- [ ] Provide a logical-NPC browser grouped by definition and bound instance.
- [ ] Stream shared and per-player behavior ID, tree hash, status, running path,
      checkpoint, next wake time, deadlines, and bounded recent outcomes.
- [ ] Animate active behavior-tree nodes without sending an event for every evaluation;
      coalesce updates and enforce a configurable maximum refresh rate.
- [ ] Stream recent condition results with safe inputs and selector failure explanations.
- [ ] Stream bounded event inbox metadata, dropped-event counts, and the current event.
- [ ] Show shared/private presentation, selected anchor, logical position, visibility,
      Citizens actor ID, projection state, viewer distance, and suspension reason.
- [ ] Show navigation target, elapsed time, path status, completion, cancellation, and
      failure reason.
- [ ] Show typed player-NPC and global NPC memories with value, creation/update times,
      expiry, source, and scope when the session has memory permission.
- [ ] Stream quest phase, objective progress, timer deadlines, completion counts, and
      recent quest events for subscribed players.
- [ ] Show active dialogue, current node, current line/choice, wait state, NPC identity,
      and cancellation reason.
- [ ] Show server-wide behavior evaluations, tick time, wake queue, inbox drops,
      persistence queue, active projections, and projection limits.
- [ ] Use subscription filters and delta messages so the plugin sends only selected
      players/NPCs and changed fields.
- [ ] Apply backpressure and drop/coalesce low-priority trace updates before they can
      affect the Minecraft tick thread.
- [ ] Build immutable snapshots on the server thread, then serialize and transmit them
      asynchronously.
- [ ] Make live monitoring read-only by default and visibly indicate stale/disconnected
      data in the web interface.

### Safe live controls

- [ ] Allow an authorized session to pause, resume, restart, wake, or signal a selected
      behavior runtime.
- [ ] Allow authorized memory set, increment, expiry, and deletion with typed forms.
- [ ] Require a confirmation dialog showing player, NPC instance, scope, old value, new
      value, source, and expiry before a memory mutation.
- [ ] Require elevated capability plus in-game confirmation for destructive or broad
      operations.
- [ ] Never expose arbitrary server command execution through the editor protocol.
- [ ] Send live mutations as typed requests validated by the plugin, not as YAML or
      executable command strings.
- [ ] Return structured success/failure results and append them to the audit log.
- [ ] Add a configurable global switch that disables every live mutation while keeping
      read-only monitoring available.

### Validation, publishing, and rollback

- [ ] Upload editor changes as a patch against a specific signed content revision.
- [ ] Run complete server-side parsing and atomic validation without activation.
- [ ] Return structured file, line, node, and reference errors to the hosted editor.
- [ ] Show a semantic diff for behaviors, NPCs, quests, dialogues, and scripts.
- [ ] Require `/persona editor apply <one-time-code>` or an equivalent explicit trusted
      confirmation before the first publish from a browser session.
- [ ] Revalidate permissions, base revision, schemas, and all extension node types at
      publish time.
- [ ] Write a recoverable content backup before applying a published revision.
- [ ] Apply files and swap the runtime registry atomically; rollback files and runtime
      state if activation fails.
- [ ] Keep revision metadata, author/session identity, validation result, timestamp,
      semantic diff, and rollback pointer in the audit history.
- [ ] Add draft, review, publish, rollback, and optional approval workflows.
- [ ] Permit download/export without granting publish permission.
- [ ] Define retention and deletion policies for hosted snapshots, drafts, live traces,
      and audit metadata.

## P2: Authoring experience

- [ ] Expand `AUTHORING.md` with complete reference tables for every behavior node.
- [ ] Document status propagation and cancellation with diagrams.
- [ ] Add recipes for patrols, schedules, relationships, secrets, cutscenes, shops,
      companions, and shared world events.
- [ ] Add a minimal example, a complete example, and focused examples per node type.
- [ ] Add comments explaining every non-obvious field in packaged examples.
- [ ] Provide a command that copies an example into the live content directories.
- [ ] Add an upgrade/migration guide for each content-format release.
- [ ] Publish the JSON schemas as versioned build artifacts.

## P3: Performance and scale

- [ ] Build a deterministic simulation harness for at least 100 players, hundreds of
      logical NPC runtimes, and thousands of memories.
- [ ] Verify the 5,000-node and 4 ms budgets under sustained load.
- [ ] Measure worst-case reactive selectors, parallel trees, and event storms.
- [ ] Add adaptive scheduling for sleeping, distant, and recently active runtimes.
- [ ] Avoid scanning every Citizens NPC for discovery and proximity every interval.
- [ ] Index active runtimes spatially by world and chunk.
- [ ] Batch projection visibility updates and avoid redundant Bukkit calls.
- [ ] Add metrics for evaluations, wake latency, inbox drops, action duration, database
      queue depth, projections, suspensions, and reload time.
- [ ] Add configurable overload behavior with rate-limited operator warnings.
- [ ] Prove durable state is not lost when projection limits are reached.

## P3: Testing matrix

- [ ] Unit-test every node status transition and cancellation path.
- [ ] Property-test randomly generated valid trees against runtime invariants.
- [ ] Test asynchronous completion before, during, and after reload/cancellation.
- [ ] Test all memory types, atomic increments, expiry races, and database sweeps.
- [ ] Test checkpoint recovery for wait, cooldown, navigation, subtree, and parallel.
- [ ] Test changed tree hashes, removed nodes, moved checkpoints, and renamed behaviors.
- [ ] Integration-test Citizens projection creation, routing, navigation, and cleanup.
- [ ] Test player join, quit, death, teleport, world change, and server shutdown.
- [ ] Test all packaged YAML, schemas, README commands, and editor fixtures in CI.
- [ ] Test supported Paper, Citizens, Java, and SQLite upgrade combinations.
- [ ] Add long-running soak tests and forced-shutdown recovery tests.

## P4: Advanced story and combat features

- [ ] Formalize basic combat conditions/actions without turning Persona into a combat
      engine.
- [ ] Add target acquisition, flee, defend, assist, and disengage extension points.
- [ ] Add schedules based on world time and configurable calendars.
- [ ] Add perception inputs such as line of sight, sound, and nearby entity type.
- [ ] Add NPC-to-NPC signals before attempting persistent social graphs.
- [ ] Evaluate rumor propagation and relationship graphs as separate optional modules.
- [ ] Keep free-text/LLM memory, transcripts, formations, and advanced companion tactics
      out of core until deterministic authoring and performance are proven.

## Release gates

### Behavior runtime stable

- [ ] P0 correctness, validation, persistence, and event tasks are complete.
- [ ] Existing dialogue/quest/script content remains compatible.
- [ ] Restart, reload, and 100-player simulations pass without state loss.

### Visual editor MVP

- [ ] Behaviors, NPCs, dialogues, quests, and scripts can be imported, edited,
      validated, and exported.
- [ ] Every editor supports both drag-and-drop visual editing and raw YAML editing with
      lossless round trips between them.
- [ ] Fixed and server-provided extension inputs render as constrained pickers and are
      authoritatively revalidated by the connected Persona server.
- [ ] Round-tripping preserves meaning and extension-owned data.
- [ ] Every editor-generated sample loads successfully in Persona.
- [ ] No live publishing or server mutation is required for the MVP.

### Production-ready authoring platform

- [ ] Runtime metrics and diagnostics identify performance and content problems.
- [ ] Staged publishing has authentication, authorization, rollback, and audit logs.
- [ ] Documentation, schemas, examples, extension API, and editor use the same versioned
      compatibility contract.
