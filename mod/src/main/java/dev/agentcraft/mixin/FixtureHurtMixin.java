package dev.agentcraft.mixin;

import dev.agentcraft.world.HqProtection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HQ protection: item frames, paintings, armor stands and mannequins on the HQ site take no damage from
 * explosions, mobs or projectiles. Every class that declares {@code hurtServer} for them is a target
 * (ItemFrame and ArmorStand override it; mannequins use LivingEntity's).
 */
@Mixin({BlockAttachedEntity.class, ItemFrame.class, ArmorStand.class, LivingEntity.class})
public abstract class FixtureHurtMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void agentcraft$spareHqFixture(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (HqProtection.refuseFixtureDamage((Entity) (Object) this, source)) {
			cir.setReturnValue(false);
		}
	}
}
