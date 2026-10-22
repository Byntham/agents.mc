package dev.agentsmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentsmc.net.MenuPayloads;
import dev.agentsmc.perception.Screenshots;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The agents menu (K, or /agent menu) and the camera for agents' screenshots. */
public final class AgentsMcClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("agentsmc", "agents"));
	public static KeyMapping openMenu;

	@Override
	public void onInitializeClient() {
		Screenshots.install(new AgentCamera());
		if (FrameRecorder.enabled()) ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> FrameRecorder.stop());
		openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentsmc.menu", InputConstants.KEY_K, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openMenu.consumeClick()) open(mc);
		});
		ClientPlayNetworking.registerGlobalReceiver(MenuPayloads.State.TYPE, (payload, ctx) -> {
			if (ctx.client().gui.screen() instanceof AgentsScreen screen) screen.accept(payload.json());
		});
		ClientPlayNetworking.registerGlobalReceiver(MenuPayloads.Open.TYPE, (payload, ctx) -> open(ctx.client()));
	}

	public static void open(Minecraft mc) {
		if (mc.player == null) return;
		if (!ClientPlayNetworking.canSend(MenuPayloads.Request.TYPE)) {
			mc.player.sendSystemMessage(Component.literal("This server doesn't have Agents MC, so there are no agents here.").withStyle(ChatFormatting.GRAY));
			return;
		}
		mc.gui.setScreen(new AgentsScreen());
	}
}
