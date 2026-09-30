package com.yunxigames.drops.mixin;

import com.yunxigames.FallInjury;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ④-b 高处跌落 → 掉木块 + 断肢。
 *
 * <p>注入点选 {@code causeFallDamage}：它是「真的吃了摔落伤害」的那一步，
 * 第一个参数就是<b>实际跌落高度</b>（已经排除掉落地缓冲的情况）。
 * 拦它而不是 {@code checkFallDamage}，是为了拿到一个干净的高度值。
 *
 * <p>这里只做「通知」，不取消原版伤害 —— 摔断手脚是额外代价，不是替玩家免伤。
 */
@Mixin(LivingEntity.class)
public class FallDamageMixin {

	@Inject(
			method = "causeFallDamage(DFLnet/minecraft/world/damagesource/DamageSource;)Z",
			at = @At("HEAD"))
	private void yg$onHardLanding(double distance, float multiplier, DamageSource source,
			CallbackInfoReturnable<Boolean> cir) {
		Object self = this;

		if (!(self instanceof Player)) {
			return;
		}

		FallInjury.onLanding((LivingEntity) self, distance);
	}
}
