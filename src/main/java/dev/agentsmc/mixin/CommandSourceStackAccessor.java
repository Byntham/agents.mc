package dev.agentsmc.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a command really came from: the player who typed it, the console, a command block... Unlike
 * the source's entity, this doesn't change under "execute as".
 */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
	@Accessor("source")
	CommandSource agentsmc$source();
}
