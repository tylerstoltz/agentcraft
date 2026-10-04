package dev.agentcraft.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.agentcraft.world.HqProtection;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla ignores whether removeBlock worked: an enderman refused an HQ block must not get a copy of it. */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockGoalMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void agentcraft$reset(CallbackInfo ci) {
		HqProtection.takeRefused();
	}

	@WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/monster/Enderman;setCarriedBlock(Lnet/minecraft/world/level/block/state/BlockState;)V"))
	private boolean agentcraft$onlyIfTaken(Enderman enderman, BlockState state) {
		return !HqProtection.takeRefused();
	}
}
