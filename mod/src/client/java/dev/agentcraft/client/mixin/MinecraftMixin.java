package dev.agentcraft.client.mixin;

import dev.agentcraft.client.dev.FrameScheduler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** End of every rendered frame (after present): drives DevBridge frame waits + captures. */
	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void agentcraft$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		FrameScheduler.onFrameEnd((Minecraft) (Object) this);
	}
}
