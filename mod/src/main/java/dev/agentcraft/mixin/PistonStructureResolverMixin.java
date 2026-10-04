package dev.agentcraft.mixin;

import dev.agentcraft.world.HqProtection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** HQ protection: a piston outside the HQ site cannot push, pull or break blocks inside it. */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
	@Shadow
	@Final
	private Level level;
	@Shadow
	@Final
	private BlockPos pistonPos;
	@Shadow
	@Final
	private Direction pushDirection;
	@Shadow
	@Final
	private List<BlockPos> toPush;
	@Shadow
	@Final
	private List<BlockPos> toDestroy;

	@Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
	private void agentcraft$keepOut(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || !HqProtection.guards(level)) {
			return;
		}
		for (BlockPos p : toPush) {
			if (HqProtection.crossesIn(level, pistonPos, p) || HqProtection.crossesIn(level, pistonPos, p.relative(pushDirection))) {
				cir.setReturnValue(false);
				return;
			}
		}
		for (BlockPos p : toDestroy) {
			if (HqProtection.crossesIn(level, pistonPos, p)) {
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
