# Real-time play: how an agent could perceive and act like a player

Research notes, October 2026. Four parallel investigations fed this: prior art on LLM Minecraft agents, a code-level feasibility check against the 26.3 jar, a survey of perception formats that language models read well, and a design of the control loop around the Claude Code stream-json protocol. Sources are listed at the end.

## The problem in one paragraph

Today an agent acts in turns. It calls `observe`, reads a snapshot, decides, calls a tool, and the world only reaches it when it asks. Movement is teleport. A frontier model takes two to eight seconds per decision, and a Minecraft tick is 50 ms. A creeper closes 8 blocks in the time one decision takes. So "real time" is really two problems. One is cadence: when does the model get to think, and what happens to the body in between. The other is bandwidth: what the model sees per token, and whether it can read it fast enough to matter.

## What every working system does

No published system runs a large model inside the tick loop. Every one of them puts something fast between the model and the game:

| System | Fast layer | Slow layer | Bridge |
|---|---|---|---|
| Mindcraft | "Modes": non-LLM reflexes on a 300 ms tick (self-preservation, self-defense, unstuck, item collecting) | LLM picks from 47 parameterized commands | Reflexes write a "recent behaviors log" the LLM reads next call; a mode can interrupt the LLM's action and tell it why |
| Project Sid (PIANO) | Small non-LLM modules for reflexes | One "cognitive controller" decides and conditions both speech and action | Shared agent state; "action awareness" compares expected vs observed outcome |
| ROCKET-1 | 20 Hz learned policy | GPT-4o re-plans every 1.5 s | Segmentation mask plus interaction type |
| SIMA 2 | Learned policy emitting 8-action chunks | Gemini | Buffered actions; "limited context window to achieve low-latency interaction" |
| Cradle | None | GPT-4o on screenshots | Had to pause the game while waiting for the model |

Two more findings shape the design. JARVIS-1, DEPS and Mindcraft all blame the low-level controller (pathfinding, stuck states, doors), not the planner, for most failures. And PIANO found that letting separate calls choose chat and action produces incoherence ("Sure thing!" while the function call says "explore"), so one decision point should drive both.

## What the 26.3 jar allows

A javap pass over the real game classes says everything except pathfinding is possible with public API and no new mixins. `AgentPlayer` already has the two overrides that matter: `tick()` calls `doTick()`, and `isClientAuthoritative()` returns false. Together they make `LivingEntity.aiStep` run `travel(input)` for the agent every tick. It already falls and takes fall damage. Walking is just writing input before the tick.

| Capability | Verdict | How |
|---|---|---|
| Walk, strafe, jump, sprint, sneak, swim, climb | As-is | Override `applyInput()` and set `xxa`, `zza`, `jumping`; `setSprinting`, `setShiftKeyDown`. Sprint auto-stop and `checkMovementStatistics` (hunger) are client logic the controller must copy. |
| Mining with real break times | As-is | `ServerPlayerGameMode.handleBlockBreakAction` START, accumulate `getDestroyProgress` per tick, STOP at 1.0, 5-tick delay. Drops land in inventory through the normal `touch` pickup. |
| Placing and using blocks | As-is | Real `Level.clip` ray to build the `BlockHitResult`, then `useItemOn`. Look at the target first; placement reads yaw and pitch. |
| Attack, interact, mount | As-is | `Player.attack(Entity)` does cooldown, crits, sweep and knockback. Gate on `getAttackStrengthScale`. |
| Eat, bow, shield, hotbar, drop | As-is | `startUsingItem` completes on its own after `getUseDuration` ticks. `releaseUsingItem` fires the bow. `Inventory.setSelectedSlot`. |
| Chests, furnaces, crafting | As-is | `openMenu` then `AbstractContainerMenu.clicked` with `QUICK_MOVE`; crafting via `handlePlacement(recipe)` on the crafting menu, then shift-click the result. |
| Damage sense | As-is | Override `hurtServer` in `AgentPlayer`. |
| Hearing | One mixin or vanilla listener | HEAD inject on `ServerLevel.playSeededSound` catches every sound with position and range. Or a `GameEventListener` (what wardens use) hears steps, block breaks, explosions within 16 blocks. |
| Block changes near the agent | One mixin | HEAD inject on `ServerLevel.sendBlockUpdated` is the single choke point. |
| Pathfinding | Custom | Every vanilla pathfinder class takes a `Mob`. Options below. |

