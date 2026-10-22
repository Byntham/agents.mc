package dev.agentsmc.test;

import com.google.gson.JsonObject;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.brain.ActivityLog;
import dev.agentsmc.brain.AgentBrain;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The real goal: a Claude agent in survival with nothing in its pockets is asked for coal, then for
 * cooked chicken. A player would chop a tree, craft a pickaxe, dig to the ore, and later craft a
 * furnace and cook. This checks that it does, without commands or teleports. Run with
 * AGENTSMC_ONLY=survival. AGENTSMC_LIVE_MODEL / AGENTSMC_LIVE_EFFORT pick the model (default sonnet, low).
 */
public class LiveSurvivalTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final String AGENT = "Scout";

	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private Narrator narrator;
	private int watched;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("survival")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		LOG.info("recording can start");
		writeConfig(System.getenv().getOrDefault("AGENTSMC_LIVE_MODEL", "sonnet"), System.getenv().getOrDefault("AGENTSMC_LIVE_EFFORT", "low"));
		// A normal world, so there are real trees and real ore. A fixed seed keeps runs comparable.
		try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
			s.setAllowCommands(true);
			s.setSeed(System.getenv().getOrDefault("AGENTSMC_SEED", "agentsmc"));
			s.setWorldType(s.getNormalPresetList().getFirst());
		}).create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			run("time set noon");
			run("weather clear");
			run("gamerule send_command_feedback false");
			run("gamerule spawn_monsters false");
			run("gamerule advance_time false");
			run("gamerule advance_weather false");
			run("gamemode creative Player0"); // the host is the camera; the agent plays survival
			ctx.runOnClient(mc -> mc.gui.hud.vignetteBrightness = 0);
			BlockPos h = world.getServer().computeOnServer(server -> host(server).blockPosition());
			int x = h.getX(), z = h.getZ();
			int y = surface(x, z);
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 10", x, y, z));

			// Spawned by a creative host it would be creative; it must play survival with empty pockets.
			chatCommand("agent spawn " + AGENT);
			world.getServer().waitFor(server -> AgentsMc.session().brains.body(AGENT) != null, 200);
			run("gamemode survival " + AGENT);
			run("clear " + AGENT);
			narrator = new Narrator(world, AGENT);
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			watch(40);
			ctx.takeScreenshot("survival-00-start");

			// 1. Coal, from nothing. Expect: logs, planks, sticks, table, pickaxe, dig, mine.
			chat("@" + AGENT + " I need some coal. you have nothing on you, so figure out what you need and go get it. tell me when you have it");
			waitForBrain(b -> b.status() == AgentBrain.Status.IDLE && count(b, ActivityLog.Kind.SAY) >= 2 && count(AGENT, "coal") > 0, 20 * 600);
			LOG.info("after the coal task: coal {}, pickaxes {}", count(AGENT, "coal"), count(AGENT, "pickaxe"));
			dump();
			if (count(AGENT, "coal") == 0) throw new AssertionError("The agent should have coal");
			if (usedCommands()) throw new AssertionError("The agent used commands or teleport in survival");
			ctx.takeScreenshot("survival-01-coal");

			// 2. Cooked chicken: expect a furnace from cobblestone, smelt, take.
			run("give " + AGENT + " chicken 2");
			chat("@" + AGENT + " nice. now cook me those 2 raw chicken you're carrying and bring them to me");
			waitForBrain(b -> b.status() == AgentBrain.Status.IDLE && count(AGENT, "cooked_chicken") > 0 || hostHas("cooked_chicken"), 20 * 420);
			LOG.info("after the cooking task: cooked chicken held {}, host has {}", count(AGENT, "cooked_chicken"), hostHas("cooked_chicken"));
			dump();
			if (count(AGENT, "cooked_chicken") == 0 && !hostHas("cooked_chicken")) throw new AssertionError("Nobody has cooked chicken");
			if (usedCommands()) throw new AssertionError("The agent used commands or teleport in survival");
			watch(40);
			ctx.takeScreenshot("survival-02-cooked");
		}
	}

	// ---- checks ----

	/** True if a creative-only tool actually ran: more calls to them than refusals. */
	private boolean usedCommands() {
		return world.getServer().computeOnServer(server -> {
			AgentBrain b = AgentsMc.session().brains.get(AGENT);
			int calls = 0, refused = 0;
			for (ActivityLog.Entry e : b.log.tail(300)) {
				String t = e.text();
				if (e.kind() == ActivityLog.Kind.TOOL && (t.startsWith("run_command") || t.startsWith("teleport") || t.startsWith("set_blocks")
						|| t.startsWith("fill") || t.startsWith("build_layers") || t.startsWith("undo"))) calls++;
				if (e.kind() == ActivityLog.Kind.ERROR && t.contains("is for creative bodies")) refused++;
			}
			if (calls > 0) LOG.info("creative-only tool calls: {}, refused: {}", calls, refused);
			return calls > refused;
		});
	}

	private int count(String agent, String item) {
		return world.getServer().computeOnServer(server -> {
			AgentPlayer a = AgentsMc.session().agents.find(agent);
			if (a == null) return 0;
			int n = 0;
			for (ItemStack s : a.getInventory().getNonEquipmentItems()) {
				if (!s.isEmpty() && s.getItem().toString().contains(item)) n += s.getCount();
			}
			return n;
		});
	}

	private boolean hostHas(String item) {
		return world.getServer().computeOnServer(server -> {
			for (ItemStack s : host(server).getInventory().getNonEquipmentItems()) {
				if (!s.isEmpty() && s.getItem().toString().contains(item)) return true;
			}
			return false;
		});
	}

	private int surface(int x, int z) {
		return world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
	}

	// ---- plumbing ----

	private void chat(String text) {
		LOG.info("chat: {}", text);
		ctx.runOnClient(mc -> mc.player.connection.sendChat(text));
	}

	private void chatCommand(String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
	}

	private void run(String command) {
		world.getServer().runCommand(command);
	}

	private void waitForBrain(Predicate<AgentBrain> done, int ticks) {
		for (int t = 0; t < ticks; t += 2) {
			boolean ok = world.getServer().computeOnServer(server -> {
				Session s = AgentsMc.session();
				AgentBrain b = s == null ? null : s.brains.find(AGENT);
				return b != null && done.test(b);
			});
			if (ok) return;
			watch(2);
		}
		dump();
		throw new AssertionError("Timed out waiting for " + AGENT);
	}

	/** Keeps the host looking at the agent from a few blocks back, and narrates. */
	private void watch(int ticks) {
		for (int i = 0; i < ticks; i += 2) {
			Vec3 at = world.getServer().computeOnServer(server -> {
				AgentPlayer a = AgentsMc.session().agents.find(AGENT);
				return a == null ? null : a.getEyePosition();
			});
			if (at != null) {
				Vec3 hp = world.getServer().computeOnServer(server -> host(server).position());
				// Stay within 10 blocks so the camera never loses it underground; the host is creative and can fly.
				if (hp.distanceTo(at) > 10 && (watched % 40 == 0)) {
					Vec3 back = at.subtract(hp).normalize().scale(-6);
					run(String.format(Locale.ROOT, "tp Player0 %.1f %.1f %.1f", at.x + back.x, at.y + 1, at.z + back.z));
				}
				ctx.runOnClient(mc -> {
					if (mc.player != null) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, at);
				});
			}
			if (narrator != null && (watched += 2) % 6 == 0) narrator.tick();
			ctx.waitTicks(2);
		}
	}

	private static ServerPlayer host(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow();
	}

	private static int count(AgentBrain b, ActivityLog.Kind kind) {
		int n = 0;
		for (ActivityLog.Entry e : b.log.tail(300)) if (e.kind() == kind) n++;
		return n;
	}

	private void dump() {
		String text = world.getServer().computeOnServer(server -> {
			Session s = AgentsMc.session();
			AgentBrain b = s == null ? null : s.brains.find(AGENT);
			if (b == null) return "(no agent)";
			StringBuilder sb = new StringBuilder(AGENT + " is " + b.status() + ", log:\n");
			for (ActivityLog.Entry e : b.log.tail(200)) sb.append("  ").append(e.kind()).append(' ').append(e.who() == null ? "" : e.who() + ": ").append(e.text()).append('\n');
			return sb.toString();
		});
		LOG.info("{}", text);
	}

	private static void writeConfig(String model, String effort) {
		JsonObject config = new JsonObject();
		config.addProperty("port", 25591);
		config.addProperty("model", model);
		config.addProperty("effort", effort);
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("agentsmc.json");
			Files.createDirectories(file.getParent());
			Files.writeString(file, config.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
