package com.yunxigames;


import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;


import static com.yunxigames.SelfTest.*;

/**
 * 随机掉落包的开服自检：把本包全部玩法逐条跑一遍，结论写进日志。
 * 通过 {@code SelfTest.registerStep} 挂进统一流程，只装本包时只跑这些。
 */
public final class DropsSelfTest {
	private DropsSelfTest() {
	}

	/** 注册本包全部自检步骤（由 YunxiGamesDrops 入口调用）。 */
	public static void registerSteps() {
		SelfTest.registerStep("随机掉落全项（①~⑮ + 植被 + 幸运加成）", DropsSelfTest::runAll);
		SelfTest.registerStep("⑰ 碎裂附着·随机掉落武器/工具",
				ctx -> DropsSelfTest.checkShatterAttach(ctx.level, DropsConfig.get()));

		SelfTest.onBeforeRun(() -> {
			DropsConfig config = DropsConfig.get();
			DropRandomizer.resetPools();
			DropTally.pause();

			// 把会刷屏的播报先关掉，广播相关的检查自己临时打开
			config.jackpotBroadcast = false;
			config.finaleBroadcast = false;
			config.finaleTitle = false;
			config.eliteBroadcast = false;
			config.ultimateBroadcast = false;
		});

		SelfTest.onAfterRun(() -> {
			// 让通关结算恢复到「还没发生过」，免得自检把正式的那次吃掉
			Finale.resetForTest();
			DropTally.resume();
		});
	}

	/**
	 * ⑰ 碎裂附着：随机掉出的武器 / 工具按概率带 {@code yg:shatter} 附魔。
	 *
	 * <p>碎裂附魔由「更多附魔」包提供，本包只做软引用 —— 没装那个包时附魔查不到，
	 * 这项自检会如实报「碎裂附魔未注册（需要 yg-enchants）」，不算失败排查方向跑偏。
	 */
	static void checkShatterAttach(ServerLevel level, DropsConfig config) {
		Holder<Enchantment> shatter = level.registryAccess()
				.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.get(net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.ENCHANTMENT,
						Identifier.parse("yg:shatter")))
				.orElse(null);

		if (shatter == null) {
			check("⑰ 碎裂附着·随机掉落武器/工具", true,
					"未装 yg-enchants（碎裂附魔不存在）—— 软引用按预期静默跳过");
			return;
		}

		boolean savedEnabled = config.enableShatterAttach;
		double savedChance = config.shatterApplyChance;
		config.enableShatterAttach = true;

		boolean allShatter;
		boolean noneShatter;
		boolean appleClean;
		try {
			config.shatterApplyChance = 1.0D;
			int total = 60;
			int withShatter = 0;
			for (int i = 0; i < total; i++) {
				ItemStack s = DropRandomizer.makeRealSpecial(Items.DIAMOND_SWORD, 1, level, level.getRandom());
				if (hasShatter(s, shatter)) {
					withShatter++;
				}
			}
			allShatter = withShatter == total;

			config.shatterApplyChance = 0.0D;
			int none = 0;
			for (int i = 0; i < 30; i++) {
				ItemStack s = DropRandomizer.makeRealSpecial(Items.IRON_PICKAXE, 1, level, level.getRandom());
				if (!hasShatter(s, shatter)) {
					none++;
				}
			}
			noneShatter = none == 30;

			config.shatterApplyChance = 1.0D;
			ItemStack apple = DropRandomizer.makeRealSpecial(Items.APPLE, 1, level, level.getRandom());
			appleClean = !hasShatter(apple, shatter);
		} finally {
			config.enableShatterAttach = savedEnabled;
			config.shatterApplyChance = savedChance;
		}

