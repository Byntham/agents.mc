package dev.agentsmc.brain;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.mcp.ToolException;
import dev.agentsmc.net.MenuPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /agent spawn|remove|stop|list|menu}. Talking to an agent is plain chat: {@code @Name ...}.
 */
public final class AgentCommand {
	private AgentCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		SuggestionProvider<CommandSourceStack> names = (ctx, builder) -> {
			Session s = AgentsMc.session();
			return SharedSuggestionProvider.suggest(s == null || s.brains == null ? List.of() : s.brains.names(), builder);
		};
		dispatcher.register(Commands.literal("agent")
				.requires(source -> {
					Session s = AgentsMc.session();
					return s != null && s.brains != null && s.brains.canUse(source);
				})
				.then(Commands.literal("spawn")
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> spawn(ctx, null))
								.then(Commands.argument("task", StringArgumentType.greedyString())
										.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "task"))))))
				.then(Commands.literal("remove")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(names)
								.executes(ctx -> run(ctx, (s, name) -> {
									s.brains.remove(name);
									return "Removed " + name + ".";
								}))))
				.then(Commands.literal("stop")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(names)
								.executes(ctx -> run(ctx, (s, name) -> {
									AgentBrain b = s.brains.get(name);
									return b.stop(ctx.getSource().getTextName()) ? "Stopping " + b.name + "." : b.name + " isn't doing anything.";
								}))))
				.then(Commands.literal("list").executes(AgentCommand::list))
				.then(Commands.literal("menu").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					if (!ServerPlayNetworking.canSend(player, MenuPayloads.Open.TYPE)) {
						ctx.getSource().sendFailure(Component.literal("The agents menu needs Agents MC installed on your game too. You can still use chat: @Name ..."));
						return 0;
					}
					ServerPlayNetworking.send(player, new MenuPayloads.Open());
					return 1;
				})));
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, String task) {
		return run(ctx, (s, name) -> {
			ServerPlayer player = ctx.getSource().getPlayer();
			AgentBrain brain = s.brains.create(name, player);
			if (task != null && !task.isBlank()) s.brains.send(ctx.getSource().getTextName(), player, "in chat", brain, task);
			return "Spawned " + brain.name + ". Talk to it in chat with @" + brain.name + ", or open the agents menu (K).";
		});
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Session s = AgentsMc.session();
		if (s == null || s.brains.all().isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No agents. Create one with /agent spawn <name>."), false);
			return 0;
		}
		for (AgentBrain b : s.brains.all()) {
			String status = switch (b.status()) {
				case IDLE -> "idle";
				case STARTING -> "starting";
				case WORKING -> "working: " + b.activity();
			};
			String body = s.brains.body(b.name) == null ? " (no body)" : "";
			ctx.getSource().sendSuccess(() -> Component.literal(b.name + body + ", " + status), false);
		}
		return s.brains.all().size();
	}

	private interface Action {
		String run(Session s, String name);
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Action action) {
		Session s = AgentsMc.session();
		if (s == null) return 0;
		try {
			String result = action.run(s, StringArgumentType.getString(ctx, "name"));
			ctx.getSource().sendSuccess(() -> Component.literal(result).withStyle(ChatFormatting.GRAY), false);
			return 1;
		} catch (ToolException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
	}
}
