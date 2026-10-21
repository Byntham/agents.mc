# Architecture

How Agents MC is put together, and why. Written October 2026 against Minecraft Java Edition 26.3.

## Minecraft modding basics (as of 26.x)

**Edition.** This is a Java Edition mod. Bedrock Edition has a separate add-on and scripting system and is not covered here.

**Loaders.** A mod loader patches the game at startup and loads mod jars from `.minecraft/mods`. The two active loaders are:

- **Fabric**: lightweight. Changes to the game go through *mixins* (bytecode injection into Minecraft classes) plus the optional **Fabric API** library of events and hooks. New Minecraft versions are usually supported on release day.
- **NeoForge**: heavier, with more built-in APIs. It reached 26.3 later, and its 26.3 builds were in beta for weeks.

This project uses Fabric: Loader 0.19.5, Fabric API 0.163.0+26.3, Loom 1.18. Versions are pinned in `gradle.properties`, and current values are listed at <https://fabricmc.net/develop>.

**26.x is unobfuscated.** From 26.1 on, Mojang ships the game with real class and method names (`ServerPlayer`, `PlayerList.placeNewPlayer`, ...), so there is no mappings step or remapping. Two consequences:

- The Loom plugin id is `net.fabricmc.fabric-loom`, dependencies use plain `implementation`, and Java 25 is required.
- You can read the game's real API straight from the installed jar:

  ```sh
  javap -cp ~/AppData/Roaming/.minecraft/versions/26.3/26.3.jar net.minecraft.server.level.ServerPlayer
  ```

  Most of this mod was written that way. In an IDE, `./gradlew genSources` gives decompiled sources.

**Client vs server.** Even single-player runs a full *integrated server* inside the client process. World state is authoritative only on the server, so a mod that registers its logic on server start works in all three settings: single-player, Open to LAN, and a dedicated server. The client only renders.

**Mixins.** A mixin is a class annotated with `@Mixin(Target.class)` whose methods are injected into `Target` at load time. Server-side mixins are listed in `agentsmc.mixins.json`, including:

- `ConnectionAccessor`: sets the private `channel` field on a `Connection`.
- `BlockItemMixin`: reports blocks placed by humans to the event log, because Fabric API has no "after place" event.

