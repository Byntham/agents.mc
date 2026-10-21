package dev.agentsmc.mixin;

import dev.agentsmc.AgentsMc;
import dev.agentsmc.agent.AgentManager;
import dev.agentsmc.perception.BlockCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reports blocks placed by human players to the event log (Fabric API has no "after place" event). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place", at = @At("RETURN"))
	private void agentsmc$afterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(context.getPlayer() instanceof ServerPlayer player) || AgentManager.isAgent(player)) return;
		if (!cir.getReturnValue().consumesAction()) return;
		var pos = context.getClickedPos();
		AgentsMc.events().add("block_placed", player.getName().getString(), player.getName().getString() + " placed "
				+ BlockCodec.id(context.getLevel().getBlockState(pos)) + " at " + AgentsMc.xyz(pos));
	}
}
