package dev.agentsmc.net;

import com.google.gson.JsonObject;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.brain.AgentBrain;
import dev.agentsmc.mcp.ToolException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Answers the agents menu. Fabric runs these handlers on the server thread. */
public final class MenuServer {
	/** The menu polls every 500 ms; a client repeating the same request much faster is ignored. */
	private static final long MIN_REQUEST_MS = 200;
	private record Served(long time, String selected) {
	}
	private static final Map<UUID, Served> lastServed = new ConcurrentHashMap<>();

	private MenuServer() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(MenuPayloads.Request.TYPE, (payload, ctx) -> {
			long now = System.currentTimeMillis();
			Served last = lastServed.get(ctx.player().getUUID());
			// A new selection is always answered, so clicking an agent shows its log at once.
			if (last != null && now - last.time() < MIN_REQUEST_MS && last.selected().equals(payload.selected())) return;
			lastServed.put(ctx.player().getUUID(), new Served(now, payload.selected()));
			sendState(ctx.player(), payload.selected(), null);
		});

		ServerPlayNetworking.registerGlobalReceiver(MenuPayloads.Action.TYPE, (payload, ctx) -> {
			ServerPlayer player = ctx.player();
			String error = null;
			try {
				act(player, payload);
			} catch (ToolException e) {
				error = e.getMessage();
			}
			sendState(player, payload.agent(), error);
		});
	}

	private static void act(ServerPlayer player, MenuPayloads.Action a) {
		Session s = AgentsMc.session();
		if (s == null || s.brains == null) return;
		if (!s.brains.canUse(player)) throw new ToolException("Only operators can use agents on this server.");
		String who = player.getName().getString();
		switch (a.action()) {
			case "send" -> {
				if (a.text().isBlank()) return;
				s.brains.send(who, player, "in the agents menu", s.brains.get(a.agent()), a.text());
			}
			case "stop" -> s.brains.get(a.agent()).stop(who);
			case "spawn" -> {
				AgentBrain brain = s.brains.create(a.agent(), player);
				if (!a.text().isBlank()) s.brains.send(who, player, "in the agents menu", brain, a.text());
			}
			case "remove" -> s.brains.remove(a.agent());
			default -> throw new ToolException("Unknown action " + a.action());
		}
	}

	private static void sendState(ServerPlayer player, String selected, String error) {
		Session s = AgentsMc.session();
		if (s == null || s.brains == null) return;
		// Players who can't use agents see who exists and what they're doing, not what they were told.
		JsonObject state = s.brains.snapshot(player, s.brains.canUse(player) ? selected : null);
		if (error != null) state.addProperty("error", error);
		ServerPlayNetworking.send(player, new MenuPayloads.State(state.toString()));
	}
}
