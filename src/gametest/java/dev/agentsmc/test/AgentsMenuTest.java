package dev.agentsmc.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentsmc.AgentsMc;
import dev.agentsmc.Session;
import dev.agentsmc.brain.ActivityLog;
import dev.agentsmc.brain.AgentBrain;
import dev.agentsmc.client.AgentsMcClient;
import dev.agentsmc.client.AgentsScreen;
import dev.agentsmc.net.MenuPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Drives the agents menu in a real client and takes screenshots (build/run/clientGameTest/screenshots).
 *
 * <p>The first part uses a fake claude (fake_claude.py) so it is quick and repeatable. Set
 * AGENTSMC_LIVE=1 to also run an agent with the real Claude Code CLI: it builds something, the world
 * is closed and reopened, and the agent is asked about it again to check the conversation resumed.
 */
public class AgentsMenuTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1280, 720);
		withFakeClaude(ctx);
		if ("1".equals(System.getenv("AGENTSMC_LIVE"))) withRealClaude(ctx);
	}

	private void withFakeClaude(ClientGameTestContext ctx) {
		writeConfig(fakeClaude().toString(), "", "");
		TestWorldSave save;
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("time set day");
			world.getServer().runCommand("weather clear");

			openMenu(ctx);
			ctx.takeScreenshot("01-empty");

			// New agent: the + button, a name, a first task in the message box, then a model and effort.
			clickGui(ctx, sideWidth(ctx) - 14, 12);
			ctx.getInput().typeChars("Builder");
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			ctx.getInput().typeChars("Build me a small oak cabin next to me");
			pick(ctx, "model", "haiku", "02-model-list");
			pick(ctx, "effort", "low", "02-effort-list");
			ctx.waitTicks(2);
			ctx.takeScreenshot("02-new-agent");
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);

			waitForBrain(world, "Builder", b -> b.status() == AgentBrain.Status.WORKING && b.log.tail(50).size() >= 10, 600);
			expectSettings(world, "Builder", "haiku", "low");
			List<JsonObject> launches = fakeLog(world, "Builder").stream().filter(o -> o.has("argv")).toList();
			if (launches.size() != 1 || !flag(launches.getFirst(), "--model", "haiku") || !flag(launches.getFirst(), "--effort", "low")) {
				throw new AssertionError("Builder should have launched once with --model haiku --effort low: " + launches);
			}
			ctx.waitTicks(25);
			ctx.takeScreenshot("03-working");

			// Mid-turn: switching the model reaches the running Claude Code without a restart.
			pick(ctx, "model", "sonnet", null);
			waitForBrain(world, "Builder", b -> b.model().equals("sonnet"), 100);
			waitForFakeLog(ctx, world, "Builder", log -> log.stream().anyMatch(o -> applied(o, "sonnet", "low")), 100);
			// The keyboard works too, with the pointer resting on the list: one row down from Low is Medium.
			clickGui(ctx, locate(ctx, "effort"));
			moveTo(ctx, locate(ctx, "option:low"));
			ctx.waitTicks(2);
			ctx.getInput().pressKey(InputConstants.KEY_DOWN);
			ctx.waitTicks(2);
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			waitForBrain(world, "Builder", b -> b.effort().equals("medium"), 100);
			waitForFakeLog(ctx, world, "Builder", log -> log.stream().anyMatch(o -> applied(o, "sonnet", "medium")), 100);
			// Default goes to Claude Code as null, which drops the --model it was launched with.
			pick(ctx, "model", "", null);
			waitForFakeLog(ctx, world, "Builder", log -> log.stream().anyMatch(o -> applied(o, "", "medium")), 100);
			pick(ctx, "model", "sonnet", null);
			waitForBrain(world, "Builder", b -> b.model().equals("sonnet"), 100);
			if (fakeLog(world, "Builder").stream().filter(o -> o.has("argv") || o.has("exit")).count() != 1) {
				throw new AssertionError("Switching models shouldn't restart Builder's Claude Code: " + fakeLog(world, "Builder"));
			}
			if (!world.getServer().computeOnServer(server -> AgentsMc.session().brains.get("Builder").status() == AgentBrain.Status.WORKING)) {
				throw new AssertionError("Builder should still be working after switching models");
			}
			// The server only takes what the menu offers.
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new MenuPayloads.Action("configure", "Builder", "", "opus & calc", "low")));
			ctx.waitTicks(10);
			expectSettings(world, "Builder", "sonnet", "medium");

			world.getServer().runCommand("agent spawn Scout quick: find a good spot for a watchtower");
			waitForBrain(world, "Scout", b -> b.status() == AgentBrain.Status.IDLE && has(b, ActivityLog.Kind.SAY), 600);

			// A message while Builder is mid-turn.
			ctx.getInput().typeChars("Make the roof spruce please");
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			ctx.waitTicks(25);
			ctx.takeScreenshot("04-steer-two-agents");

			clickRow(ctx, world, "Scout");
			ctx.waitTicks(25);
			ctx.takeScreenshot("05-scout");

			// An older Claude Code refuses to switch while running (the fake does that for names starting
			// with "Old"): the next message that starts a turn restarts it with the new settings.
			world.getServer().runCommand("agent spawn OldTimer quick: look around");
			waitForBrain(world, "OldTimer", b -> b.status() == AgentBrain.Status.IDLE && has(b, ActivityLog.Kind.SAY), 600);
			world.getServer().runOnServer(server -> AgentsMc.session().brains.get("OldTimer").configure("haiku", "high", "Player0"));
			waitForFakeLog(ctx, world, "OldTimer", log -> log.stream().anyMatch(o -> o.has("control")), 200);
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> mc.player.connection.sendChat("@OldTimer quick: look again"));
			waitForBrain(world, "OldTimer", b -> b.status() == AgentBrain.Status.IDLE && count(b, ActivityLog.Kind.SAY) >= 2, 600);
			List<JsonObject> oldLaunches = fakeLog(world, "OldTimer").stream().filter(o -> o.has("argv")).toList();
			JsonObject relaunch = oldLaunches.getLast();
			if (oldLaunches.size() != 2 || !flag(relaunch, "--model", "haiku") || !flag(relaunch, "--effort", "high") || !flag(relaunch, "--resume", null)) {
				throw new AssertionError("OldTimer should have resumed with --model haiku --effort high: " + oldLaunches);
			}

			// Bringing back an agent whose body is gone (kicked, or despawned over MCP) from the menu keeps its
			// own model and effort.
			world.getServer().runOnServer(server -> AgentsMc.session().agents.despawn("OldTimer", "Test"));
			world.getServer().waitFor(server -> AgentsMc.session().brains.body("OldTimer") == null, 100);
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new MenuPayloads.Action("spawn", "OldTimer", "", "", "")));
			world.getServer().waitFor(server -> AgentsMc.session().brains.body("OldTimer") != null, 100);
			expectSettings(world, "OldTimer", "haiku", "high");

			world.getServer().runCommand("agent stop Builder");
			waitForBrain(world, "Builder", b -> b.status() == AgentBrain.Status.IDLE, 200);
			clickRow(ctx, world, "Builder");
			ctx.waitTicks(25);
			ctx.takeScreenshot("06-stopped");

			ctx.getInput().resizeWindow(1920, 1080);
			ctx.waitTicks(25);
			ctx.takeScreenshot("07-1080p");
			ctx.getInput().resizeWindow(1280, 720);

			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			ctx.takeScreenshot("08-world");
			save = world.getWorldSave();
		}

		// A shared world could carry anything in its roster. Values the menu doesn't offer fall back to the default.
		Path roster = save.getSaveDirectory().resolve("agentsmc").resolve("agents.json");
		try {
			JsonArray saved = JsonParser.parseString(Files.readString(roster)).getAsJsonArray();
			for (JsonElement el : saved) {
				JsonObject a = el.getAsJsonObject();
				if (a.get("name").getAsString().equals("Scout")) {
					a.addProperty("model", "opus & calc");
					a.addProperty("effort", "ludicrous");
				}
			}
			Files.writeString(roster, saved.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Each agent's model and effort are saved with the world.
		try (TestSingleplayerContext world = save.open()) {
			world.getConnection().waitForChunksRender();
			expectSettings(world, "Builder", "sonnet", "medium");
			expectSettings(world, "OldTimer", "haiku", "high");
			expectSettings(world, "Scout", "", "");
		}
	}

	private void withRealClaude(ClientGameTestContext ctx) {
		// The config's model is what new agents start with; the menu sets the effort.
		writeConfig("", System.getenv().getOrDefault("AGENTSMC_LIVE_MODEL", "haiku"), "");
		TestWorldSave save;
		String sessionId;
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("time set day");
			world.getServer().runCommand("weather clear");
			openMenu(ctx);
			clickGui(ctx, sideWidth(ctx) - 14, 12);
			ctx.getInput().typeChars("Helper");
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			ctx.getInput().typeChars("Say hi in chat, then build a 5 by 5 cobblestone platform on the ground right next to me, and say in chat when it's done.");
			pick(ctx, "effort", "low", null);
			ctx.getInput().pressKey(InputConstants.KEY_RETURN);
			ctx.setScreen(() -> null);
			waitForBrain(world, "Helper", b -> b.status() == AgentBrain.Status.IDLE && has(b, ActivityLog.Kind.SAY)
					&& b.log.last().kind() == ActivityLog.Kind.INFO, 20 * 300);
			expectSettings(world, "Helper", System.getenv().getOrDefault("AGENTSMC_LIVE_MODEL", "haiku"), "low");
			int cobble = world.getServer().computeOnServer(server -> countNear(server.getPlayerList().getPlayers().stream()
					.filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow()));
			LOG.info("Cobblestone near the player after the live build: {}", cobble);
			ctx.waitTicks(10);
			ctx.takeScreenshot("10-live-world");
			openMenu(ctx);
			ctx.takeScreenshot("11-live-menu");
			ctx.setScreen(() -> null);
			dump(world, "Helper");
			if (cobble < 20) throw new AssertionError("Expected a cobblestone platform near the player, found " + cobble + " blocks");

			// Claude Code is still running: switching to Sonnet in the menu reaches it without a restart.
			openMenu(ctx);
			pick(ctx, "model", "sonnet", null);
			waitForBrain(world, "Helper", b -> b.model().equals("sonnet"), 100);
			ctx.setScreen(() -> null);
			askAndExpectModel(ctx, world, "@Helper in one short sentence, what did you just build? Answer in chat.", "sonnet");
			openMenu(ctx);
			ctx.takeScreenshot("12-live-switched");
			ctx.setScreen(() -> null);
			sessionId = world.getServer().computeOnServer(server -> AgentsMc.session().brains.get("Helper").sessionId());
			save = world.getWorldSave();
		}

		try (TestSingleplayerContext world = save.open()) {
			world.getConnection().waitForChunksRender();
			boolean back = world.getServer().computeOnServer(server -> {
				Session s = AgentsMc.session();
				return s.brains.body("Helper") != null && sessionId.equals(s.brains.get("Helper").sessionId());
			});
			if (!back) throw new AssertionError("Helper didn't come back with its conversation after reopening the world");
			expectSettings(world, "Helper", "sonnet", "low");

			// No Claude Code is running after the reopen, so this switch goes on the next launch's command line.
			openMenu(ctx);
			pick(ctx, "model", "haiku", null);
			waitForBrain(world, "Helper", b -> b.model().equals("haiku"), 100);
			ctx.takeScreenshot("13-reopened");
			ctx.setScreen(() -> null);
			askAndExpectModel(ctx, world, "@Helper what did you build for me before? Answer in chat.", "haiku");
			ctx.waitTicks(10);
			ctx.takeScreenshot("14-resumed-chat");
			openMenu(ctx);
			ctx.takeScreenshot("15-resumed-menu");
			ctx.setScreen(() -> null);
			dump(world, "Helper");
		}
	}

	/**
	 * Asks the agent something in chat and waits for its answer. Claude Code reports the model each turn
	 * runs on, and the agent's log shows it ("Using claude-sonnet-..."): it must name this model.
	 */
	private static void askAndExpectModel(ClientGameTestContext ctx, TestSingleplayerContext world, String message, String model) {
		int says = world.getServer().computeOnServer(server -> count(AgentsMc.session().brains.get("Helper"), ActivityLog.Kind.SAY));
		long since = world.getServer().computeOnServer(server -> AgentsMc.session().brains.get("Helper").log.last().id());
		ctx.runOnClient(mc -> mc.player.connection.sendChat(message));
		waitForBrain(world, "Helper", b -> count(b, ActivityLog.Kind.SAY) > says && b.status() == AgentBrain.Status.IDLE, 20 * 180);
		String using = world.getServer().computeOnServer(server -> {
			String last = "";
			for (ActivityLog.Entry e : AgentsMc.session().brains.get("Helper").log.tail(300)) {
				if (e.kind() == ActivityLog.Kind.INFO && e.text().startsWith("Using ")) last = e.id() > since ? e.text() : "";
			}
			return last;
		});
		LOG.info("Helper after switching to {}: {}", model, using);
		if (!using.contains(model)) {
			dump(world, "Helper");
			throw new AssertionError("Helper's turn should have run on " + model + ", log says: " + using);
		}
	}

	// ---- helpers ----

	private static void openMenu(ClientGameTestContext ctx) {
		ctx.getInput().pressKey(AgentsMcClient.openMenu);
		ctx.waitForScreen(AgentsScreen.class);
		ctx.waitTicks(15);
	}

	/** Window pixels per menu pixel, mirroring AgentsScreen's scale. */
	private static int menuScale(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> Mth.clamp(mc.getWindow().getWidth() / 620, 1, Math.max(1, mc.getWindow().getGuiScale())));
	}

	private static int sideWidth(ClientGameTestContext ctx) {
		int menuWidth = ctx.computeOnClient(mc -> mc.getWindow().getWidth()) / menuScale(ctx);
		return Mth.clamp(menuWidth * 3 / 10, 110, 170);
	}

	/**
	 * Opens the model or effort list, optionally takes a screenshot of it, and clicks the option
	 * with this id.
	 */
	private static void pick(ClientGameTestContext ctx, String button, String option, String screenshot) {
		clickGui(ctx, locate(ctx, button));
		if (screenshot != null) {
			ctx.waitTicks(2);
			ctx.takeScreenshot(screenshot);
		}
		clickGui(ctx, locate(ctx, "option:" + option));
	}

	/** Menu coordinates of a model or effort button or option (see AgentsScreen.locate). */
	private static int[] locate(ClientGameTestContext ctx, String what) {
		int[] at = ctx.computeOnClient(mc -> mc.gui.screen() instanceof AgentsScreen menu ? menu.locate(what) : null);
		if (at == null) throw new AssertionError("Nothing to click for " + what);
		return at;
	}

	private static void clickGui(ClientGameTestContext ctx, int[] at) {
		clickGui(ctx, at[0], at[1]);
	}

	/** Moves the pointer to menu coordinates. */
	private static void moveTo(ClientGameTestContext ctx, int[] at) {
		double perPixel = menuScale(ctx) * ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getWidth());
		ctx.getInput().setCursorPos(at[0] * perPixel + perPixel / 2, at[1] * perPixel + perPixel / 2);
	}

	/** Clicks at menu coordinates. */
	private static void clickGui(ClientGameTestContext ctx, int x, int y) {
		double perPixel = menuScale(ctx) * ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getWidth());
		ctx.getInput().setCursorPos(x * perPixel + perPixel / 2, y * perPixel + perPixel / 2);
		ctx.getInput().pressMouse(0);
		ctx.waitTicks(2);
	}

	/** Clicks an agent in the sidebar. The menu lists agents by most recent activity. */
	private static void clickRow(ClientGameTestContext ctx, TestSingleplayerContext world, String name) {
		ctx.waitTicks(12);
		int index = world.getServer().computeOnServer(server -> {
			List<AgentBrain> sorted = new ArrayList<>(AgentsMc.session().brains.all());
			sorted.sort(Comparator.comparingLong(AgentBrain::lastActive).reversed());
			for (int i = 0; i < sorted.size(); i++) if (sorted.get(i).name.equals(name)) return i;
			throw new AssertionError("No agent " + name);
		});
		clickGui(ctx, 40, 24 + index * 30 + 12);
	}

	private static void waitForBrain(TestSingleplayerContext world, String name, Predicate<AgentBrain> done, int ticks) {
		try {
			world.getServer().waitFor(server -> {
				Session s = AgentsMc.session();
				AgentBrain b = s == null ? null : s.brains.find(name);
				return b != null && done.test(b);
			}, ticks);
		} catch (RuntimeException | AssertionError e) {
			dump(world, name);
			throw e;
		}
	}

	private static void expectSettings(TestSingleplayerContext world, String name, String model, String effort) {
		String actual = world.getServer().computeOnServer(server -> {
			AgentBrain b = AgentsMc.session().brains.get(name);
			return b.model() + "/" + b.effort();
		});
		if (!actual.equals(model + "/" + effort)) throw new AssertionError(name + " should be on " + model + "/" + effort + ", is on " + actual);
	}

	/** What the fake claude wrote to fake-claude.jsonl in this agent's folder: its launches, control requests and exits. */
	private static List<JsonObject> fakeLog(TestSingleplayerContext world, String agent) {
		return readFakeLog(fakeLogFile(world, agent));
	}

	private static Path fakeLogFile(TestSingleplayerContext world, String agent) {
		return world.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.ROOT)
				.resolve("agentsmc").resolve("agents").resolve(agent.toLowerCase(Locale.ROOT)).resolve("fake-claude.jsonl"));
	}

	private static List<JsonObject> readFakeLog(Path file) {
		try {
			if (!Files.exists(file)) return List.of();
			return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void waitForFakeLog(ClientGameTestContext ctx, TestSingleplayerContext world, String agent, Predicate<List<JsonObject>> done, int ticks) {
		try {
			Path file = fakeLogFile(world, agent);
			ctx.waitFor(mc -> done.test(readFakeLog(file)), ticks);
		} catch (RuntimeException | AssertionError e) {
			LOG.info("{}'s fake claude log: {}", agent, fakeLog(world, agent));
			throw e;
		}
	}

	/** Whether a launch's command line has this flag, followed by this value unless it is null. */
	private static boolean flag(JsonObject launch, String flag, String value) {
		JsonArray argv = launch.getAsJsonArray("argv");
		for (int i = 0; i < argv.size(); i++) {
			if (!argv.get(i).getAsString().equals(flag)) continue;
			return value == null || i + 1 < argv.size() && argv.get(i + 1).getAsString().equals(value);
		}
		return false;
	}

	private static boolean applied(JsonObject entry, String model, String effort) {
		if (!entry.has("control")) return false;
		JsonObject request = entry.getAsJsonObject("control");
		if (!request.get("subtype").getAsString().equals("apply_flag_settings")) return false;
		JsonObject settings = request.getAsJsonObject("settings");
		return value(settings, "model").equals(model) && value(settings, "effortLevel").equals(effort);
	}

	/** A setting the mod sent, with null (Claude Code's default) as "". */
	private static String value(JsonObject settings, String key) {
		return settings.get(key).isJsonNull() ? "" : settings.get(key).getAsString();
	}

	private static boolean has(AgentBrain b, ActivityLog.Kind kind) {
		return count(b, kind) > 0;
	}

	private static int count(AgentBrain b, ActivityLog.Kind kind) {
		int n = 0;
		for (ActivityLog.Entry e : b.log.tail(300)) if (e.kind() == kind) n++;
		return n;
	}

	private static int countNear(ServerPlayer player) {
		int n = 0;
		BlockPos c = player.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -4, -12), c.offset(12, 4, 12))) {
			if (player.level().getBlockState(p).is(Blocks.COBBLESTONE)) n++;
		}
		return n;
	}

	private static void dump(TestSingleplayerContext world, String name) {
		String text = world.getServer().computeOnServer(server -> {
			Session s = AgentsMc.session();
			AgentBrain b = s == null ? null : s.brains.find(name);
			if (b == null) return "(no agent " + name + ")";
			StringBuilder sb = new StringBuilder(name + " is " + b.status() + ", log:\n");
			for (ActivityLog.Entry e : b.log.tail(80)) sb.append("  ").append(e.kind()).append(' ').append(e.who() == null ? "" : e.who() + ": ").append(e.text()).append('\n');
			return sb.toString();
		});
		LOG.info("{}", text);
	}

	private static void writeConfig(String claudePath, String model, String effort) {
		JsonObject config = new JsonObject();
		config.addProperty("port", 25591);
		config.addProperty("claudePath", claudePath);
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

	/** Writes fake_claude.py and a launcher for it into the game directory. */
	private static Path fakeClaude() {
		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve("agentsmc-test");
			Files.createDirectories(dir);
			Path script = dir.resolve("fake_claude.py");
			try (InputStream in = AgentsMenuTest.class.getResourceAsStream("/fake_claude.py")) {
				Files.write(script, in.readAllBytes());
			}
			if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
				Path cmd = dir.resolve("fake_claude.cmd");
				Files.writeString(cmd, "@python \"%~dp0fake_claude.py\" %*\r\n");
				return cmd;
			}
			Path sh = dir.resolve("fake_claude");
			Files.writeString(sh, "#!/bin/sh\nexec python3 \"$(dirname \"$0\")/fake_claude.py\" \"$@\"\n");
			sh.toFile().setExecutable(true);
			return sh;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
