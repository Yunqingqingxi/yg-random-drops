package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ① 刷怪蛋（Spawner Egg）禁用。
 *
 * <p>刷怪蛋一旦能掉出来，整个玩法就废了：一颗僵尸蛋 = 无限刷怪 = 无限物资，
 * 「这一下会掉什么」的悬念也随之消失。所以这里做的是<b>彻底剔除</b>，而不是降权：
 * <ol>
 *   <li>随机物品池构建时直接跳过（{@link #isBanned(Item, DropsConfig)}）；</li>
 *   <li>抽中时兜底重抽（{@link #pickAllowed(List, RandomSource, DropsConfig)}）；</li>
 *   <li>万一还是漏出来（比如被写进了宝藏池 / 维度池），由掉落流程丢弃并广播警告。</li>
 * </ol>
 *
 * <p>判定三层，从宽到严：物品类是不是 {@code SpawnEggItem}、id 是不是 {@code *_spawn_egg}、
 * 有没有写在 {@code extraSpawnerEggItems} 里。第三层是给不继承 {@code SpawnEggItem} 的模组兜底的。
 */
public final class SpawnerEggGuard {
	/** 累计拦下的刷怪蛋次数（只用于自检统计）。 */
	private static final AtomicLong BLOCKED = new AtomicLong();

	private SpawnerEggGuard() {
	}

	/** 这件物品是不是「被禁掉的刷怪蛋」。 */
	public static boolean isBanned(Item item, DropsConfig config) {
		if (item == null || config == null || !config.blockSpawnerEggDrops) {
			return false;
		}

		if (item instanceof SpawnEggItem) {
			return true;
		}

		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id != null && config.isSpawnerEggId(id);
	}

	/** 物品 id 是不是「刷怪蛋 id」—— 只按 id 判，供自检核对配置写法。 */
	public static boolean isBannedId(Identifier id, DropsConfig config) {
		if (id == null || config == null || !config.blockSpawnerEggDrops) {
			return false;
		}

		return id.getPath().toLowerCase(Locale.ROOT).endsWith("_spawn_egg") || config.isSpawnerEggId(id);
	}

	/**
	 * 从池子里挑一件「不是刷怪蛋」的物品。
	 *
	 * <p>重抽次数复用 {@code exclusiveRerollAttempts}：正常情况一次就中，
	 * 只有池子里全是刷怪蛋才会抽不到 —— 那时返回 {@code null}，调用方按「这次什么都不掉」处理，
	 * 好过把刷怪蛋漏出去。
	 */
	public static Item pickAllowed(List<Item> pool, RandomSource random, DropsConfig config) {
		if (pool == null || pool.isEmpty() || random == null) {
			return null;
		}

		int attempts = Math.max(1, config == null ? 1 : config.exclusiveRerollAttempts);

		for (int i = 0; i < attempts; i++) {
			Item item = pool.get(random.nextInt(pool.size()));

			if (!isBanned(item, config)) {
				return item;
			}
		}

		return null;
	}

	/** 记一次「拦下了刷怪蛋」，并给出可见反馈。 */
	public static void reportBlocked(ServerLevel level, BlockPos pos, ItemStack stack, Entity cause) {
		BLOCKED.incrementAndGet();
		Feedback.spawnerEggBlocked(level, pos, stack, cause);
	}

	/** 累计拦下次数（自检用）。 */
	public static long blocked() {
		return BLOCKED.get();
	}
}
