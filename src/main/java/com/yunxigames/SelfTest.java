package com.yunxigames;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;

/**
 * yunxigames 系列的开服自检<b>框架</b>（yg core）。
 *
 * <p>本类不含任何玩法检查 —— 各玩法包（drops / enchants / events / bingo / mobs）通过
 * {@link #registerStep} 把自己的检查注册进来，通过 {@link #onBeforeRun} / {@link #onAfterRun}
 * 挂自检前后的环境准备（关播报、暂停统计、重置随机池等）。谁注册，谁被跑；只装一个包时
 * 就只跑那一个包的自检，完全独立。
 *
 * <p>为什么在<b>真服务器</b>上跑而不是写单元测试：这些功能全都要摸到 {@code ServerLevel}、
 * 实体生成、掉落路径和广播，纯 mock 测不出「真的能用」。自检挂在 {@code SERVER_STARTED} 上，
 * 拿真实的 {@code overworld} 当实验场。
 *
 * <p>触发方式：任一已安装包的配置里把 {@code selfTestRolls} 设成大于 0 的数（比如 200），
 * 开服时就会跑一遍；跑完改回 0 即可。
 *
 * <p>自检期间 {@link SessionStats} 是暂停的 —— 几千次假掉落不该污染「本局战绩」。
 */
public final class SelfTest {
	/** 自检上下文：所有步骤共享同一份世界 / 坐标 / 服务器 / 掷骰次数。 */
	public static final class Context {
		public final ServerLevel level;
		public final BlockPos pos;
		public final MinecraftServer server;
		public final long rolls;

		Context(ServerLevel level, BlockPos pos, MinecraftServer server, long rolls) {
			this.level = level;
			this.pos = pos;
			this.server = server;
			this.rolls = rolls;
		}
	}

	/** 自动自检只跑一次（同进程重开服也只跑一次，避免刷屏）。 */
	private static final AtomicBoolean AUTO_RAN = new AtomicBoolean();

	private static int passed;
	private static int failed;

	/** 子模块注册进来的自检步骤。 */
	public static final class Step {
		public final String name;
		final java.util.function.Consumer<Context> action;

		Step(String name, java.util.function.Consumer<Context> action) {
			this.name = name;
			this.action = action;
		}
	}

	/** 各包注册的自检步骤容器。 */
	public static final java.util.List<Step> STEPS = new java.util.ArrayList<>();

	/** 由子模块调用：注册一项自检步骤。 */
	public static void registerStep(String name, java.util.function.Consumer<Context> action) {
		STEPS.add(new Step(name, action));
	}

	/** 自检开始前的环境准备钩子（各包注册：关播报 / 暂停统计 / 重置随机池）。 */
	public static final java.util.List<Runnable> BEFORE_RUN = new java.util.ArrayList<>();

	/** 自检结束后的恢复钩子（各包注册：恢复播报 / 恢复统计 / 清理测试残留）。 */
	public static final java.util.List<Runnable> AFTER_RUN = new java.util.ArrayList<>();

	public static void onBeforeRun(Runnable r) {
		BEFORE_RUN.add(r);
	}

	public static void onAfterRun(Runnable r) {
		AFTER_RUN.add(r);
	}

	/**
	 * 注册开服自检触发器。各包入口在 onInitialize 里调用，传入「本包配置的 selfTestRolls」；
	 * 任一包的值 &gt; 0 时开服自动跑一遍（整个进程只跑一次）。
	 */
	public static void register(java.util.function.IntSupplier rollsSupplier) {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			int rolls = rollsSupplier.getAsInt();
			if (rolls <= 0) {
				return;
			}

			if (AUTO_RAN.compareAndSet(false, true)) {
				run(server, rolls);
			}
		});
	}

	/** 跑一遍全部自检，返回失败条数（0 = 全过）。 */
	public static int run(MinecraftServer server, int rolls) {
		passed = 0;
		failed = 0;

		Yg.LOGGER.info("[yg] ===== 自检开始（每项掷 {} 次）=====", rolls);

		ServerLevel level = server.overworld();
		BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(0, 64, 0));

		// 自检期间的假掉落不进「本局战绩」
		SessionStats.pause();

		try {
			for (Runnable r : BEFORE_RUN) {
				r.run();
			}

			Context ctx = new Context(level, pos, server, Math.max(1L, rolls));
			for (Step step : STEPS) {
				try {
					step.action.accept(ctx);
				} catch (Throwable t) {
					failed++;
					Yg.LOGGER.error("[yg] 自检步骤「{}」抛异常", step.name, t);
				}
			}
		} catch (Throwable error) {
			failed++;
			Yg.LOGGER.error("[yg] 自检过程中抛异常", error);
		} finally {
			for (Runnable r : AFTER_RUN) {
				try {
					r.run();
				} catch (Throwable t) {
					Yg.LOGGER.warn("[yg] 自检恢复钩子抛异常", t);
				}
			}

			SessionStats.resume();
		}

		if (failed == 0) {
			Yg.LOGGER.info("[yg] ===== 自检结束：{} 项全部通过 =====", passed);
		} else {
			Yg.LOGGER.error("[yg] ===== 自检结束：通过 {} 项，失败 {} 项 =====", passed, failed);
		}

		return failed;
	}

	/** 自检用：在指定位置生成一个盔甲架（有护甲槽与属性表，测诅咒足够了）。 */
	public static ArmorStand spawnArmorStand(ServerLevel level, BlockPos pos) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:armor_stand"));
		if (!(type instanceof EntityType)) {
			return null;
		}

		@SuppressWarnings("unchecked")
		EntityType<? extends ArmorStand> as = (EntityType<? extends ArmorStand>) type;
		ArmorStand stand = new ArmorStand(as, level);
		stand.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		level.addFreshEntity(stand);
		return stand;
	}


	/** 自检辅助：取一个原版附魔的 Holder。 */
	public static Holder<Enchantment> vanillaEnchant(ServerLevel level, String id) {
		return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.get(net.minecraft.resources.ResourceKey.create(
						net.minecraft.core.registries.Registries.ENCHANTMENT,
						Identifier.parse(id))).orElse(null);
	}


	// ------------------------------------------------------------ 工具

	// ------------------------------------------------------------ 工具

	public static void check(String name, boolean ok, String detail) {
		if (ok) {
			passed++;
			Yg.LOGGER.info("[yg] 自检 ✅ {} —— {}", name, detail);
		} else {
			failed++;
			Yg.LOGGER.error("[yg] 自检 ❌ {} —— {}", name, detail);
		}
	}

	/** 把 double 写成好看的样子（去掉多余的 0）。 */
	public static String trim(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1.0E9D) {
			return String.valueOf((long) value);
		}

		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
