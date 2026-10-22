package dev.agentsmc.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.agent.AgentPlayer;
import dev.agentsmc.body.Intent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
 * Digging like a survival player, in a flat world with a block of stone built on it: mine coal
 * buried eight blocks under the agent (a staircase down), dig back up to the surface after the way
 * down is filled in, and report the cost honestly when there's no pickaxe. Set AGENTSMC_ONLY=dig.
 */
public class DigTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final String AGENT = "Digger";

	private int port;
	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private Narrator narrator;
	private int watched;
	/** Largest distance the agent moved between two samples two ticks apart; a teleport shows up here. */
	private double maxStep;
	private Vec3 lastSample;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("dig")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		freeMcpPort();
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

			// A 13x12x13 block of stone south of the host, with coal 8 blocks under the middle of its top.
			int cx = x, cz = z + 12, top = y + 12;
			mound(cx, cz, y, top);
			BlockPos coal = new BlockPos(cx, top - 8, cz);
			run(String.format(Locale.ROOT, "setblock %d %d %d coal_ore", coal.getX(), coal.getY(), coal.getZ()));
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 30", cx - 5, top, cz - 6));
			run("effect give Player0 minecraft:night_vision infinite 0 true");

			JsonObject spawned = call("spawn_agent", obj("name", AGENT, "x", cx + 0.5, "y", (double) top, "z", cz + 0.5, "gamemode", "survival"), 30);
			LOG.info("spawn: {}", text(spawned));
			narrator = new Narrator(world, AGENT);
			run("give " + AGENT + " iron_pickaxe");
			watch(40);

			// ---- 1. mine buried coal with an iron pickaxe: a staircase down ----
			narrator.tool("mine block=coal_ore");
			String mine = text(call("mine", obj("agent", AGENT, "block", "coal_ore"), 30));
			LOG.info("mine: {}", mine);
			if (!mine.contains("digging")) throw new AssertionError("mine should say it will dig to the coal: " + mine);
			String outcome = finish("mine", 180);
			boolean coalGone = world.getServer().computeOnServer(server -> !server.overworld().getBlockState(coal).is(Blocks.COAL_ORE));
			int coalHeld = countCoal();
			LOG.info("mine ended: {}; coal ore gone = {}, coal held = {}, largest step = {}", outcome, coalGone, coalHeld, maxStep);
			LOG.info("agent at {}, coal was at {}; items around: {}", agentPos(), coal, world.getServer().computeOnServer(server ->
					server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(coal).inflate(10), e -> true)
							.stream().map(e -> e.getItem() + " at " + e.position()).toList()));
			if (!coalGone) throw new AssertionError("The coal ore should be mined: " + outcome);
			if (coalHeld < 1) throw new AssertionError("The agent should have picked up coal: " + outcome);
			if (!outcome.contains("dug through")) throw new AssertionError("mine should report what it dug: " + outcome);
			if (maxStep > 2.5) throw new AssertionError("The agent moved " + maxStep + " blocks in two ticks: that's a teleport");
			ctx.takeScreenshot("dig-01-mined");

			// ---- 2. the way down gets filled in; goto the surface digs a staircase up ----
			BlockPos at = world.getServer().computeOnServer(server -> agent(server).blockPosition());
			fill(cx - 6, y, cz - 6, cx + 6, top - 1, cz + 6, "stone replace air");
			fill(at.getX() - 1, at.getY(), at.getZ() - 1, at.getX() + 1, at.getY() + 1, at.getZ() + 1, "air"); // room to stand, nothing to walk out by
			float health = world.getServer().computeOnServer(server -> agent(server).getHealth());
			watch(20);
			Vec3 goal = new Vec3(cx + 3.5, top, cz + 0.5);
			narrator.tool(String.format(Locale.ROOT, "goto x=%.1f y=%.0f z=%.1f", goal.x, goal.y, goal.z));
			String go = text(call("goto", obj("agent", AGENT, "x", goal.x, "y", goal.y, "z", goal.z), 30));
			LOG.info("goto from {}: {}", at, go);
			if (!go.contains("digging")) throw new AssertionError("goto should say it will dig up: " + go);
			maxStep = 0;
			outcome = finish("goto", 180);
			Vec3 end = agentPos();
			float after = world.getServer().computeOnServer(server -> agent(server).getHealth());
			LOG.info("goto ended: {}; agent at {}, {} from the goal, health {} -> {}, largest step = {}", outcome, end, end.distanceTo(goal), health, after, maxStep);
			if (end.distanceTo(goal) > 2.5 || end.y < top - 0.1) throw new AssertionError("The agent should be back on the surface near " + goal + " but is at " + end + ": " + outcome);
			if (!outcome.contains("digging through")) throw new AssertionError("goto should report what it dug: " + outcome);
			if (after < health) throw new AssertionError("The agent lost health on the way up (" + health + " -> " + after + "): a fall?");
			if (maxStep > 2.5) throw new AssertionError("The agent moved " + maxStep + " blocks in two ticks: that's a teleport");
			ctx.takeScreenshot("dig-02-surfaced");

			// ---- 3. no pickaxe: the estimate says how slow digging by hand is ----
			int bx = x + 20, bz = cz;
			mound(bx, bz, y, top);
			run(String.format(Locale.ROOT, "setblock %d %d %d coal_ore", bx, top - 8, bz));
			run("clear " + AGENT);
			run(String.format(Locale.ROOT, "tp %s %.1f %d %.1f", AGENT, bx + 0.5, top, bz + 0.5));
			watch(20);
			narrator.tool("mine block=coal_ore (no pickaxe)");
			String bare = text(call("mine", obj("agent", AGENT, "block", "coal_ore"), 30));
			LOG.info("mine without a pickaxe: {}", bare);
			if (!bare.contains("pickaxe")) throw new AssertionError("Without a pickaxe, mine should say a pickaxe would be faster: " + bare);
			LOG.info("cancel: {}", text(call("cancel", obj("agent", AGENT), 30)));
			watch(20);
		}
	}

	/**
	 * A dev client left running (runClient) holds the configured MCP port, and the mod reads its config
	 * when the world starts. If the port is taken, point this run's config at a free one first.
	 */
	private static void freeMcpPort() {
		java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("agentsmc.json");
		try {
			JsonObject config = java.nio.file.Files.exists(file) ? JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject() : new JsonObject();
			int port = config.has("port") ? config.get("port").getAsInt() : 25590;
			try (java.net.ServerSocket probe = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
				return; // free
			} catch (java.io.IOException taken) {
				try (java.net.ServerSocket any = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
					config.addProperty("port", any.getLocalPort());
				}
			}
			java.nio.file.Files.writeString(file, config.toString());
			LOG.info("MCP port {} is taken; this run uses {}", port, config.get("port"));
		} catch (java.io.IOException | RuntimeException e) {
			LOG.warn("Couldn't move the MCP port", e);
		}
	}

	/** Solid stone from {@code bottom} to {@code top - 1}, 13 blocks square around (cx, cz). */
	private void mound(int cx, int cz, int bottom, int top) {
		fill(cx - 6, bottom, cz - 6, cx + 6, top - 1, cz + 6, "stone");
	}

	private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String what) {
		run(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x1, y1, z1, x2, y2, z2, what));
	}

	/** Waits for the agent's current intent of this kind to end and returns its outcome. */
	private String finish(String kind, int seconds) {
		int id = world.getServer().computeOnServer(server -> {
			Intent i = agent(server).body().current();
			return i != null && i.kind().equals(kind) ? i.id() : -1;
		});
		for (int t = 0; t < seconds * 20; t += 2) {
			String done = world.getServer().computeOnServer(server -> {
				var body = agent(server).body();
				if (body.current() != null && body.current().id() == id) return null;
				if (body.paused() != null && body.paused().id() == id) return null;
				for (Intent i : body.recent()) if (i.id() == id) return i.tag() + " " + i.status() + ": " + i.outcome();
				return "gone";
			});
			if (done != null) return done;
			if (t % 100 == 0) LOG.info("{}: {}", kind, world.getServer().computeOnServer(server -> agent(server).body().doing()));
			watch(2);
		}
		throw new AssertionError(kind + " didn't finish in " + seconds + "s: " + world.getServer().computeOnServer(server -> agent(server).body().doing()));
	}

	/** Lets ticks pass while the host looks at the agent and the chat narrates. Samples the agent's position. */
	private void watch(int ticks) {
		for (int i = 0; i < ticks; i += 2) {
			Vec3[] at = world.getServer().computeOnServer(server -> {
				AgentPlayer a = AgentsMc.session().agents.find(AGENT);
				return a == null ? null : new Vec3[]{a.getEyePosition(), a.position()};
			});
			if (at != null) {
				ctx.runOnClient(mc -> {
					if (mc.player != null) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, at[0]);
				});
				if (lastSample != null) maxStep = Math.max(maxStep, at[1].distanceTo(lastSample));
				lastSample = at[1];
			}
			if (narrator != null && (watched += 2) % 6 == 0) narrator.tick();
			ctx.waitTicks(2);
		}
	}

	private void run(String command) {
		world.getServer().runCommand(command);
		lastSample = null; // a command may move the agent; that isn't the agent teleporting
	}

	private Vec3 agentPos() {
		return world.getServer().computeOnServer(server -> agent(server).position());
	}

	private int countCoal() {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (ItemStack s : agent(server).getInventory().getNonEquipmentItems()) {
				if (s.is(Items.COAL)) n += s.getCount();
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

	private JsonObject call(String tool, JsonObject args, int timeoutS) {
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
		CompletableFuture<JsonObject> f = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result");
			if (result == null) throw new AssertionError(tool + " failed: " + response.body());
			return result;
		});
		for (int t = 0; !f.isDone() && t < timeoutS * 20; t += 2) watch(2);
		if (!f.isDone()) throw new AssertionError(tool + " didn't return in " + timeoutS + "s");
		return f.join();
	}

	private static String text(JsonObject result) {
		return BodyTest.text(result);
	}

	private static JsonObject obj(Object... kv) {
		return BodyTest.obj(kv);
	}
}
