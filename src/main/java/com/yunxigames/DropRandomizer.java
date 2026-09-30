package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 随机掉落的核心逻辑。
 *
 * <p>每次掉落掷一次骰子：
 * <ol>
 *   <li>以 {@code mobChance} 的概率走「生物」分支 —— 再按 敌对 / 中立 / 友好 的权重抽一只生物，
 *       直接生成在方块的位置上；</li>
 *   <li>否则走「物品」分支 —— 从全部已注册物品里等概率抽一个。</li>
 * </ol>
 *
 * <p>随机数种子以时间戳为主熵源，见 {@link #timestampSeed(BlockPos)}。
 */
public final class DropRandomizer {
	private static final AtomicLong SEQUENCE = new AtomicLong();

	/** 当前游戏刻已经生成过多少只生物，每个 tick 开头清零。 */
	private static final AtomicInteger MOBS_THIS_TICK = new AtomicInteger();

	/** 每个玩家、每个「物品 / 生物类型」各自的保底计数。 */
	private static final Map<UUID, Map<String, Integer>> PITY = new ConcurrentHashMap<>();

	/**
	 * 累计「抽中了生物、但 {@code spawn()} 没成功」的次数。
	 *
	 * <p>这种情况会静默退回掉物品，于是实测的生物比例会略低于配置值 —— 计数是为了让自检能解释这个差额。
	 */
	private static final AtomicLong SPAWN_FAILURES = new AtomicLong();

	/** 「随机数判中生物」的次数，只用于自检核对概率。 */
	private static final AtomicLong MOB_ROLLS = new AtomicLong();

	/** 实际生成出来的生物个数（一次可能爆好几只），只用于自检统计。 */
	private static final AtomicLong MOBS_SPAWNED = new AtomicLong();

	/** 落在「什么都不掉」那一段的次数，只用于自检统计。 */
	private static final AtomicLong NOTHING_ROLLS = new AtomicLong();

	/** 开局保护结束只公告一次。 */
	private static volatile boolean EARLY_GAME_ANNOUNCED = false;

	/** 已经公告过的进度档位（null = 还没公告过中期/后期）。 */
	private static volatile Progression.Tier ANNOUNCED_TIER;

	/** 兜底限速用的窗口计数与窗口起点。 */
	private static final AtomicInteger MOB_RATE_COUNT = new AtomicInteger();
	private static final AtomicLong MOB_RATE_WINDOW_START = new AtomicLong();

	/** 被限速拦下的次数，只用于自检统计。 */
	private static final AtomicLong RATE_THROTTLED = new AtomicLong();

	private static volatile List<Item> itemPool;
	private static volatile MobPools mobPools;
	private static volatile List<Item> jackpotPool;

	/**
	 * 「维度 / 群系专属池」的解析缓存。
	 *
	 * <p>键就是配置里那份 id 列表本身（值相等即命中），所以配置重载后旧键会被自然覆盖，
	 * 不会无限增长 —— 玩家不可能配出成千上万份不同的列表。
	 */
	private static final Map<List<String>, List<Item>> BONUS_POOLS = new ConcurrentHashMap<>();

	/** 累计暴击次数，只用于自检。 */
	private static final AtomicLong JACKPOTS = new AtomicLong();

	private DropRandomizer() {
	}

	// ------------------------------------------------------------ 对外入口

	/**
	 * 方块被破坏时调用。
	 *
	 * @param breaker 破坏方块的实体，玩家挖掘时是玩家；爆炸 / 活塞等路径为 {@code null}
	 * @return 要掉落的物品；返回空列表表示「这次什么都不掉」（生物已经另行生成）
	 */
	/**
	 * 「无掉落方块」黑名单：这些方块被移除时<b>不</b>触发随机掉落。
	 *
	 * <p>修「灭火也掉随机物品」bug —— 火被水扑灭走的同样是「无破坏者的方块移除」路径，
	 * 但火原版就没有任何掉落物，凭空掉随机物品是 bug 不是惊喜。
	 * 判定走配置的 {@code noDropBlocks}（默认 fire / soul_fire，可自行追加）。
	 */
	public static boolean isDroplessBlock(net.minecraft.world.level.block.state.BlockState state) {
		if (state == null) {
			return false;
		}

		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id != null && DropsConfig.get().noDropBlocks.contains(id.toString());
	}

	/**
	 * <b>植被类方块</b>（v1.14.1）：花草 / 枯叶堆 / 水草等被破坏时<b>什么都不掉</b> ——
	 * 连原版的小麦种子也不掉，更不允许随机掉落借道。
	 *
	 * <p>覆盖：各类花（含 #minecraft:flowers 标签）、各种草与蕨、枯灌木、海草 / 海带、
	 * 枯叶堆、灌木、萤火虫灌木、粉红色花瓣、眼眸花。
	 */
	public static boolean isNoLootPlant(net.minecraft.world.level.block.state.BlockState state) {
		if (state == null) {
			return false;
		}

		// 全部花卉走原版标签（含花苞与盛开变种），个别 26.2 新增植物直接枚举
		if (state.is(net.minecraft.tags.BlockTags.FLOWERS)) {
			return true;
		}

		return NO_LOOT_PLANTS.contains(state.getBlock());
	}

	/** 不允许任何掉落的植被方块（花草 / 水草 / 枯叶堆等，v1.14.1）。 */
	private static final java.util.Set<net.minecraft.world.level.block.Block> NO_LOOT_PLANTS =
			java.util.Set.of(
					net.minecraft.world.level.block.Blocks.SHORT_GRASS,
					net.minecraft.world.level.block.Blocks.TALL_GRASS,
					net.minecraft.world.level.block.Blocks.FERN,
					net.minecraft.world.level.block.Blocks.LARGE_FERN,
					net.minecraft.world.level.block.Blocks.DEAD_BUSH,
					net.minecraft.world.level.block.Blocks.SEAGRASS,
					net.minecraft.world.level.block.Blocks.TALL_SEAGRASS,
					net.minecraft.world.level.block.Blocks.KELP,
					net.minecraft.world.level.block.Blocks.KELP_PLANT,
					net.minecraft.world.level.block.Blocks.LEAF_LITTER,
					net.minecraft.world.level.block.Blocks.BUSH,
					net.minecraft.world.level.block.Blocks.FIREFLY_BUSH,
					net.minecraft.world.level.block.Blocks.TALL_DRY_GRASS,
					net.minecraft.world.level.block.Blocks.PINK_PETALS);

	public static List<ItemStack> rollBlockDrop(ServerLevel level, BlockPos pos, Entity breaker) {
		DropsConfig config = DropsConfig.get();
		boolean allowMob = config.mobChance > 0.0D
				&& (!config.spawnMobsOnlyFromPlayers || breaker instanceof Player);
		return roll(level, pos, breaker, allowMob, true);
	}

	/**
	 * 生物死亡时调用（玩家死亡不会走到这里）。
	 *
	 * <p>这条路径的调用方是 {@code void} 的，所以物品必须由我们自己丢进世界里。
	 *
	 * @param playerKill 这次击杀是不是玩家造成的 —— 决定能不能爆出新生物（见
	 *                   {@link DropsConfig#mobSpawnRequiresPlayerKill}）
	 * @param killer 击杀者（用来判断能不能爆生物、以及播报时署谁的名），可能为 {@code null}
	 */
	public static void applyMobDrop(ServerLevel level, BlockPos pos, Entity killer) {
		DropsConfig config = DropsConfig.get();
		boolean playerKill = killer instanceof Player;
		boolean allowMob = config.mobChance > 0.0D
				&& (playerKill || !config.mobSpawnRequiresPlayerKill);

		// 生物死亡默认不出现「什么都不掉」：前期杀牛却啥也没有太劝退了
		for (ItemStack stack : roll(level, pos, killer, allowMob, config.allowNothingOnMobDrop)) {
			Block.popResource(level, pos, stack);
		}
	}

	/** 配置重载后清空缓存的候选池。 */
	public static void resetPools() {
		itemPool = null;
		mobPools = null;
		jackpotPool = null;
		BONUS_POOLS.clear();
	}

	/** 每个游戏刻开头调用，重置这一 tick 的生物生成配额。 */
	public static void resetTickBudget() {
		MOBS_THIS_TICK.set(0);
	}

	// ------------------------------------------------------------ 保底机制

	/**
	 * 方块掉落的保底 —— <b>按物品分别计数</b>。
	 *
	 * <p>键是这次原本会掉出来的物品 id，所以中间穿插挖别的方块完全不会影响某个物品的保底进度：
	 * 连着挖 5 次泥土（中间夹着挖石头）时，第 5 次泥土必定掉泥土，石头那边也各自独立计数。
	 *
	 * <p>注意这个方法有副作用（推进计数），每次掉落事件只应调用一次。
	 *
	 * @param cause  掉落的原因方，只有玩家才计数
	 * @param itemId 这次原本会掉出来的物品 id
	 * @return true 表示保底触发 —— 调用方应当什么都不做，让原版掉落照常执行
	 */
	public static boolean isPityDropForItem(ServerLevel level, Entity cause, Identifier itemId) {
		if (!(cause instanceof Player player) || itemId == null) {
			return false;
		}

		DropsConfig config = DropsConfig.get();
		return advancePity(player.getUUID(), "item:" + itemId,
				config.effectivePityThreshold(isEarlyGame(level, config)));
	}

	/**
	 * 生物掉落的保底 —— 同样按对象分别计数，键是<b>生物类型</b>。
	 *
	 * <p>连杀 3 只同类生物，第 3 只必定掉回它的原版战利品；中间杀别的生物不影响。
	 */
	public static boolean isPityDropForMob(ServerLevel level, Entity cause, Identifier entityTypeId) {
		if (!(cause instanceof Player player) || entityTypeId == null) {
			return false;
		}

		return advancePity(player.getUUID(), "mob:" + entityTypeId, DropsConfig.get().mobPityThreshold);
	}

	// ------------------------------------------------------------ 开局保护

	/**
	 * 是否还在「开局保护」窗口内。
	 *
	 * <p>因为玩法是「打到末影龙就结束」，**世界时间就约等于这一局的进度**，
	 * 所以直接拿它当判据，不需要任何持久化。
	 */
	public static boolean isEarlyGame(ServerLevel level, DropsConfig config) {
		int minutes = config.earlyGameMinutes;
		return minutes > 0 && level != null && level.getGameTime() < (long) minutes * 60L * 20L;
	}

	/** 开局保护结束时公告一次（纯氛围，一局一次）。 */
	private static void announceEarlyGameEnd(ServerLevel level, DropsConfig config, boolean early) {
		if (config.earlyGameMinutes <= 0 || early || EARLY_GAME_ANNOUNCED) {
			return;
		}

		EARLY_GAME_ANNOUNCED = true;

		if (level != null) {
			level.getServer().sendSystemMessage(Component.literal("§e[随机掉落] 开局保护结束 —— 从现在开始是完全随机！"));
		}
	}

	/**
	 * 进入中 / 后期时公告一次（纯氛围）。
	 *
	 * <p>进度靠世界时间判断，玩家自己看不见，所以得说一声 ——
	 * 不然「后半段暴击变多了」这个设计就白做了。
	 */
	private static void announceProgressTier(ServerLevel level, DropsConfig config) {
		if (!config.progressScaling || !config.announceProgressTier || level == null) {
			return;
		}

		Progression.Tier now = Progression.tier(level, config);

		if (now == Progression.Tier.EARLY || now == ANNOUNCED_TIER) {
			return;
		}

		// 只往前走，不回头（世界时间不会倒退，这里只是防御性写法）
		if (ANNOUNCED_TIER == null || now.ordinal() > ANNOUNCED_TIER.ordinal()) {
			ANNOUNCED_TIER = now;

			String text = now == Progression.Tier.MID
					? "§e[随机掉落] 进入中期 —— 暴击概率提升！"
					: "§6[随机掉落] 进入后期 —— 暴击概率大幅提升，好东西要来了！";

			level.getServer().sendSystemMessage(Component.literal(text));
		}
	}

	/** 推进「某玩家 + 某个键」的保底计数；自检直接调这个重载。 */
	public static boolean advancePity(UUID playerId, String key, int threshold) {
		if (threshold <= 0) {
			return false;
		}

		Map<String, Integer> counters = PITY.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
		int count = counters.merge(key, 1, Integer::sum);

		if (count >= threshold) {
			counters.put(key, 0);
			return true;
		}

		return false;
	}

	/**
	 * 取原版掉落里第一件非空物品的 id —— 它就是这次「原物品」的保底键。
	 *
	 * <p>取值来自原版算好的结果，所以别的模组改了掉落表也能正确跟随。
	 */
	public static Identifier firstItemId(List<ItemStack> drops) {
		if (drops == null) {
			return null;
		}

		for (ItemStack stack : drops) {
			if (stack != null && !stack.isEmpty()) {
				return BuiltInRegistries.ITEM.getKey(stack.getItem());
			}
		}

		return null;
	}

	/** 清掉某个玩家的所有保底计数（自检用完清理）。 */
	public static void forgetPity(UUID playerId) {
		PITY.remove(playerId);
	}

	/** 服务器停止时清空。 */
	public static void clearPity() {
		PITY.clear();
	}

	/** 累计的生物生成失败次数（只用于自检统计）。 */
	public static long spawnFailures() {
		return SPAWN_FAILURES.get();
	}

	/** 累计的「随机数判中生物」次数（只用于自检统计）。 */
	public static long mobRolls() {
		return MOB_ROLLS.get();
	}

	/** 累计实际生成的生物个数（只用于自检统计）。 */
	public static long mobsSpawned() {
		return MOBS_SPAWNED.get();
	}

	/** 累计「什么都不掉」的次数（只用于自检统计）。 */
	public static long nothingRolls() {
		return NOTHING_ROLLS.get();
	}

	/** 累计被限速拦下的次数（只用于自检统计）。 */
	public static long rateThrottled() {
		return RATE_THROTTLED.get();
	}

	/** 自检用：把限速窗口重置到当前时刻。 */
	public static void resetMobRate() {
		MOB_RATE_COUNT.set(0);
		MOB_RATE_WINDOW_START.set(System.currentTimeMillis());
	}

	/** 兜底限速：窗口内已经生成够多生物了就返回 false。 */
	private static boolean rateLimitAllows(DropsConfig config) {
		int limit = config.mobSpawnRateLimit;

		if (limit <= 0) {
			return true;
		}

		long windowMillis = Math.max(1, config.mobSpawnRateWindowSeconds) * 1000L;
		long now = System.currentTimeMillis();
		long start = MOB_RATE_WINDOW_START.get();

		// 跨过窗口边界：重置计数
		if (now - start >= windowMillis && MOB_RATE_WINDOW_START.compareAndSet(start, now)) {
			MOB_RATE_COUNT.set(0);
		}

		if (MOB_RATE_COUNT.get() >= limit) {
			RATE_THROTTLED.incrementAndGet();
			return false;
		}

		return true;
	}

	/** 自检用：看一眼当前的随机物品池。 */
	static List<Item> itemPoolSnapshot() {
		return itemPool();
	}

	/** 暴击宝藏池（懒加载）。配置里写了不存在的 id 会跳过并记日志。 */
	private static List<Item> jackpotPool() {
		List<Item> pool = jackpotPool;
		if (pool != null) {
			return pool;
		}

		synchronized (DropRandomizer.class) {
			if (jackpotPool != null) {
				return jackpotPool;
			}

			DropsConfig config = DropsConfig.get();
			List<Item> built = new ArrayList<>();
			List<String> missing = new ArrayList<>();

			for (String entry : config.jackpotItems) {
				if (entry == null || entry.isBlank()) {
					continue;
				}

				String text = entry.trim().toLowerCase(java.util.Locale.ROOT);
				Identifier id = Identifier.tryParse(text.indexOf(':') < 0 ? "minecraft:" + text : text);

				if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
					missing.add(entry);
					continue;
				}

				Item item = BuiltInRegistries.ITEM.getValue(id);
				if (item != null && item != Items.AIR) {
					built.add(item);
				}
			}

			if (!missing.isEmpty()) {
				Yg.LOGGER.warn("[yg] 宝藏池里这些 id 不存在，已忽略：{}", missing);
			}

			if (built.isEmpty()) {
				Yg.LOGGER.warn("[yg] 宝藏池是空的，暴击大爆暂时失效");
			} else {
				Yg.LOGGER.info("[yg] 暴击宝藏池已构建：{} 项", built.size());
			}

			jackpotPool = List.copyOf(built);
			return jackpotPool;
		}
	}

	/** 自检用：这个物品是不是宝藏池里的。 */
	static boolean isJackpotItem(Item item) {
		return jackpotPool().contains(item);
	}

	/**
	 * 把配置里的一组 id 解析成物品池（带缓存）。
	 *
	 * <p>维度专属池、群系专属池都走这里。写错的 id 会被跳过并记一次日志 ——
	 * 和 {@link #jackpotPool()} 一个脾气，配置写坏不该让游戏崩，只该少几件东西。
	 *
	 * @return 解析成功的物品；可能是空列表（调用方要自己兜底退回普通池）
	 */
	private static List<Item> resolvePool(List<String> rawIds) {
		if (rawIds == null || rawIds.isEmpty()) {
			return List.of();
		}

		return BONUS_POOLS.computeIfAbsent(List.copyOf(rawIds), ids -> {
			List<Item> built = new ArrayList<>();
			List<String> missing = new ArrayList<>();

			for (String entry : ids) {
				if (entry == null || entry.isBlank()) {
					continue;
				}

				String text = entry.trim().toLowerCase(java.util.Locale.ROOT);
				Identifier id = Identifier.tryParse(text.indexOf(':') < 0 ? "minecraft:" + text : text);

				if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
					missing.add(entry);
					continue;
				}

				Item item = BuiltInRegistries.ITEM.getValue(id);

				if (item != null && item != Items.AIR) {
					built.add(item);
				}
			}

			if (!missing.isEmpty()) {
				Yg.LOGGER.warn("[yg] 专属池里这些 id 不存在，已忽略：{}", missing);
			}

			return List.copyOf(built);
		});
	}

	/** 自检用：看一眼某个专属池解析出来是什么。 */
	static List<Item> resolvePoolSnapshot(List<String> rawIds) {
		return resolvePool(rawIds);
	}

	/**
	 * 稀有物品：暴击物品 + 额外的高价值物品。
	 *
	 * <p>"稀有" 的补充列表：附魔书、所有药水、附魔台、苦力怕头颅、海晶块等。
	 */
	static boolean isRareItem(Item item) {
		if (jackpotPool().contains(item)) {
			return true;
		}

		// Bonus rare items
		return BuiltInRegistries.ITEM.getKey(item).toString().matches(
				"enchanted_.*|potion:.*|beacon|sculk_skeleton|skull" +
				"|prismarine_shard|amber_chunk");
	}

	/** 累计暴击次数（自检用）。 */
	public static long jackpots() {
		return JACKPOTS.get();
	}

	// ------------------------------------------------------------ 核心掷骰

	private static List<ItemStack> roll(ServerLevel level, BlockPos pos, Entity cause, boolean allowMob, boolean allowEmpty) {
		DropsConfig config = DropsConfig.get();
		RandomSource random = RandomSource.create(timestampSeed(pos));

		// 本局统计：一次掉落事件（自检期间的假掉落会被 SessionStats.pause() 挡掉）
		SessionStats.dropEvent();

		// 开局保护：窗口内用更温和的空手率/权重/保底
		boolean early = isEarlyGame(level, config);
		announceEarlyGameEnd(level, config, early);
		announceProgressTier(level, config);

		// 暴击大爆：先单独掷一次，命中就必定从宝藏池出，不走后面的三段分支。
		// 概率会被「进度分档」与「维度」放大 —— 后期 / 下界 / 末地都更爱暴击。
		// v1.14.1 幸运加成：手持工具 / 武器的附魔总等级越高，暴击率越高（挖矿 / 击杀都看主手）。
		double jackpotChance = Math.min(1.0D,
				config.jackpotChance * Progression.jackpotMultiplier(level, config));
		if (config.enableLuckBonus && cause instanceof Player holder) {
			jackpotChance = Math.min(1.0D,
					jackpotChance + handLuckBonus(holder.getMainHandItem(), config));
		}

		if (config.enableJackpot && jackpotChance > 0.0D && random.nextDouble() < jackpotChance) {
			List<Item> treasure = jackpotPool();

			if (!treasure.isEmpty()) {
				Item treasureItem = treasure.get(random.nextInt(treasure.size()));
				int treasureCount = Math.min(randomBetween(random, config.itemCountMin, config.itemCountMax),
						Math.max(1, new ItemStack(treasureItem).getMaxStackSize()));
				ItemStack stack = makeStack(treasureItem, treasureCount, level, random);

				JACKPOTS.incrementAndGet();
				SessionStats.jackpot();
				SessionStats.itemsGiven(treasureCount);
				DropTally.record(treasureItem, treasureCount);
				Feedback.jackpot(level, pos, stack, cause);

				if (config.rareDropBroadcast) {
					Feedback.rareDrop(level, pos, stack, cause);
				}

				if (config.debugLog) {
					Yg.LOGGER.info("[yg] {} -> 暴击大爆 {} x{}",
							formatPos(pos), idOf(treasureItem), treasureCount);
				}

				return List.of(stack);
			}
		}

		// ⑤ 终极物资：在指定维度（默认末地）小概率直接产出末地传送门框架 / 末影之眼。
		// 这两样正常生存拿不到，是这一局真正的「终点奖励」。
		ItemStack ultimate = TieredDrops.rollUltimate(level, random, config);

		if (!ultimate.isEmpty()) {
			SessionStats.itemsGiven(ultimate.getCount());
			DropTally.record(ultimate.getItem(), ultimate.getCount());
			Feedback.ultimate(level, pos, ultimate, cause);

			if (config.debugLog) {
				Yg.LOGGER.info("[yg] {} -> 终极物资 {} x{}",
						formatPos(pos), idOf(ultimate.getItem()), ultimate.getCount());
			}

			return List.of(ultimate);
		}

		double roll = random.nextDouble();
		double mobBand = config.mobChance;
		double emptyBand = mobBand + config.effectiveEmptyChance(early);

		// 落在「什么都不掉」那一段（生物掉落的场合可以关掉这一段，那份概率自动并给物品）
		if (allowEmpty && roll >= mobBand && roll < emptyBand) {
			NOTHING_ROLLS.incrementAndGet();
			SessionStats.nothing();

			if (config.debugLog) {
				Yg.LOGGER.info("[yg] {} -> 什么都没掉", formatPos(pos));
			}

			return List.of();
		}

		boolean mobRolled = allowMob && roll < mobBand;

		// 只统计「随机数判中」的次数，不掺入后续生成成功与否 —— 自检用它核对概率本身
		if (mobRolled) {
			MOB_ROLLS.incrementAndGet();
			SessionStats.mobRoll();
		}

		if (mobRolled && MOBS_THIS_TICK.get() < config.maxMobsPerTick) {
			EntityType<?> type = pickMob(random, config, early);

			if (type != null) {
				int wanted = randomBetween(random, config.mobCountMin, config.mobCountMax);
				int spawnedCount = 0;

				for (int i = 0; i < wanted; i++) {
					// 两条硬上限：本 tick 的配额、以及滑动窗口的总量
					if (MOBS_THIS_TICK.get() >= config.maxMobsPerTick || !rateLimitAllows(config)) {
						break;
					}

					Entity spawned = type.spawn(level, pos, EntitySpawnReason.EVENT);

					// 兜底：万一注册表里混进了「名字像生物、实际不是 Mob」的条目，撤销它
					if (spawned != null && !(spawned instanceof Mob)) {
						spawned.discard();
						spawned = null;
					}

					if (spawned == null) {
						// 生成失败：计入统计，方便解释实测比例为何略低于配置值
						SPAWN_FAILURES.incrementAndGet();
						break;
					}

					MOBS_THIS_TICK.incrementAndGet();
					MOBS_SPAWNED.incrementAndGet();
					MOB_RATE_COUNT.incrementAndGet();
					spawnedCount++;

					// 刚爆出来的生物先「僵直」几秒，免得玩家在洞里被贴脸围死
					if (spawned instanceof Mob mob) {
						MobStun.stun(mob, config.spawnedMobStunTicks);

						// 精英怪：同样先僵直，再叠状态，免得刚登场就把玩家秒了
						if (EliteMobs.rollElite(cause, random, config)) {
							EliteMobs.makeElite(mob, level, cause);
						}
					}
				}

				if (spawnedCount > 0) {
					SessionStats.mobsSpawned(spawnedCount);

					if (config.debugLog) {
						Yg.LOGGER.info("[yg] {} -> 生成生物 {} x{} [{}]",
								formatPos(pos), idOf(type), spawnedCount, groupOf(type, config).label);
					}
					return List.of();
				}
			}
		}

		// 维度 / 群系专属池：命中就优先从「当地特产」里出，让挖矿地点真的尝得出区别
		List<String> exclusiveIds = Progression.rollExclusivePool(level, pos, random, config);
		List<Item> pool = exclusiveIds == null ? itemPool() : resolvePool(exclusiveIds);

		// 专属池配坏了（id 全不存在）就静默退回普通池，不给玩家一个「空手」的意外
		if (pool.isEmpty()) {
			pool = itemPool();
		}

		if (pool.isEmpty()) {
			return List.of();
		}

		// ① 刷怪蛋 / ⑤ 分层掉落：抽到刷怪蛋或「别的维度专属」的物品就重抽几次
		Item item = pickFinalItem(pool, random, level, pos, config);

		if (item == null) {
			// 极端配置（整个池子都是被排除的物品）才会走到这里：宁可什么都不掉，也不放行
			if (config.debugLog) {
				Yg.LOGGER.info("[yg] {} -> 池子里没有能掉的东西，跳过", formatPos(pos));
			}

			return List.of();
		}

		// 数量在配置范围内随机，但不超过该物品自身的堆叠上限（剑就是 1 把）
		ItemStack probe = new ItemStack(item);
		int count = Math.min(randomBetween(random, config.itemCountMin, config.itemCountMax),
				Math.max(1, probe.getMaxStackSize()));

		if (config.debugLog) {
			Yg.LOGGER.info("[yg] {} -> 掉落物品 {} x{}", formatPos(pos), idOf(item), count);
		}

		SessionStats.itemsGiven(count);
		DropTally.record(item, count);

		// 稀有物品：触发广播
		if (config.rareDropBroadcast && isRareItem(item)) {
			Feedback.rareDrop(level, pos, makeStack(item, count, level, random), cause);
		}

		return List.of(makeStack(item, count, level, random));
	}

	/**
	 * 从池子里挑一件「这一次真的能掉出来」的物品。
	 *
	 * <p>两道过滤：① 刷怪蛋（漏网的会被报告并丢弃）、⑤ 别的维度专属物品。
	 * 正常配置下一次就中；只有池子里全是被排除的物品才会返回 {@code null}，
	 * 那时调用方按「这次什么都不掉」处理 —— 好过把刷怪蛋漏出去、或把下界物资掉在主世界。
	 */
	private static Item pickFinalItem(List<Item> pool, RandomSource random, ServerLevel level, BlockPos pos,
			DropsConfig config) {
		int attempts = Math.max(1, config.exclusiveRerollAttempts);

		for (int i = 0; i < attempts; i++) {
			Item item = pool.get(random.nextInt(pool.size()));

			if (SpawnerEggGuard.isBanned(item, config)) {
				SpawnerEggGuard.reportBlocked(level, pos, new ItemStack(item), null);
				continue;
			}

			if (TieredDrops.isExclusiveElsewhere(item, level, config)) {
				continue;
			}

			return item;
		}

		return null;
	}

	/**
	 * 幸运加成（v1.14.1）：主手物品的<b>附魔总等级</b> × 每级加成，封顶
	 * {@code luckBonusCap}。诅咒不计入（背诅咒不是幸运）。空手 / 无附魔 = 0。
	 */
	static double handLuckBonus(ItemStack tool, DropsConfig config) {
		if (tool == null || tool.isEmpty() || config.luckBonusPerLevel <= 0.0D) {
			return 0.0D;
		}

		ItemEnchantments ench = tool.get(DataComponents.ENCHANTMENTS);
		if (ench == null || ench.isEmpty()) {
			return 0.0D;
		}

		int totalLevels = 0;
		for (Holder<Enchantment> h : ench.keySet()) {
			if (h != null && !h.is(net.minecraft.tags.EnchantmentTags.CURSE)) {
				totalLevels += ench.getLevel(h);
			}
		}

		return Math.min(config.luckBonusCap, totalLevels * config.luckBonusPerLevel);
	}

	/**
	 * Bingo 物品板用：从主随机池随机抽 {@code count} 个<b>不重复</b>的物品 id 字符串。
	 *
	 * <p>走完整主池（含模组物品），所以 bingo 的目标就是「玩家挖一挖真的能碰到的东西」。
	 * 池子小于请求数时有多少给多少。
	 */
	public static List<String> samplePoolForBingo(ServerLevel level, int count, RandomSource random) {
		List<Item> pool = new ArrayList<>(itemPool());

		List<String> picked = new ArrayList<>();
		int attempts = Math.min(count * 30, pool.size() * 3);
		while (picked.size() < count && attempts-- > 0 && !pool.isEmpty()) {
			Item item = pool.remove(random.nextInt(pool.size()));
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (id != null) {
				picked.add(id.toString());
			}
		}
		return picked;
	}

	/**
	 * 贪婪附魔用：从随机掉落池抽<b>一件</b>（含维度 / 群系专属池、刷怪蛋与分层过滤），
	 * 数量固定 1 —— 额外奖励不放大数量，保持「多一次机会」而不是「一次多一把」。
	 *
	 * <p>池子为空或全是被排除物品时返回 {@link ItemStack#EMPTY}（这次贪婪空手）。
	 */
	public static ItemStack randomLootOne(ServerLevel level, BlockPos pos, RandomSource random) {
		DropsConfig config = DropsConfig.get();

		List<String> exclusiveIds = Progression.rollExclusivePool(level, pos, random, config);
		List<Item> pool = exclusiveIds == null ? itemPool() : resolvePool(exclusiveIds);

		if (pool.isEmpty()) {
			pool = itemPool();
		}

		if (pool.isEmpty()) {
			return ItemStack.EMPTY;
		}

		Item item = pickFinalItem(pool, random, level, pos, config);
		return item == null ? ItemStack.EMPTY : makeStack(item, 1, level, random);
	}

	/**
	 * 通关宝藏雨用：从暴击宝藏池里随机取一件，数量在 1 ~ min(8, 堆叠上限) 之间。
	 * <p>宝藏池为空（配置里的 id 全都不存在）时返回 {@link ItemStack#EMPTY}，
	 * 调用方据此直接收尾，不会撒出一地空气。
	 *
	 * <p>{@code level} 用来给附魔书 / 药水这类「必须带数据才有真实效果」的特殊物品写数据。
	 */
	public static ItemStack randomTreasure(ServerLevel level, RandomSource random) {
		List<Item> pool = jackpotPool();

		if (pool.isEmpty()) {
			return ItemStack.EMPTY;
		}

		Item item = pool.get(random.nextInt(pool.size()));
		int max = Math.max(1, new ItemStack(item).getMaxStackSize());
		return makeStack(item, 1 + random.nextInt(Math.min(8, max)), level, random);
	}

	/** 在 [min, max] 内取一个随机整数（含两端）。 */
	private static int randomBetween(RandomSource random, int min, int max) {
		return min >= max ? min : min + random.nextInt(max - min + 1);
	}

	// ------------------------------------------------------------ 特殊物品（必须带数据才有真实效果）

	/** 裸 Item 就是水瓶的那几类，必须写入 {@code PotionContents} 才有效。 */
	private static final Set<Item> POTION_ITEMS = Set.of(
			Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW);

	/** 附魔书 / 药水真实数据的缓存（注册表全局不变，懒加载一次）。 */
	private static volatile List<Holder<Enchantment>> ENCHANTMENT_HOLDERS;

	/**
	 * 造掉落物堆。
	 *
	 * <p>绝大多数物品直接 {@code new ItemStack(item, count)} 就够了，但有两类例外 ——
	 * 它们本身只是个「容器」，不写数据组件就是个空壳、没有任何真实效果：
	 * <ul>
	 *   <li><b>附魔书</b>：裸书没有任何附魔数据，必须写入随机附魔；</li>
	 *   <li><b>药水类</b>（普通 / 喷溅 / 滞留 / 染色箭）：裸 Item 是水瓶，必须写入 {@code PotionContents}。</li>
	 * </ul>
	 * 这俩就是之前「爆出来的附魔书是错的、没效果」那个 bug 的根因：掉出来的书根本没存任何附魔。
	 */
	private static ItemStack makeStack(Item item, int count, ServerLevel level, RandomSource random) {
		ItemStack stack;
		if (item == Items.ENCHANTED_BOOK) {
			stack = enchantedBook(count, level, random);
		} else if (POTION_ITEMS.contains(item)) {
			stack = randomPotion(item, random);
			stack.setCount(Math.max(1, count));
		} else {
			stack = new ItemStack(item, count);
		}

		// 武器 / 工具按概率附带「碎裂」附魔（软引用：没装 yg-enchants 时该附魔不存在，自然跳过）
		tryAttachShatter(stack, item, level, random);
		return stack;
	}

	/**
	 * 给随机掉出的武器 / 工具按概率附着「碎裂」附魔。
	 *
	 * <p>碎裂附魔由「更多附魔」包注册（{@code yg:shatter}）。本包不依赖它：直接查附魔注册表，
	 * 查不到就什么都不做 —— 只装随机掉落时这条路径永远静默跳过，装了两包才有联动。
	 */
	private static void tryAttachShatter(ItemStack stack, Item item, ServerLevel level, RandomSource random) {
		DropsConfig config = DropsConfig.get();
		if (!config.enableShatterAttach || random.nextDouble() >= config.shatterApplyChance) {
			return;
		}

		// 26.2 用组件判定武器 / 工具（原版剑镐斧锹锄都有 TOOL，武器另有 WEAPON）；
		// 走组件而不是 instanceof 具体类，模组添加的工具同样能被识别。
		if (!stack.has(DataComponents.TOOL) && !stack.has(DataComponents.WEAPON)) {
			return;
		}

		Holder<Enchantment> shatter = level.registryAccess()
				.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.get(net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.ENCHANTMENT,
						Identifier.parse("yg:shatter")))
				.orElse(null);
		if (shatter == null) {
			return;
		}

		ItemEnchantments.Mutable ench = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		ench.set(shatter, 1);
		net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(stack, ench.toImmutable());
	}

	/** 自检用：直接造一个带真实数据的特殊物品，验证不会掉出空壳。 */
	static ItemStack makeRealSpecial(Item item, int count, ServerLevel level, RandomSource random) {
		return makeStack(item, count, level, random);
	}

	/** 造一本带 1~3 条随机真实附魔的书。 */
	private static ItemStack enchantedBook(int count, ServerLevel level, RandomSource random) {
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		book.setCount(Math.max(1, count));

		List<Holder<Enchantment>> all = enchantmentHolders(level);

		if (all.isEmpty()) {
			return book; // 极端情况下注册表拿不到：留空书也比崩好
		}

		ItemEnchantments.Mutable ench = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		int want = 1 + random.nextInt(3); // 1~3 条

		for (int i = 0; i < want; i++) {
			Holder<Enchantment> pick = all.get(random.nextInt(all.size()));
			Enchantment def = pick.value();
			int max = Math.max(def.getMinLevel(), def.getMaxLevel());
			int lvl = def.getMinLevel() + random.nextInt(Math.max(1, max - def.getMinLevel() + 1));

			// 与已写入的做兼容性检查，不兼容就跳过 —— 书不会因此变成无效物品
			boolean compatible = true;
			for (Holder<Enchantment> existing : ench.keySet()) {
				if (!Enchantment.areCompatible(pick, existing)) {
					compatible = false;
					break;
				}
			}

			if (compatible) {
				ench.set(pick, lvl);
			}
		}

		EnchantmentHelper.setEnchantments(book, ench.toImmutable());
		return book;
	}

	/** 全部附魔的 {@code Holder} 列表（缓存，注册表全局不变）。 */
	private static List<Holder<Enchantment>> enchantmentHolders(ServerLevel level) {
		List<Holder<Enchantment>> cached = ENCHANTMENT_HOLDERS;

		if (cached == null) {
			synchronized (DropRandomizer.class) {
				cached = ENCHANTMENT_HOLDERS;
				if (cached == null) {
					List<Holder<Enchantment>> list = new ArrayList<>();
					level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
							.listElements().forEach(list::add);
					cached = ENCHANTMENT_HOLDERS = List.copyOf(list);
				}
			}
		}

		return cached;
	}

	/** 造一个带真实药水效果的物品；退化时返回裸 Item（水瓶）而非崩溃。 */
	private static ItemStack randomPotion(Item item, RandomSource random) {
		List<Potion> potions = BuiltInRegistries.POTION.stream()
				.filter(p -> !p.getEffects().isEmpty())
				.toList();

		if (potions.isEmpty()) {
			return new ItemStack(item);
		}

		Potion pick = potions.get(random.nextInt(potions.size()));
		return BuiltInRegistries.POTION.getResourceKey(pick)
				.flatMap(BuiltInRegistries.POTION::get)
				.map(holder -> PotionContents.createItemStack(item, holder))
				.orElseGet(() -> new ItemStack(item));
	}

	// ------------------------------------------------------------ 候选池

	private static List<Item> itemPool() {
		List<Item> pool = itemPool;
		if (pool != null) {
			return pool;
		}

		synchronized (DropRandomizer.class) {
			if (itemPool != null) {
				return itemPool;
			}

			DropsConfig config = DropsConfig.get();
			List<Item> built = new ArrayList<>();

			for (Item item : BuiltInRegistries.ITEM.stream().toList()) {
				if (item == null || item == Items.AIR) {
					continue;
				}

			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (id == null || config.isItemBlacklisted(id)) {
				continue;
			}

			// v1.11 ①：刷怪蛋整个剔出随机池 —— 掉出一颗就等于无限刷物资
			if (SpawnerEggGuard.isBanned(item, config)) {
				continue;
			}

				// 兜底：模组里可能出现「拿不到默认堆叠」的占位物品，跳过更安全
				if (new ItemStack(item).isEmpty()) {
					continue;
				}

				built.add(item);
			}

			itemPool = List.copyOf(built);
			Yg.LOGGER.info("[yg] 随机物品池已构建：{} 项，{}",
					itemPool.size(), itemNamespaceSummary(itemPool));

			if (config.debugLog) {
				Yg.LOGGER.info("[yg] 物品池自检：dirt={} stone={} diamond={} air={}",
						itemPool.contains(Items.DIRT), itemPool.contains(Items.STONE),
						itemPool.contains(Items.DIAMOND), itemPool.contains(Items.AIR));
			}

			return itemPool;
		}
	}

	private static MobPools mobPools() {
		MobPools pools = mobPools;
		if (pools != null) {
			return pools;
		}

		synchronized (DropRandomizer.class) {
			if (mobPools != null) {
				return mobPools;
			}

			DropsConfig config = DropsConfig.get();
			MobPools built = new MobPools();

			for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE.stream().toList()) {
				if (!isSpawnableMob(type, config)) {
					continue;
				}

				switch (groupOf(type, config)) {
					case HOSTILE -> built.hostile.add(type);
					case NEUTRAL -> built.neutral.add(type);
					case PASSIVE -> built.passive.add(type);
				}
			}

			mobPools = built;
			Yg.LOGGER.info("[yg] 随机生物池已构建：敌对 {} / 中立 {} / 友好 {}，{}",
					built.hostile.size(), built.neutral.size(), built.passive.size(), mobNamespaceSummary(built));

			if (config.debugLog) {
				Yg.LOGGER.info("[yg] 生物池样例：敌对={} 中立={} 友好={}",
						sampleIds(built.hostile), sampleIds(built.neutral), sampleIds(built.passive));
			}

			return mobPools;
		}
	}

	/** 只保留「能被生成出来的普通生物」，排除掉落物、画、船、玩家之类。 */
	private static boolean isSpawnableMob(EntityType<?> type, DropsConfig config) {
		if (type == null) {
			return false;
		}

		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		if (id == null || config.isEntityBlacklisted(id)) {
			return false;
		}

		// 注意：26.2 的 EntityType#getBaseClass() 是个残留方法，永远返回 Entity.class，
		// 不能用来判断「是不是生物」，这里用 MobCategory 分类来判断。
		// 被用户在配置里明确点名的生物（村民、铁傀儡这种原版归为 MISC 的）直接放行。
		boolean named = config.isNeutral(id) || config.isExtraPassive(id);
		if (!named && type.getCategory() == MobCategory.MISC) {
			return false;
		}

		return type.canSummon();
	}

	private static EntityType<?> pickMob(RandomSource random, DropsConfig config, boolean early) {
		MobPools pools = mobPools();

		int hostile = pools.hostile.isEmpty() ? 0 : config.effectiveHostileWeight(early);
		int neutral = pools.neutral.isEmpty() ? 0 : config.neutralWeight;
		int passive = pools.passive.isEmpty() ? 0 : config.passiveWeight;
		int total = hostile + neutral + passive;

		if (total <= 0) {
			return null;
		}

		int roll = random.nextInt(total);
		if (roll < hostile) {
			return pick(random, pools.hostile);
		}

		roll -= hostile;
		if (roll < neutral) {
			return pick(random, pools.neutral);
		}

		return pick(random, pools.passive);
	}

	private static <T> T pick(RandomSource random, List<T> candidates) {
		return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
	}

	/**
	 * 这只生物算不算「敌对」。
	 *
	 * <p>直接复用本模组的既有分组规则（{@code neutralMobs} / {@code extraPassiveMobs} 优先，
	 * 否则看原版 {@code MobCategory}），所以击杀奖励的「只打敌对」与爆怪权重是同一套口径。
	 */
	static boolean isHostile(EntityType<?> type, DropsConfig config) {
		return type != null && groupOf(type, config) == Group.HOSTILE;
	}

	private static Group groupOf(EntityType<?> type, DropsConfig config) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

		if (id != null) {
			if (config.isNeutral(id)) {
				return Group.NEUTRAL;
			}
			if (config.isExtraPassive(id)) {
				return Group.PASSIVE;
			}
		}

		return type.getCategory() == MobCategory.MONSTER ? Group.HOSTILE : Group.PASSIVE;
	}

	// ------------------------------------------------------------ 工具

	/**
	 * 以时间戳为主熵源生成种子：毫秒时间戳 + 方块坐标 + 进程内自增序号，
	 * 最后过一遍 splitmix64 做位混合，避免相邻时间戳产生高度相关的随机序列。
	 */
	private static long timestampSeed(BlockPos pos) {
		long mixed = System.currentTimeMillis() * 0x9E3779B97F4A7C15L;
		mixed ^= pos.asLong() * 0xC2B2AE3D27D4EB4FL;
		mixed ^= SEQUENCE.incrementAndGet() * 0x165667B19E3779F9L;
		return mix64(mixed);
	}

	private static long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	private static String idOf(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id == null ? String.valueOf(item) : id.toString();
	}

	private static String idOf(EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return id == null ? String.valueOf(type) : id.toString();
	}

	private static String formatPos(BlockPos pos) {
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private static String sampleIds(List<EntityType<?>> types) {		List<String> ids = new ArrayList<>();

		for (EntityType<?> type : types) {
			if (ids.size() >= 12) {
				break;
			}

			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			if (id != null) {
				ids.add(id.getPath());
			}
		}

		return ids.toString();
	}

	/** 统计物品池来自哪些命名空间 —— 一眼就能看出模组的物品有没有进池子。 */
	private static String itemNamespaceSummary(List<Item> items) {
		Map<String, Integer> counts = new TreeMap<>();

		for (Item item : items) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (id != null) {
				counts.merge(id.getNamespace(), 1, Integer::sum);
			}
		}

		return "按命名空间：" + counts;
	}

	/** 同上，生物池。 */
	private static String mobNamespaceSummary(MobPools pools) {
		Map<String, Integer> counts = new TreeMap<>();

		for (List<EntityType<?>> list : List.of(pools.hostile, pools.neutral, pools.passive)) {
			for (EntityType<?> type : list) {
				Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
				if (id != null) {
					counts.merge(id.getNamespace(), 1, Integer::sum);
				}
			}
		}

		return "按命名空间：" + counts;
	}

	private enum Group {
		HOSTILE("敌对"),
		NEUTRAL("中立"),
		PASSIVE("友好");

		private final String label;

		Group(String label) {
			this.label = label;
		}
	}

	private static final class MobPools {
		private final List<EntityType<?>> hostile = new ArrayList<>();
		private final List<EntityType<?>> neutral = new ArrayList<>();
		private final List<EntityType<?>> passive = new ArrayList<>();
	}
}
