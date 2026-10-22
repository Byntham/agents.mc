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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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
 * Drives a survival agent body through an obstacle course in a real client: walk over a step and
 * through a gap, chop logs, kill a zombie, follow the host around, and back away from a creeper. The
 * host's camera tracks the agent the whole time and the chat shows what the agent senses, so a screen
 * recording of this test shows it working. Set AGENTSMC_ONLY=body to run just this test.
 */
public class BodyTest implements FabricClientGameTest {
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
		if (TestFilter.skip("body")) return;
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
			// A flat test world: the host stands at spawn. Everything is laid out south of them.
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 10", x, y, z));

			// ---- setup: a survival agent with tools, in front of the host ----
			JsonObject spawned = call("spawn_agent", obj("name", AGENT, "x", x + 0.5, "y", (double) y, "z", z + 5.5, "gamemode", "survival"), 30);
			LOG.info("spawn: {}", text(spawned));
			narrator = new Narrator(world, AGENT);
			run("give " + AGENT + " iron_pickaxe");
			run("give " + AGENT + " iron_axe");
			run("give " + AGENT + " iron_sword");
			run("give " + AGENT + " bread 8");
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			watch(40);
			ctx.takeScreenshot("body-00-spawned");

			// ---- 1. goto: over a one-block step and through a gap in a two-block wall ----
			vantage(x + 9, y, z + 8, -140, 15);
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d stone", x - 8, y, z + 10, x + 8, y, z + 10));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d oak_planks", x - 8, y, z + 15, x + 8, y + 1, z + 15));
			run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", x + 3, y, z + 15, x + 3, y + 1, z + 15));
			Vec3 goal = new Vec3(x + 0.5, y, z + 21.5);
			narrator.tool(String.format(Locale.ROOT, "goto x=%.0f y=%.0f z=%.0f", goal.x, goal.y, goal.z));
			JsonObject go = call("goto", obj("agent", AGENT, "x", goal.x, "y", goal.y, "z", goal.z), 30);
			LOG.info("goto: {}", text(go));
			if (!text(go).contains("goto#")) throw new AssertionError("goto didn't start: " + text(go));
			String woke = waitFor("goto", 60);
			Vec3 at = agentPos();
			LOG.info("goto ended ({}): agent at {}, {} from the goal", woke, at, at.distanceTo(goal));
			if (at.distanceTo(goal) > 2.5) throw new AssertionError("Agent should have reached " + goal + " but is at " + at + ": " + woke);
			ctx.takeScreenshot("body-01-arrived");

			// ---- 2. mine: two oak trunks nearby, with an iron axe ----
			vantage(x, y, z + 17, 0, 10);
			for (int i = 0; i < 4; i++) {
				run(String.format(Locale.ROOT, "setblock %d %d %d oak_log", x + 5, y + i, z + 24));
				run(String.format(Locale.ROOT, "setblock %d %d %d oak_log", x - 5, y + i, z + 24));
			}
			watch(10);
			narrator.tool("mine block=oak_log count=6");
			JsonObject mine = call("mine", obj("agent", AGENT, "block", "oak_log", "count", 6), 30);
			LOG.info("mine: {}", text(mine));
			woke = waitFor("mine", 90);
			int logsLeft = world.getServer().computeOnServer(server -> {
				int n = 0;
				for (int i = 0; i < 4; i++) {
					if (server.overworld().getBlockState(new BlockPos(x + 5, y + i, z + 24)).is(Blocks.OAK_LOG)) n++;
					if (server.overworld().getBlockState(new BlockPos(x - 5, y + i, z + 24)).is(Blocks.OAK_LOG)) n++;
				}
				return n;
			});
			int logsHeld = count("oak_log");
			LOG.info("mine ended ({}): {} logs left standing, {} in inventory", woke, logsLeft, logsHeld);
			if (logsLeft > 2) throw new AssertionError("Expected at most 2 of 8 logs left, found " + logsLeft + ": " + woke);
			if (logsHeld < 3) throw new AssertionError("Expected at least 3 oak logs in the inventory, found " + logsHeld + ": " + woke);
			ctx.takeScreenshot("body-02-mined");

			// ---- 3. attack: a zombie spawns 10 blocks away; the senses should notice it, then the sword ends it ----
			vantage(x - 8, y, z + 26, -90, 10);
			run(String.format(Locale.ROOT, "summon zombie %d %d %d {PersistenceRequired:1b}", x, y, z + 32));
			watch(40);
			String events = narrator.sensedText();
			LOG.info("sensed so far:\n{}", events);
			if (!events.toLowerCase(Locale.ROOT).contains("zombie")) throw new AssertionError("The senses should have reported the zombie: " + events);
			narrator.tool("attack target=zombie");
			JsonObject attack = call("attack", obj("agent", AGENT, "target", "zombie"), 30);
			LOG.info("attack: {}", text(attack));
			woke = waitFor("attack", 60);
			boolean zombieAlive = world.getServer().computeOnServer(server -> !server.overworld().getEntitiesOfClass(Monster.class,
					new AABB(h).inflate(64), Entity::isAlive).isEmpty());
			LOG.info("attack ended ({}): zombie alive = {}", woke, zombieAlive);
			if (zombieAlive) throw new AssertionError("The zombie should be dead: " + woke);
			ctx.takeScreenshot("body-03-fought");

			// ---- 4. follow: the host walks away and turns; the agent keeps up ----
			narrator.tool("follow player=Player0 distance=3");
			JsonObject follow = call("follow", obj("agent", AGENT, "player", "Player0", "distance", 3), 30);
			LOG.info("follow: {}", text(follow));
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 90 0", x - 6, y, z + 4)); // face west
			ctx.waitTicks(10);
			ctx.getInput().holdKey(o -> o.keyUp);
			lookFor(60, 90, 0); // west for 3 s
			lookFor(60, 180, 0); // north for 3 s
			lookFor(40, -90, 0); // east for 2 s
			ctx.getInput().releaseKey(o -> o.keyUp);
			watch(80);
			double gap = agentPos().distanceTo(hostPos());
			LOG.info("follow: agent is {} blocks from the host", gap);
			if (gap > 7) throw new AssertionError("Agent should be near the host after following, but is " + gap + " blocks away");
			ctx.takeScreenshot("body-04-followed");
			narrator.tool("cancel");
			LOG.info("cancel: {}", text(call("cancel", obj("agent", AGENT), 30)));

			// ---- 5. creeper: it hisses, the senses hear it, the body backs off ----
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d", x - 12, y, z + 4));
			watch(20);
			Vec3 before = agentPos();
			Vec3 facing = world.getServer().computeOnServer(server -> agent(server).getLookAngle());
			Vec3 creeper = before.add(facing.x * 2.5, 0, facing.z * 2.5);
			run(String.format(Locale.ROOT, "summon creeper %.1f %.1f %.1f {ExplosionRadius:0b,PersistenceRequired:1b}", creeper.x, before.y, creeper.z));
			watch(80);
			events = narrator.sensedText();
			LOG.info("sensed so far:\n{}", events);
			Vec3 after = agentPos();
			LOG.info("creeper: moved {} blocks", after.distanceTo(before));
			if (!events.toLowerCase(Locale.ROOT).contains("creeper")) throw new AssertionError("The senses should have reported the creeper: " + events);
			ctx.takeScreenshot("body-05-creeper");

			// ---- 6. the cheap looks: status line, sweep ----
			LOG.info("sweep:\n{}", text(call("sweep", obj("agent", AGENT), 30)));
			LOG.info("observe:\n{}", text(call("observe", obj("agent", AGENT, "radius", 8), 30)));
			watch(40);
		}
	}

	// ---- waiting while the camera follows the agent ----------------------------------------------

	/** Waits for the agent's current intent to end (via the wait tool), keeping the camera on the agent. */
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

	/** Lets {@code ticks} pass while the host keeps looking at the agent and the chat narrates. */
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

	/** Holds the host's view at a yaw and pitch for a number of ticks (while a movement key is held). */
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

	// ---- world helpers ---------------------------------------------------------------------------

	/** Moves the host to a spot with a view of the next phase. */
	private void vantage(int x, int y, int z, float yaw, float pitch) {
		run(String.format(Locale.ROOT, "tp Player0 %d %d %d %.0f %.0f", x, y, z, yaw, pitch));
		watch(10);
	}

	private void run(String command) {
		world.getServer().runCommand(command);
	}

	private Vec3 agentPos() {
		return world.getServer().computeOnServer(server -> agent(server).position());
	}

	private Vec3 hostPos() {
		return world.getServer().computeOnServer(server -> host(server).position());
	}

	private int count(String item) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (ItemStack s : agent(server).getInventory().getNonEquipmentItems()) {
				if (!s.isEmpty() && s.getItem().toString().contains(item)) n += s.getCount();
			}
			return n;
		});
	}

	private static AgentPlayer agent(MinecraftServer server) {
		AgentPlayer a = AgentsMc.session().agents.find(AGENT);
		if (a == null) throw new AssertionError("The agent body is gone");
		return a;
	}

	private static ServerPlayer host(MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow();
	}

	// ---- MCP -------------------------------------------------------------------------------------

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

	static String text(JsonObject result) {
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			String k = (String) kv[i];
			Object v = kv[i + 1];
			if (v instanceof Number num) o.addProperty(k, num);
			else o.addProperty(k, v.toString());
		}
		return o;
	}
}
