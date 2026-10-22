package dev.agentsmc.test;

import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentState;
import dev.agentsmc.brain.ActivityLog;
import dev.agentsmc.brain.AgentBrain;
import dev.agentsmc.perception.EventLog;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Subtitles for recordings: what an agent senses and what tools it calls, shown in the host's chat.
 * Sensed events are drained through the agent's own cursor, so the test reads them from here.
 */
final class Narrator {
	private final TestSingleplayerContext world;
	private final String agent;
	private final List<String> sensed = new ArrayList<>();
	private long lastLogEntry;

	Narrator(TestSingleplayerContext world, String agent) {
		this.world = world;
		this.agent = agent;
	}

	/** Everything the agent's senses reported so far, one line each. */
	String sensedText() {
		return String.join("\n", sensed);
	}

	/** Shows a tool call the test made on the agent's behalf. */
	void tool(String text) {
		say("> " + text, ChatFormatting.AQUA);
	}

	/** Drains new events and brain activity into the chat. Call it often. */
	void tick() {
		List<String> lines = world.getServer().computeOnServer(server -> {
			Session s = AgentsMc.session();
			List<String> out = new ArrayList<>();
			if (s == null || s.agents.find(agent) == null) return out;
			AgentState st = s.agents.state(agent);
			boolean managed = s.brains.find(agent) != null;
			EventLog.Page page = s.events.since(st.eventCursor, agent, 20, !managed);
			st.eventCursor = page.cursor();
			for (EventLog.Event e : page.events()) {
				if (e.audience() == null) continue; // chat and the like are already on screen
				out.add("S" + e.text());
			}
			AgentBrain b = s.brains.find(agent);
			if (b != null) {
				for (ActivityLog.Entry en : b.log.tail(50)) {
					if (en.id() <= lastLogEntry) continue;
					lastLogEntry = en.id();
					switch (en.kind()) {
						case TOOL -> out.add("T" + en.text());
						case TEXT -> out.add("X" + en.text());
						default -> {
						}
					}
				}
			}
			return out;
		});
		for (String line : lines) {
			char kind = line.charAt(0);
			String text = line.substring(1);
			switch (kind) {
				case 'S' -> {
					sensed.add(text);
					say("[" + agent + " senses] " + text, ChatFormatting.YELLOW);
				}
				case 'T' -> say("[" + agent + " calls] " + text, ChatFormatting.AQUA);
				case 'X' -> say("[" + agent + " thinks] " + text, ChatFormatting.GRAY);
				default -> {
				}
			}
		}
	}

	private void say(String text, ChatFormatting color) {
		String shown = text.length() > 160 ? text.substring(0, 157) + "..." : text;
		world.getServer().runOnServer(server -> {
			MutableComponent c = Component.literal(shown).withStyle(color);
			for (var p : server.getPlayerList().getPlayers()) {
				if (!dev.agentsmc.agent.AgentManager.isAgent(p)) p.sendSystemMessage(c);
			}
		});
	}
}
