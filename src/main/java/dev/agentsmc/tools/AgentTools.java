package dev.agentsmc.tools;

import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.mcp.Schema;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.perception.Views;
import dev.agentsmc.util.OnServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Spawning agent bodies, moving them, chat and commands. */
public final class AgentTools {
	private AgentTools() {
	}

	public static void register(Session s) {
		s.tools.registerUnbound("spawn_agent",
				"Spawn a player-shaped body for an agent. It appears to humans as a normal player (name tag, tab list). "
						+ "By default it spawns 2 blocks in front of a human player, facing them. Bodies persist until despawned or the world closes.",
				Schema.of()
						.str("name", "Player name, 3-16 letters/digits/underscores. Pick something humans will recognise.", true)
						.str("near_player", "Spawn in front of this player (default: the first human online)", false)
						.number("x", "Spawn x (instead of near_player)", false).number("y", "Spawn y", false).number("z", "Spawn z", false)
						.enumStr("gamemode", "Default creative (instant building, flying, no damage).", false, "creative", "survival", "adventure", "spectator"),
				args -> OnServer.call(s.server, () -> {
					String name = args.str("name");
					GameType mode = GameType.byName(args.str("gamemode", "creative"), GameType.CREATIVE);
					ServerLevel level = s.server.overworld();
					Vec3 pos;
					float yaw = 0;
					ServerPlayer near = args.has("near_player") ? Targets.player(s, args.str("near_player"))
							: s.agents.humans().isEmpty() ? null : s.agents.humans().getFirst();
					if (args.has("x") && args.has("y") && args.has("z")) {
						pos = new Vec3(args.number("x"), args.number("y"), args.number("z"));
					} else if (near != null) {
						level = near.level();
						pos = Targets.inFrontOf(near, 2);
						yaw = Targets.yawToward(pos, near.position());
					} else {
						pos = Vec3.atBottomCenterOf(level.getRespawnData().pos());
					}
					AgentPlayer agent = s.agents.spawn(name, level, pos, yaw, mode);
					s.agents.state(name).eventCursor = s.events.lastSeq();
					return "Spawned " + name + ".\n" + Views.status(agent);
				}));

		s.tools.registerUnbound("despawn_agent", "Remove an agent's body from the world (its inventory and position are saved).",
				Schema.of().agent(true),
				args -> OnServer.call(s.server, () -> {
					s.agents.despawn(args.str("agent"), "Agent left");
					return "Despawned " + args.str("agent");
				}));

		s.tools.register("list_agents",
				"List spawned agent bodies and the human players online. Positions are a snapshot: players keep moving, "
						+ "so use find_player or a tool's player-name target when you need where someone is now.",
				Schema.of(),
				args -> OnServer.call(s.server, () -> {
					StringBuilder sb = new StringBuilder("Agents:\n");
					if (s.agents.all().isEmpty()) sb.append("  (none spawned)\n");
					for (AgentPlayer a : s.agents.all()) sb.append("  ").append(Views.describe(a)).append(" in ").append(Views.dimension(a.level())).append('\n');
					sb.append("Humans:\n");
					List<ServerPlayer> humans = s.agents.humans();
					if (humans.isEmpty()) sb.append("  (none online)\n");
					for (ServerPlayer h : humans) sb.append("  ").append(Views.describe(h)).append(" in ").append(Views.dimension(h.level())).append('\n');
					return sb.toString();
				}));

		s.tools.register("find_player",
				"Where a player is right now: position, distance and compass direction from your agent, facing, what they hold, "
						+ "and the block they are looking at (plus where a block placed against it would go). "
						+ "Use it before acting on someone's position, and when a human says \"here\" or \"this\" they usually mean the block they are looking at.",
				Schema.of().agent(false).str("player", "Player name (default: the only human online)", false),
				args -> OnServer.call(s.server, () -> {
					ServerPlayer target = Targets.playerOrOnlyHuman(s, args.str("player", null));
					AgentPlayer from = args.has("agent") ? s.agents.get(args.str("agent")) : null;
					return Views.player(target, from);
				}));

		s.tools.register("teleport",
				"Move an agent instantly, either to coordinates or next to a player (near_player: 2 blocks in front of them, facing them).",
				Schema.of().agent(true)
						.str("near_player", "Go to this player instead of coordinates", false)
						.number("distance", "With near_player: how far in front of them, in blocks (default 2)", false)
						.number("x", "Target x", false).number("y", "Target y", false).number("z", "Target z", false)
						.number("yaw", "Horizontal facing in degrees: 0 south, 90 west, 180 north, -90 east (optional)", false)
						.number("pitch", "Vertical look in degrees: -90 up, 0 level, 90 down (optional)", false)
						.bool("fly", "Creative/spectator only: hover at the target instead of falling (default true)", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Rules.creativeOnly(s, a, "teleport", "walk there with goto (it digs if it has to), or follow the player.");
					ServerLevel level = a.level();
					Vec3 pos;
					float yaw = a.getYRot();
					if (args.has("near_player")) {
						ServerPlayer p = Targets.player(s, args.str("near_player"));
						level = p.level();
						pos = Targets.inFrontOf(p, args.number("distance", 2));
						yaw = Targets.yawToward(pos, p.position());
					} else if (args.has("x") && args.has("y") && args.has("z")) {
						pos = new Vec3(args.number("x"), args.number("y"), args.number("z"));
					} else {
						throw new ToolException("Pass x, y and z, or near_player");
					}
					a.teleportTo(level, pos.x, pos.y, pos.z, Set.of(),
							(float) args.number("yaw", yaw), (float) args.number("pitch", a.getXRot()), true);
					if (a.getAbilities().mayfly) {
						a.getAbilities().flying = args.bool("fly", true);
						a.onUpdateAbilities();
					}
					return Views.status(a);
				}));

