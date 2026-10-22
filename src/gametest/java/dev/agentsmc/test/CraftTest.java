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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
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
 * Gets things the survival way in a real client: from 3 oak logs, 8 cobblestone and a raw chicken, the
 * agent plans a wooden pickaxe, crafts planks, sticks and a crafting table, crafts the pickaxe (placing
 * the table itself), crafts a furnace, cooks the chicken with planks as fuel, takes it out, and puts
 * things into and takes them out of a chest. Set AGENTSMC_ONLY=craft to run just this test.
 */
public class CraftTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final String AGENT = "Smith";

	private int port;
	private ClientGameTestContext ctx;
	private TestSingleplayerContext world;
	private Narrator narrator;
	private int watched;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("craft")) return;
		this.ctx = ctx;
		ctx.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			run("time set noon");
			run("weather clear");
			run("gamerule send_command_feedback false");
			run("gamerule spawn_monsters false");
			run("gamerule advance_time false");
			port = world.getServer().computeOnServer(server -> AgentsMc.session().mcp.port());
			ctx.runOnClient(mc -> mc.gui.hud.vignetteBrightness = 0);

			BlockPos h = world.getServer().computeOnServer(server -> host(server).blockPosition());
			int x = h.getX(), y = h.getY(), z = h.getZ();
			run(String.format(Locale.ROOT, "tp Player0 %d %d %d 0 20", x, y, z));

			// ---- setup: a survival agent with raw materials, in front of the host ----
			LOG.info("spawn: {}", text(call("spawn_agent", obj("name", AGENT, "x", x + 0.5, "y", (double) y, "z", z + 5.5, "gamemode", "survival"))));
			narrator = new Narrator(world, AGENT);
			call("set_reflexes", obj("agent", AGENT, "auto_eat", "false"));
			run("give " + AGENT + " oak_log 3");
			run("give " + AGENT + " cobblestone 8");
			run("give " + AGENT + " chicken 1");
			watch(20);

			// ---- recipe and plan_craft ----
			String recipe = ok("recipe", obj("agent", AGENT, "item", "wooden_pickaxe"));
			if (!recipe.contains("crafting table")) throw new AssertionError("recipe should say a pickaxe needs a crafting table: " + recipe);
			String plan = ok("plan_craft", obj("agent", AGENT, "item", "wooden_pickaxe"));
			if (!plan.contains("planks") || !plan.contains("stick")) throw new AssertionError("plan_craft should mention planks and sticks: " + plan);
			if (plan.contains("Missing:")) throw new AssertionError("3 logs are enough for a pickaxe and its table: " + plan);
			ok("plan_craft", obj("agent", AGENT, "item", "cooked_chicken"));

			// ---- craft: planks (a group), sticks and a table in the 2x2 grid ----
			ok("craft", obj("agent", AGENT, "item", "planks", "count", 12));
			expect("oak_planks", 12);
			ok("craft", obj("agent", AGENT, "item", "stick", "count", 4));
			expect("stick", 4);
			ok("craft", obj("agent", AGENT, "item", "crafting_table"));
			expect("crafting_table", 1);

			// ---- craft a pickaxe: needs the 3x3 grid, so the agent places its table ----
			String pick = ok("craft", obj("agent", AGENT, "item", "wooden_pickaxe"));
			if (!pick.contains("Placed a crafting_table")) throw new AssertionError("The agent should have placed its crafting table: " + pick);
			expect("wooden_pickaxe", 1);
			expect("crafting_table", 0);
			ok("craft", obj("agent", AGENT, "item", "furnace"));
			expect("furnace", 1);

			// ---- smelt the chicken with planks; the furnace is placed from the inventory ----
			String smelt = ok("smelt", obj("agent", AGENT, "item", "chicken", "fuel", "planks"));
			if (!smelt.contains("Placed a furnace")) throw new AssertionError("The agent should have placed its furnace: " + smelt);
			expect("chicken", 0);
			LOG.info("look: {}", ok("container", obj("agent", AGENT, "action", "look")));
			watch(240);
			LOG.info("look: {}", ok("container", obj("agent", AGENT, "action", "look")));
			ok("container", obj("agent", AGENT, "action", "take"));
			expect("cooked_chicken", 1);

			// ---- a chest the host sets down: put and take ----
			BlockPos chest = world.getServer().computeOnServer(server -> {
				BlockPos feet = agent(server).blockPosition();
				// Two blocks away with the block in between clear, so the agent can see it (a chest
				// behind the furnace it just used must not count as reachable).
				for (BlockPos[] c : new BlockPos[][] {{feet.north(2), feet.north()}, {feet.south(2), feet.south()}, {feet.east(2), feet.east()}, {feet.west(2), feet.west()}}) {
					var level = server.overworld();
					if (level.getBlockState(c[0]).isAir() && level.getBlockState(c[0].above()).isAir() && level.getBlockState(c[1]).isAir() && level.getBlockState(c[1].above()).isAir()) return c[0];
				}
				throw new AssertionError("No room for a chest near the agent");
			});
			run(String.format(Locale.ROOT, "setblock %d %d %d chest", chest.getX(), chest.getY(), chest.getZ()));
			watch(10);
			ok("container", obj("agent", AGENT, "action", "put", "item", "cooked_chicken", "x", chest.getX(), "y", chest.getY(), "z", chest.getZ()));
			ok("container", obj("agent", AGENT, "action", "put", "item", "stick", "count", 1, "x", chest.getX(), "y", chest.getY(), "z", chest.getZ()));
			expect("cooked_chicken", 0);
			expect("stick", 1);
			int inChest = world.getServer().computeOnServer(server -> chestCount(server, chest, "cooked_chicken") + chestCount(server, chest, "stick"));
			if (inChest != 2) throw new AssertionError("The chest should hold 1 cooked_chicken and 1 stick, found " + inChest + " items");
			watch(20);
			ok("container", obj("agent", AGENT, "action", "take", "item", "cooked_chicken", "x", chest.getX(), "y", chest.getY(), "z", chest.getZ()));
			expect("cooked_chicken", 1);
			ok("container", obj("agent", AGENT, "action", "take", "x", chest.getX(), "y", chest.getY(), "z", chest.getZ()));
			expect("stick", 2);
			watch(20);
			LOG.info("craft test passed");
		}
	}

	// ---- assertions ------------------------------------------------------------------------------

	/** Calls a tool, logs its result and fails the test if it reported an error. */
	private String ok(String tool, JsonObject args) {
		narrator.tool(tool + " " + args.toString().replace("\"agent\":\"" + AGENT + "\",", ""));
		JsonObject r = call(tool, args);
		String t = text(r);
		LOG.info("{}: {}", tool, t);
		if (r.has("isError") && r.get("isError").getAsBoolean()) throw new AssertionError(tool + " failed: " + t);
		watch(10);
		return t.split("--- meanwhile ---")[0].strip();
	}

	private void expect(String item, int count) {
		int have = count(item);
		if (have != count) throw new AssertionError("Expected " + count + " " + item + " in the inventory, found " + have);
	}

	private int count(String item) {
		return world.getServer().computeOnServer(server -> {
			int n = 0;
			for (ItemStack s : agent(server).getInventory().getNonEquipmentItems()) {
				if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(item)) n += s.getCount();
			}
			return n;
		});
	}

	private static int chestCount(MinecraftServer server, BlockPos pos, String item) {
		if (!(server.overworld().getBlockEntity(pos) instanceof Container c)) throw new AssertionError("No chest at " + pos);
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(item)) n += s.getCount();
		}
		return n;
	}

	// ---- world helpers ---------------------------------------------------------------------------

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

	private void run(String command) {
		world.getServer().runCommand(command);
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

	private JsonObject call(String tool, JsonObject args) {
		CompletableFuture<JsonObject> f = callAsync(tool, args);
		int ticks = 0;
		while (!f.isDone() && ticks < 30 * 20) {
			watch(2);
			ticks += 2;
		}
		if (!f.isDone()) throw new AssertionError(tool + " didn't return in 30s");
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
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	private static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			String k = (String) kv[i];
			Object v = kv[i + 1];
			if (v instanceof Number num) o.addProperty(k, num);
			else if ("false".equals(v) || "true".equals(v)) o.addProperty(k, Boolean.parseBoolean((String) v));
			else o.addProperty(k, v.toString());
		}
		return o;
	}
}
