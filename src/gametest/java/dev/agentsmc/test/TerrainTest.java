package dev.agentsmc.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.agent.AgentPlayer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The body in a normally generated world: a long walk back to the host over real terrain, then a
 * night with monsters while following the host. Set AGENTSMC_ONLY=terrain.
 */
public class TerrainTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final String AGENT = "Scout";

	private int port;
	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private Narrator narrator;
	private int watched;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("terrain")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		LOG.info("recording can start");
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
			run("difficulty hard");
			run("gamemode creative Player0");
			run("effect give Player0 minecraft:night_vision infinite 0 true");
			port = world.getServer().computeOnServer(server -> AgentsMc.session().mcp.port());
			ctx.runOnClient(mc -> mc.gui.hud.vignetteBrightness = 0);

			BlockPos h = world.getServer().computeOnServer(server -> host(server).blockPosition());
			int x = h.getX(), z = h.getZ();
			int y = surface(x, z);
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 10", x, y, z));
			LOG.info("spawn: {} {} {}", x, y, z);

			JsonObject spawned = call("spawn_agent", obj("name", AGENT, "near_player", "Player0", "gamemode", "survival"), 30);
			LOG.info("spawn: {}", text(spawned));
			narrator = new Narrator(world, AGENT);
			run("give " + AGENT + " iron_sword");
			run("give " + AGENT + " iron_axe");
			run("give " + AGENT + " bread 8");
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			watch(40);

			// ---- 1. a long walk home over whatever the terrain is ----
			int fx = x + 45, fz = z + 25;
			int fy = surface(fx, fz);
			narrator.tool("teleport x=" + fx + " y=" + fy + " z=" + fz + "  (dropped off 50 blocks away)");
			call("teleport", obj("agent", AGENT, "x", fx + 0.5, "y", (double) fy, "z", fz + 0.5, "fly", false), 30);
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d", x, y + 1, z));
			watch(40);
			ctx.takeScreenshot("terrain-00-far");
			narrator.tool("goto near_player=Player0 distance=3");
			JsonObject go = call("goto", obj("agent", AGENT, "near_player", "Player0", "distance", 3), 30);
			LOG.info("goto: {}", text(go));
			String woke = waitFor("goto", 150);
			double gap = agentPos().distanceTo(hostPos());
			LOG.info("goto ended ({}): {} blocks from the host", woke, gap);
			ctx.takeScreenshot("terrain-01-home");
			if (gap > 8) throw new AssertionError("Agent should have walked back to the host; it is " + gap + " blocks away: " + woke);

			// ---- 2. night falls; monsters come; the agent follows the host and defends itself ----
			narrator.tool("follow player=Player0 distance=3");
			LOG.info("follow: {}", text(call("follow", obj("agent", AGENT, "player", "Player0", "distance", 3), 30)));
			run("time set midnight");
			run("gamerule spawn_monsters true");
			watch(40);
			Vec3 a = agentPos();
			run(String.format(Locale.ROOT, "summon zombie %.1f %.1f %.1f", a.x + 9, (double) surface((int) a.x + 9, (int) a.z + 6), a.z + 6));
			run(String.format(Locale.ROOT, "summon skeleton %.1f %.1f %.1f", a.x - 12, (double) surface((int) a.x - 12, (int) a.z + 4), a.z + 4));
			run(String.format(Locale.ROOT, "summon zombie %.1f %.1f %.1f", a.x - 6, (double) surface((int) a.x - 6, (int) a.z - 10), a.z - 10));
			// The host wanders a little so following stays visible.
			ctx.getInput().holdKey(o -> o.keyUp);
			lookFor(40, 90, 0);
			ctx.getInput().releaseKey(o -> o.keyUp);
			watch(20 * 25);
			ctx.getInput().holdKey(o -> o.keyUp);
			lookFor(40, -90, 0);
			ctx.getInput().releaseKey(o -> o.keyUp);
			watch(20 * 35);
			ctx.takeScreenshot("terrain-02-night");
			String sensed = narrator.sensedText();
			LOG.info("sensed during the night:\n{}", sensed);
			if (!sensed.contains("Fighting back") && !sensed.contains("coming for you") && !sensed.contains("damage")) {
				throw new AssertionError("Expected the agent to meet a monster during the night: " + sensed);
			}
			run("time set day");
			run("gamerule spawn_monsters false");
			narrator.tool("cancel");
			call("cancel", obj("agent", AGENT), 30);
			LOG.info("status: {}", text(call("get_status", obj("agent", AGENT), 30)));
			watch(40);
		}
	}

	private int surface(int x, int z) {
		return world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
	}

	private String waitFor(String kind, int seconds) {
		CompletableFuture<JsonObject> pending = callAsync("wait", obj("agent", AGENT, "seconds", seconds, "until", "intent", "min_salience", 2));
		int ticks = 0;
		while (!pending.isDone() && ticks < (seconds + 10) * 20) {
			watch(2);
			ticks += 2;
		}
		if (!pending.isDone()) throw new AssertionError("wait for " + kind + " didn't return in " + (seconds + 10) + "s");
		String t = text(pending.join());
		LOG.info("wait: {}", t);
		return t.lines().findFirst().orElse(t);
	}

	private void watch(int ticks) {
		for (int i = 0; i < ticks; i += 2) {
			Vec3 at = world.getServer().computeOnServer(server -> {
				AgentPlayer a = AgentsMc.session().agents.find(AGENT);
				return a == null ? null : a.getEyePosition();
			});
			if (at != null) ctx.runOnClient(mc -> {
				if (mc.player != null) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, at);
			});
			if (narrator != null && (watched += 2) % 6 == 0) narrator.tick();
			ctx.waitTicks(2);
		}
	}

	private void lookFor(int ticks, float yaw, float pitch) {
		for (int i = 0; i < ticks; i += 2) {
			ctx.runOnClient(mc -> {
				if (mc.player != null) {
					mc.player.setYRot(yaw);
					mc.player.setXRot(pitch);
				}
			});
			if (narrator != null && (watched += 2) % 6 == 0) narrator.tick();
			ctx.waitTicks(2);
		}
	}

	private void run(String command) {
		world.getServer().runCommand(command);
	}

	private Vec3 agentPos() {
		return world.getServer().computeOnServer(server -> AgentsMc.session().agents.get(AGENT).position());
	}

	private Vec3 hostPos() {
		return world.getServer().computeOnServer(server -> host(server).position());
	}

	private static ServerPlayer host(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow();
	}

	private JsonObject call(String tool, JsonObject args, int timeoutS) {
		CompletableFuture<JsonObject> f = callAsync(tool, args);
		int ticks = 0;
		while (!f.isDone() && ticks < timeoutS * 20) {
			watch(2);
			ticks += 2;
		}
		if (!f.isDone()) throw new AssertionError(tool + " didn't return in " + timeoutS + "s");
		return f.join();
	}

	private CompletableFuture<JsonObject> callAsync(String tool, JsonObject args) {
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", args);
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", 1);
		msg.addProperty("method", "tools/call");
		msg.add("params", params);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(msg.toString()))
				.build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
			if (result == null) throw new AssertionError(tool + " failed: " + response.body());
			return result;
		});
	}

	private static String text(JsonObject result) {
		return BodyTest.text(result);
	}

	private static JsonObject obj(Object... kv) {
		return BodyTest.obj(kv);
	}
}
