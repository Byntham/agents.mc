# agents.mc

A Fabric mod for Minecraft Java Edition 26.3 that lets AI agents join your world as players: they walk, follow you, mine, fight and build with you.

Each agent gets a real player body: a name tag, a tab-list entry, an inventory, and survival physics. Humans see it as another player. There are two ways to run agents:

- **From inside the game.** `/agent spawn Builder build me a house` starts an agent that runs on your own Claude Code login. You talk to it in chat with `@Builder ...`, and press K for a menu that shows what each agent is doing.
- **From any MCP client.** The mod runs an MCP server on `http://127.0.0.1:25590/mcp`, so Claude Code or another client outside the game can drive bodies.

Agents control their bodies through MCP tools either way. Long actions (walking somewhere, following you, mining, fighting) run on the body tick by tick while the model waits for something to happen. They see the world mostly as structured text:

- palette grids of exact blocks;
- top-down height/terrain maps;
- entity lists;
- block search;
- a feed of chat and block changes made by others;
- their own senses: a mob closing in, a sound and where it came from, damage and what dealt it, a player walking up, dusk.

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
| Playing | `goto`, `follow`, `mine`, `attack`, `collect` (intents the body carries out on its own), `cancel`, `wait` (block until something happens), `set_reflexes`, `hold`, `eat`, `drop`, `place_block`, `use_block`, `recipe`, `plan_craft` (what's missing and the steps), `craft` (2x2 grid or a crafting table, placed if needed), `smelt`, `container` (look, take, put) |
| Perception | `observe` (status + map + entities + new events in one call), `sweep` (what is in each direction), `find_player` (where someone is now and what they're looking at), `get_status`, `get_blocks`, `get_surface`, `get_entities`, `find_blocks`, `inspect_block`, `get_events`, `screenshot` |
| Building | `set_blocks`, `fill`, `build_layers` (same grid format as `get_blocks`), `undo` |

## Playing in real time

A model takes seconds per decision and a Minecraft tick is 50 ms, so the body does the moment-to-moment work itself:

- **Intents.** `goto`, `follow`, `mine`, `attack` and `collect` return at once with an id like `goto#3`. The body walks the path (with jumps, drops, swimming and doors), swings the right tool for the block's break time, hits when the attack cooldown allows, and picks up drops. When the intent ends, the agent gets an event: `goto#3 done: arrived at 100 64 -20 after 12.4s`.
- **Reflexes.** Without asking the model, the body eats when food is low, hits back at a mob that hit it, and backs away from a creeper about to explode. Each reflex says what it did as an event. `set_reflexes` turns them off.
- **The meanwhile block.** Every tool result ends with one status line (position, facing, footing, health, food, time, light, what is in hand, what the body is doing) and the events since the agent last looked. The model never acts on stale state.
- **`wait`.** While the body works, the model calls `wait`. It returns as soon as something notable happens (an intent finishes, damage, a mob closing in, a player talking) or when the time runs out. It costs nothing while waiting.
- **Senses.** Events are written in the second person with distance, compass direction and where that is relative to the agent's facing: `You heard a creeper hissing, 2m E (ahead).`, `Steve is 20m NE (ahead-left).`, `You took 3 damage from the zombie 2m SW (behind). Health 14/20.` They cover hearing (the server's sounds), damage, hostile mobs appearing and closing in, players walking up or out of range, hunger, light, time of day, lava and drowning.
- **Waking up.** An idle agent whose body senses something important (damage, a mob coming for it, a task finishing, sunset) gets a message from `World` and decides what to do, at most once every 20 seconds. `wakeOnEvents: false` turns this off.

Survival bodies (`spawn_agent` with `gamemode: survival`, or `/gamemode survival Name`) mine with real tools and place blocks from their inventory with `place_block`. The instant build tools stay creative-style.

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
| K | Open the agents menu: every agent, what it's doing now, its history, and a message box. The + button creates an agent. |
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
| `model` | empty | Model for in-game agents, such as `opus`, `sonnet` or `haiku`. Empty means Claude Code's default. |
| `effort` | empty | `low`, `medium`, `high`, `xhigh` or `max`. Empty means Claude Code's default. |
| `whoCanUseAgents` | `ops` | `ops` (operators and the single-player host) or `everyone` |

`./gradlew runClientGameTest` opens a real client, drives the agents menu with a fake `claude`, and saves screenshots to `build/run/clientGameTest/screenshots`. With `AGENTSMC_LIVE=1` set, it also runs an agent with the real Claude Code CLI (model `haiku` unless `AGENTSMC_LIVE_MODEL` says otherwise), closes and reopens the world, and checks that the agent picks up its conversation.
