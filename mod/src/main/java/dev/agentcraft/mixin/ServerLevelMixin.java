package dev.agentcraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.agentcraft.world.HqProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** HQ protection: marks which non-player entity is ticking, so its block edits on the HQ site can be refused. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@WrapMethod(method = "tickNonPassenger")
	private void agentcraft$tickNonPassenger(Entity entity, Operation<Void> original) {
		Entity prev = HqProtection.beginTick(entity);
		try {
			original.call(entity);
		} finally {
			HqProtection.endTick(prev);
		}
	}

	@WrapMethod(method = "tickPassenger")
	private void agentcraft$tickPassenger(Entity vehicle, Entity passenger, Operation<Void> original) {
		Entity prev = HqProtection.beginTick(passenger);
		try {
			original.call(vehicle, passenger);
		} finally {
			HqProtection.endTick(prev);
		}
	}
}
