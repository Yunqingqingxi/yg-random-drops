package com.yunxigames;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ⑤ 分层掉落 + 终极物资。
 *
 * <p><b>分层掉落</b>：某些物品<b>只</b>在对应维度产出。
 * 它和已有的「维度专属池」是两回事 —— 专属池是「在下界更可能抽到烈焰棒」，
 * 这里是「下界合金碎片在主世界根本抽不到」，想拿就得去下界。
 * 实现方式是抽到「别的维度专属」的物品就重抽（次数见 {@code exclusiveRerollAttempts}）。
 *
 * <p><b>终极物资</b>：在指定维度（默认末地）按 {@code ultimateChance}（默认 1%）直接产出
 * 末地传送门框架 / 末影之眼。这两样在正常生存里几乎拿不到
 * （末地传送门框架默认就在物品黑名单里），所以它是这一局真正的终点奖励 ——
 * 凑齐 12 个框架就能自己开一个传送门。
 */
public final class TieredDrops {
	/** 累计产出终极物资的次数（只用于自检统计）。 */
	private static final AtomicLong ULTIMATES = new AtomicLong();

	private TieredDrops() {
	}

	/**
	 * 这件物品是不是「别的维度的专属物品」。
	 *
	 * <p>只有 {@code enableTieredDrops} 打开时才判定；维度为 {@code null}（自检的假掉落）
	 * 一律放行 —— 自检不应该因为维度被过滤掉。
	 */
	public static boolean isExclusiveElsewhere(Item item, ServerLevel level, DropsConfig config) {
		if (item == null || config == null || !config.enableTieredDrops) {
			return false;
		}

		Identifier id = BuiltInRegistries.ITEM.getKey(item);

		if (id == null) {
			return false;
		}

		if (config.isNetherExclusive(id) && !inDimension(level, Level.NETHER)) {
			return true;
		}

		return config.isEndExclusive(id) && !inDimension(level, Level.END);
	}

	/**
	 * 终极物资掷骰。
	 *
	 * @return 命中返回物品堆；否则返回 {@link ItemStack#EMPTY}
	 */
	public static ItemStack rollUltimate(ServerLevel level, RandomSource random, DropsConfig config) {
		if (config == null || !config.enableUltimateDrops || random == null) {
			return ItemStack.EMPTY;
		}

		double chance = config.ultimateChance;

		if (!(chance > 0.0D) || random.nextDouble() >= chance) {
			return ItemStack.EMPTY;
		}

		if (!dimensionMatches(level, config)) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = pickUltimate(random, config);

		if (!stack.isEmpty()) {
			ULTIMATES.incrementAndGet();
		}

		return stack;
	}

	/** 自检用：跳过维度与概率，直接看命中时会给什么。 */
	public static ItemStack pickUltimate(RandomSource random, DropsConfig config) {
		boolean wantFrames = random.nextBoolean();

		if (wantFrames && config.ultimatePortalFrameCount > 0) {
			return stack(Items.END_PORTAL_FRAME, config.ultimatePortalFrameCount);
		}

		if (config.ultimateEnderEyeCount > 0) {
			return stack(Items.ENDER_EYE, config.ultimateEnderEyeCount);
		}

		return config.ultimatePortalFrameCount > 0
				? stack(Items.END_PORTAL_FRAME, config.ultimatePortalFrameCount)
				: ItemStack.EMPTY;
	}

	/** 当前维度是不是配置里那个「终点维度」；{@code any} 表示任意维度。 */
	public static boolean dimensionMatches(ServerLevel level, DropsConfig config) {
		if (level == null || config == null) {
			return false;
		}

		if (config.ultimateDimension == null || config.ultimateDimension.isBlank()
				|| "any".equalsIgnoreCase(config.ultimateDimension.trim())) {
			return true;
		}

		return config.ultimateDimension.trim().equalsIgnoreCase(level.dimension().identifier().getPath());
	}

	/** 累计产出次数（自检用）。 */
	public static long ultimates() {
		return ULTIMATES.get();
	}

	// ------------------------------------------------------------ 内部

	private static boolean inDimension(ServerLevel level, net.minecraft.resources.ResourceKey<Level> dimension) {
		return level != null && dimension.equals(level.dimension());
	}

	private static ItemStack stack(Item item, int count) {
		ItemStack stack = new ItemStack(item);
		stack.setCount(Math.min(64, Math.max(1, count)));
		return stack;
	}
}