Client-side mixins in `agentsmc.client.mixins.json` let the host's game draw agents' screenshots (see [Screenshots](#screenshots)).

**Dev loop.**

| Task | Command |
|---|---|
| Build `build/libs/agentsmc-<ver>.jar` | `./gradlew build` |
| Headless dedicated server with the mod (`run/server`) | `./gradlew runServer --args=nogui` |
| Dev game client with the mod (offline account) | `./gradlew runClient` |

## How agents should see the world

Options considered:

| Approach | Verdict |
|---|---|
| **Screenshots / vision** | An LLM cannot reliably count blocks, judge depth, or tell which block is behind another from pixels. Fine as a supplement, not as the primary channel. Agents get it as the `screenshot` tool (see [Screenshots](#screenshots)). |
| **Protocol bot** (Mineflayer etc.) connecting as a separate client | The usual approach (Voyager, Mindcraft). It depends on a third party reimplementing the network protocol for every version. As of Oct 2026, Mineflayer does not support 26.2 or 26.3, and vanilla 26.3 kicks it after about 1.5 s. Agent perception would also be limited to what a client is sent. |
| **Client mod driving *your* player** (e.g. dgurney/minecraft-mcp) | Useful for "AI, operate my game", but the agent shares your body. That is the opposite of playing *with* you. |
| **Server-side mod with player-shaped agent bodies + MCP** | **Chosen.** No protocol reimplementation and no version lag. Reads the authoritative world directly, with no render-distance limits. Each agent is a real `ServerPlayer` that humans see as a player. Any MCP client can drive one. |

### Agent bodies

`AgentPlayer extends ServerPlayer` and is attached to a `FakeConnection`: a `Connection` whose outbound packets are dropped and whose channel is an inert Netty `EmbeddedChannel`. Spawning follows vanilla's own login path (`PrepareSpawnTask`):

1. Load saved player data from `world/playerdata/<uuid>.dat`, if any.
2. `PlayerList.placeNewPlayer`.
3. Mark the client as loaded. Until a client says it has loaded, vanilla keeps the player frozen and invulnerable.

The result is a player with a name tag and a tab-list entry that is saved with the world and takes damage. Its inventory and stats persist across sessions under the same name. UUIDs are the offline UUIDs for the name, so skins are the default Steve/Alex.

Two adjustments replace work a real client would do:

- **Physics.** `tick()` also calls `doTick()`. For real players, movement physics is driven from the client's packets.
- **Authority.** `isClientAuthoritative()` returns false. Vanilla skips server-side fall damage and similar checks for players, because their client owns their movement. An agent has no client.

### Perception: text designed for language models

Every view uses the same conventions:

- Absolute block coordinates.
- +x is east, +z is south, +y is up.
- Grids print rows north to south (z ascending, each row labelled with its z) and columns west to east (x ascending, with a last-digit ruler above).
- Blocks are written as `id[prop=value,...]` without the `minecraft:` prefix.

**Palette and layer grids (`get_blocks`).** One character per block. A per-response palette maps characters to block states, using mnemonic letters where possible: `.` is always air and `~` is always water. The region prints as one grid per y level. All-air layers and runs of identical layers are collapsed to one line, so a 16×16×16 box of mostly terrain costs a few hundred tokens rather than 4,096 JSON objects.

Block properties have three detail levels:

- `auto` (default) always prints direction properties (`facing`, `axis`, `half`, `rotation`, `type`, `hinge`, `face`, `orientation`), even at their default value. A door reads `oak_door[facing=north,half=lower,hinge=left]`, not a bare `oak_door` that looks like it has no direction. Other properties appear only when they differ from the default, and leaf `distance` is dropped. Every palette string is valid build input.
- `full` prints every property.
- `none` prints ids only.

**Read/write symmetry.** `build_layers` takes exactly this layered format (origin, palette, layers of rows). An agent can read a region, edit the grid, and write it back.

**Matching what the agent asked for.** When an agent writes `oak_fence`, it doesn't care which way the fence connects. The parser records which properties the agent actually wrote (`BlockCodec.Spec`). A block matches a request if it is the same block type and has every property the agent named. Two features use this rule:

- Every build checks each requested position afterwards. It reports the positions that didn't end up as asked, such as a torch that dropped because nothing held it up. Fence connections and stair shapes that the game adjusted don't count. Usually the agent doesn't need to read its build back.
- `get_blocks` accepts the agent's build palette. Matching blocks print with the agent's own characters, so the read-back lines up with what it wrote.

**Top-down surface map (`get_surface`, and inside `observe`).** For each column:

- the top block, from the `MOTION_BLOCKING_NO_LEAVES` heightmap, which skips plants and leaves, so the map shows the ground under trees (trunks still show). `include_leaves` switches to the canopy view;
- its height, as a numeric grid with x column headers.

The observing agent is marked `@` and other players `*`. This is the "map" view for terrain, finding flat ground, and planning.

**Targeted queries.**

- `find_blocks` scans chunk sections, skipping all-air sections and sections whose palette can't contain the target. It accepts ids, `#tags` and exact states.
- `get_entities` returns mobs, players and dropped items, nearest first.
- `inspect_block` shows full state, light level, and container contents or other block-entity data.

**Time and other players (`get_events`).** Polling blocks can't show what changed or what people said. A ring buffer records:

- chat, joins and leaves;
- blocks placed or broken by humans;
- builds and commands by agents.

Each agent reads it through its own cursor, so it sees each event once and never its own actions. This is how humans talk to agents ("hey Builder, put a door here").

**`observe`** combines status, the surface map, nearby entities and new events into one call: the default "look around" action.

**Players as targets.** Humans move constantly, so any position an agent saw is probably stale by the time it acts. Instead of copying coordinates, tools take a player name and resolve it at call time:

- `spawn_agent` and `teleport` take `near_player`. The body goes a couple of blocks in front of the player, facing them, and steps up out of terrain if that spot is inside a hill.
- `look_at` takes `player`.
- `get_surface`, `get_entities` and `find_blocks` take `around_player`.
- `find_player` reports where someone is right now, with distance and compass direction from the agent. It also gives the block they're looking at and where a block placed against it would go, so a human can point at a spot and say "build here".

Tools that list positions (`list_agents`, `observe`) say in their descriptions that player positions are a snapshot.

### Screenshots

Text views are exact, but they don't show how a build looks. `screenshot` returns a 1280×720 JPEG as an MCP image block, which costs the model about 1,200 input tokens. By default the camera is at the agent's eyes, facing where it faces. `from_x/y/z` moves the camera and `at_x/y/z` or `at_player` aims it, so an agent can look at a build from above without moving its body.

Only a game client can render. The client part of the mod installs a camera (`perception/Screenshots`) that the server-side tool calls, so the picture comes from the host's own game. That works in single-player and Open to LAN. A dedicated server has no host game, and the tool says so. The client only has the chunks around the host, so the camera must be in the host's dimension and within their render distance.

How a shot is drawn (`client/AgentCamera`):

1. On the server thread, the tool works out the camera position and angle from the agent's server-side position, and queues the shot for the client.
2. Before the host's next frame, the client draws an extra frame from that camera. This follows vanilla's panorama screenshot: resize the main render target, run `GameRenderer.update`, `extract` and `renderLevel`, then read the pixels back. The host's own frame then draws over the target, so the extra frame never reaches the screen.
3. Vanilla only builds meshes for terrain sections in the host's view, so a shot of something behind the host would show bare ground. The client keeps drawing extra frames, which queue those sections for building, until no section has been waiting to be built for 150 ms. It stops after 3 s regardless. Most shots take a few frames.
4. A background thread encodes the pixels as JPEG for the tool.

The renderer keeps state tied to the host's camera. Client mixins hold that state still during an extra frame:

| Mixin | What it does during an extra frame |
|---|---|
| `CameraMixin` | Sets the shot's position and angle after the camera follows the host, with a fixed 70° field of view. |
| `SectionOcclusionGraphMixin` | Vanilla rebuilds the graph of visible sections in the background when the camera moves, which would arrive a frame late. The extra frame builds one for its own camera on the render thread and puts the host's back afterwards. It doesn't wait on the background pool, because terrain builders there can spin until the render thread uploads their meshes. |
| `LevelExtractorMixin` | Builds that graph before the frame is extracted. Hides the agent's own body when the camera is at its eyes. |
| `LevelRendererMixin`, `SectionUpdateTrackerMixin` | Keep the section grid centred on the host. Recentring on the shot's camera would drop sections at the far edge, and the host would rebuild them next frame. Also skips re-sorting glass and water for the shot's camera. |
| `RenderSectionMixin` | Draws newly built sections without the fade-in. |
| `GameRendererMixin` | Skips the host's hand, fire and underwater overlays and crosshair. |
| `GameRendererAccessor` | Does what a full frame does and `renderLevel` alone doesn't: sets the uniform that holds the camera position, draws the lightmap, and moves the fog and cloud uniforms to their next buffers so the host's frame doesn't overwrite them. |
| `MinecraftMixin` | Runs the extra frames before the host's frame. |

`extract` also copies the host's view bobbing and hurt tilt into the frame, so the extra frame turns those off before drawing.

### Actions

- **Movement:** `teleport` (to coordinates or `near_player`), `look_at` (a point or a `player`). Pathfinding and walking are on the roadmap.
- **Chat:** `say`.
- **Commands:** `run_command`, executed as the agent at gamemaster level, so the full vanilla command set (`/give`, `/clone`, `/summon`...) is available. Owner-level commands like `/stop` are not.
- **Building:** `set_blocks`, `fill` (solid/hollow/outline, optional replace filter), `build_layers`.
  - Each build places blocks first, then recomputes neighbour shapes so fences, panes, stairs and walls connect as if placed by hand, then sends normal block updates.
  - Each build records the previous states, so `undo` reverts it (32 levels per agent).
  - Builds are currently creative-style: instant, with no inventory cost.

### Transport: MCP over Streamable HTTP

`McpServer` is a small dependency-free JSON-RPC server on the JDK's built-in `HttpServer`, at `http://127.0.0.1:25590/mcp`. It is **dual-era**:

- Modern (2026-07-28) clients send per-request `_meta` and may call `server/discover`.
- Legacy (2025-11-25 and earlier) clients call `initialize`.

It keeps no sessions. Responses are plain JSON; it offers no SSE stream. It binds to loopback, rejects non-local `Origin` headers (DNS-rebinding protection), and supports an optional bearer token (`config/agentsmc.json`).

**Threading.** HTTP handlers never touch the world. Each tool call is marshalled onto the server thread with `OnServer.call`, a `CompletableFuture` on the server's executor with a 10-second timeout. Results are rendered there and returned as text.

## Agents run by the mod

Players can also start agents from inside the game. Each one is a Claude Code process on the host's computer, using the host's own `claude` login.

### Why the CLI and not the Agent SDK

The Agent SDK only exists for TypeScript and Python. Both start the same `claude` binary and talk to it over stdin and stdout. The mod is Java, so it starts `claude` itself and speaks the same protocol:

- `claude -p --input-format stream-json --output-format stream-json --verbose`, one process per agent;
- one JSON user message per line on stdin, and assistant, tool and result events on stdout.

This needs only the CLI the user already has. It gives up the SDK's typed API and its bundled, version-matched binary. The one SDK-only feature the mod uses is the interrupt control message. The mod sends it only when the CLI's `system/init` event lists the `interrupt_receipt_v1` capability. Otherwise it falls back to a `"now"` priority message, which also interrupts the turn.

### One process per agent (`brain/AgentBrain`)

- **Lifecycle.** The process starts on the agent's first message and stays up between messages. After 10 minutes idle it is closed. The next message starts it again with `--resume <session id>`.
- **Steering.** A message that arrives mid-turn is written with `"priority": "next"`. Claude reads it as soon as the running tool calls finish, in the same turn.
- **Threads.** Starting, writing and closing the process happen on the agent's own I/O thread, and a second thread reads its output. The server thread doesn't wait on Claude, except when the world closes: it then gives the processes a few seconds to exit.
- **Failures.** A missing `claude`, a crash or an error result is logged in the menu and sent to the player who last talked to the agent. If a saved session no longer exists (the CLI answers "No conversation found"), the agent starts a new conversation and re-sends what the old one never read.

### What an agent can reach

Anyone who can talk to an agent can try to talk it into something, so the process is locked down:

| Flag | Effect |
|---|---|
| `--tools WebSearch,WebFetch` | The only built-in tools. No shell, no file access, no subagents. |
| `--allowedTools mcp__minecraft,WebSearch,WebFetch` and `--permission-prompts none` | Those run without prompts. Anything else is denied, since nobody is there to answer a prompt. |
| `--restricted` | Ignores the user's settings files and `CLAUDE.md`, so their hooks, plugins and personal instructions don't apply. |
| `--strict-mcp-config --mcp-config <file>` | The only MCP server is the agent's own body. |
| `--disable-slash-commands` | No skills or commands. |
| `--system-prompt-file` | Replaces Claude Code's coding prompt with one about playing in the world (`brain/Prompts`). |

`--bare` would isolate the session more simply, but it never reads OAuth credentials, so it can't use a subscription login.

### Binding a process to its body

The MCP server has a second endpoint, `/agent/<key>/mcp`. The mod issues a random key each time it starts a process and writes it into that process's MCP config. On this endpoint:

- every tool call gets `agent` set to the key's agent;
- tool schemas don't show the `agent` argument;
- `spawn_agent` and `despawn_agent` are hidden.

So an agent's tool calls act as its own body, and it can't spawn or remove bodies. This keeps agents from mixing each other up. It is not a security boundary: `run_command` runs at gamemaster level and can reach any entity, the same as an operator's commands. That is acceptable because only operators can direct agents (see below). When `whoCanUseAgents` is `everyone`, the mod's agents lose `run_command`. Set `allowCommands` to false to take commands away from every agent. The key is revoked when the process exits.

### Talking to agents

- **Chat.** A chat message that @mentions an agent (`@Builder ...`) goes to that agent as `<player> (in chat): ...`. Messages agents send with `say` are system messages, which don't fire chat events, so agents can't trigger each other in a loop.
- **The menu (`client/AgentsScreen`).** While the menu is open, the client asks for the state twice a second (`menu_request`). The server answers with a JSON snapshot (`menu_state`): every agent's status and current activity, plus the selected agent's activity log. Sending, stopping and creating agents go through `menu_action`. All packets are in `net/MenuPayloads`.
- **The log.** `ActivityLog` turns the stream into entries the menu shows: player messages, the model's own text, one line per tool call, `say` messages, errors, and "Finished in 12s".

### Who can use them

Agents spend the host's Claude usage and can run operator commands. Commands, chat routing and menu actions check `Brains.canUse`: the player must be an operator or the single-player host, unless `whoCanUseAgents` is `everyone`. The host check matters because in single-player with cheats off the host isn't an operator.

Agents themselves always fail the check. For commands, the check looks at where a command came from (the player who typed it, or the console), not at the entity it runs as. So an agent can't start more agents with `/agent`, whether through `execute as` or a command block it placed. The menu sends agent logs only to players who pass it; others see the list of agents and their status.

Agents also read chat through `observe` and `get_events`. Chat from players who fail that check is marked `restricted` in the event log, and the mod's agents don't see it, so it can't reach them as instructions that way either.

### Saving (`<world>/agentsmc/`)

- `agents.json` lists each agent's name, session id, dimension, position and game mode. It is written when agents change, every 5 minutes, and when the world closes.
- `agents/<name>/` is the process's working directory. It holds the generated system prompt and MCP config, and `log.json`, the menu history.

When the world opens, every saved agent's body comes back where it was, idle. Its process starts on the next message and resumes the conversation.

### Testing

`src/gametest` holds a Fabric client game test. It opens a real client, creates agents through the menu, and saves screenshots. It uses `fake_claude.py`, which speaks the stream-json protocol, so the menu can be checked without a model. With `AGENTSMC_LIVE=1` it also runs the real CLI end to end: build, close the world, reopen it, and continue the conversation.

`ScreenshotTest` calls the screenshot tool over MCP from several cameras, including one facing away from the host. It checks each picture for the blocks it placed there and saves it as `agent-*.jpg` next to the other screenshots.

## Roadmap

Rough order:

1. **Survival-mode play:** walking with A* pathfinding over the block grid; mining with tool and break times; placing from inventory; crafting, smelting and containers.
2. **Push instead of poll for outside clients:** agents the mod runs already get messages pushed to them. Outside MCP clients still poll; a `wait_for_events` long-poll tool (or a `subscriptions/listen` stream) would let them idle until someone talks to them.
3. **Structure I/O:** read and write vanilla `.nbt` structure files and schematics, and copy/paste regions between agents.
4. **Skins:** fetch textures for a chosen skin so agents look distinct.
5. **Per-agent permissions:** build regions, a protected area around humans, command allow-lists.
6. **Screenshots on dedicated servers:** ask a connected player's game, if it has the mod, to draw the shot.
