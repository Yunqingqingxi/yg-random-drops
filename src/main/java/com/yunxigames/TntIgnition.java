package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ② 挖方块有几率引爆 TNT，并朝四周甩射。
 *
 * <p>流程：玩家破坏一个「能引燃」的方块 → 按 {@code tntIgniteChance} 掷一次 →
 * 命中就以挖掘点为圆心，朝 {@code tntIgniteDirections} 个方向各甩出一枚已点燃的 TNT，
 * 引信 {@code tntIgniteFuseTicks} 刻（默认 4 秒），够跑开几步。
 *
 * <p>两条安全阀：
 * <ul>
 *   <li><b>冷却</b>：同一个玩家引爆后要等 {@code tntIgniteCooldownSeconds} 秒。
 *       挖方块太密集了，不设冷却 2% 会变成「每分钟都在炸」；</li>
 *   <li><b>无破坏者的路径不触发</b>：TNT 炸掉的方块 breaker 是 {@code null}，
 *       所以不会「炸 → 掉 → 再炸」无限连锁。</li>
 * </ul>
 */
public final class TntIgnition {
	/** 每个玩家上次引爆的墙钟时刻（毫秒）。 */
	private static final Map<UUID, Long> LAST_IGNITE = new ConcurrentHashMap<>();

	/** 标签缓存：{@code minecraft:logs -> TagKey}。配置重载后按同样的 key 复用，不会无限增长。 */
	private static final Map<String, TagKey<Block>> TAG_CACHE = new ConcurrentHashMap<>();

	/** 累计引爆次数（只用于自检统计）。 */
	private static long ignitions;

	private TntIgnition() {
	}

	/**
	 * 玩家破坏方块时调用。
	 *
	 * @return true 表示这次真的引爆了
	 */
	public static boolean tryIgnite(ServerLevel level, BlockPos pos, BlockState state, Player player,
			RandomSource random) {
		DropsConfig config = DropsConfig.get();

		if (!config.enableTntIgnition || level == null || pos == null || player == null || random == null) {
			return false;
		}

		if (!matches(state, config.tntIgniteBlocks)) {
			return false;
		}

		if (!cooldownAllows(player, config)) {
			return false;
		}

		double chance = config.tntIgniteChance;
		if (!(chance > 0.0D) || random.nextDouble() >= chance) {
			return false;
		}

		ignite(level, pos, player, config);
		return true;
	}

	/**
	 * 真的炸一次：甩 TNT + 撒碎块 + 播报 + 记冷却。
	 *
	 * @return 生成出来的 TNT（自检拿着它可以立刻销毁，避免真炸掉地形）
	 */
	public static List<PrimedTnt> ignite(ServerLevel level, BlockPos pos, LivingEntity owner,
			DropsConfig config) {
		List<PrimedTnt> spawned = spawnRing(level, pos, owner, config);

		if (config.tntIgniteDebris) {
			dropDebris(level, pos, config.tntIgniteDebrisItems);
		}

		if (owner != null) {
			LAST_IGNITE.put(owner.getUUID(), System.currentTimeMillis());
		}

		ignitions++;
		Feedback.tntIgnition(level, pos, owner, config);
		return spawned;
	}

	/** 自检用：只生成不播报、不记冷却，返回实体让调用方销毁。 */
	public static List<PrimedTnt> spawnOnlyForTest(ServerLevel level, BlockPos pos, DropsConfig config) {
		return spawnRing(level, pos, null, config);
	}

	/** 自检用：累计引爆次数。 */
	public static long ignitions() {
		return ignitions;
	}

	/** 关服 / 配置重载时清掉冷却表。 */
	public static void reset() {
		LAST_IGNITE.clear();
	}

	// ------------------------------------------------------------ 触发条件

	/**
	 * 这个方块算不算「能引燃」的方块。
	 *
	 * <p>支持两种写法：{@code #minecraft:logs}（方块标签）和 {@code minecraft:cobweb}（精确 id）。
	 * 清单为空表示<b>任何方块</b>都算。
	 */
	public static boolean matches(BlockState state, List<String> entries) {
		if (state == null) {
			return false;
		}

		if (entries == null || entries.isEmpty()) {
			return true;
		}

		for (String entry : entries) {
			if (entry == null || entry.isBlank()) {
				continue;
			}

			String text = entry.trim();

			if (text.startsWith("#")) {
				TagKey<Block> tag = tagOf(text.substring(1));

				if (tag != null && state.is(tag)) {
					return true;
				}
			} else {
				Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());

				if (id != null && id.toString().equals(text.toLowerCase(Locale.ROOT))) {
					return true;
				}
			}
		}

