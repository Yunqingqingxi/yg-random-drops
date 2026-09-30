package com.yunxigames;

import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 「刚落地僵直」管理器。
 *
 * <p>掉落爆出来的生物先禁用 AI 若干刻，让玩家有时间反应 —— 否则你正在洞里挖矿，
 * 突然贴脸爆出三只僵尸，跑都没地方跑。僵直期间生物不动也不攻击，但**可以被打**。
 *
 * <p>实现上用 tick 倒计时而不是调度器：逻辑简单、不会泄漏，服务器一停自然清空。
 */
public final class MobStun {
	private static final List<Entry> PENDING = new ArrayList<>();

	private MobStun() {
	}

	/** 让这只生物僵直 {@code ticks} 刻。 */
	public static void stun(Mob mob, int ticks) {
		if (mob == null || ticks <= 0) {
			return;
		}

		mob.setNoAi(true);
		mob.setTarget(null);
		PENDING.add(new Entry(mob, ticks));
	}

	/** 每个游戏刻调用一次。 */
	public static void tick() {
		if (PENDING.isEmpty()) {
			return;
		}

		Iterator<Entry> iterator = PENDING.iterator();

		while (iterator.hasNext()) {
			Entry entry = iterator.next();

			// 生物已经死了 / 被移除了，就不用管了
			if (entry.mob.isRemoved() || !entry.mob.isAlive()) {
				iterator.remove();
				continue;
			}

			if (--entry.ticks <= 0) {
				entry.mob.setNoAi(false);
				iterator.remove();
			}
		}
	}

	/** 自检用：当前还有多少只在僵直中。 */
	public static int pendingCount() {
		return PENDING.size();
	}

	private static final class Entry {
		private final Mob mob;
		private int ticks;

		private Entry(Mob mob, int ticks) {
			this.mob = mob;
			this.ticks = ticks;
		}
	}
}
