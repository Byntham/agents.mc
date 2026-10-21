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

**Mixins.** A mixin is a class annotated with `@Mixin(Target.class)` whose methods are injected into `Target` at load time. They are listed in `agentsmc.mixins.json`. This mod uses two:

- `ConnectionAccessor`: sets the private `channel` field on a `Connection`.
- `BlockItemMixin`: reports blocks placed by humans to the event log, because Fabric API has no "after place" event.

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
| **Screenshots / vision** | An LLM cannot reliably count blocks, judge depth, or tell which block is behind another from pixels. Fine as a supplement, not as the primary channel. |
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

## Roadmap

Rough order:

1. **Survival-mode play:** walking with A* pathfinding over the block grid; mining with tool and break times; placing from inventory; crafting, smelting and containers.
2. **Push instead of poll:** a `wait_for_events` long-poll tool (or a `subscriptions/listen` stream) so agents can idle until someone talks to them.
3. **Structure I/O:** read and write vanilla `.nbt` structure files and schematics, and copy/paste regions between agents.
4. **Skins:** fetch textures for a chosen skin so agents look distinct.
5. **Per-agent permissions:** build regions, a protected area around humans, command allow-lists.
6. **Optional client companion:** screenshots from an agent's point of view for visual checks of builds.
