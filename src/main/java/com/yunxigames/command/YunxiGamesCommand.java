package com.yunxigames.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.yunxigames.DropRandomizer;
import com.yunxigames.KillEffects;
import com.yunxigames.DropsConfig;
import com.yunxigames.SelfTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /yg ...}（随机掉落包主命令）：游戏内查看状态、重载配置、快速启停。
 *
 * <p>命令树是系列统一的「{@code /yg <玩法名>}」布局：每个玩法包都往 {@code /yg} 根下
 * 挂一个以玩法名命名的子树（本包是 {@code /yg drops}），Brigadier 会把各包注册的
 * 同名根节点合并成一棵命令树 —— 装了几个包，{@code /yg} 下就有几个玩法子树。
 * 旧的顶层快捷方式（{@code /yg on|off} / {@code reload} / {@code selftest}）保留兼容。
 */
public final class YunxiGamesCommand {
	/** 26.2 的新权限 API：等价于旧的「权限等级 2」。 */
	private static final PermissionCheck PERMISSION = new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);

	private YunxiGamesCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("yg")
				.requires(Commands.hasPermission(PERMISSION))
				.executes(context -> status(context.getSource()))
				.then(Commands.literal("status")
						.executes(context -> status(context.getSource())))
				.then(Commands.literal("reload")
						.executes(context -> {
							DropsConfig.load();
							DropRandomizer.resetPools();
							KillEffects.resetCache();
							context.getSource().sendSuccess(() -> Component.literal("[yg] 配置已重新载入"), false);
							return status(context.getSource());
						}))
				.then(Commands.literal("on")
						.executes(context -> toggle(context.getSource(), true)))
				.then(Commands.literal("off")
						.executes(context -> toggle(context.getSource(), false)))
			.then(Commands.literal("selftest")
					.executes(context -> selfTest(context.getSource())))
			// 系列统一布局：/yg drops ...（与顶层 on|off 同义，方便和其它玩法包的子树对照记忆）
			.then(Commands.literal("drops")
					.executes(context -> status(context.getSource()))
					.then(Commands.literal("on")
							.executes(context -> toggle(context.getSource(), true)))
					.then(Commands.literal("off")
							.executes(context -> toggle(context.getSource(), false))));

		dispatcher.register(root);
	}

	/**
	 * 整体启停随机掉落。
	 *
	 * <p><b>必须同时切方块与生物两个开关</b>：玩法核心是「掉落随机化」，off 的语义是
	 * 掉落恢复原样 —— 只关 {@code enableBlockDrops} 的话生物还在掉随机物品，等于没关。
	 * TNT 引燃 / 断肢这类独立事件有自己的开关，不受这里影响（回显里能看出它们仍然开着）。
	 */
	private static int toggle(CommandSourceStack source, boolean enabled) {
		DropsConfig config = DropsConfig.get();
		config.enableBlockDrops = enabled;
		config.enableMobDrops = enabled;
		config.save();
		source.sendSuccess(() -> Component.literal("[yg] 随机掉落整体：" + (enabled ? "开启" : "关闭")
				+ "（方块 + 生物，掉落恢复" + (enabled ? "随机化" : "原样") + "）"), false);
		return status(source);
	}

	/** 手动跑一遍自检，结论同时写进聊天栏与日志。 */
	private static int selfTest(CommandSourceStack source) {
		MinecraftServer server = source.getServer();

		source.sendSuccess(() -> Component.literal("[yg] 自检开始，请看聊天栏与日志……"), false);

		int failed = SelfTest.run(server, Math.max(1, DropsConfig.get().selfTestRolls));

		if (failed == 0) {
			source.sendSuccess(() -> Component.literal("[yg] 自检全部通过 ✅（详见日志）"), false);
		} else {
			source.sendFailure(Component.literal("[yg] 自检有 " + failed + " 项失败 ❌（详见日志）"));
		}

		return failed == 0 ? 1 : 0;
	}

	private static int status(CommandSourceStack source) {
		DropsConfig config = DropsConfig.get();
		StringBuilder text = new StringBuilder(String.format(
				"[yg] 方块掉落=%s 生物掉落=%s 生物概率=%.2f 权重(敌对/中立/友好)=%d/%d/%d 仅玩家破坏掉生物=%s 保底(方块/生物)=%d/%d 数量(物品/生物)=%d~%d/%d~%d 开局保护=%d分钟",
				config.enableBlockDrops ? "开" : "关",
				config.enableMobDrops ? "开" : "关",
				config.mobChance,
				config.hostileWeight, config.neutralWeight, config.passiveWeight,
				config.spawnMobsOnlyFromPlayers ? "是" : "否",
				config.pityThreshold, config.mobPityThreshold,
				config.itemCountMin, config.itemCountMax, config.mobCountMin, config.mobCountMax,
				config.earlyGameMinutes));

		text.append(String.format(
				" | 暴击=%.3f(进度×%s 维度×%s) 稀有广播=%s | 通关=%s 宝藏雨=%d | 精英=%s(%.3f) | 击杀效果=%s(%.2f, 有益%d/负面%d, 负面率%.2f)",
				config.jackpotChance,
				config.progressScaling ? "开" : "关",
				config.dimensionAffectsPools ? "开" : "关",
				config.rareDropBroadcast ? "开" : "关",
				config.enableFinale ? "开" : "关",
				config.finaleTreasureCount,
				config.enableEliteMobs ? "开" : "关",
				config.eliteChance,
				config.enableKillEffects ? "开" : "关",
				config.killEffectChance,
				KillEffects.poolSnapshot(config).beneficial().size(),
				KillEffects.poolSnapshot(config).harmful().size(),
				config.killEffectHarmfulChance));

		text.append(String.format(
				" | v1.11：刷怪蛋=%s TNT=%.3f(冷却%ds) 合并=%s(%.1f格) 播报=%d分钟 | 徒手木=%d 跌落=%s(%.0f格) | 分层=%s 终极=%.3f(%s)",
				config.blockSpawnerEggDrops ? "禁" : "放",
				config.enableTntIgnition ? config.tntIgniteChance : 0.0D,
				config.tntIgniteCooldownSeconds,
				config.dropMergeEnabled ? "开" : "关",
				config.dropMergeRadius,
				config.dropSummaryEnabled ? config.dropSummaryIntervalSeconds / 60 : 0,
				config.enableBareHandLogEvent ? config.bareHandLogThreshold : 0,
				config.enableFallInjury ? "开" : "关",
				config.fallInjuryHeight,
				config.enableTieredDrops ? "开" : "关",
				config.enableUltimateDrops ? config.ultimateChance : 0.0D,
				config.ultimateDimension));

		source.sendSuccess(() -> Component.literal(text.toString()), false);
		return 1;
	}
}
