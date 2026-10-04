package dev.agentcraft.mixin;

import dev.agentcraft.world.HqProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** HQ protection: water or lava poured outside the HQ site does not flow into it. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Inject(method = "canMaybePassThrough", at = @At("HEAD"), cancellable = true)
	private void agentcraft$keepOut(BlockGetter level, BlockPos from, BlockState fromState, Direction dir, BlockPos to, BlockState toState,
		FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		if (level instanceof Level l && HqProtection.crossesIn(l, from, to)) {
			cir.setReturnValue(false);
		}
	}
}