Pathfinding options, in order of preference:

1. Depend on or port Arcade's `arcade-npcs` module (MIT, tracks 26.3, updated 2026-10-16). It is a `FakePlayer extends ServerPlayer` with the same tick pattern as ours, a 246-line A*, movement types for traverse, ascend, descend, fall, climb, swim and parkour, and per-type executors. About 120 KB of Kotlin for the pathfinding package. A Java port is 1,200 to 2,000 lines, one to two weeks.
2. For a quick MVP, drive vanilla `PathFinder` plus `WalkNodeEvaluator` through a dummy `Zombie` that is never added to the world (same 1x2 node footprint as a player). Flat terrain only. No gaps, ladders or real swimming.
3. Straight-line walking with jump-on-collision covers most "come here" requests and takes a day.

CPU: the controller is microseconds per tick. Path searches are the only heavy step, 0.1 to 10 ms, so re-plan at most every 20 ticks, stagger agents, and cap visited nodes. Four agents should stay under 1% of the tick budget.

## Perception: what the model should see

Evidence on formats, briefly:

- Text beats images for decisions in every model family except Claude, where images help a little (BALROG). Grid-rendered images are out of distribution for VLMs, and the 3D render of Crafter lowered Claude's score.
- Short lists of objects with relative position and distance ("zombie 6m behind-left, approaching") are what working agents use (Voyager, Mindcraft, BALROG wrappers, SmartPlay). SmartPlay found raw ray matrices unreadable and had to group them.
- Small character grids under about 16 wide are read well. Accuracy falls fast as grids grow (98% to 61% on locate tasks). Our palette grids are fine on demand, not as a stream.
- Models struggle with frames of reference relative to an embodied agent (GSU). Give both relative and compass terms.
- Image cost is now `ceil(w/28) * ceil(h/28)` tokens, and Claude is unreliable on images under about 200 px. Our 1280x720 shot is 1,196 tokens. A 640x360 shot is 299.
- Annotated screenshots work: Set-of-Mark labels, PIVOT's numbered arrows, and both Pokemon harnesses converged on drawing coordinates and labels onto the frame.

A 2 to 4k token glance every few seconds is 25k to 120k tokens per minute per agent. Not sustainable. The only shape that fits is a quiet always-on channel of 50 to 300 tokens, often zero when nothing changed, with expensive views spent on demand.

### Layered senses

**Proprioception, one line, on every message.** Position, facing, surface, speed, on ground or falling, in water, health, food, time, light, held item. About 50 tokens. Every working Minecraft agent ships a block like this, and it is the cheapest thing that stops the model acting on stale state.

```
You: (120,64,-45) facing NE, on grass, walking 4.3 m/s. HP 14/20 food 9/20. Dusk, clear, light 7. Holding stone_sword.
```

**Salient event feed, always on.** The server turns world changes into second-person lines with relative direction, compass direction, distance and trend. A rule-based gate drops noise (a cow wandering) and keeps approach vectors, sight gained or lost, light changes, sounds with direction, damage with source. Coalesce per entity per second so combat does not flood.

```
[t+2.1s] Creeper 8m NE (ahead-right), closing, was 12m.
[t+2.1s] Steve walked out of sight to the W, last seen 24m.
[t+3.4s] You heard an explosion, far, NW (~60m).
[t+4.0s] Light fell to 6. Sunset in about 2 min.
[t+4.6s] You took 3 damage from a zombie behind you (SW, 1.5m).
```

**Sector sweep, on demand.** A sonar-like text view. Cast rays in 12 sectors of 30 degrees at three elevations, and report the nearest solid thing, openings and entities per sector, relative to facing. 150 to 300 tokens. This replaces the first-person screenshot for most navigation decisions.

```
Facing N. Sweep (near <4m, mid 4-12m, far >12m):
 ahead       : oak_log 3m | open | stone hill 18m
 ahead-right : open | cow 7m | trees 15m
 right (E)   : stone wall 2m
 behind (S)  : open | zombie 6m approaching | your house (door) 14m
 left (W)    : water edge 5m | plains
 below       : grass; 4m drop 2 blocks ahead-left
```

**Landmarks, remembered.** A `remember_place` tool and a server-side table. Status lines and sweeps then name the nearest two or three places in both forms ("home 40m NW, behind-left"). Mindcraft's saved places and the Pokemon text minimap are the precedents.

