package com.yunxigames;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ③-b 定时播报「这段时间掉了些什么」。
 *
 * <p>随机掉落的乐趣有一半是给别人看的 —— 别人挖到钻石这件事如果没人知道，就只剩自己偷着乐。
 * 所以这里按 {@code dropSummaryIntervalSeconds}（默认 2 分钟）把掉落汇总广播一次。
 *
 * <p>只统计<b>本模组替换出来的掉落</b>（{@code DropRandomizer} 那条路径），
 * 不含原版掉落，也不含保底放行的原物品 —— 报的是「随机给了什么」。
 *
 * <p>自检期间由 {@link #pause()} 暂停，几千次假掉落不该刷屏。
 */
public final class DropTally {
	private static final Map<Identifier, Integer> TALLY = new ConcurrentHashMap<>();
	private static final AtomicLong TOTAL = new AtomicLong();
	private static final AtomicBoolean PAUSED = new AtomicBoolean();

	/** 下一次播报的墙钟时刻；0 表示还没排（开服第一分钟不播）。 */
	private static volatile long nextBroadcastAt;

	private DropTally() {
	}

	/** 记一件掉落。 */
	public static void record(Item item, int count) {
		if (item == null || count <= 0 || PAUSED.get()) {
			return;
		}

		Identifier id = BuiltInRegistries.ITEM.getKey(item);

		if (id == null) {
			return;
		}

		TALLY.merge(id, count, Integer::sum);
		TOTAL.addAndGet(count);
	}

	/** 每个游戏刻调用一次；内部只在整秒推进。 */
	public static void onTick(MinecraftServer server) {
		DropsConfig config = DropsConfig.get();

		if (!config.dropSummaryEnabled || server == null) {
			return;
		}

		long now = System.currentTimeMillis();
		long interval = Math.max(10, config.dropSummaryIntervalSeconds) * 1000L;

		if (nextBroadcastAt == 0L) {
			nextBroadcastAt = now + interval;
			return;
		}

		if (now < nextBroadcastAt) {
			return;
		}

		nextBroadcastAt = now + interval;

		if (TALLY.isEmpty()) {
			return;
		}

		server.sendSystemMessage(summary(interval / 1000L, config));
		TALLY.clear();
		TOTAL.set(0L);
	}

	/** 自检用：暂停 / 恢复统计。 */
	public static void pause() {
		PAUSED.set(true);
	}

	public static void resume() {
		PAUSED.set(false);
	}

	/** 开服 / 关服时清零。 */
	public static void reset() {
		TALLY.clear();
		TOTAL.set(0L);
		nextBroadcastAt = 0L;
	}

	// ------------------------------------------------------------ 内部

	private static Component summary(long seconds, DropsConfig config) {
		List<Map.Entry<Identifier, Integer>> top = new ArrayList<>(TALLY.entrySet());
		top.sort(Comparator.<Map.Entry<Identifier, Integer>>comparingInt(Map.Entry::getValue).reversed()
				.thenComparing(entry -> entry.getKey().toString()));

		int lines = Math.min(config.dropSummaryMaxLines, top.size());
		long minutes = Math.max(1, Math.round(seconds / 60.0D));

		StringBuilder text = new StringBuilder("§6[随机掉落] §e掉落统计 §7(最近 ")
				.append(minutes).append(" 分钟，共 ").append(TOTAL.get()).append(" 件)");

		for (int i = 0; i < lines; i++) {
			Map.Entry<Identifier, Integer> entry = top.get(i);
			text.append("\n§f  · ").append(displayName(entry.getKey()))
					.append(" §7×").append(entry.getValue());
		}

		if (DropsConfig.get().debugLog) {
			Yg.LOGGER.info("[yg] 掉落统计播报：{} 种 {} 件", TALLY.size(), TOTAL.get());
		}

		return Component.literal(text.toString());
	}

	private static String displayName(Identifier id) {
		Item item = BuiltInRegistries.ITEM.getValue(id);

		if (item == null) {
			return id.toString();
		}

		return new ItemStack(item).getHoverName().getString();
	}
}
