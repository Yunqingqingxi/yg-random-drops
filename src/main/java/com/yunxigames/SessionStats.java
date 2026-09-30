package com.yunxigames;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>本局</b>（= 这一次服务器进程）的掉落统计。
 *
 * <p>玩法是「一命打到末影龙就收工」，所以这里刻意什么都不持久化：这些数字只活在内存里，
 * 通关那一刻一次性摊开给全服看，然后就完成了它的使命。世界时间约等于这一局的进度，
 * 不需要存档、不需要数据库、也不怕重开服。
 *
 * <p>为什么要另开一套计数器，而不是直接用 {@link DropRandomizer} 里那些：
 * 自检在开服时会掷几千次假掉落，那些数字必须排除在外，所以这里有 {@link #pause()}。
 * 而 {@code DropRandomizer} 里原有的计数器是自检自己要读的，不能停。
 */
public final class SessionStats {
	private SessionStats() {
	}

	/** 自检期间为 true —— 所有计数直接丢弃。 */
	private static volatile boolean paused = false;

	private static final AtomicLong DROP_EVENTS = new AtomicLong();
	private static final AtomicLong ITEMS_GIVEN = new AtomicLong();
	private static final AtomicLong MOB_ROLLS = new AtomicLong();
	private static final AtomicLong MOBS_SPAWNED = new AtomicLong();
	private static final AtomicLong NOTHING_ROLLS = new AtomicLong();
	private static final AtomicLong JACKPOTS = new AtomicLong();
	private static final AtomicLong TREASURE_GIVEN = new AtomicLong();
	private static final AtomicLong ELITES = new AtomicLong();
	private static final AtomicLong KILL_EFFECTS = new AtomicLong();
	private static final AtomicLong KILL_EFFECTS_HARMFUL = new AtomicLong();

	private static volatile long startedAtMillis = System.currentTimeMillis();

	// ------------------------------------------------------------ 暂停 / 重置

	/** 自检开始前调用。 */
	public static void pause() {
		paused = true;
	}

	/** 自检结束后调用（建议放在 finally 里，别让自检抛异常把统计永久冻住）。 */
	public static void resume() {
		paused = false;
	}

	/** 开服时清零，让「本局」名副其实（同进程重开服也算新的一局）。 */
	public static void reset() {
		DROP_EVENTS.set(0L);
		ITEMS_GIVEN.set(0L);
		MOB_ROLLS.set(0L);
		MOBS_SPAWNED.set(0L);
		NOTHING_ROLLS.set(0L);
		JACKPOTS.set(0L);
		TREASURE_GIVEN.set(0L);
		ELITES.set(0L);
		KILL_EFFECTS.set(0L);
		KILL_EFFECTS_HARMFUL.set(0L);
		startedAtMillis = System.currentTimeMillis();
	}

	private static boolean active() {
		return !paused;
	}

	// ------------------------------------------------------------ 记账

	/** 每发生一次掉落事件（挖一个方块 / 死一只生物）记一次。 */
	public static void dropEvent() {
		if (active()) {
			DROP_EVENTS.incrementAndGet();
		}
	}

	/** 这次掉落实际给了玩家几件物品。 */
	public static void itemsGiven(int count) {
		if (active() && count > 0) {
			ITEMS_GIVEN.addAndGet(count);
		}
	}

	/** 随机数判中了「生物」分支（生成成不成功另算）。 */
	public static void mobRoll() {
		if (active()) {
			MOB_ROLLS.incrementAndGet();
		}
	}

	/** 实际爆出来几只生物。 */
	public static void mobsSpawned(int count) {
		if (active() && count > 0) {
			MOBS_SPAWNED.addAndGet(count);
		}
	}

	/** 落在「什么都不掉」那一段。 */
	public static void nothing() {
		if (active()) {
			NOTHING_ROLLS.incrementAndGet();
		}
	}

	/** 触发了一次暴击大爆。 */
	public static void jackpot() {
		if (active()) {
			JACKPOTS.incrementAndGet();
		}
	}

	/** 通关宝藏雨撒出去了几件。 */
	public static void treasureGiven(int count) {
		if (active() && count > 0) {
			TREASURE_GIVEN.addAndGet(count);
		}
	}

	/** 爆出来一只精英怪。 */
	public static void elite() {
		if (active()) {
			ELITES.incrementAndGet();
		}
	}

	/** 击杀生物拿到了一次药水效果。 */
	public static void killEffect() {
		if (active()) {
			KILL_EFFECTS.incrementAndGet();
		}
	}

	/** 击杀生物拿到的那次药水效果是负面的。 */
	public static void killEffectHarmful() {
		if (active()) {
			KILL_EFFECTS_HARMFUL.incrementAndGet();
		}
	}

	// ------------------------------------------------------------ 读

	public static long dropEvents() {
		return DROP_EVENTS.get();
	}

	public static long itemsGiven() {
		return ITEMS_GIVEN.get();
	}

	public static long mobRolls() {
		return MOB_ROLLS.get();
	}

	public static long mobsSpawned() {
		return MOBS_SPAWNED.get();
	}

	public static long nothingRolls() {
		return NOTHING_ROLLS.get();
	}

	public static long jackpots() {
		return JACKPOTS.get();
	}

	public static long treasureGiven() {
		return TREASURE_GIVEN.get();
	}

	/** 本局爆出过几只精英怪。 */
	public static long elites() {
		return ELITES.get();
	}

	/** 本局击杀生物拿到过几次药水效果。 */
	public static long killEffects() {
		return KILL_EFFECTS.get();
	}

	/** 其中几次是负面的。 */
	public static long killEffectsHarmful() {
		return KILL_EFFECTS_HARMFUL.get();
	}

	/** 这一局打了多久（毫秒）。 */
	public static long elapsedMillis() {
		return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
	}

	// ------------------------------------------------------------ 结算播报

	/**
	 * 通关结算要播的那几行。
	 *
	 * <p>数字全部来自这一局的真实掉落，不含自检；物品栏里本来就有多少东西不在此列。
	 */
	public static List<String> report() {
		List<String> lines = new ArrayList<>();

		lines.add("本局用时 " + formatDuration(elapsedMillis()));
		lines.add("掉落事件 " + DROP_EVENTS.get() + " 次");
		lines.add("拿到物品 " + ITEMS_GIVEN.get() + " 件"
				+ (TREASURE_GIVEN.get() > 0 ? "（其中通关宝藏 " + TREASURE_GIVEN.get() + " 件）" : ""));
		lines.add("爆出生物 " + MOBS_SPAWNED.get() + " 只（生物分支 " + MOB_ROLLS.get() + " 次）");
		lines.add("什么都没掉 " + NOTHING_ROLLS.get() + " 次");
		lines.add("暴击大爆 " + JACKPOTS.get() + " 次");
		lines.add("精英怪 " + ELITES.get() + " 只");
		lines.add("击杀奖励（药水效果）" + KILL_EFFECTS.get() + " 次"
				+ (KILL_EFFECTS_HARMFUL.get() > 0
						? "（其中 " + KILL_EFFECTS_HARMFUL.get() + " 次是负面效果）"
						: ""));

		return lines;
	}

	/** 把毫秒数写成「1 小时 23 分」这种给人看的样子。 */
	private static String formatDuration(long millis) {
		long totalSeconds = millis / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;

		if (hours > 0L) {
			return hours + " 小时 " + minutes + " 分";
		}

		if (minutes > 0L) {
			return minutes + " 分 " + (totalSeconds % 60L) + " 秒";
		}

		return totalSeconds + " 秒";
	}
}
