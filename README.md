# agents.mc

A Fabric mod for Minecraft Java Edition 26.3 that lets AI agents join your world as players and build with you.

Each agent gets a real player body: a name tag, a tab-list entry, an inventory, and survival physics. Humans see it as another player. Agents control their bodies through an MCP server the mod runs on `http://127.0.0.1:25590/mcp`. Instead of screenshots, they see the world as structured text:

- palette grids of exact blocks;
- top-down height/terrain maps;
- entity lists;
- block search;
- a feed of chat and block changes made by others.

```
y=119:
        23456
  z=-8  ooooo        palette:  c = cobblestone
  z=-7  o...o                  o = oak_planks
  z=-6  o...g                  g = glass
  z=-5  o...o                  . = air
  z=-4  oo.oo
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the modding background, the design choices, and the roadmap.

## Tools

| | |
|---|---|
| Bodies | `spawn_agent`, `despawn_agent`, `list_agents`, `teleport`, `look_at`, `say`, `run_command` |
| Perception | `observe` (status + map + entities + new events in one call), `find_player` (where someone is now and what they're looking at), `get_status`, `get_blocks`, `get_surface`, `get_entities`, `find_blocks`, `inspect_block`, `get_events` |
| Building | `set_blocks`, `fill`, `build_layers` (same grid format as `get_blocks`), `undo` |

## Play with it

1. Install **Fabric Loader** for 26.3 with the installer from <https://fabricmc.net/use/installer/>. It adds a "fabric-loader-26.3" profile to the launcher.
2. Copy these into `%APPDATA%\.minecraft\mods\`:
   - `build/libs/agentsmc-0.1.0.jar`
   - the Fabric API jar for 26.3 ([Modrinth](https://modrinth.com/mod/fabric-api/versions))
3. Launch the Fabric profile and open a world. The log will show `Agents MC: MCP server listening on http://127.0.0.1:25590/mcp`.
4. Point an agent at it.
   - Claude Code in this repo picks it up from `.mcp.json`; approve it the first time.
   - Elsewhere, run `claude mcp add --transport http minecraft http://127.0.0.1:25590/mcp`.
   - Then try: *"Spawn an agent called Builder next to me and build a small house."*

Pausing a single-player game (Esc menu) also pauses the server, and with it the agents. Open the world to LAN to keep the world ticking while you're in menus.

The mod only adds server-side logic, so on a dedicated Fabric server vanilla clients should be able to join without it. This hasn't been tested yet.

## Develop

Requires JDK 25. Gradle and Minecraft are downloaded automatically.

```sh
export JAVA_HOME=~/.jdks/jdk-25.0.4.1+1
./gradlew build                      # -> build/libs/agentsmc-0.1.0.jar
./gradlew runServer --args=nogui     # headless test server with the mod (run/server, port 25566)
./gradlew runClient                  # dev client with the mod
```

Test without a client by calling tools with curl:

```sh
curl -s localhost:25590/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"spawn_agent","arguments":{"name":"Builder"}}}'
```

Settings live in `config/agentsmc.json`: host, port, optional bearer token, `allowCommands`, `maxBuildBlocks`.