**Annotated screenshot, on demand.** Drop to 640x360 and draw on it: a compass bar, numbered labels on entities with name and distance, the crosshair block with coordinates, a 3-block ground grid. About 350 tokens all in. Image before text.

**Expectation vs outcome.** Each intent reports what it expected and what happened ("expected to arrive at (100,64,-20); stopped at (96,64,-20), water ahead"). PIANO credits this with stopping hallucinations from compounding.

### Experiments worth running

- **Patch-aligned semantic retina.** Render a 16x9 ray grid as a 448x252 image, one block category per colour, one cell per 28 px patch, 144 tokens. No published evidence either way. Cheap enough to A/B against the sector sweep.
- **Narrated vision.** Haiku reads the frame plus the sweep and writes three lines in the agent's voice. 60 to 120 tokens to the main model. Good for recognising places and mood, never for counts or coordinates. The Doom agent that worked this way forgot things out of frame.
- **Fair-play perception.** Limit the event feed and sweep to line of sight and hearing range, so the agent perceives like a player rather than a server. This makes looking around matter, which is more player-like and more interesting to play with. Keep an "omniscient" mode as a config option.
- **Watch requests.** Let the model set its own triggers: `watch(hostile within 10)`, `watch(Steve beyond 30)`, `watch(sunset)`. The body fires only when a trigger matches. This is attention the model chooses rather than attention the gate guesses.
- **Doom-style ASCII render.** Assessed and rejected. Perspective breaks the regularity that makes small grids readable, and it carries the same information as the sweep at several times the tokens.

## The control loop

### Facts about the channel

Checked against the Claude Code docs:

- Only two paths reach the model's context: tool results and stdin user messages. Claude Code as an MCP client does not pass server notifications, logging or resource updates to the model, and our server has no event stream anyway. Both paths are under the mod's control, which is enough.
- `priority: "next"` (what the mod uses now) is read as soon as running tool calls finish, inside the same turn. `"now"` interrupts the turn and discards the in-flight generation. `shouldQuery: false` appends a message without starting a turn; it rides along with the next real message for free.
- A turn ends when the model answers without tool calls. There is no always-on mode.
- An MCP tool call with no response for five minutes aborts (`CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT`). Our `OnServer.call` has a 10 s timeout and must not be the thing that blocks.
- Automatic compaction runs in `-p` mode (`--autocompact 150k` lowers the threshold and emits `compact_boundary`). `--max-budget-usd` is the per-agent kill switch.
- Prompt cache TTL is one hour on a subscription within plan usage, five minutes on API keys. `CLAUDE_CODE_PROMPT_CACHE_TTL=1h` forces one hour.

### Architectures compared

Assumes Opus 5.5 prices, 300 to 400 output tokens per call, about 500 new input tokens per cycle.

| Loop | Reaction latency | Calls/min | $/agent-hour | Main problem |
|---|---|---|---|---|
| Tick: a delta message every 5 s | 5 s plus a model call | 12 | ~18 | Pays for ticks where nothing happened; context grows 500k tokens an hour |
| Event-driven: message only on salient events, plus heartbeat | one model call | 2 to 8 | ~5 to 9 | Every event crosses a turn boundary and the model writes a closing line |
| Async intents plus blocking `wait` | one model call, same turn | 2 to 8 | ~5 to 9 | Holds an HTTP thread; capped by the idle timeout; model may forget to wait |
| The model is the loop: a tool call every 3 s | one model call | 20 | ~32 to 56 | Never idles, and cache reads scale with the growing context |
| Code-as-policy: model writes scripts run at tick rate | 50 ms once written | low | low | Sandboxing an interpreter at gamemaster level; GraalJS sandboxing needs Oracle GraalVM |

### Recommended: a two-speed hybrid

