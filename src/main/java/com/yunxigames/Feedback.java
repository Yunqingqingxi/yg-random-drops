package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 音效 / 粒子 / 播报。
 *
 * <p>集中放在一处，是为了统一开关（有人嫌吵就把 sound/particles 关掉），
 * 也为了统一做空值保护 —— 自检和爆炸路径里 {@code cause} 都可能是 {@code null}。
 */
public final class Feedback {
	private Feedback() {
	}

	/** 暴击大爆：金色粒子 + 升级音效 + （可选）全服广播。 */
	public static void jackpot(ServerLevel level, BlockPos pos, ItemStack stack, Entity cause) {
		DropsConfig config = DropsConfig.get();

		if (config.jackpotParticles) {
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
					pos.getX() + 0.5D, pos.getY() + 0.7D, pos.getZ() + 0.5D, 40, 0.5D, 0.7D, 0.5D, 0.25D);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
					pos.getX() + 0.5D, pos.getY() + 0.7D, pos.getZ() + 0.5D, 20, 0.6D, 0.6D, 0.6D, 0.1D);
		}

		if (config.jackpotSound) {
			level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.4F);
			level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.8F);
		}

		if (config.jackpotBroadcast) {
			String who = cause instanceof Player player ? player.getName().getString() : "有人";
			level.getServer().sendSystemMessage(Component.literal(
					"§6[随机掉落] §e" + who + " §6触发了暴击大爆，爆出 §b"
							+ stack.getHoverName().getString() + " ×" + stack.getCount() + "§6！"));
		}
	}

	/**
	 * 保底触发：轻量提示。
	 *
	 * <p>保底原本是「暗机制」—— 玩家根本不知道再挖两下就能拿到原物品，期待感全浪费了。
	 * 这里给一层可见反馈：脚下冒几颗友好粒子 + 一声脆响 + 动作栏写明拿到了什么。
	 */
	public static void pity(ServerLevel level, BlockPos pos, ItemStack original, Entity cause) {
		DropsConfig config = DropsConfig.get();
		if (!config.showPityFeedback) {
			return;
		}

		level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
				pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D, 8, 0.4D, 0.3D, 0.4D, 0.0D);
		level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.9F);

		if (cause instanceof ServerPlayer player) {
			player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
					"§a保底触发 §7· §f" + original.getHoverName().getString() + " ×" + original.getCount())));
		}
	}

	/** 稀有掉落：全服广播。 */
	public static void rareDrop(ServerLevel level, BlockPos pos, ItemStack stack, Entity cause) {
 		DropsConfig config = DropsConfig.get();
		if (!config.rareDropBroadcast) {
			return;
 		}

		String who = cause instanceof Player player ? player.getName().getString() : "有人";

		level.getServer().sendSystemMessage(Component.literal(
				"§6[随机掉落] §e" + who + " §6爆出 §b" + stack.getHoverName().getString()
						+ " ×" + stack.getCount() + "§6！"));
	}

	// ------------------------------------------------------------ v1.11

	/** ① 刷怪蛋被拦下：警告广播（默认开，方便服主发现「有人的配置放行了刷怪蛋」）。 */
	public static void spawnerEggBlocked(ServerLevel level, BlockPos pos, ItemStack stack, Entity cause) {
		DropsConfig config = DropsConfig.get();

		if (!config.spawnerEggBroadcast || level == null) {
			return;
		}

		String who = cause instanceof Player player ? player.getName().getString() : "有人";

		level.getServer().sendSystemMessage(Component.literal(
				"§c[随机掉落] §e" + who + " §c的掉落里出现了刷怪蛋 §7("
						+ stack.getHoverName().getString() + ")§c，已拦截移除"));
	}

	/** ② TNT 引燃：引信音效 + 烟雾 + 全服警告。 */
	public static void tntIgnition(ServerLevel level, BlockPos pos, Entity owner, DropsConfig config) {
		if (level == null || pos == null) {
			return;
		}

		level.playSound(null, pos, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.SMOKE,
				pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D, 20, 0.5D, 0.4D, 0.5D, 0.05D);

		if (!config.tntIgniteBroadcast) {
			return;
		}

		String who = owner instanceof Player player ? player.getName().getString() : "有人";

		level.getServer().sendSystemMessage(Component.literal(
				"§c[随机掉落] §e" + who + " §c挖出了引爆点！§7(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
						+ ") §c四周 TNT 已点燃，快跑开！"));
	}

	/** ④-a 徒手挖木头达标：就地补木材 + 提示。 */
	public static void bareHandReward(ServerLevel level, BlockPos pos, ItemStack reward, Player player) {
		DropsConfig config = DropsConfig.get();

		level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
				pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D, 12, 0.5D, 0.4D, 0.5D, 0.05D);
		level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.6F);

		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
					"§a徒手挖满 " + config.bareHandLogThreshold + " 个原木 —— 补给 §f"
							+ reward.getHoverName().getString() + " ×" + reward.getCount())));
		}

		if (!config.bareHandLogBroadcast) {
			return;
		}

		String who = player == null ? "有人" : player.getName().getString();

		level.getServer().sendSystemMessage(Component.literal(
				"§a[随机掉落] §e" + who + " §a徒手挖满了 " + config.bareHandLogThreshold + " 个原木，补给 §f"
						+ reward.getHoverName().getString() + " ×" + reward.getCount() + "§a！"));
	}

	/** ④-b 高处跌落：广播 + 给当事人一条动作栏。 */
	public static void fallInjury(ServerLevel level, BlockPos pos, Player player, double height,
			FallInjury.Plan plan, DropsConfig config) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
					"§c从 " + (int) height + " 格高空摔下来 —— "
							+ describe(plan) + "，§7" + plan.seconds() + " 秒后恢复")));
		}

		if (!config.limbInjuryBroadcast || level == null) {
			return;
		}

		String who = player == null ? "有人" : player.getName().getString();

		level.getServer().sendSystemMessage(Component.literal(
				"§c[随机掉落] §e" + who + " §c从 " + (int) height + " 格高空坠落 —— "
						+ describe(plan) + "，§7" + plan.seconds() + " 秒后恢复"));
	}

	/** ④-b 断肢生效提示（动作栏）。 */
	public static void limbInjury(ServerPlayer player, int legs, int arms, int seconds, DropsConfig config) {
		player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
				"§c" + describe(new FallInjury.Plan(legs, arms, seconds)) + " §7· §c"
						+ seconds + " 秒后恢复")));
	}

	/** ⑤ 终极物资：金色粒子 + 全服广播。 */
	public static void ultimate(ServerLevel level, BlockPos pos, ItemStack stack, Entity cause) {
		DropsConfig config = DropsConfig.get();

		if (level == null) {
			return;
		}

		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
				pos.getX() + 0.5D, pos.getY() + 0.7D, pos.getZ() + 0.5D, 60, 0.8D, 0.8D, 0.8D, 0.3D);
		level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.2F);

		if (!config.ultimateBroadcast) {
			return;
		}

		String who = cause instanceof Player player ? player.getName().getString() : "有人";

		level.getServer().sendSystemMessage(Component.literal(
				"§6[随机掉落] §e" + who + " §6挖出了§d终极物资§6：§b"
						+ stack.getHoverName().getString() + " ×" + stack.getCount() + "§6！"));
	}

	/** 把「断了几条腿 / 几只手」说成人话。 */
	private static String describe(FallInjury.Plan plan) {
		DropsConfig config = DropsConfig.get();
		String one = (int) Math.round(config.limbOnePenalty * 100) + "%";
		String two = (int) Math.round(config.limbTwoPenalty * 100) + "%";

		StringBuilder text = new StringBuilder();

		if (plan.legs() > 0) {
			text.append(plan.legs() >= 2 ? "§c双腿骨折（移速 -" + two + "）" : "§c摔断一条腿（移速 -" + one + "）");
		}

		if (plan.arms() > 0) {
			if (text.length() > 0) {
				text.append("§7、");
			}

			text.append(plan.arms() >= 2 ? "§c双手骨折（操作 -" + two + "）" : "§c摔断一只手（操作 -" + one + "）");
		}

		return text.length() == 0 ? "§c受了点伤" : text.toString();
	}
}
