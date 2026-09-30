package com.yunxigames;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * 进度分档 + 维度/群系加成。
 *
 * <p>这一局的玩法是「打到末影龙就结束」，所以<b>世界时间 ≈ 这一局的进度</b>，
 * 完全不需要存档、等级或成就系统就能判断「玩家现在打到哪了」。
 *
 * <p>两个加成是相乘的：
 * <ul>
 *   <li><b>进度</b>：{@code EARLY -> MID -> LATE}，暴击概率逐档变高，给长局一点补偿；</li>
 *   <li><b>维度</b>：下界 ×2、末地 ×3 —— 跑图是要花时间的，掉落得「认这个地方」。</li>
 * </ul>
 *
 * <p>除了调概率，还会给出一个「专属池」：在下界/末地/特色群系里，一定概率改从当地池抽，
 * 让「我在哪」这件事真的能尝出味道。
 */
public final class Progression {
	private Progression() {
	}

	/** 这一局打到哪一档了。 */
	public enum Tier {
		/** 开局保护窗口内。 */
		EARLY,
		/** 开局保护结束，但还没到 {@code midGameMinutes}。 */
		MID,
		/** 后期。 */
		LATE
	}

	/** 一分钟多少游戏刻。 */
	private static final long TICKS_PER_MINUTE = 60L * 20L;

	/**
	 * 按世界时间分档。
	 *
	 * <p>{@code level == null}（自检里的假掉落）一律按 {@link Tier#EARLY} 处理 ——
	 * 自检不应该因为「档位」而拿到放大后的暴击率。
	 */
	public static Tier tier(ServerLevel level, DropsConfig config) {
		if (level == null) {
			return Tier.EARLY;
		}

		return tierForMinutes(level.getGameTime() / TICKS_PER_MINUTE, config);
	}

	/**
	 * 分档的纯函数版本（不依赖世界）—— 分界值可以脱离服务器单独验证。
	 *
	 * <p>{@code earlyGameMinutes} 为 0 表示关闭开局保护，此时没有 EARLY 档。
	 */
	public static Tier tierForMinutes(long minutes, DropsConfig config) {
		long early = Math.max(0, config.earlyGameMinutes);

		if (early > 0 && minutes < early) {
			return Tier.EARLY;
		}

		long mid = Math.max(early, config.midGameMinutes);

		return minutes < mid ? Tier.MID : Tier.LATE;
	}

	/**
	 * 暴击概率的总倍率（进度 × 维度）。
	 *
	 * <p>刻意允许配成 0（= 该档/该维度不暴击），但不允许负数。
	 */
	public static double jackpotMultiplier(ServerLevel level, DropsConfig config) {
		double multiplier = 1.0D;

		if (config.progressScaling) {
			multiplier *= switch (tier(level, config)) {
				case EARLY -> 1.0D;
				case MID -> nonNegative(config.midJackpotMultiplier);
				case LATE -> nonNegative(config.lateJackpotMultiplier);
			};
		}

		if (config.dimensionAffectsPools && level != null) {
			multiplier *= dimensionMultiplier(level, config);
		}

		return nonNegative(multiplier);
	}

	/** 下界 / 末地的暴击倍率，主世界为 1。 */
	public static double dimensionMultiplier(ServerLevel level, DropsConfig config) {
		if (level == null) {
			return 1.0D;
		}

		ResourceKey<Level> dimension = level.dimension();

		if (Level.NETHER.equals(dimension)) {
			return nonNegative(config.netherJackpotMultiplier);
		}

		if (Level.END.equals(dimension)) {
			return nonNegative(config.endJackpotMultiplier);
		}

		return 1.0D;
	}

	/**
	 * 掷一次「专属池」。
	 *
	 * <p>优先级：维度（下界/末地）> 群系。两者都不命中就返回 {@code null}，
	 * 调用方照常用普通物品池。
	 *
	 * @return 命中的物品 id 列表（可能包含不存在的 id，由调用方过滤）；
	 *         {@code null} 表示这次不触发专属池
	 */
	public static List<String> rollExclusivePool(ServerLevel level, BlockPos pos, RandomSource random,
			DropsConfig config) {
		if (level == null || random == null) {
			return null;
		}

		if (config.dimensionAffectsPools) {
			List<String> dimensionPool = dimensionPool(level, config);

			if (dimensionPool != null && !dimensionPool.isEmpty()
					&& random.nextDouble() < clamp01(config.dimensionBonusChance)) {
				return dimensionPool;
			}
		}

		if (config.biomeAffectsPools && pos != null && !config.biomeBonusItems.isEmpty()
				&& isSpecialBiome(level, pos, config)
				&& random.nextDouble() < clamp01(config.biomeBonusChance)) {
			return config.biomeBonusItems;
		}

		return null;
	}

	/** 当前维度对应的专属池，主世界返回 {@code null}。 */
	public static List<String> dimensionPool(ServerLevel level, DropsConfig config) {
		if (level == null) {
			return null;
		}

		ResourceKey<Level> dimension = level.dimension();

		if (Level.NETHER.equals(dimension)) {
			return config.netherBonusItems;
		}

		if (Level.END.equals(dimension)) {
			return config.endBonusItems;
		}

		return null;
	}

	/** 这个位置所在的群系算不算「特色群系」（按 id 路径子串匹配）。 */
	public static boolean isSpecialBiome(ServerLevel level, BlockPos pos, DropsConfig config) {
		if (level == null || pos == null || config.biomeKeywords.isEmpty()) {
			return false;
		}

		Identifier id = biomeId(level, pos);

		if (id == null) {
			return false;
		}

		String path = id.getPath().toLowerCase(Locale.ROOT);

		for (String keyword : config.biomeKeywords) {
			if (keyword == null || keyword.isBlank()) {
				continue;
			}

			if (path.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}

		return false;
	}

	/** 取某个位置的群系 id；拿不到就返回 {@code null}。 */
	public static Identifier biomeId(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return null;
		}

		Holder<Biome> holder = level.getBiome(pos);

		return holder.unwrapKey().map(ResourceKey::identifier).orElse(null);
	}

	/** 自检用：把分档名字拿出来。 */
	public static String tierName(ServerLevel level, DropsConfig config) {
		return tier(level, config).name();
	}

	private static double nonNegative(double value) {
		return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value) || value <= 0.0D) {
			return 0.0D;
		}

		return Math.min(1.0D, value);
	}
}
