package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * ④-b 高处跌落 → 掉木块 + 断肢。
 *
 * <p>跌落高度超过 {@code fallInjuryHeight}（默认 50 格）且身上没有缓降时，
 * 落地那一刻就地掉几个木块，并按高度摔断手脚：
 * <ul>
 *   <li>{@code fallInjuryHeight} 以上：断一条腿（+ 一只手）—— 移速 / 挖掘 -30%；</li>
 *   <li>{@code fallInjurySevereHeight} 以上：双腿双手 —— -80%，持续 {@code limbInjurySevereSeconds}。</li>
 * </ul>
 *
 * <p>50 格本来是必死的，能活下来（水、伤害吸收、缓降……）也该留下点代价。
 * 有缓降效果（"降落伞"）则完全不触发 —— 那本来就是玩家自己做的准备。
 */
public final class FallInjury {
	private FallInjury() {
	}

	/** 一次跌落的处置方案。 */
	public record Plan(int legs, int arms, int seconds) {
	}

	/**
	 * 按高度算出断几处、疼多久 —— 纯函数，脱离世界也能验证。
	 */
	public static Plan plan(double height, DropsConfig config) {
		boolean severe = height >= config.fallInjurySevereHeight;
		int legs = severe ? 2 : 1;
		int arms = config.fallInjuryHurtsArms ? legs : 0;
		int seconds = severe ? config.limbInjurySevereSeconds : config.limbInjurySeconds;

		return new Plan(legs, arms, seconds);
	}

	/** 高度够不够触发（纯函数）。 */
	public static boolean triggers(double height, DropsConfig config) {
		return height >= config.fallInjuryHeight;
	}

	/**
	 * 落地时调用（挂在 {@code LivingEntity#causeFallDamage} 上）。
	 *
	 * @return true 表示这次真的摔断了
	 */
	public static boolean onLanding(LivingEntity entity, double height) {
		if (!(entity instanceof Player player) || entity.level().isClientSide()) {
			return false;
		}

		DropsConfig config = DropsConfig.get();

		if (!config.enableFallInjury || !triggers(height, config)) {
			return false;
		}

		// 有缓降（降落伞）就不算
		if (player.hasEffect(MobEffects.SLOW_FALLING)) {
			return false;
		}

		ServerLevel level = (ServerLevel) player.level();
		BlockPos pos = player.blockPosition();
		Plan plan = plan(height, config);

		dropItems(level, pos, config.fallInjuryDropItems);
		LimbInjury.injure(player, plan.legs(), plan.arms(), plan.seconds());
		Feedback.fallInjury(level, pos, player, height, plan, config);

		return true;
	}

	/** 落地的碎木块：{@code id;数量}。 */
	private static void dropItems(ServerLevel level, BlockPos pos, List<String> entries) {
		if (level == null || pos == null || entries == null) {
			return;
		}

		for (String entry : entries) {
			ItemStack stack = TntIgnition.parseEntry(entry);

			if (!stack.isEmpty()) {
				Block.popResource(level, pos, stack);
			}
		}
	}
}