		return false;
	}

	/** 冷却是否放行；冷却未到返回 false。 */
	public static boolean cooldownAllows(Player player, DropsConfig config) {
		return cooldownAllows(player == null ? null : player.getUUID(), config);
	}

	/** 冷却是否放行（按玩家 id 判，自检直接用这个重载）。 */
	public static boolean cooldownAllows(UUID id, DropsConfig config) {
		if (id == null || config == null || config.tntIgniteCooldownSeconds <= 0) {
			return true;
		}

		Long last = LAST_IGNITE.get(id);

		if (last == null) {
			return true;
		}

		return System.currentTimeMillis() - last >= config.tntIgniteCooldownSeconds * 1000L;
	}

	/** 自检用：把冷却表塞一个「刚刚引爆过」，用来验证第二次被挡下。 */
	public static void markCooldownForTest(UUID id) {
		LAST_IGNITE.put(id, System.currentTimeMillis());
	}

	// ------------------------------------------------------------ 内部

	/** 按配置的方向数把 TNT 均匀甩一圈。 */
	private static List<PrimedTnt> spawnRing(ServerLevel level, BlockPos pos, LivingEntity owner,
			DropsConfig config) {
		List<PrimedTnt> spawned = new ArrayList<>();

		if (level == null || pos == null) {
			return spawned;
		}

		int directions = Math.max(1, config.tntIgniteDirections);
		double spread = Math.max(0.0D, config.tntIgniteSpreadBlocks);
		double centerX = pos.getX() + 0.5D;
		double centerY = pos.getY() + 0.5D;
		double centerZ = pos.getZ() + 0.5D;

		for (int i = 0; i < directions; i++) {
			// 4 个方向时从 +X 起步，正好是东 / 南 / 西 / 北
			double angle = (Math.PI * 2.0D * i) / directions;
			PrimedTnt tnt = new PrimedTnt(level,
					centerX + Math.cos(angle) * spread,
					centerY,
					centerZ + Math.sin(angle) * spread,
					owner);

			tnt.setFuse(config.tntIgniteFuseTicks);

			if (level.addFreshEntity(tnt)) {
				spawned.add(tnt);
			}
		}

		return spawned;
	}

	/** 撒碎块：{@code id;数量}，数量省略为 1。 */
	private static void dropDebris(ServerLevel level, BlockPos pos, List<String> entries) {
		if (level == null || pos == null || entries == null) {
			return;
		}

		for (String entry : entries) {
			ItemStack stack = parseEntry(entry);

			if (!stack.isEmpty()) {
				Block.popResource(level, pos, stack);
			}
		}
	}

	/** 通用解析：{@code minecraft:dirt;3} → 3 个泥土。id 不存在或数量为 0 时返回空。 */
	public static ItemStack parseEntry(String entry) {
		if (entry == null || entry.isBlank()) {
			return ItemStack.EMPTY;
		}

		String[] parts = entry.split(";");
		String rawId = parts[0].trim();

		if (rawId.isEmpty()) {
			return ItemStack.EMPTY;
		}

		Identifier id = Identifier.tryParse(rawId.indexOf(':') < 0 ? "minecraft:" + rawId : rawId);

		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
			return ItemStack.EMPTY;
		}

		int count = 1;

		if (parts.length > 1) {
			try {
				count = Integer.parseInt(parts[1].trim());
			} catch (NumberFormatException ignored) {
				count = 1;
			}
		}

		if (count <= 0) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(id));
		stack.setCount(Math.min(count, stack.getMaxStackSize()));
		return stack;
	}

	private static TagKey<Block> tagOf(String raw) {
		String text = raw.trim().toLowerCase(Locale.ROOT);
		Identifier id = Identifier.tryParse(text.indexOf(':') < 0 ? "minecraft:" + text : text);

		if (id == null) {
			return null;
		}

		return TAG_CACHE.computeIfAbsent(id.toString(),
				key -> TagKey.create(Registries.BLOCK, Identifier.tryParse(key)));
	}
}
