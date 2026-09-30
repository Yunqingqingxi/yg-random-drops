package com.yunxigames.drops.mixin;

import com.yunxigames.DropRandomizer;
import com.yunxigames.DropsConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管生物死亡的掉落表（由 {@code enableMobDrops} 控制，默认开启）。
 *
 * <p>注入点是 {@code dropFromLootTable} 而不是 {@code dropAllDeathLoot}：
 * 后者的结构是
 * <pre>
 *   if (shouldDropLoot(level)) { dropFromLootTable(...); dropCustomDeathLoot(...); }
 *   dropEquipment(level);                        // 生物身上的装备
 *   dropExperience(level, source.getEntity());   // 经验值
 * </pre>
 * 如果拦在上层，会把装备和经验值一起吞掉——杀怪不掉经验在生存服上是致命的。
 * 只拦掉落表这一层，装备与经验保持原版行为。
 *
 * <p>玩家被显式排除，否则死亡时背包会被替换成一个随机物品。
 */
@Mixin(LivingEntity.class)
public class LivingEntityDropsMixin {

	@Inject(
			method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V",
			at = @At("HEAD"),
			cancellable = true)
	private void yg$replaceMobLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit,
			CallbackInfo ci) {
		if (!DropsConfig.get().enableMobDrops) {
			return;
		}

		Object self = this;
		if (self instanceof Player) {
			return;
		}

		// 保底：按生物类型分别计数，这一把轮到「该生物的原版掉落」就放行
		Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(((LivingEntity) self).getType());
		if (DropRandomizer.isPityDropForMob(level, damageSource.getEntity(), typeId)) {
			return;
		}

		ci.cancel();

		// 只有玩家造成的击杀才可能爆出新生物 —— 这是防指数爆炸的关键（见配置说明）
		DropRandomizer.applyMobDrop(level, ((LivingEntity) self).blockPosition(), damageSource.getEntity());
	}
}
