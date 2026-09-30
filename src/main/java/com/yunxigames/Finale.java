package com.yunxigames;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 末影龙通关结算。
 *
 * <p>这一局的终点就是末影龙 —— 杀掉它之后：
 * <ol>
 *   <li>全服标题 + 全服聊天栏播报「本局结束」；</li>
 *   <li>把 {@link SessionStats 本局统计}一次性摊开（挖了多少、爆了多少、暴击几次）；</li>
 *   <li>原地来一场<b>宝藏雨</b>：从暴击宝藏池里撒一堆好东西，作为收尾的烟花。</li>
 * </ol>
 *
 * <p>刻意<b>不给任何长期奖励</b>（存档、排行榜、经济都没有）：一局就是一局，
 * 打完就散,所以也不需要持久化。换句话说这里做的是「仪式感」，不是「成长曲线」。
 *
 * <p>只触发一次：即使有人把末影龙复活再杀，也不会重复结算。
 * 用 Fabric 的 {@code ServerLivingEntityEvents.AFTER_DEATH}，不额外写 mixin。
 */
public final class Finale {
	private Finale() {
	}

	/** 一局只结算一次。 */
	private static final AtomicBoolean FIRED = new AtomicBoolean();

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(Finale::onDeath);
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof EnderDragon)) {
			return;
		}

		if (!(entity.level() instanceof ServerLevel level)) {
			return;
		}

		if (!DropsConfig.get().enableFinale) {
			return;
		}

		// 复活末影龙再杀一次也不重复结算
		if (!FIRED.compareAndSet(false, true)) {
			return;
		}

		fire(level, entity.blockPosition(), source.getEntity());
	}

	/**
	 * 自检入口：绕过「只结算一次」的闸门，把流程完整跑一遍。
	 *
	 * @return 宝藏雨实际撒出去的件数
	 */
	static int fireForTest(ServerLevel level, BlockPos pos, Entity killer) {
		FIRED.set(true);
		return fire(level, pos, killer);
	}

	/** 自检用：让下一次结算重新变成「没发生过」。 */
	static void resetForTest() {
		FIRED.set(false);
	}

	private static int fire(ServerLevel level, BlockPos pos, Entity killer) {
		DropsConfig config = DropsConfig.get();
		String who = killer instanceof Player player ? player.getName().getString() : "不知名的力量";

		if (config.finaleTitle) {
			sendTitle(level, who);
		}

		if (config.finaleBroadcast) {
			broadcast(level, who);
		}

		if (config.finaleEffects) {
			effects(level, pos);
		}

		return treasureRain(level, pos, config);
	}

	/** 全服大标题：「通关！」 */
	private static void sendTitle(ServerLevel level, String who) {
		ClientboundSetTitlesAnimationPacket animation = new ClientboundSetTitlesAnimationPacket(10, 80, 20);
		ClientboundSetTitleTextPacket title = new ClientboundSetTitleTextPacket(
				Component.literal("§6§l通 关 ！"));
		ClientboundSetSubtitleTextPacket subtitle = new ClientboundSetSubtitleTextPacket(
				Component.literal("§e" + who + " §6终结了末影龙"));

		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			player.connection.send(animation);
			player.connection.send(title);
			player.connection.send(subtitle);
		}
	}

	/** 聊天栏播报：结尾一行 + 本局统计。 */
	private static void broadcast(ServerLevel level, String who) {
		say(level, "§6§l[随机掉落] §e" + who + " §6击杀了末影龙 —— 本局到此结束！");
		say(level, "§7——— 本局战绩 ———");

		for (String line : SessionStats.report()) {
			say(level, "§7· §f" + line);
		}

		say(level, "§7——— §6辛苦了，下一局见 §7———");
	}

	private static void say(ServerLevel level, String text) {
		level.getServer().sendSystemMessage(Component.literal(text));
	}

	/** 收尾的音效与粒子。 */
	private static void effects(ServerLevel level, BlockPos pos) {
		double x = pos.getX() + 0.5D;
		double y = pos.getY() + 1.0D;
		double z = pos.getZ() + 0.5D;

		level.playSound(null, pos, SoundEvents.ENDER_DRAGON_DEATH, SoundSource.HOSTILE, 2.0F, 1.0F);
		level.playSound(null, pos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.5F, 1.0F);

		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 6, 3.0D, 1.5D, 3.0D, 0.0D);
		level.sendParticles(ParticleTypes.FIREWORK, x, y + 2.0D, z, 120, 4.0D, 2.0D, 4.0D, 0.35D);
		level.sendParticles(ParticleTypes.END_ROD, x, y + 1.0D, z, 80, 3.0D, 2.0D, 3.0D, 0.15D);
	}

	/**
	 * 宝藏雨：从暴击宝藏池里撒 {@code finaleTreasureCount} 件。
	 *
	 * <p>在死亡点上方几格生成，让它们真的「落」下来；横向散布由
	 * {@code finaleTreasureSpread} 控制，默认 8 格，撒在末影龙挂掉的地方附近。
	 */
	private static int treasureRain(ServerLevel level, BlockPos pos, DropsConfig config) {
		int wanted = config.finaleTreasureCount;

		if (wanted <= 0) {
			return 0;
		}

		RandomSource random = level.getRandom();
		int spread = Math.max(0, config.finaleTreasureSpread);
		int dropped = 0;

		for (int i = 0; i < wanted; i++) {
			ItemStack stack = DropRandomizer.randomTreasure(level, random);

			if (stack.isEmpty()) {
				// 宝藏池是空的（配置被改坏了），没必要继续
				break;
			}

			int dx = spread == 0 ? 0 : random.nextInt(spread * 2 + 1) - spread;
			int dz = spread == 0 ? 0 : random.nextInt(spread * 2 + 1) - spread;
			int dy = 2 + random.nextInt(4);

			Block.popResource(level, pos.offset(dx, dy, dz), stack);
			dropped++;
		}

		SessionStats.treasureGiven(dropped);
		return dropped;
	}
}