1. **Reflex layer in Java, at tick rate.** Pathfinding, walking, following, mining, placing from inventory, auto-eat, fight back, flee creepers, avoid lava and falls. No model involved. The LLM can turn individual reflexes on and off.
2. **Asynchronous intents.** `goto`, `follow`, `mine`, `attack`, `collect` return an intent id and an estimate at once. Progress and completion become events. `cancel` stops them.
3. **A "meanwhile" delta on every tool result.** Position, health change, new events, intent progress, 60 to 120 tokens, timestamp first, fixed format. Zero extra round trips, read before the next model call by construction. This is the single most valuable change and needs no body work.
4. **A `wait` tool.** While it has a goal, the model calls `wait(timeout, until)`. The mod returns early on salient events or intent completion. The model's turn never ends, so it keeps its plan as the live turn instead of re-reading it. Cap at 240 s, under the idle timeout and the five-minute cache TTL. Park the HTTP handler thread, never the server thread.
5. **Event-driven wake-ups when idle.** If the model ends its turn, events arrive as user messages above a salience threshold, with a heartbeat (60 s while working, 5 min otherwise). Low-salience lines go in with `shouldQuery: false`.
6. **Interrupts.** If a tool is in flight, complete it early with the urgent event. If the model is generating, send `"now"`. If idle, a normal message. Reflexes already acted; the model's job is to re-plan.
7. **Compaction hygiene.** Launch with `--autocompact` and `--max-budget-usd`. On `compact_boundary`, send a state-of-play message (position, inventory, active intents, goal, last instructions) so the summary's losses do not matter.

Cost of the hybrid, API list prices per agent-hour:

| Cadence | Fable 5.1 | Opus 5.5 | Sonnet 5.5 | Haiku 5.5 |
|---|---|---|---|---|
| Active play, 6 calls/min, 40k context | $13.50 | $6.80 | $4.90 | $0.24 |
| Following with 60 s heartbeat | $1.35 | $0.78 | $0.63 | $0.03 |

Sonnet saves little over Opus here because cache reads cost the same. Haiku is a different class and worth testing as the whole brain for simple roles. On a subscription this is plan usage, shared across all agents on the account.

A second small-model "reflex brain" was considered and rejected for now: two processes cannot share a conversation, so it would be a second agent arguing with the first over one body. Reflexes belong in Java.

### Tools for the recommended design

| Tool | Args | Returns | Blocks? |
|---|---|---|---|
| `goto` | `x,y,z` or `near_player`, `tolerance`, `sprint` | intent id, path length, ETA | No |
| `follow` | `player`, `distance` | intent id | No |
| `mine` | block ids, positions or box, `count` | intent id, estimate | No |
| `collect` | `item`, `radius` | intent id | No |
| `attack` | entity id or name | intent id | No |
| `cancel` | intent id or `all` | cancelled ids | No |
| `set_reflexes` | `auto_eat`, `fight_back`, `flee_creepers`, `avoid_lava` | settings | No |
| `wait` | `timeout_s` (max 240), `until` (intent ids, event kinds, `any`), `min_salience` | events, intent updates, delta | Yes, returns early |
| `watch` | a trigger condition | watch id | No |
| `sweep` | none | sector sweep | No |
| `remember_place`, `places` | name, optional pos | landmarks with relative and compass directions | No |
| `craft`, `smelt`, `container`, `equip`, `eat`, `drop`, `place` | item, count, pos | result or reason | Immediate |
| existing tools | as today | as today plus the meanwhile delta | No |

Mark read-only tools with `readOnlyHint` so Claude Code runs them in parallel. Keep `teleport` for creative mode.

## Build order

Phase 1 makes agents feel alive before any walking exists. Each phase is useful on its own.

1. **Perception plumbing, no body work.** Meanwhile delta on every tool result. Status line. Event gate with second-person lines, coalescing and salience. `hurtServer` override, sound hook, `sendBlockUpdated` hook feeding `EventLog`. `wait` tool. Event-driven wake-ups when idle. About a week.
2. **Body MVP.** `applyInput` controller with look target, sprint stop and movement stats. Straight-line `goto` and `follow` with jump-on-collision. Mining. Placing from inventory. Attack with cooldown gating. Auto-eat reflex. About a week.
3. **Real pathfinding and survival depth.** Port or depend on Arcade's pathfinder. Containers and crafting. Sector sweep, landmarks, annotated 640x360 screenshot. Flee and avoid reflexes. Two to three weeks.
4. **Experiments.** Semantic retina A/B, narrated vision, Haiku as whole brain, fair-play perception, watch requests, a JSON behavior-tree DSL for repeated routines once the repeated routines are visible.

## Risks