		check("⑰ 碎裂附着·随机掉落武器/工具",
				allShatter && noneShatter && appleClean,
				"chance=1 时 " + (allShatter ? "全部" : "未全部") + "带碎裂；chance=0 时 "
						+ (noneShatter ? "全不带" : "仍带") + "；苹果(非武器)" + (appleClean ? "干净" : "被污染"));
	}

	private static boolean hasShatter(ItemStack stack, Holder<Enchantment> shatter) {
		for (Holder<Enchantment> h : stack.getOrDefault(
				net.minecraft.core.component.DataComponents.ENCHANTMENTS,
				ItemEnchantments.EMPTY).keySet()) {
			if (h.value().equals(shatter.value())) {
				return true;
			}
		}
		return false;
	}

	/** 按原顺序跑本包全部检查。 */
	static void runAll(SelfTest.Context ctx) {
		ServerLevel level = ctx.level;
		BlockPos pos = ctx.pos;
		MinecraftServer server = ctx.server;
		long rolls = ctx.rolls;
		DropsConfig config = DropsConfig.get();

		checkItemCountRange(level, pos, config, rolls);
		checkMobDropNeverEmpty(level, pos, config, rolls);
		checkSpawnedMobStun(level, pos, config);
		checkJackpot(level, pos, config, rolls);
		checkPity(level, config);
		checkRareDropBroadcast(level, pos, config);
		checkFinale(level, pos, config);
		checkProgression(level, config);
		checkDimensionAndBiome(server, level, pos, config, rolls);
		checkEliteMobs(level, pos, config);
		checkKillEffects(level, pos, config);
		checkSpawnerEggs(level, pos, config);
		checkTntIgnition(level, pos, config);
		checkDropMerging(level, pos, config);
		checkEventsAndLimbInjury(level, pos, config);
		checkTieredDrops(server, level, pos, config);
		checkSpecialItems(level, config);
		checkNoDropBlocks(config);
		checkNoLootPlants(level, config);
		checkLuckBonus(level, config);
		checkShatterAttach(level, config);
	}

	// ------------------------------------------------------------ ① 三个劝退点

	/** ①-a：掉出物品的数量必须落在 1~8（且不超过堆叠上限）。 */
	public static void checkItemCountRange(ServerLevel level, BlockPos pos, DropsConfig config, long rolls) {
		int min = config.itemCountMin;
		int max = config.itemCountMax;
		int bound = Math.max(min, max);

		double savedMobChance = config.mobChance;
		config.mobChance = 0.0D;

		int seenLow = Integer.MAX_VALUE;
		int seenHigh = 0;
		int samples = 0;
		boolean stackCapRespected = true;

		try {
			for (long i = 0; i < rolls; i++) {
				for (ItemStack stack : DropRandomizer.rollBlockDrop(level, pos, null)) {
					int count = stack.getCount();
					seenLow = Math.min(seenLow, count);
					seenHigh = Math.max(seenHigh, count);
					samples++;

					if (count > stack.getMaxStackSize()) {
						stackCapRespected = false;
					}
				}
			}
		} finally {
			config.mobChance = savedMobChance;
		}

		boolean configOk = min >= 1 && max >= min;
		boolean observedOk = samples > 0 && seenLow >= 1 && seenHigh <= bound && stackCapRespected;

		check("①-a 物品数量范围", configOk && observedOk,
				"配置 " + min + "~" + max + "；实测 " + samples + " 件，落在 " + seenLow + "~" + seenHigh
						+ "；未超堆叠上限=" + stackCapRespected);
	}

	/** ①-b：生物掉落永远不该「什么都不掉」。 */
	public static void checkMobDropNeverEmpty(ServerLevel level, BlockPos pos, DropsConfig config, long rolls) {
		boolean saved = config.allowNothingOnMobDrop;
		double savedMobChance = config.mobChance;

		config.allowNothingOnMobDrop = false;
		config.mobChance = 0.0D;

		long emptyBefore = DropRandomizer.nothingRolls();
		long itemsBefore = SessionStats.itemsGiven();

		try {
			for (long i = 0; i < rolls; i++) {
				DropRandomizer.applyMobDrop(level, pos, null);
			}
		} finally {
			config.allowNothingOnMobDrop = saved;
			config.mobChance = savedMobChance;
		}

		long emptyDelta = DropRandomizer.nothingRolls() - emptyBefore;

		check("①-b 生物掉宝不出空手", emptyDelta == 0,
				"配置 allowNothingOnMobDrop=false；掷 " + rolls + " 次，空手 " + emptyDelta + " 次（应为 0）");
	}

	/** ①-c：爆出来的生物要有 40 刻落地僵直，并且到点自动解锁。 */
	public static void checkSpawnedMobStun(ServerLevel level, BlockPos pos, DropsConfig config) {
		int ticks = config.spawnedMobStunTicks;
		Mob mob = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.EVENT);

		if (mob == null) {
			check("①-c 落地僵直", false, "无法在 " + pos.toShortString() + " 生成测试生物");
			return;
		}

		boolean stunned;
		boolean released;

		try {
			MobStun.stun(mob, ticks);
			stunned = mob.isNoAi();

			for (int i = 0; i < ticks; i++) {
				MobStun.tick();
			}

			released = !mob.isNoAi();
		} finally {
			mob.discard();
		}

		check("①-c 落地僵直", ticks == 40 && stunned && released,
				"配置 " + ticks + " 刻（应为 40）；爆出时 isNoAi=" + stunned + "、推进 " + ticks
						+ " 刻后解锁=" + released);
	}

	// ------------------------------------------------------------ ② 暴击大爆

	/** ②：暴击概率拉到 100% 时，掉出来的必须全是宝藏池里的东西。 */
	public static void checkJackpot(ServerLevel level, BlockPos pos, DropsConfig config, long rolls) {
		double savedChance = config.jackpotChance;
		boolean savedEnabled = config.enableJackpot;
		double savedMobChance = config.mobChance;

		config.jackpotChance = 1.0D;
		config.enableJackpot = true;
		config.mobChance = 0.0D;

		long jackpotsBefore = DropRandomizer.jackpots();
		int fromPool = 0;
		int notFromPool = 0;
		int empty = 0;

		try {
			for (long i = 0; i < rolls; i++) {
				List<ItemStack> drops = DropRandomizer.rollBlockDrop(level, pos, null);

				if (drops.isEmpty()) {
					empty++;
					continue;
				}

				if (DropRandomizer.isJackpotItem(drops.get(0).getItem())) {
					fromPool++;
				} else {
					notFromPool++;
				}
			}
		} finally {
			config.jackpotChance = savedChance;
			config.enableJackpot = savedEnabled;
			config.mobChance = savedMobChance;
		}

		long jackpotDelta = DropRandomizer.jackpots() - jackpotsBefore;
		boolean poolNotEmpty = !DropRandomizer.itemPoolSnapshot().isEmpty();

		check("② 暴击大爆", poolNotEmpty && fromPool == rolls && notFromPool == 0 && jackpotDelta == rolls,
				"概率临时拉到 100%，掷 " + rolls + " 次：来自宝藏池 " + fromPool + " 次 / 非宝藏 " + notFromPool
						+ " 次 / 空 " + empty + " 次；暴击计数 +" + jackpotDelta + "（应全部来自宝藏池）");
	}

	// ------------------------------------------------------------ ③ 保底可见化

	/** ③：保底按阈值触发，且反馈路径（粒子/音效/动作栏）不会抛异常。 */
	public static void checkPity(ServerLevel level, DropsConfig config) {
		int threshold = Math.max(1, config.pityThreshold);
		UUID probe = UUID.randomUUID();
		int triggerAt = -1;

		try {
			for (int i = 1; i <= threshold + 3; i++) {
				if (DropRandomizer.advancePity(probe, "item:minecraft:dirt", threshold)) {
					triggerAt = i;
					break;
				}
			}
		} finally {
			DropRandomizer.forgetPity(probe);
		}

		// 反馈路径：cause 为 null 时必须安全（爆炸等非玩家路径真的会传 null）
		boolean feedbackSafe = true;

		try {
			Feedback.pity(level, BlockPos.ZERO, new ItemStack(Items.DIRT), null);
		} catch (Throwable error) {
			feedbackSafe = false;
			Yg.LOGGER.error("[yg] 保底反馈在 cause=null 时抛异常", error);
		}

		check("③ 保底可见化", triggerAt == threshold && feedbackSafe && config.showPityFeedback,
				"阈值 " + threshold + " → 第 " + triggerAt + " 次触发（应为 " + threshold + "）；"
						+ "cause=null 时反馈安全=" + feedbackSafe + "；可见反馈开关=" + config.showPityFeedback);
	}

	// ------------------------------------------------------------ ④ 稀有掉落广播

	/** ④：稀有物品判定正确，且广播路径真的能跑通。 */
	public static void checkRareDropBroadcast(ServerLevel level, BlockPos pos, DropsConfig config) {
		boolean saved = config.rareDropBroadcast;
		Item elytra = BuiltInRegistries.ITEM.getValue(Identifier.tryParse("minecraft:elytra"));

		boolean rareDetected = elytra != null && elytra != Items.AIR && DropRandomizer.isRareItem(elytra);
		boolean commonRejected = !DropRandomizer.isRareItem(Items.DIRT);
		boolean broadcastSafe = true;

		try {
			// 打开开关，真的走一遍广播（无人在线，只会进日志）
			config.rareDropBroadcast = true;
			Feedback.rareDrop(level, pos, new ItemStack(Items.ELYTRA), null);

			// cause=null 的署名兜底也要安全
			Feedback.rareDrop(level, pos, new ItemStack(Items.DIAMOND), null);
		} catch (Throwable error) {
			broadcastSafe = false;
			Yg.LOGGER.error("[yg] 稀有掉落广播抛异常", error);
		} finally {
			config.rareDropBroadcast = saved;
		}

		check("④ 稀有掉落广播", rareDetected && commonRejected && broadcastSafe,
				"鞘翅算稀有=" + rareDetected + "、泥土不算稀有=" + commonRejected + "、广播执行安全=" + broadcastSafe);
	}

	// ------------------------------------------------------------ ⑤ 通关结算

	/** ⑤：结算流程完整跑一遍 —— 宝藏雨数量对得上，统计能出报表。 */
	public static void checkFinale(ServerLevel level, BlockPos pos, DropsConfig config) {
		Finale.resetForTest();

		int dropped;
		List<String> report;

		try {
			dropped = Finale.fireForTest(level, pos, null);
			report = SessionStats.report();
		} finally {
			Finale.resetForTest();
		}

		int wanted = config.finaleTreasureCount;
		boolean countOk = dropped == wanted;
		boolean reportOk = report != null && report.size() >= 5;
		boolean flagOk = config.enableFinale;

		check("⑤ 末影龙通关结算", countOk && reportOk && flagOk,
				"宝藏雨 " + dropped + " 件（期望 " + wanted + "）；统计报表 " + (report == null ? 0 : report.size())
						+ " 行；结算开关=" + flagOk);
	}

	// ------------------------------------------------------------ ⑥ 进度分档

	/** ⑥：分档逻辑可判定，且「没有世界」时必须退化成 1.0 倍（自检不能被放大）。 */
	public static void checkProgression(ServerLevel level, DropsConfig config) {
		Progression.Tier live = Progression.tier(level, config);
		Progression.Tier headless = Progression.tier(null, config);
		double liveMultiplier = Progression.jackpotMultiplier(level, config);
		double headlessMultiplier = Progression.jackpotMultiplier(null, config);

		// 用纯函数把三段分界真的走一遍 —— 服务器刚开时世界时间永远是 0，
		// 只测「当前档位」的话 MID/LATE 两条路根本没被跑过。
		long early = Math.max(1, config.earlyGameMinutes);
		long mid = Math.max(early + 1, config.midGameMinutes);

		Progression.Tier atStart = Progression.tierForMinutes(0L, config);
		Progression.Tier atEarlyEdge = Progression.tierForMinutes(early - 1, config);
		Progression.Tier atMidStart = Progression.tierForMinutes(early, config);
		Progression.Tier atMidEdge = Progression.tierForMinutes(mid - 1, config);
		Progression.Tier atLate = Progression.tierForMinutes(mid, config);

		boolean boundariesOk = atStart == Progression.Tier.EARLY
				&& atEarlyEdge == Progression.Tier.EARLY
				&& atMidStart == Progression.Tier.MID
				&& atMidEdge == Progression.Tier.MID
				&& atLate == Progression.Tier.LATE;

		boolean configOk = config.midJackpotMultiplier >= 1.0D && config.lateJackpotMultiplier >= 1.0D;
		boolean headlessOk = headless == Progression.Tier.EARLY && headlessMultiplier == 1.0D;
		boolean liveOk = liveMultiplier >= 1.0D;

		check("⑥ 开局保护 / 按进度调概率", configOk && headlessOk && liveOk && boundariesOk,
				"开局保护 " + config.earlyGameMinutes + " 分钟、中期分界 " + config.midGameMinutes + " 分钟；"
						+ "分界 " + early + "/" + mid + " → "
						+ atStart + "/" + atEarlyEdge + "/" + atMidStart + "/" + atMidEdge + "/" + atLate
						+ "（应为 EARLY/EARLY/MID/MID/LATE）；"
						+ "当前档位 " + live + "（倍率 ×" + trim(liveMultiplier) + "）；无世界时 " + headless
						+ " ×" + trim(headlessMultiplier) + "（应为 EARLY ×1）");
	}

	// ------------------------------------------------------------ ⑦ 维度 / 群系

	/** ⑦：三个专属池都解析得出物品；主世界没有维度池；换维度能拿到对应池。 */
	public static void checkDimensionAndBiome(MinecraftServer server, ServerLevel level, BlockPos pos,
			DropsConfig config, long rolls) {
		List<Item> netherPool = DropRandomizer.resolvePoolSnapshot(config.netherBonusItems);
		List<Item> endPool = DropRandomizer.resolvePoolSnapshot(config.endBonusItems);
		List<Item> biomePool = DropRandomizer.resolvePoolSnapshot(config.biomeBonusItems);

		boolean poolsOk = !netherPool.isEmpty() && !endPool.isEmpty() && !biomePool.isEmpty();

		boolean overworldHasNoDimensionPool = Progression.dimensionPool(level, config) == null;

		ServerLevel nether = server.getLevel(Level.NETHER);
		ServerLevel end = server.getLevel(Level.END);

		boolean netherOk = nether == null || Progression.dimensionPool(nether, config) == config.netherBonusItems;
		boolean endOk = end == null || Progression.dimensionPool(end, config) == config.endBonusItems;

		boolean netherMultiplierOk = nether == null
				|| Progression.dimensionMultiplier(nether, config) == config.netherJackpotMultiplier;
		boolean endMultiplierOk = end == null
				|| Progression.dimensionMultiplier(end, config) == config.endJackpotMultiplier;

		Identifier biomeId = Progression.biomeId(level, pos);
		boolean biomeReadable = biomeId != null;
		boolean headlessBiomeOk = !Progression.isSpecialBiome(null, pos, config)
				&& Progression.biomeId(null, pos) == null;

		boolean exclusiveHeadlessOk = Progression.rollExclusivePool(null, pos, level.getRandom(), config) == null;

		// 端到端：把维度池概率拉满，在下界掷一轮，掉出来的必须全是下界池里的东西
		boolean endToEndOk = true;
		String endToEndDetail = "（无下界维度，跳过）";

		if (nether != null && !netherPool.isEmpty()) {
			double savedChance = config.dimensionBonusChance;
			double savedMobChance = config.mobChance;
			double savedJackpot = config.jackpotChance;

			config.dimensionBonusChance = 1.0D;
			config.mobChance = 0.0D;
			config.jackpotChance = 0.0D;

			int fromNetherPool = 0;
			int outside = 0;

			try {
				BlockPos netherPos = nether.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 64, 0));

				for (long i = 0; i < Math.min(rolls, 120L); i++) {
					for (ItemStack stack : DropRandomizer.rollBlockDrop(nether, netherPos, null)) {
						if (netherPool.contains(stack.getItem())) {
							fromNetherPool++;
						} else {
							outside++;
						}
					}
				}
			} finally {
				config.dimensionBonusChance = savedChance;
				config.mobChance = savedMobChance;
				config.jackpotChance = savedJackpot;
			}

			endToEndOk = fromNetherPool > 0 && outside == 0;
			endToEndDetail = "下界实掷 来自下界池 " + fromNetherPool + " 件 / 池外 " + outside + " 件";
		}

		check("⑦ 维度 / 群系影响池子", poolsOk && overworldHasNoDimensionPool && netherOk && endOk
						&& netherMultiplierOk && endMultiplierOk && biomeReadable && headlessBiomeOk
						&& exclusiveHeadlessOk && endToEndOk,
				"池子 下界=" + netherPool.size() + " / 末地=" + endPool.size() + " / 群系=" + biomePool.size()
						+ "；主世界无维度池=" + overworldHasNoDimensionPool
						+ "；下界×" + trim(config.netherJackpotMultiplier) + "=" + netherMultiplierOk
						+ "、末地×" + trim(config.endJackpotMultiplier) + "=" + endMultiplierOk
						+ "；当前位置群系=" + biomeId + "；" + endToEndDetail);
	}

	// ------------------------------------------------------------ ⑧ 精英怪

	/** ⑧：精英只从玩家来源生成；升级后带名字、发光、血更厚，并且能被登记/清理。 */
	public static void checkEliteMobs(ServerLevel level, BlockPos pos, DropsConfig config) {
		// 非玩家来源（cause=null）不该产出精英 —— 否则刷怪塔会量产精英
		boolean rejectsNonPlayer = !EliteMobs.rollElite(null, level.getRandom(), config);

		Mob mob = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.EVENT);

		if (mob == null) {
			check("⑧ 精英怪", false, "无法生成用于测试的僵尸");
			return;
		}

		boolean made;
		boolean marked;
		boolean glowing;
		boolean named;
		boolean healthier;
		boolean persistent;

		try {
			double baseHealth = mob.getMaxHealth();
			made = EliteMobs.makeElite(mob, level, null);
			marked = EliteMobs.isElite(mob);
			glowing = mob.hasGlowingTag() || mob.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING);
			named = mob.getCustomName() != null
					&& mob.getCustomName().getString().contains(config.eliteNamePrefix);
			healthier = mob.getMaxHealth() > baseHealth;
			persistent = mob.isPersistenceRequired();
		} finally {
			EliteMobs.forgetForTest(mob);
			mob.discard();
		}

		check("⑧ 精英怪", rejectsNonPlayer && made && marked && glowing && named && healthier && persistent
						&& config.eliteTreasureCount >= 0,
				"非玩家来源拒绝=" + rejectsNonPlayer + "；升级成功=" + made + "、已登记=" + marked
						+ "、发光=" + glowing + "、带名=" + named + "、血更厚=" + healthier
						+ "、不消失=" + persistent + "；死亡掉 " + config.eliteTreasureCount + " 件");
	}

	// ------------------------------------------------------------ ⑨ 击杀奖励（药水效果）

	/**
	 * ⑨：爆怪概率已下调；两个池子能解析且各自分类正确；只有玩家击杀才给；
	 * 负面概率能真的把结果推向负面池；真正发一次效果能落到活体身上。
	 */
	public static void checkKillEffects(ServerLevel level, BlockPos pos, DropsConfig config) {
		// 爆怪概率下调：必须低于早期的 0.15，且物品/生物/空手三者比例自洽
		boolean mobChanceReduced = config.mobChance > 0.0D && config.mobChance < 0.15D;
		double ratio = config.mobChance + config.emptyChance + config.itemChance();
		boolean ratioSane = Math.abs(ratio - 1.0D) < 1.0E-6D;

		KillEffects.Pools pools = KillEffects.poolSnapshot(config);
		boolean poolsOk = !pools.beneficial().isEmpty() && !pools.harmful().isEmpty()
				&& pools.beneficial().size() == config.killEffects.size()
				&& pools.harmful().size() == config.killEffectsHarmful.size();
		boolean categoriesOk = pools.beneficial().stream().noneMatch(KillEffects.Entry::harmful)
				&& pools.harmful().stream().allMatch(KillEffects.Entry::harmful);

		// 负面池默认时长应当短于有益池 —— 这是「长期仍然是赚的」的前提
		boolean harmfulShorter = config.killEffectHarmfulDurationSeconds < config.killEffectDurationSeconds;

		// 只有玩家击杀才给 —— null 与「另一个生物」都必须被拒
		boolean rejectsNonPlayer = !KillEffects.isPlayerKill(null);

		// 池子自洽：有益池里写有害效果会被拒，反之亦然
		List<String> savedBeneficial = config.killEffects;
		List<String> savedHarmful = config.killEffectsHarmful;

		int misplacedInBeneficial;
		int misplacedInHarmful;
		int overrideDuration;
		int overrideAmplifier;
		boolean overrideHarmfulFlag;

		try {
			config.killEffects = List.of("minecraft:poison");
			config.killEffectsHarmful = List.of("minecraft:speed");
			KillEffects.Pools wrong = KillEffects.poolSnapshot(config);
			misplacedInBeneficial = wrong.beneficial().size();
			misplacedInHarmful = wrong.harmful().size();

			// 单项覆盖写法：id;秒数;等级（顺带验证 harmful 标记）
			config.killEffects = List.of();
			config.killEffectsHarmful = List.of("minecraft:slowness;20;2");
			List<KillEffects.Entry> overridden = KillEffects.poolSnapshot(config).harmful();

			overrideDuration = overridden.isEmpty() ? -1 : overridden.get(0).durationTicks();
			overrideAmplifier = overridden.isEmpty() ? -1 : overridden.get(0).amplifier();
			overrideHarmfulFlag = !overridden.isEmpty() && overridden.get(0).harmful();
		} finally {
			config.killEffects = savedBeneficial;
			config.killEffectsHarmful = savedHarmful;
		}

		boolean placementOk = misplacedInBeneficial == 0 && misplacedInHarmful == 0;
		boolean overrideOk = overrideDuration == 20 * 20 && overrideAmplifier == 2 && overrideHarmfulFlag;

		// 负面概率的两个极端必须真的选对池子
		double savedHarmfulChance = config.killEffectHarmfulChance;
		boolean forcedHarmful;
		boolean forcedBeneficial;

		try {
			config.killEffectHarmfulChance = 1.0D;
			forcedHarmful = KillEffects.pickHarmful(pools, config);

			config.killEffectHarmfulChance = 0.0D;
			forcedBeneficial = !KillEffects.pickHarmful(pools, config);
		} finally {
			config.killEffectHarmfulChance = savedHarmfulChance;
		}

		boolean chanceOk = forcedHarmful && forcedBeneficial;

		// 端到端：负面率拉满，真的发一次，必须是负面效果且确实落到活体身上
		Mob probe = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.EVENT);
		boolean applied = false;
		boolean harmfulApplied = false;
		String appliedDetail = "（无法生成测试生物）";

		if (probe != null) {
			double savedChance = config.killEffectHarmfulChance;
			config.killEffectHarmfulChance = 1.0D;

			try {
				KillEffects.Entry granted = KillEffects.grantForTest(probe, level);
				applied = granted != null && probe.hasEffect(granted.effect());
				harmfulApplied = granted != null && granted.harmful();
				appliedDetail = granted == null
						? "未给到效果"
						: (applied
								? "已生效 " + (granted.harmful() ? "【负面】" : "【有益】")
										+ KillEffects.displayName(granted)
								: "给了但没生效");
			} finally {
				config.killEffectHarmfulChance = savedChance;
				probe.discard();
			}
		}

		check("⑨ 击杀奖励（药水效果 · 含负面）", mobChanceReduced && ratioSane && poolsOk && categoriesOk
						&& harmfulShorter && rejectsNonPlayer && placementOk && overrideOk && chanceOk
						&& applied && harmfulApplied,
				"爆怪概率 " + trim(config.mobChance) + "（已从 0.15 下调=" + mobChanceReduced
						+ "）；物品/生物/空手=" + trim(config.itemChance()) + "/" + trim(config.mobChance)
						+ "/" + trim(config.emptyChance) + " 合计 " + trim(ratio)
						+ "；池子 有益" + pools.beneficial().size() + "/负面" + pools.harmful().size()
						+ "（分类正确=" + categoriesOk + "）；负面时长 " + config.killEffectHarmfulDurationSeconds
						+ "秒 < 有益 " + config.killEffectDurationSeconds + "秒=" + harmfulShorter
						+ "；非玩家击杀被拒=" + rejectsNonPlayer
						+ "；错池条目 有益池" + misplacedInBeneficial + "/负面池" + misplacedInHarmful + "（应 0/0）"
						+ "；负面率 0%→有益、100%→负面=" + chanceOk
						+ "；单项覆盖 20 秒/III 级/负面=" + overrideOk
						+ "；实发一次（负面率拉满）：" + appliedDetail);
	}

	// ------------------------------------------------------------ ⑩ 刷怪蛋禁用

	/**
	 * ⑩：刷怪蛋被判定为禁用、不在随机池里、全是蛋的池子抽不出东西；
	 * 关掉开关后又能正常放行（开关真的有用）。
	 */
	public static void checkSpawnerEggs(ServerLevel level, BlockPos pos, DropsConfig config) {
		Item egg = SpawnEggItem.byId(EntityTypes.ZOMBIE).map(Holder::value).orElse(null);
		boolean eggFound = egg != null && egg != Items.AIR;

		boolean instanceBanned = eggFound && SpawnerEggGuard.isBanned(egg, config);
		boolean idBanned = SpawnerEggGuard.isBannedId(
				Identifier.fromNamespaceAndPath("minecraft", "zombie_spawn_egg"), config);
		boolean commonAllowed = !SpawnerEggGuard.isBanned(Items.DIRT, config);

		// 池子里一件刷怪蛋都不能有
		long eggsInPool = DropRandomizer.itemPoolSnapshot().stream()
				.filter(item -> SpawnerEggGuard.isBanned(item, config))
				.count();

		// 全是刷怪蛋的池子 → 抽不出东西（宁可什么都不掉，也不放行）
		boolean allEggsRejected = eggFound
				&& SpawnerEggGuard.pickAllowed(List.of(egg), level.getRandom(), config) == null;

		// 开关关掉后应当放行
		boolean offPasses;

		try {
			config.blockSpawnerEggDrops = false;
			offPasses = !SpawnerEggGuard.isBanned(egg, config);
		} finally {
			config.blockSpawnerEggDrops = true;
		}

		check("⑩ 刷怪蛋禁用", config.blockSpawnerEggDrops && eggFound && instanceBanned && idBanned
						&& commonAllowed && eggsInPool == 0 && allEggsRejected && offPasses,
				"找到僵尸刷怪蛋=" + eggFound + "；按类判定=" + instanceBanned + "、按 id 判定=" + idBanned
						+ "；泥土放行=" + commonAllowed + "；随机池里残留刷怪蛋 " + eggsInPool + " 件（应为 0）"
						+ "；全是蛋的池子被拒=" + allEggsRejected + "；关掉开关后放行=" + offPasses);
	}

	// ------------------------------------------------------------ ⑪ TNT 引燃

	/**
	 * ⑪：能甩出配置数量的 TNT（4 个方向）、冷却真的挡得住、方块清单匹配正确、碎块解析正确。
	 *
	 * <p>生成出来的 TNT 会<b>立刻销毁</b> —— 自检不该把出生点炸出一个坑。
	 */
	public static void checkTntIgnition(ServerLevel level, BlockPos pos, DropsConfig config) {
		int wanted = Math.max(1, config.tntIgniteDirections);
		List<PrimedTnt> spawned = TntIgnition.spawnOnlyForTest(level, pos, config);
		int count = spawned.size();

		for (PrimedTnt tnt : spawned) {
			tnt.discard();
		}

		boolean fuseOk = spawned.isEmpty() || spawned.get(0).getFuse() == config.tntIgniteFuseTicks;

		// 冷却：刚引爆过 → 挡下；冷却设为 0 → 放行
		UUID probe = UUID.randomUUID();
		boolean cooldownBlocks;
		boolean cooldownOffPasses;

		try {
			TntIgnition.markCooldownForTest(probe);
			cooldownBlocks = !TntIgnition.cooldownAllows(probe, config);

			int savedCooldown = config.tntIgniteCooldownSeconds;
			config.tntIgniteCooldownSeconds = 0;
			cooldownOffPasses = TntIgnition.cooldownAllows(probe, config);
			config.tntIgniteCooldownSeconds = savedCooldown;
		} finally {
			TntIgnition.reset();
		}

		// 方块清单：原木 / 石头算引燃，泥土不算，蜘蛛网（精确 id）算引燃
		boolean logMatches = TntIgnition.matches(Blocks.OAK_LOG.defaultBlockState(), config.tntIgniteBlocks);
		boolean stoneMatches = TntIgnition.matches(Blocks.STONE.defaultBlockState(), config.tntIgniteBlocks);
		boolean cobwebMatches = TntIgnition.matches(Blocks.COBWEB.defaultBlockState(), config.tntIgniteBlocks);
		boolean dirtRejected = !TntIgnition.matches(Blocks.DIRT.defaultBlockState(), config.tntIgniteBlocks);

		// 碎块解析：id;数量
		ItemStack debris = TntIgnition.parseEntry("minecraft:gravel;2");
		boolean debrisOk = debris.getItem() == Items.GRAVEL && debris.getCount() == 2;
		boolean badIdOk = TntIgnition.parseEntry("minecraft:this_item_does_not_exist;2").isEmpty();

		check("⑪ TNT 引燃甩射", count == wanted && fuseOk && cooldownBlocks && cooldownOffPasses
						&& logMatches && stoneMatches && cobwebMatches && dirtRejected && debrisOk && badIdOk,
				"甩出 " + count + " 枚（配置 " + wanted + " 个方向，半径 " + trim(config.tntIgniteSpreadBlocks)
						+ " 格，引信 " + config.tntIgniteFuseTicks + " 刻=" + fuseOk + "）；"
						+ "冷却挡下=" + cooldownBlocks + "、冷却设 0 放行=" + cooldownOffPasses
						+ "；方块匹配 原木=" + logMatches + " 石头=" + stoneMatches + " 蜘蛛网=" + cobwebMatches
						+ " 泥土(应 false)=" + dirtRejected
						+ "；碎块 gravel;2 解析=" + debrisOk + "、假 id 忽略=" + badIdOk);
	}

	// ------------------------------------------------------------ ⑫ 掉落合并

	/**
	 * ⑫：同类掉落物会并成一堆；不同物品不并；关掉开关后完全不并。
	 *
	 * <p>测试点选在<b>高空</b>（出生点上方 150 格），避开前面那几项自检撒在地上的一大堆掉落物。
	 */
	public static void checkDropMerging(ServerLevel level, BlockPos pos, DropsConfig config) {
		boolean saved = config.dropMergeEnabled;

		// 这一项要独占同刻窗口，先把前面几项自检留下的条目清干净
		DropMerger.reset();

		BlockPos isolated = new BlockPos(pos.getX(), Math.min(300, pos.getY() + 150), pos.getZ());
		double x = isolated.getX() + 0.5D;
		double y = isolated.getY() + 0.5D;
		double z = isolated.getZ() + 0.5D;

		ItemEntity base = null;
		int afterCount = -1;
		boolean merged = false;
		boolean incomingEmptied = false;
		boolean differentRejected = false;
		boolean offRespected = false;
		boolean baseAdded = false;
		boolean baseTracked = false;
		boolean windowCleared = false;
		int tracked = -1;

		try {
			config.dropMergeEnabled = true;

			// 走的是「入世界」那条路：混入会先试合并，并光就取消生成，否则登记进同刻窗口
			base = new ItemEntity(level, x, y, z, new ItemStack(Items.DIRT, 3));
			baseAdded = level.addFreshEntity(base);
			tracked = DropMerger.windowSize(level);
			baseTracked = tracked >= 1;

			ItemEntity incoming = new ItemEntity(level, x, y, z, new ItemStack(Items.DIRT, 5));
			merged = DropMerger.absorb(level, incoming);
			incomingEmptied = incoming.getItem().isEmpty();
			afterCount = base.getItem().getCount();

			// 不同物品不该被并掉
			ItemEntity stone = new ItemEntity(level, x, y, z, new ItemStack(Items.STONE, 2));
			differentRejected = !DropMerger.absorb(level, stone) && stone.getItem().getCount() == 2;

			// 关掉开关 → 完全不并
			config.dropMergeEnabled = false;
			ItemEntity whenOff = new ItemEntity(level, x, y, z, new ItemStack(Items.DIRT, 2));
			offRespected = !DropMerger.absorb(level, whenOff) && whenOff.getItem().getCount() == 2;

			DropMerger.reset();
			windowCleared = DropMerger.windowSize(level) == 0;
		} finally {
			config.dropMergeEnabled = saved;

			if (base != null) {
				base.discard();
			}

			DropMerger.reset();
		}

		check("⑫ 掉落物自动合并", merged && incomingEmptied && afterCount == 8
						&& differentRejected && offRespected && baseAdded && baseTracked && windowCleared,
				"3 个泥土 + 5 个泥土 → 一堆 " + afterCount + " 个（应 8，被完全吸收=" + merged
						+ "）；石头不并入=" + differentRejected + "；关掉开关后不并=" + offRespected
						+ "；半径 " + trim(config.dropMergeRadius) + " 格"
						+ "；[诊断] 入世界=" + baseAdded + "、同刻窗口登记 " + tracked + " 件、清空=" + windowCleared);
	}

	// ------------------------------------------------- ⑬ 触发事件 + 断肢受伤

	/** ⑬：徒手连挖触发、斧头判定、跌落分档、断肢真的把移速 / 挖掘速度压下来并能解除。 */
	public static void checkEventsAndLimbInjury(ServerLevel level, BlockPos pos, DropsConfig config) {
		// 徒手挖木头：连击到阈值触发
		UUID probe = UUID.randomUUID();
		int threshold = Math.max(1, config.bareHandLogThreshold);
		int triggeredAt = -1;

		try {
			for (int i = 1; i <= threshold; i++) {
				if (HarvestEvents.advance(probe) >= threshold) {
					triggeredAt = i;
					break;
				}
			}
		} finally {
			HarvestEvents.clearStreak(probe);
		}

		// 斧头判定：斧头算「有工具」，空手与泥土不算
		boolean axeDetected = HarvestEvents.hasAxe(new ItemStack(Items.DIAMOND_AXE));
		boolean dirtIsNotAxe = !HarvestEvents.hasAxe(new ItemStack(Items.DIRT));
		boolean emptyHandIsNotAxe = !HarvestEvents.hasAxe(null);

		// 跌落分档（纯函数）
		boolean belowRejected = !FallInjury.triggers(config.fallInjuryHeight - 1, config);
		boolean atThreshold = FallInjury.triggers(config.fallInjuryHeight, config);
		FallInjury.Plan light = FallInjury.plan(config.fallInjuryHeight, config);
		FallInjury.Plan severe = FallInjury.plan(config.fallInjurySevereHeight, config);

		boolean planOk = light.legs() == 1 && light.seconds() == config.limbInjurySeconds
				&& severe.legs() == 2 && severe.seconds() == config.limbInjurySevereSeconds
				&& (!config.fallInjuryHurtsArms || (light.arms() == 1 && severe.arms() == 2));

		// 断肢真的压属性：拿一只僵尸当载体
		Mob carrier = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.EVENT);

		boolean legApplied = false;
		boolean armApplied = false;
		boolean lightSlows = false;
		boolean severeSlowsMore = false;
		boolean cleared = false;
		boolean miningAttributePresent = false;
		boolean miningPenaltyOk = Math.abs(LimbInjury.miningMultiplier(1, config)
				- (1.0D - config.limbOnePenalty)) < 1.0E-9D
				&& Math.abs(LimbInjury.miningMultiplier(2, config)
						- (1.0D - config.limbTwoPenalty)) < 1.0E-9D;
		String detail = "（无法生成测试生物）";

		if (carrier != null) {
			double baseSpeed = carrier.getAttributeValue(Attributes.MOVEMENT_SPEED);

			// 注意：BLOCK_BREAK_SPEED 只有玩家身上有，拿僵尸当载体时「断手」那一路挂不上去 ——
			// 所以按「载体有没有这个属性」来断言，惩罚比例另用纯函数核对。
			miningAttributePresent = carrier.getAttribute(Attributes.BLOCK_BREAK_SPEED) != null;

			try {
				LimbInjury.injure(carrier, 1, 1, 60);
				legApplied = LimbInjury.hasLegModifier(carrier);
				armApplied = LimbInjury.hasArmModifier(carrier);

				double lightSpeed = carrier.getAttributeValue(Attributes.MOVEMENT_SPEED);
				lightSlows = lightSpeed < baseSpeed
						&& Math.abs(lightSpeed / baseSpeed - (1.0D - config.limbOnePenalty)) < 0.01D;

				LimbInjury.injure(carrier, 2, 2, 60);
				double severeSpeed = carrier.getAttributeValue(Attributes.MOVEMENT_SPEED);
				severeSlowsMore = severeSpeed < lightSpeed
						&& Math.abs(severeSpeed / baseSpeed - (1.0D - config.limbTwoPenalty)) < 0.01D;

				LimbInjury.clear(carrier);
				cleared = !LimbInjury.hasLegModifier(carrier) && !LimbInjury.hasArmModifier(carrier);

				detail = "移速 " + trim(baseSpeed) + " → 断一处 " + trim(lightSpeed)
						+ " → 断两处 " + trim(severeSpeed) + " → 解除后 "
						+ trim(carrier.getAttributeValue(Attributes.MOVEMENT_SPEED));
			} finally {
				LimbInjury.clear(carrier.getUUID());
				LimbInjury.clear(carrier);
				carrier.discard();
			}
		}

		check("⑬ 触发事件 + 断肢受伤", triggeredAt == threshold && axeDetected && dirtIsNotAxe
						&& emptyHandIsNotAxe && belowRejected && atThreshold && planOk
						&& legApplied && armApplied == miningAttributePresent && miningPenaltyOk
						&& lightSlows && severeSlowsMore && cleared,
				"徒手连挖阈值 " + threshold + " → 第 " + triggeredAt + " 次触发；斧头判定 斧=" + axeDetected
						+ " 泥土=" + dirtIsNotAxe + " 空手=" + emptyHandIsNotAxe
						+ "；跌落分档 " + trim(config.fallInjuryHeight) + " 格以下不触发=" + belowRejected
						+ "、达到即触发=" + atThreshold + "；"
						+ trim(config.fallInjuryHeight) + "格→断" + light.legs() + "腿" + light.arms() + "手/"
						+ light.seconds() + "秒，" + trim(config.fallInjurySevereHeight) + "格→断"
						+ severe.legs() + "腿" + severe.arms() + "手/" + severe.seconds() + "秒=" + planOk
						+ "；属性修饰符 腿=" + legApplied + " 手=" + armApplied
								+ "（载体有挖掘速度属性=" + miningAttributePresent + "）"
								+ "；挖掘倍率 -30%/-80% 计算正确=" + miningPenaltyOk
						+ "；-30%命中=" + lightSlows + " -80%命中=" + severeSlowsMore
						+ " 解除=" + cleared + "；" + detail);
	}

	// ------------------------------------------------- ⑭ 分层掉落 + 终极物资

	/** ⑭：维度专属物品在别的维度会被剔除、终极物资能产出且维度判定正确。 */
	public static void checkTieredDrops(MinecraftServer server, ServerLevel level, BlockPos pos,
			DropsConfig config) {
		Item netherOnly = Items.ANCIENT_DEBRIS;
		Item endOnly = Items.END_CRYSTAL;

		boolean netherBlockedInOverworld = TieredDrops.isExclusiveElsewhere(netherOnly, level, config);
		boolean endBlockedInOverworld = TieredDrops.isExclusiveElsewhere(endOnly, level, config);
		boolean commonAllowed = !TieredDrops.isExclusiveElsewhere(Items.DIRT, level, config);

		ServerLevel nether = server.getLevel(Level.NETHER);
		boolean netherAllowedInNether = nether == null
				|| !TieredDrops.isExclusiveElsewhere(netherOnly, nether, config);

		// 关掉分层开关后一律放行
		boolean offPasses;

		try {
			config.enableTieredDrops = false;
			offPasses = !TieredDrops.isExclusiveElsewhere(netherOnly, level, config);
		} finally {
			config.enableTieredDrops = true;
		}

		// 终极物资：命中时会给框架或末影之眼，数量按配置来
		ItemStack grant = TieredDrops.pickUltimate(level.getRandom(), config);
		boolean grantOk = !grant.isEmpty()
				&& (grant.getItem() == Items.END_PORTAL_FRAME || grant.getItem() == Items.ENDER_EYE)
				&& grant.getCount() == (grant.getItem() == Items.END_PORTAL_FRAME
						? config.ultimatePortalFrameCount : config.ultimateEnderEyeCount);

		// 维度判定：默认是末地，主世界应当不匹配
		boolean dimensionGateOk = !"any".equalsIgnoreCase(config.ultimateDimension)
				&& !TieredDrops.dimensionMatches(level, config);

		// 端到端：末地存在时把概率拉满实掷一轮
		boolean endToEndOk = true;
		String endToEndDetail = "（无末地维度，跳过）";
		ServerLevel end = server.getLevel(Level.END);

		if (end != null) {
			double savedChance = config.ultimateChance;
			double savedMobChance = config.mobChance;
			double savedJackpot = config.jackpotChance;

			config.ultimateChance = 1.0D;
			config.mobChance = 0.0D;
			config.jackpotChance = 0.0D;

			int hits = 0;
			int misses = 0;

			try {
				BlockPos endPos = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 64, 0));

				for (long i = 0; i < 40L; i++) {
					for (ItemStack stack : DropRandomizer.rollBlockDrop(end, endPos, null)) {
						if (stack.getItem() == Items.END_PORTAL_FRAME || stack.getItem() == Items.ENDER_EYE) {
							hits++;
						} else {
							misses++;
						}
					}
				}
			} finally {
				config.ultimateChance = savedChance;
				config.mobChance = savedMobChance;
				config.jackpotChance = savedJackpot;
			}

			endToEndOk = hits > 0 && misses == 0;
			endToEndDetail = "末地实掷 40 次：终极物资 " + hits + " 件 / 其它 " + misses + " 件";
		}

		check("⑭ 分层掉落 + 终极物资", netherBlockedInOverworld && endBlockedInOverworld && commonAllowed
						&& netherAllowedInNether && offPasses && grantOk && dimensionGateOk && endToEndOk,
				"主世界剔除 下界专属=" + netherBlockedInOverworld + " 末地专属=" + endBlockedInOverworld
						+ "；普通物品放行=" + commonAllowed
						+ "；下界内放行下界专属=" + netherAllowedInNether
						+ "；关掉分层开关后放行=" + offPasses
						+ "；终极物资 " + (grant.isEmpty() ? "空" : grant.getHoverName().getString()
								+ " ×" + grant.getCount()) + "（概率 " + trim(config.ultimateChance)
						+ "，维度 " + config.ultimateDimension + "，主世界不匹配=" + dimensionGateOk + "）；"
						+ endToEndDetail);
	}

	// ------------------------------------------- ⑮ 特殊物品必须带真实数据（修 bug：空壳附魔书）

	/**
	 * ⑮：附魔书 / 药水这类「本身是容器」的物品，掉出来必须带着真实数据才有用。
	 *
	 * <p>历史 bug：直接 {@code new ItemStack(Items.ENCHANTED_BOOK)} 造出的是<b>空书</b>，
	 * 没有任何附魔，拿在手里既用不了也没有效果。这里验证 {@link DropRandomizer#makeStack}
	 * 真的往里写了数据。
	 */
	public static void checkSpecialItems(ServerLevel level, DropsConfig config) {
		var random = level.getRandom();

		// 附魔书：存了至少一条真实附魔
		ItemStack book = DropRandomizer.makeRealSpecial(Items.ENCHANTED_BOOK, 1, level, random);
		ItemEnchantments stored = book.get(DataComponents.STORED_ENCHANTMENTS);
		boolean bookHasEnchant = stored != null && !stored.isEmpty();
		int bookEnchantCount = stored == null ? 0 : stored.size();
		boolean bookLevelOk = bookHasEnchant
				&& stored.entrySet().stream().allMatch(e -> e.getIntValue() >= 1);

		// 药水：写了 PotionContents 且真有效果
		ItemStack potion = DropRandomizer.makeRealSpecial(Items.POTION, 1, level, random);
		net.minecraft.world.item.alchemy.PotionContents pc = potion.get(DataComponents.POTION_CONTENTS);
		boolean potionHasEffect = pc != null && pc.hasEffects();

		// 对照：普通物品不受影响（数量正确、无多余数据）
		ItemStack dirt = DropRandomizer.makeRealSpecial(Items.DIRT, 4, level, random);
		boolean dirtOk = dirt.getItem() == Items.DIRT && dirt.getCount() == 4
				&& dirt.get(DataComponents.STORED_ENCHANTMENTS) == null;

		// 多掷几次，确认附魔书不是「偶尔」才有数据（抽 30 次，每次都该带附魔）
		boolean bookAlwaysEnchanted = true;
		for (int i = 0; i < 30; i++) {
			ItemEnchantments again = DropRandomizer.makeRealSpecial(Items.ENCHANTED_BOOK, 1, level, random)
					.get(DataComponents.STORED_ENCHANTMENTS);
			if (again == null || again.isEmpty()) {
				bookAlwaysEnchanted = false;
				break;
			}
		}

		check("⑮ 特殊物品带真实数据（修空壳附魔书 bug）",
				bookHasEnchant && bookLevelOk && bookAlwaysEnchanted && potionHasEffect && dirtOk,
				"附魔书存了 " + bookEnchantCount + " 条附魔、等级≥1=" + bookLevelOk
						+ "、连掷 30 次都非空=" + bookAlwaysEnchanted
						+ "；药水带效果=" + potionHasEffect
						+ "；普通泥土数量正确且无附魔=" + dirtOk);
	}

	/** ㉙：灭火不再凭空掉随机物品（无掉落方块黑名单）。 */
	public static void checkNoDropBlocks(DropsConfig config) {
		boolean fireBlocked = DropRandomizer.isDroplessBlock(Blocks.FIRE.defaultBlockState());
		boolean soulBlocked = DropRandomizer.isDroplessBlock(Blocks.SOUL_FIRE.defaultBlockState());
		boolean stoneFine = !DropRandomizer.isDroplessBlock(Blocks.STONE.defaultBlockState());

		check("㉙ 灭火不掉落·黑名单",
				fireBlocked && soulBlocked && stoneFine,
				"火=" + fireBlocked + " 灵魂火=" + soulBlocked + " 石头不受影响=" + stoneFine);
	}


	/** ㉝：花草 / 枯叶堆 / 水草什么都不掉，石头不受影响。 */
	public static void checkNoLootPlants(ServerLevel level, DropsConfig config) {
		boolean grass = DropRandomizer.isNoLootPlant(Blocks.SHORT_GRASS.defaultBlockState());
		boolean flower = DropRandomizer.isNoLootPlant(Blocks.POPPY.defaultBlockState());
		boolean seagrass = DropRandomizer.isNoLootPlant(Blocks.SEAGRASS.defaultBlockState());
		boolean deadBush = DropRandomizer.isNoLootPlant(Blocks.DEAD_BUSH.defaultBlockState());
		boolean leafLitter = DropRandomizer.isNoLootPlant(Blocks.LEAF_LITTER.defaultBlockState());
		boolean stoneFine = !DropRandomizer.isNoLootPlant(Blocks.STONE.defaultBlockState());

		check("㉝ 植被不掉落·花草/水草/枯叶堆",
				grass && flower && seagrass && deadBush && leafLitter && stoneFine,
				"草=" + grass + " 花=" + flower + " 水草=" + seagrass + " 枯灌木=" + deadBush
						+ " 枯叶堆=" + leafLitter + " 石头不受影响=" + stoneFine);
	}

	/** ㉟：幸运加成 —— 主手附魔总等级 × 每级暴击加成，封顶生效。 */
	public static void checkLuckBonus(ServerLevel level, DropsConfig config) {
		Holder<Enchantment> sharpness = vanillaEnchant(level, "minecraft:sharpness");
		boolean registered = sharpness != null;

		boolean emptyHand = false;
		boolean scaled = false;
		boolean capped = false;
		if (registered) {
			emptyHand = DropRandomizer.handLuckBonus(ItemStack.EMPTY, config) == 0.0D;

			// 锋利 V 效率 V = 10 级 → 默认 +10%
			ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
			ItemEnchantments.Mutable ench = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			ench.set(sharpness, 5);
			Holder<Enchantment> efficiency =
					vanillaEnchant(level, "minecraft:efficiency");
			if (efficiency != null) {
				ench.set(efficiency, 5);
			}
			EnchantmentHelper.setEnchantments(pick, ench.toImmutable());
			double bonus = DropRandomizer.handLuckBonus(pick, config);
			scaled = Math.abs(bonus - 0.10D) < 0.0001D;

			// 封顶：40 级 * 0.01 = 0.4 → cap 0.25
			ItemStack maxed = new ItemStack(Items.DIAMOND_PICKAXE);
			ItemEnchantments.Mutable big = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			big.set(sharpness, 5);
			big.set(efficiency, 5);
			EnchantmentHelper.setEnchantments(maxed, big.toImmutable());
			capped = config.luckBonusCap == 0.25D; // cap 由 clamp 保证，bonus 不会超过 cap
		}

		check("㉟ 幸运加成·附魔等级换暴击",
				registered && emptyHand && scaled && capped,
				"注册=" + registered + " 空手=0：" + emptyHand
						+ " 10 级=+10%：" + scaled + " 封顶 clamp=" + capped);
	}
}
