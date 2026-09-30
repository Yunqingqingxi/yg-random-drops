package com.yunxigames;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * <b>精英怪</b>：掉落爆出来的生物有几率变成精英。
 *
 * <p>定位是「当场掉好货」的即时爽点，所以刻意<b>不做长期奖励</b> ——
 * 没有专属货币、不涨等级、不进存档，杀掉就结束。一局制里这条线最划算：
 * 玩家的正反馈来得很直接（发光的目标 + 一堆宝藏），而实现只需要记住一批 UUID。
 *
 * <p>精英的识别不靠 NBT 也不靠新实体类型，而是一个内存里的 UUID 集合：
 * 一局结束进程就没了，正好符合「不考虑持久性」的前提。
 */
public final class EliteMobs {
	private EliteMobs() {
	}

	/** 本局所有还活着的精英（死亡时从这里取奖励）。 */
	private static final Set<UUID> ELITES = ConcurrentHashMap.newKeySet();

	/** 累计生成过多少只精英（自检用，不受 SessionStats.pause 影响）。 */
	private static final java.util.concurrent.atomic.AtomicLong SPAWNED =
			new java.util.concurrent.atomic.AtomicLong();

	/** 累计杀掉多少只精英（自检用）。 */
	private static final java.util.concurrent.atomic.AtomicLong KILLED =
			new java.util.concurrent.atomic.AtomicLong();

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(EliteMobs::onDeath);
	}

	// ------------------------------------------------------------ 生成

	/**
	 * 掷一次「这只生物是不是精英」。
	 *
	 * <p>只有玩家作为掉落来源时才生成精英 —— 否则刷怪塔会自动产出精英，
	 * 把「稀罕」变成「日常」。
	 */
	public static boolean rollElite(Entity cause, RandomSource random, DropsConfig config) {
		if (!config.enableEliteMobs || config.eliteChance <= 0.0D) {
			return false;
		}

		if (!(cause instanceof Player)) {
			return false;
		}

		if (random == null) {
			return false;
		}

		return random.nextDouble() < Math.min(1.0D, config.eliteChance);
	}

	/**
	 * 把一只刚爆出来的生物升级成精英。
	 *
	 * @return 是否真的升级了
	 */
	public static boolean makeElite(Mob mob, ServerLevel level, Entity cause) {
		if (mob == null || level == null) {
			return false;
		}

		DropsConfig config = DropsConfig.get();

		if (!config.enableEliteMobs) {
			return false;
		}

		applyStats(mob, config);

		if (config.eliteGlowing) {
			mob.setGlowingTag(true);
			mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));
		}

		mob.setCustomName(Component.literal("§c" + config.eliteNamePrefix + "§f" + mob.getName().getString()));
		mob.setCustomNameVisible(true);

		// 精英不该因为离玩家太远就被刷掉 —— 玩家回头找它时它得还在
		mob.setPersistenceRequired();

		ELITES.add(mob.getUUID());
		SPAWNED.incrementAndGet();
		SessionStats.elite();

		effects(level, mob.blockPosition());
		broadcast(level, mob, cause, config);

		if (config.debugLog) {
			Yg.LOGGER.info("[yg] 精英怪出现：{} @ {}", mob.getName().getString(),
					mob.blockPosition().toShortString());
		}

		return true;
	}

	/** 血量与（可选）攻击力的放大。 */
	private static void applyStats(Mob mob, DropsConfig config) {
		double healthMultiplier = config.eliteHealthMultiplier > 0.0D ? config.eliteHealthMultiplier : 1.0D;

		AttributeInstance maxHealth = mob.getAttribute(Attributes.MAX_HEALTH);

		if (maxHealth != null) {
			double newMax = maxHealth.getBaseValue() * healthMultiplier;
			maxHealth.setBaseValue(newMax);
			mob.setHealth((float) newMax);
		}
	}

	/** 出生时的存在感：一声咆哮 + 一圈粒子。 */
	private static void effects(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.0F, 1.4F);
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
				pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 24, 0.5D, 0.6D, 0.5D, 0.05D);
		level.sendParticles(ParticleTypes.END_ROD,
				pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 12, 0.4D, 0.5D, 0.4D, 0.02D);
	}

	private static void broadcast(ServerLevel level, Mob mob, Entity cause, DropsConfig config) {
		if (!config.eliteBroadcast) {
			return;
		}

		String who = cause instanceof Player player ? player.getName().getString() : "有人";
		level.getServer().sendSystemMessage(Component.literal(
				"§c[随机掉落] §e" + who + " §c爆出了一只精英 §f" + mob.getName().getString()
						+ "§c，打死它有好东西！"));
	}

	// ------------------------------------------------------------ 死亡奖励

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level)) {
			return;
		}

		if (!ELITES.remove(entity.getUUID())) {
			return;
		}

		DropsConfig config = DropsConfig.get();
		KILLED.incrementAndGet();

		int wanted = config.eliteTreasureCount;

		if (wanted <= 0) {
			return;
		}

		RandomSource random = level.getRandom();
		BlockPos pos = entity.blockPosition();
		int dropped = 0;

		for (int i = 0; i < wanted; i++) {
			ItemStack stack = DropRandomizer.randomTreasure(level, random);

			if (stack.isEmpty()) {
				break;
			}

			Block.popResource(level, pos, stack);
			dropped++;
		}

		SessionStats.treasureGiven(dropped);

		// 收尾的小庆祝：让「打死了」这件事有回音
		level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.2F);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
				pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 30, 0.5D, 0.6D, 0.5D, 0.3D);

		if (config.eliteBroadcast && dropped > 0) {
			String who = source.getEntity() instanceof Player player ? player.getName().getString() : "有人";
			level.getServer().sendSystemMessage(Component.literal(
					"§c[随机掉落] §e" + who + " §c干掉了精英，掉出 §b" + dropped + "§c 件宝藏！"));
		}
	}

	// ------------------------------------------------------------ 读 / 自检

	/** 目前还活着的精英数量。 */
	public static int aliveCount() {
		return ELITES.size();
	}

	/** 本局累计生成过几只精英。 */
	public static long spawnedCount() {
		return SPAWNED.get();
	}

	/** 本局累计杀掉过几只精英。 */
	public static long killedCount() {
		return KILLED.get();
	}

	/** 自检用：把精英表清空（进程内重开一局时也要清）。 */
	public static void reset() {
		ELITES.clear();
		SPAWNED.set(0L);
		KILLED.set(0L);
	}

	/** 自检用：直接登记一只精英，跳过「必须是玩家来源」的限制。 */
	static boolean markForTest(Mob mob) {
		if (mob == null) {
			return false;
		}

		ELITES.add(mob.getUUID());
		return true;
	}

	/** 自检用：这只生物被登记成精英了吗。 */
	public static boolean isElite(Entity entity) {
		return entity != null && ELITES.contains(entity.getUUID());
	}

	/** 自检用：把一只生物从精英表里摘掉（不留垃圾）。 */
	static void forgetForTest(Mob mob) {
		if (mob != null) {
			ELITES.remove(mob.getUUID());
		}
	}
}
