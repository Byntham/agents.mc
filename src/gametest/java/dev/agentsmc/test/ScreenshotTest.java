package dev.agentsmc.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentsmc.AgentsMc;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;

/**
 * Calls the screenshot tool over MCP in a real client and checks what the pictures show. They are
 * saved next to the other test screenshots as agent-*.jpg.
 *
 * <p>The host faces south at a gold tower, with a red wall behind them. The renderer only builds
 * terrain the host has in view, so a picture of the wall checks that screenshots build it themselves.
 */
public class ScreenshotTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// Not the screenshot size, so capture frames have to resize the render target and put it back.
		ctx.getInput().resizeWindow(1600, 900);
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			BlockPos host = world.getServer().computeOnServer(server -> player(server.getPlayerList().getPlayers()).blockPosition());
			int x = host.getX(), y = host.getY(), z = host.getZ();
			world.getServer().runCommand("tp Player0 " + x + " " + y + " " + z + " 0 0"); // face south
			world.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d gold_block", x - 1, y, z + 8, x + 1, y + 5, z + 10));
			world.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d red_wool", x - 4, y, z - 9, x + 4, y + 4, z - 9));
			ctx.waitTicks(40);
			ctx.takeScreenshot("20-host-before");

			int port = world.getServer().computeOnServer(server -> AgentsMc.session().mcp.port());
			call(ctx, port, "spawn_agent", obj("name", "Cam", "x", x + 3.5, "y", y, "z", z + 0.5));

			// From the agent's eyes, turned toward the tower in front of the host.
			call(ctx, port, "look_at", obj("agent", "Cam", "x", x + 0.5, "y", y + 2.5, "z", z + 9.5));
			BufferedImage eyes = screenshot(ctx, port, "agent-1-eyes", obj("agent", "Cam"));
			expect(eyes, "gold tower", ScreenshotTest::gold, 0.02);

			// Behind the host, where their game hasn't built the terrain.
			BufferedImage behind = screenshot(ctx, port, "agent-2-behind", obj("agent", "Cam", "at_x", x + 0.5, "at_y", y + 2.0, "at_z", z - 8.5));
			expect(behind, "red wall", ScreenshotTest::red, 0.02);

			// From above the tower, looking down: the agent's own body shows from here.
			screenshot(ctx, port, "agent-3-above", obj("agent", "Cam", "from_x", x + 0.5, "from_y", y + 24.0, "from_z", z + 2.5,
					"at_x", x + 0.5, "at_y", (double) y, "at_z", z + 4.5));

			// Looking back at the host from the tower.
			screenshot(ctx, port, "agent-4-at-host", obj("agent", "Cam", "from_x", x + 0.5, "from_y", y + 7.0, "from_z", z + 9.5, "at_player", "Player0"));

			JsonObject far = call(ctx, port, "screenshot", obj("agent", "Cam", "from_x", x + 5000.0, "from_y", y + 10.0, "from_z", (double) z));
			if (!far.get("isError").getAsBoolean()) throw new AssertionError("A camera 5000 blocks away should fail: " + far);
			LOG.info("Far camera: {}", text(far));

			// The host's own view is unchanged afterwards.
			ctx.waitTicks(10);
			ctx.takeScreenshot("21-host-after");
		}
	}

	private static ServerPlayer player(java.util.List<ServerPlayer> players) {
		return players.stream().filter(p -> p.getName().getString().equals("Player0")).findFirst().orElseThrow();
	}

	private static BufferedImage screenshot(ClientGameTestContext ctx, int port, String name, JsonObject args) {
		JsonObject result = call(ctx, port, "screenshot", args);
		if (result.get("isError").getAsBoolean()) throw new AssertionError(name + ": " + text(result));
		LOG.info("{}: {}", name, text(result));
		JsonArray content = result.getAsJsonArray("content");
		JsonObject image = content.get(1).getAsJsonObject();
		if (!image.get("mimeType").getAsString().equals("image/jpeg")) throw new AssertionError("Expected a JPEG: " + image.get("mimeType"));
		byte[] jpeg = Base64.getDecoder().decode(image.get("data").getAsString());
		try {
			Path file = FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve(name + ".jpg");
			Files.createDirectories(file.getParent());
			Files.write(file, jpeg);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
			LOG.info("{}: {}x{}, {} KB", name, img.getWidth(), img.getHeight(), jpeg.length / 1024);
			return img;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** Calls a tool over HTTP while the game keeps running, and returns the tool result. */
	private static JsonObject call(ClientGameTestContext ctx, int port, String tool, JsonObject args) {
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
		CompletableFuture<HttpResponse<String>> response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		ctx.waitFor(mc -> response.isDone(), 20 * 30);
		JsonObject result = JsonParser.parseString(response.join().body()).getAsJsonObject().getAsJsonObject("result");
		if (result == null) throw new AssertionError(tool + " failed: " + response.join().body());
		return result;
	}

	private static String text(JsonObject result) {
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	/** Fails unless at least {@code share} of the picture's pixels match. */
	private static void expect(BufferedImage img, String what, IntPredicate color, double share) {
		int n = 0;
		for (int py = 0; py < img.getHeight(); py++) {
			for (int px = 0; px < img.getWidth(); px++) if (color.test(img.getRGB(px, py))) n++;
		}
		double found = (double) n / (img.getWidth() * img.getHeight());
		LOG.info("{}: {}% of pixels", what, String.format(Locale.ROOT, "%.1f", found * 100));
		if (found < share) throw new AssertionError(String.format(Locale.ROOT, "Expected the %s in the picture, but only %.2f%% of pixels match", what, found * 100));
	}

	private static boolean gold(int rgb) {
		int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
		return r > 180 && g > 140 && b < 110 && r - b > 100;
	}

	private static boolean red(int rgb) {
		int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
		return r > 110 && g < 70 && b < 70;
	}

	private static JsonObject obj(Object... kv) {
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