		s.tools.register("look_at",
				"Turn an agent's head and body to look at a point (e.g. a block centre: x+0.5, y+0.5, z+0.5) or at a player's face.",
				Schema.of().agent(true)
						.str("player", "Look at this player (instead of x/y/z)", false)
						.number("x", "x", false).number("y", "y", false).number("z", "z", false),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					Vec3 target;
					if (args.has("player")) {
						target = Targets.player(s, args.str("player")).getEyePosition();
					} else if (args.has("x") && args.has("y") && args.has("z")) {
						target = new Vec3(args.number("x"), args.number("y"), args.number("z"));
					} else {
						throw new ToolException("Pass x, y and z, or player");
					}
					a.lookAt(EntityAnchorArgument.Anchor.EYES, target);
					return Views.status(a);
				}));

		s.tools.register("say", "Send a chat message as the agent. Everyone online sees it.",
				Schema.of().agent(true).str("message", "Text to say", true),
				args -> OnServer.call(s.server, () -> {
					AgentPlayer a = s.agents.get(args.str("agent"));
					String name = a.getName().getString();
					String message = args.str("message");
					s.server.getPlayerList().broadcastSystemMessage(Component.literal("<" + name + "> " + message), false);
					s.events.add("chat", name, name + ": " + message);
					return "Said: " + message;
				}));

		s.tools.register("run_command",
				"Run a Minecraft command as the agent (gamemaster permission, positioned at the agent), e.g. "
						+ "\"give @s oak_planks 64\", \"time set day\", \"weather clear\". Returns the command's feedback.",
				Schema.of().agent(true).str("command", "Command, with or without the leading slash", true),
				args -> {
					if (!s.config.allowCommands) throw new ToolException("run_command is disabled in config/agentsmc.json (allowCommands)");
					return OnServer.call(s.server, () -> {
						// Commands run at gamemaster level, so when every player may direct the mod's agents, they don't get them.
						if (s.brains != null && s.brains.everyone() && s.brains.find(args.str("agent")) != null) {
							throw new ToolException("run_command is off for agents while every player can direct them (whoCanUseAgents in config/agentsmc.json)");
						}
						AgentPlayer a = s.agents.get(args.str("agent"));
						Rules.creativeOnly(s, a, "run_command", "get what you need by gathering, crafting and mining; ask the player to change your game mode if the task truly needs commands.");
						List<String> output = new ArrayList<>();
						CommandSource collector = new CommandSource() {
							@Override
							public void sendSystemMessage(Component message) {
								output.add(message.getString());
							}

							@Override
							public boolean acceptsSuccess() {
								return true;
							}

							@Override
							public boolean acceptsFailure() {
								return true;
							}

							@Override
							public boolean shouldInformAdmins() {
								return false;
							}
						};
						CommandSourceStack source = a.createCommandSourceStack().withSource(collector).withPermission(LevelBasedPermissionSet.GAMEMASTER);
						String command = args.str("command").trim();
						s.server.getCommands().performPrefixedCommand(source, command);
						s.events.add("command", a.getName().getString(), a.getName().getString() + " ran /" + (command.startsWith("/") ? command.substring(1) : command));
						return output.isEmpty() ? "Command ran with no output." : String.join("\n", output);
					});
				});
	}
}
