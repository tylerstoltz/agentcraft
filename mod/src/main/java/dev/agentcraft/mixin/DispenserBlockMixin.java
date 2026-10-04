package dev.agentcraft.mixin;

import dev.agentcraft.world.HqProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HQ protection: a dispenser outside the HQ site cannot pour, place or ignite into it. */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
	@Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
	private void agentcraft$keepOut(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
		if (HqProtection.crossesIn(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
			ci.cancel();
		}
	}
}
