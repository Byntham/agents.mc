# agents.mc

A Fabric mod for Minecraft Java Edition 26.3 that lets AI agents join your world as players and build with you.

Each agent gets a real player body: a name tag, a tab-list entry, an inventory, and survival physics. Humans see it as another player. There are two ways to run agents:

- **From inside the game.** `/agent spawn Builder build me a house` starts an agent that runs on your own Claude Code login. You talk to it in chat with `@Builder ...`, and press K for a menu that shows what each agent is doing.
- **From any MCP client.** The mod runs an MCP server on `http://127.0.0.1:25590/mcp`, so Claude Code or another client outside the game can drive bodies.

Agents control their bodies through MCP tools either way. They see the world mostly as structured text:

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

They can also take [screenshots](#screenshots) to see how a build looks.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the modding background, the design choices, and the roadmap.

## Tools

| | |
|---|---|
| Bodies | `spawn_agent`, `despawn_agent`, `list_agents`, `teleport`, `look_at`, `say`, `run_command` |
| Perception | `observe` (status + map + entities + new events in one call), `find_player` (where someone is now and what they're looking at), `get_status`, `get_blocks`, `get_surface`, `get_entities`, `find_blocks`, `inspect_block`, `get_events`, `screenshot` |
| Building | `set_blocks`, `fill`, `build_layers` (same grid format as `get_blocks`), `undo` |

## Screenshots

`screenshot` returns a 1280×720 picture of what the agent sees from its eyes. `from_x`, `from_y` and `from_z` put the camera somewhere else, such as above a build, and `at_x`, `at_y` and `at_z` or `at_player` aim it.

- The host's game draws the picture, so screenshots work in single-player and Open to LAN, but not on a dedicated server.
- The camera must be within the host's render distance, in the dimension the host is in.
- Each picture takes a few hidden frames on the host's game, usually under half a second.
- A picture costs the model about 1,200 input tokens.

## Agents in the game

You need [Claude Code](https://code.claude.com) installed and logged in on the computer that hosts the world. Run `claude` once in a terminal to log in. Each agent is a `claude` process the mod starts in the background.

| | |
|---|---|
| `/agent spawn <name> [task]` | Create an agent in front of you, optionally with a first task |
| `@Name message` in chat | Talk to an agent. A message sent while it's working changes what it's doing. |
| K | Open the agents menu: every agent, what it's doing now, its history, and a message box. The + button creates an agent. The buttons under the message box pick the agent's model and reasoning effort. |
| `/agent stop <name>` | Stop what it's doing |
| `/agent remove <name>` | Remove the agent and its body |
| `/agent list` | Show every agent and its status |

Agents answer in chat with short messages. Everything else they do, including tool calls and notes, shows in the menu.

![The agents menu: Builder is working, and a message sent mid-build changes the roof](https://gist.githubusercontent.com/Byntham/acb3c3004a05ccea4a0c33513d9d00bf/raw/104fd1602a0932931682e1aa68a8b8b53231f580/agents-menu.png)

What else to know:

- **Usage.** Agents use your Claude plan's usage, the same as running `claude -p`.
- **Who can use them.** Agents run on the host's account, so only the single-player host and operators can create or talk to them. Set `whoCanUseAgents` to `everyone` to change that.
- **What agents can do.** An agent gets its own body's tools plus web search and web fetch. It has no shell or file tools, so a player who talks it into something can't read or change files on your computer or run programs. Web fetch does run from your computer, so it can load pages on your local network. Agents don't load your `CLAUDE.md`, settings, hooks, plugins or other MCP servers.
- **Commands.** Agents can run Minecraft commands at gamemaster level, like an operator. When `whoCanUseAgents` is `everyone`, they can't, since then any player could use an agent to run commands. `allowCommands: false` turns commands off for every agent.
- **Saving.** Agents are saved with the world. When the world reopens, their bodies come back idle where they were, and the next message continues the same conversation.
- **Idle agents.** An agent's `claude` process closes after 10 minutes without activity, and starts again on the next message.

## Play with it

1. Install **Fabric Loader** for 26.3 with the installer from <https://fabricmc.net/use/installer/>. It adds a "fabric-loader-26.3" profile to the launcher.
2. Copy these into `%APPDATA%\.minecraft\mods\`:
   - `build/libs/agentsmc-0.1.0.jar`
   - the Fabric API jar for 26.3 ([Modrinth](https://modrinth.com/mod/fabric-api/versions))
3. Launch the Fabric profile and open a world. The log will show `Agents MC: MCP server listening on http://127.0.0.1:25590/mcp`.
4. Create an agent with `/agent spawn Builder` or the K menu (see above). Or point an outside agent at the MCP server:
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

Settings live in `config/agentsmc.json`:

| Setting | Default | |
|---|---|---|
| `host`, `port` | `127.0.0.1`, `25590` | Where the MCP server listens |
| `token` | empty | If set, MCP clients must send `Authorization: Bearer <token>` |
| `allowCommands` | `true` | Whether agents may use `run_command` |
| `maxBuildBlocks` | `32768` | Most blocks one build call may change |
| `claudePath` | empty | Path to `claude`. Empty means search PATH and `~/.local/bin`. |
| `model` | empty | Model a new in-game agent starts with, such as `opus`, `sonnet` or `haiku`. Empty means Claude Code's default. Each agent's model can be changed in the menu. A full model id here is offered in the menu too. |
| `effort` | empty | Reasoning effort a new agent starts with: `low`, `medium`, `high`, `xhigh` or `max`. Empty means Claude Code's default. |
| `whoCanUseAgents` | `ops` | `ops` (operators and the single-player host) or `everyone` |

`./gradlew runClientGameTest` opens a real client, drives the agents menu with a fake `claude`, and saves screenshots to `build/run/clientGameTest/screenshots`. With `AGENTSMC_LIVE=1` set, it also runs an agent with the real Claude Code CLI (model `haiku` unless `AGENTSMC_LIVE_MODEL` says otherwise). That run switches the agent to Sonnet in the menu, closes and reopens the world, switches it back to Haiku, and checks that the agent picks up its conversation and that each turn runs on the chosen model.
