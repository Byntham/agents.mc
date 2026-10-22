package dev.agentsmc.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.agent.AgentPlayer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * A survival body given a build_layers plan builds it by hand: a 5x5 cobblestone hut, three high,
 * with a doorway, placed from its inventory bottom layer first. It carries too little glass for the
 * windows, so those are reported missing. AGENTSMC_ONLY=build.
 */
public class BuildTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final String AGENT = "Mason";

	private int port;
	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("build")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		LOG.info("recording can start");
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			run("time set noon");
			run("gamerule send_command_feedback false");
			run("gamerule spawn_monsters false");
			run("gamerule advance_time false");
			port = world.getServer().computeOnServer(server -> AgentsMc.session().mcp.port());
			BlockPos h = world.getServer().computeOnServer(server -> host(server).blockPosition());
			int x = h.getX(), y = h.getY(), z = h.getZ();
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 20", x, y + 1, z - 2));

			call("spawn_agent", obj("name", AGENT, "x", x + 0.5, "y", (double) y, "z", z + 3.5, "gamemode", "survival"));
			run("give " + AGENT + " cobblestone 64");
			run("give " + AGENT + " glass 1");
			run("give " + AGENT + " oak_door 1");
			ctx.waitTicks(20);

			// A 5x5 hut: walls three high with a door on the north side and two windows. The door is
			// one item for two cells; the plan names both halves, and the lower one makes the upper.
			JsonObject args = new JsonObject();
			args.addProperty("agent", AGENT);
			args.addProperty("x", x + 3);
			args.addProperty("y", y);
			args.addProperty("z", z + 6);
			JsonObject palette = new JsonObject();
			palette.addProperty("c", "cobblestone");
			palette.addProperty("g", "glass");
			palette.addProperty("d", "oak_door[facing=south,half=lower]");
			palette.addProperty("D", "oak_door[facing=south,half=upper]");
			args.add("palette", palette);
			JsonArray layers = new JsonArray();
			layers.add(rows("ccdcc", "c...c", "c...c", "c...c", "ccccc"));
			layers.add(rows("ccDcc", "g...g", "c...c", "c...c", "ccccc"));
			layers.add(rows("ccccc", "c...c", "c...c", "c...c", "ccccc"));
			args.add("layers", layers);
			JsonObject started = call("build_layers", args);
			LOG.info("build: {}", text(started));
			if (!text(started).contains("build#")) throw new AssertionError("Expected a build job in survival: " + text(started));
			if (!text(started).contains("glass")) throw new AssertionError("Expected the shortfall of glass to be reported: " + text(started));

			String woke = waitFor(240);
			LOG.info("build ended: {}", woke);
			int cobble = world.getServer().computeOnServer(server -> {
				int n = 0;
				for (BlockPos p : BlockPos.betweenClosed(new BlockPos(x + 3, y, z + 6), new BlockPos(x + 7, y + 2, z + 10))) {
					if (server.overworld().getBlockState(p).is(Blocks.COBBLESTONE)) n++;
				}
				return n;
			});
			int held = count("cobblestone");
			LOG.info("cobblestone placed: {} (expected 42), left in inventory: {}", cobble, held);
			if (cobble < 40) throw new AssertionError("Expected the hut's cobblestone placed, found " + cobble + ": " + woke);
			if (held > 24) throw new AssertionError("The cobblestone should have come from the inventory, " + held + " left");
			if (!woke.contains("glass")) throw new AssertionError("The missing glass should be reported: " + woke);
			boolean door = world.getServer().computeOnServer(server -> server.overworld().getBlockState(new BlockPos(x + 5, y, z + 6)).is(Blocks.OAK_DOOR)
					&& server.overworld().getBlockState(new BlockPos(x + 5, y + 1, z + 6)).is(Blocks.OAK_DOOR));
			if (!door) throw new AssertionError("Both halves of the door should stand in the doorway: " + woke);
			if (count("oak_door") != 0) throw new AssertionError("The door should have cost its one item");
			ctx.takeScreenshot("build-01-hut");
		}
	}

	private static JsonArray rows(String... rows) {
		JsonArray a = new JsonArray();
		for (String r : rows) a.add(r);
		return a;
	}

	private String waitFor(int seconds) {
		long end = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < end) {
			JsonObject r = call("wait", obj("agent", AGENT, "seconds", 45, "until", "intent", "min_salience", 2));
			String t = text(r);
			if (t.startsWith("Waited")) return t.lines().findFirst().orElse(t);
		}
		throw new AssertionError("The build didn't finish in " + seconds + "s");
	}

	private void run(String command) {
		world.getServer().runCommand(command);
	}

	private int count(String item) {
		return world.getServer().computeOnServer(server -> {
			AgentPlayer a = AgentsMc.session().agents.find(AGENT);
			int n = 0;
			for (ItemStack s : a.getInventory().getNonEquipmentItems()) if (!s.isEmpty() && s.getItem().toString().contains(item)) n += s.getCount();
			return n;
		});
	}

	private static ServerPlayer host(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow();
	}

	private JsonObject call(String tool, JsonObject args) {
		JsonObject params = new JsonObject();
		params.addProperty("name", tool);
		params.add("arguments", args);
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", 1);
		msg.addProperty("method", "tools/call");
		msg.add("params", params);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(msg.toString())).build();
		CompletableFuture<HttpResponse<String>> f = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		ctx.waitFor(mc -> f.isDone(), 20 * 70);
		JsonObject result = JsonParser.parseString(f.join().body()).getAsJsonObject().getAsJsonObject("result");
		if (result == null) throw new AssertionError(tool + " failed: " + f.join().body());
		return result;
	}

	private static String text(JsonObject result) {
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	private static JsonObject obj(Object... kv) {
		return BodyTest.obj(kv);
	}

	static List<String> none() {
		return List.of();
	}
}
