package dev.agentcraft.mixin;

import dev.agentcraft.world.HqProtection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** HQ protection: explosions (TNT, creepers, crystals, withers, beds, ...) destroy and ignite nothing on the HQ site. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void agentcraft$spareHq(CallbackInfoReturnable<List<BlockPos>> cir) {
		if (HqProtection.guards(level)) {
			cir.getReturnValue().removeIf(pos -> HqProtection.protects(level, pos));
		}
	}
}
