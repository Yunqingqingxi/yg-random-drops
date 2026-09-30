package com.yunxigames;

import com.yunxigames.command.YunxiGamesCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 随机掉落包入口（yg_drops）。
 *
 * <p>把方块掉落与生物掉落全部替换成随机结果：随机物品或随机生物。挖泥土可能掉出钻石，
 * 也可能掉出一只僵尸。本包自带 yg-core 基础库（配置框架 / 自检框架 / 命令 / 战绩 / 播报），
 * 可独立安装，也可以作为其它玩法包（更多附魔 / 事件 / Bingo / 更多生物）的掉落底座。
 *
 * <p>玩法清单：随机掉落引擎（暴击 / 保底 / 进度分档 / 维度群系池）、爆出生物
 * （僵直 / 精英怪 / 刷怪蛋守卫 / 击杀药水奖励）、通关结算、掉落合并 + 定时播报、
 * TNT 引燃甩射、徒手伐木 + 断肢、摔落断肢、分层掉落 + 终极物资、幸运加成。
 */
public class YunxiGamesDrops implements ModInitializer {
	public static final String MOD_ID = "yg_drops";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		DropsConfig config = DropsConfig.load();
		LOGGER.info("[yg-drops] 配置载入完成：方块掉落={} 生物掉落={} 生物概率={} 权重(敌对/中立/友好)={}/{}/{}",
				config.enableBlockDrops, config.enableMobDrops, config.mobChance,
				config.hostileWeight, config.neutralWeight, config.passiveWeight);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				YunxiGamesCommand.register(dispatcher));

		// 每个游戏刻重置「本 tick 最多生成多少只生物」的配额
		ServerTickEvents.START_SERVER_TICK.register(server -> DropRandomizer.resetTickBudget());

		// 爆出来的生物落地僵直的倒计时
		ServerTickEvents.END_SERVER_TICK.register(server -> MobStun.tick());

		// 关服时清掉保底计数，避免残留
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> DropRandomizer.clearPity());

		// 关服时也清掉精英表（下次开服就是新的一局）
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> EliteMobs.reset());

		// 关服时清掉击杀奖励的计数与效果池缓存
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> KillEffects.reset());

		// 关服时清掉 TNT 冷却、徒手挖木头连击、断肢状态与掉落统计
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			TntIgnition.reset();
			HarvestEvents.reset();
			LimbInjury.reset();
			DropTally.reset();
			DropMerger.reset();
		});

		// 每秒一次：掉落统计播报 + 断肢到期 / 重生后重新贴合
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0) {
				return;
			}

			DropTally.onTick(server);
			LimbInjury.tick(server);
		});

		// 开服时清零「本局统计」——一局就是一局，不跨进程累计
		ServerLifecycleEvents.SERVER_STARTING.register(server -> SessionStats.reset());

		// 精英怪：出生时标记，死亡时掉宝藏
		EliteMobs.register();

		// 击杀奖励：玩家击杀生物时直接赋予随机药水效果
		KillEffects.register();

		// 末影龙被击杀 → 通关结算（标题 + 统计 + 宝藏雨）
		Finale.register();

		// 自检：本包全部检查 + 自检前后的环境准备，selfTestRolls > 0 时开服自动跑
		DropsSelfTest.registerSteps();
		SelfTest.register(() -> DropsConfig.get().selfTestRolls);

		LOGGER.info("[yg-drops] 随机掉落已加载");
	}
}
