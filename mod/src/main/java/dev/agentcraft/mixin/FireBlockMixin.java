package dev.agentcraft.mixin;

import dev.agentcraft.world.HqProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HQ protection: fire burns away no HQ blocks. Fire ticks are block ticks, not entity ticks, so the
 * entity guard does not see them; the fire-spread game rule HqWorld sets usually stops them first.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
	@Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
	private void agentcraft$spareHq(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
		if (HqProtection.protects(level, pos)) {
			ci.cancel();
		}
	}
}
