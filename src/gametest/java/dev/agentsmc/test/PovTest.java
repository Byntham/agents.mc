package dev.agentsmc.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentsmc.AgentsMc;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
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
 * The obstacle course seen through the agent's own eyes: the host spectates the agent in first
 * person while it walks, chops, fights, follows a second agent, and backs off from a creeper.
 * Set AGENTSMC_ONLY=pov.
 */
public class PovTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final String AGENT = "Scout", GUIDE = "Guide";

	private int port;
	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private Narrator narrator;
	private int watched;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("pov")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		LOG.info("recording can start");
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			run("time set noon");
			run("weather clear");
			run("gamerule send_command_feedback false");
			run("gamerule spawn_monsters false");
			run("gamerule advance_time false");
			run("gamerule advance_weather false");
			port = world.getServer().computeOnServer(server -> AgentsMc.session().mcp.port());
			ctx.runOnClient(mc -> mc.gui.hud.vignetteBrightness = 0);

			BlockPos h = world.getServer().computeOnServer(server -> host(server).blockPosition());
			int x = h.getX(), y = h.getY(), z = h.getZ();

			// The course: a step, a wall with a gap, two oak trunks, and a target beyond.
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d stone", x - 8, y, z + 10, x + 8, y, z + 10));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d oak_planks", x - 8, y, z + 15, x + 8, y + 1, z + 15));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", x + 3, y, z + 15, x + 3, y + 1, z + 15));
			for (int i = 0; i < 4; i++) {
				run(String.format(Locale.ROOT, "setblock %d %d %d oak_log", x + 5, y + i, z + 24));
				run(String.format(Locale.ROOT, "setblock %d %d %d oak_log", x - 5, y + i, z + 24));
			}
			// A few trees to look at on the way, so the view isn't empty.
			for (int i = 0; i < 5; i++) run(String.format(Locale.ROOT, "setblock %d %d %d spruce_log", x + 12, y + i, z + 5));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d spruce_leaves", x + 10, y + 4, z + 3, x + 14, y + 6, z + 7));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d cobblestone", x - 14, y, z + 2, x - 10, y + 2, z + 6, "hollow"));

			call("spawn_agent", obj("name", AGENT, "x", x + 0.5, "y", (double) y, "z", z + 2.5, "gamemode", "survival"), 30);
			narrator = new Narrator(world, AGENT);
			run("give " + AGENT + " iron_axe");
			run("give " + AGENT + " iron_sword");
			run("give " + AGENT + " bread 8");
			call("look_at", obj("agent", AGENT, "x", x + 0.5, "y", y + 1.0, "z", z + 20.5), 30);
			// The host watches through the agent's eyes.
			run("gamemode spectator Player0");
			run("spectate " + AGENT + " Player0");
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			watch(60);
			ctx.takeScreenshot("pov-00-start");

			// 1. Walk the course.
			Vec3 goal = new Vec3(x + 0.5, y, z + 21.5);
			narrator.tool(String.format(Locale.ROOT, "goto x=%.0f y=%.0f z=%.0f", goal.x, goal.y, goal.z));
			call("goto", obj("agent", AGENT, "x", goal.x, "y", goal.y, "z", goal.z), 30);
			String woke = waitFor("goto", 60);
			LOG.info("goto: {}", woke);
			ctx.takeScreenshot("pov-01-arrived");

			// 2. Chop.
			narrator.tool("mine block=oak_log count=6");
			call("mine", obj("agent", AGENT, "block", "oak_log", "count", 6), 30);
			woke = waitFor("mine", 90);
			LOG.info("mine: {}", woke);
			ctx.takeScreenshot("pov-02-mined");

			// 3. A zombie.
			run(String.format(Locale.ROOT, "summon zombie %d %d %d {PersistenceRequired:1b}", x, y, z + 34));
			watch(40);
			narrator.tool("attack target=zombie");
			call("attack", obj("agent", AGENT, "target", "zombie"), 30);
			woke = waitFor("attack", 60);
			LOG.info("attack: {}", woke);
			boolean zombieAlive = world.getServer().computeOnServer(server -> !server.overworld().getEntitiesOfClass(Monster.class,
					new AABB(h).inflate(64), Entity::isAlive).isEmpty());
			if (zombieAlive) throw new AssertionError("The zombie should be dead: " + woke);
			ctx.takeScreenshot("pov-03-fought");

			// 4. Follow another agent around.
			call("spawn_agent", obj("name", GUIDE, "x", x + 2.5, "y", (double) y, "z", z + 26.5, "gamemode", "survival"), 30);
			watch(20);
			narrator.tool("follow player=" + GUIDE + " distance=3");
			call("follow", obj("agent", AGENT, "player", GUIDE, "distance", 3), 30);
			int[][] route = {{x - 12, z + 30}, {x - 12, z + 12}, {x + 6, z + 8}};
			for (int[] p : route) {
				call("goto", obj("agent", GUIDE, "x", p[0] + 0.5, "y", (double) y, "z", p[1] + 0.5), 30);
				waitForGuide(40);
			}
			watch(40);
			double gap = world.getServer().computeOnServer(server -> AgentsMc.session().agents.get(AGENT).distanceTo(AgentsMc.session().agents.get(GUIDE)));
			LOG.info("follow: {} blocks from the guide", gap);
			if (gap > 7) throw new AssertionError("Agent should be near the guide, but is " + gap + " blocks away");
			narrator.tool("cancel");
			call("cancel", obj("agent", AGENT), 30);
			call("despawn_agent", obj("agent", GUIDE), 30);
			ctx.takeScreenshot("pov-04-followed");

			// 5. A creeper from the front.
			watch(20);
			Vec3 before = agentPos();
			Vec3 facing = world.getServer().computeOnServer(server -> AgentsMc.session().agents.get(AGENT).getLookAngle());
			Vec3 creeper = before.add(facing.x * 3, 0, facing.z * 3);
			run(String.format(Locale.ROOT, "summon creeper %.1f %.1f %.1f {ExplosionRadius:0b,PersistenceRequired:1b}", creeper.x, before.y, creeper.z));
			watch(100);
			LOG.info("creeper: moved {} blocks", agentPos().distanceTo(before));
			ctx.takeScreenshot("pov-05-creeper");
			LOG.info("sensed:\n{}", narrator.sensedText());
			watch(40);
		}
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
		return t.lines().findFirst().orElse(t);
	}

	/** Waits for the guide's goto to end (its own cursor, so the narrator isn't disturbed). */
	private void waitForGuide(int seconds) {
		CompletableFuture<JsonObject> pending = callAsync("wait", obj("agent", GUIDE, "seconds", seconds, "until", "intent", "min_salience", 2));
		int ticks = 0;
		while (!pending.isDone() && ticks < (seconds + 10) * 20) {
			watch(2);
			ticks += 2;
		}
	}

	/** Lets ticks pass while the chat narrates. The camera is the agent's own. */
	private void watch(int ticks) {
		for (int i = 0; i < ticks; i += 2) {
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
