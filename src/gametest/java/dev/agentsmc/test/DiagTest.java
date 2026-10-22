package dev.agentsmc.test;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs how big the game thinks its window is, to chase a zoomed display in the test client. AGENTSMC_ONLY=diag. */
public class DiagTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("agentsmc-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip("diag")) return;
		sizes(ctx, "at start");
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(20);
		sizes(ctx, "after resizeWindow(1280, 720)");
		try (TestSingleplayerContext world = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			world.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			sizes(ctx, "in the world");
			ctx.takeScreenshot("diag-0-world");
			ctx.runOnClient(mc -> mc.getWindow().setWindowed(1280, 720));
			ctx.waitTicks(20);
			sizes(ctx, "after setWindowed(1280, 720)");
			ctx.takeScreenshot("diag-1-after-setWindowed");
			ctx.waitTicks(100);
		}
	}

	private static void sizes(ClientGameTestContext ctx, String when) {
		String s = ctx.computeOnClient(mc -> {
			Window w = mc.getWindow();
			return String.format("Window.getWidth/Height=%dx%d screen=%dx%d guiScale=%d guiScaled=%dx%d pixelDensity=%.2f | mainRenderTarget=%dx%d",
					w.getWidth(), w.getHeight(), w.getScreenWidth(), w.getScreenHeight(), w.getGuiScale(), w.getGuiScaledWidth(), w.getGuiScaledHeight(), w.getPixelDensity(),
					mc.gameRenderer.mainRenderTarget().width, mc.gameRenderer.mainRenderTarget().height);
		});
		LOG.info("sizes {}: {}", when, s);
	}
}