- The reflex layer is most of the work. Player bodies have no vanilla navigation; falls, water, doors and ladders all need handling before `goto` is trustworthy.
- The model stops calling `wait` or loops on it. The fallback is event-driven wake-ups, plus a minimum interval and maximum duration on `wait`.
- `"now"` semantics differ by Claude Code version (on 2.1.286+ with a human origin it moves MCP calls to the background instead of interrupting). Prefer early tool return; test on the installed version.
- Context growth in long sessions. `--autocompact` and re-injected state after compaction.
- Prompt injection through the event feed. Keep the existing `restricted` chat filter, and keep dangerous actions behind Java-side permission checks.
- Blocking waits exhaust the HTTP executor. Size it at two threads per agent plus the screenshot pair.
- Shared account limits. One agent in a tight loop can rate-limit the others.

## Not verified

- Reach padding constants inside `handleBlockBreakAction` and `handleUseItemOn` (1.0 in 1.21).
- Boat and horse steering for a server-simulated rider.
- Whether `arcade-npcs` snapshot artifacts resolve under Loom 1.18 with a Java-only build.
- Any study of colour-coded semantic images at one block per 28 px patch. The retina is untested.
- Real output tokens per decision at each effort level. Measure with `usage` in result events before trusting the cost tables.
- Whether `--settings` JSON accepts hooks under `--restricted`.

## Sources

Prior art: [Mindcraft](https://github.com/kolbytn/mindcraft) and [MineCollab](https://arxiv.org/html/2504.17950), [Voyager](https://arxiv.org/html/2305.16291), [VPT](https://arxiv.org/html/2206.11795v1), [STEVE-1](https://arxiv.org/html/2306.00937v3), [DEPS](https://arxiv.org/html/2302.01560v3), [JARVIS-1](https://arxiv.org/html/2311.05997v3), [Cradle](https://arxiv.org/html/2403.03186), [Odyssey](https://arxiv.org/html/2407.15325v3), [ROCKET-1](https://arxiv.org/html/2410.17856v3), [OmniJARVIS](https://arxiv.org/html/2407.00114), [Project Sid / PIANO](https://arxiv.org/html/2411.00114v1), [SIMA](https://arxiv.org/html/2404.10179v1) and [SIMA 2](https://deepmind.google/blog/sima-2-an-agent-that-plays-reasons-and-learns-with-you-in-virtual-3d-worlds/), [RRARA](https://arxiv.org/abs/2506.07223), [DPT-Agent](https://arxiv.org/html/2502.11882v5), [The Latent Bridge](https://arxiv.org/abs/2606.24470).

Perception: [BALROG](https://arxiv.org/html/2411.13543v2), [SpatialEval](https://www.arxiv.org/pdf/2406.14852), [Stuck in the Matrix](https://arxiv.org/html/2510.20198v1), [SmartPlay](https://arxiv.org/html/2310.01557), [GSU](https://arxiv.org/abs/2603.17333), [Set-of-Mark](https://arxiv.org/pdf/2310.11441), [PIVOT](https://arxiv.org/pdf/2402.07872), [Vision language models are blind](https://arxiv.org/html/2407.06581v4), [Generative Agents](https://arxiv.org/pdf/2304.03442), [Lyfe Agents](https://arxiv.org/html/2310.02172v1), [Parallelized Planning-Acting](https://arxiv.org/html/2503.03505v1), [Gemini plays Pokemon](https://www.dbreunig.com/2025/06/17/an-agentic-case-study-playing-pok%C3%A9mon-with-gemini.html), [Claude vision docs](https://platform.claude.com/docs/en/build-with-claude/vision).

Body control: [Carpet EntityPlayerActionPack](https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/helpers/EntityPlayerActionPack.java), [Arcade (arcade-npcs)](https://github.com/CasualChampionships/arcade), [Puppet Players](https://github.com/senseiwells/PuppetPlayers), [Automatone](https://github.com/Ladysnake/Automatone) (dead since 1.17).

Control loop: [headless mode](https://code.claude.com/docs/en/headless), [CLI reference](https://code.claude.com/docs/en/cli-reference), [SDK TypeScript reference](https://code.claude.com/docs/en/agent-sdk/typescript), [agent loop](https://code.claude.com/docs/en/agent-sdk/agent-loop), [MCP in Claude Code](https://code.claude.com/docs/en/mcp), [prompt caching](https://code.claude.com/docs/en/prompt-caching), [model config](https://code.claude.com/docs/en/model-config), [costs](https://code.claude.com/docs/en/costs), [GraalVM sandboxing](https://graalvm.org/dev/security-guide/sandboxing).
