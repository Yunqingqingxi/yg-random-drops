package com.yunxigames;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ③-a 掉落物自动合并堆叠。
 *
 * <p>掉落物刚要生成到世界里的那一刻，先看看附近有没有同类的一堆：
 * 有就并进去，直到堆满（64 或物品自身上限）。
 *
 * <p>为什么放在「生成瞬间」而不是定期扫描全场：定期扫要遍历世界里所有掉落物，
 * 大爆炸时开销不可控；在生成点做一次小范围查询则是 O(附近几个)。
 * 一次 TNT 连锁炸掉几百个方块时，原本会掉出几百个掉落物实体，合并后只剩几个。
 *
 * <h2>候选从哪来（两条腿走路）</h2>
 * <ol>
 *   <li><b>同刻窗口（主）</b>：自己维护一份「刚掉出来的掉落物」短名单。
 *       方块破坏与爆炸的掉落都是在<b>同一刻</b>里连续调用生成接口的，
 *       这一批彼此之间用世界查询是看不见的 —— 见下条。</li>
 *   <li><b>世界查询（兜底）</b>：{@code level.getEntities(...)} 走「区块区段存储」，
 *       但它只遍历 {@code Visibility.isAccessible()} 的区段。
 *       实测：服务器刚起、没有玩家时，新生成的区段拿不到 accessible 状态，
 *       查询会稳定返回空。所以它只能当兜底，不能当主力。</li>
 * </ol>
 *
 * <p><b>不会出现物品凭空消失</b>：只有被完全吸收（数量减到 0）的那一件才会取消生成，
 * 剩下多少就照样生成多少。
 */
public final class DropMerger {
	/** 累计合并成功的件数（只用于自检统计）。 */
	private static final AtomicLong MERGED = new AtomicLong();

	/** 同刻窗口保留多久（刻）。5 秒足够覆盖一次爆炸连锁的全部掉落。 */
	private static final long WINDOW_TICKS = 100L;

	/** 单个维度窗口最多记多少件，防止极端爆炸把内存顶起来。 */
	private static final int WINDOW_CAP = 512;

	/** 每个维度一份窗口，键用同一性比较 —— 维度对象就该按引用相等来认。 */
	private static final IdentityHashMap<ServerLevel, ArrayDeque<Entry>> WINDOWS = new IdentityHashMap<>();

	private DropMerger() {
	}

	/** 窗口里的一条：某个掉落物实体，以及它入世界时的游戏刻。 */
	private record Entry(ItemEntity entity, long tick) {
	}

	/**
	 * 登记一件「马上要入世界」的掉落物，供同一刻后续的掉落物合并。
	 *
	 * <p>必须在 {@link #absorb} 判定之后调用：已经被并掉的那件不用登记。
	 */
	public static void track(ServerLevel level, ItemEntity entity) {
		DropsConfig config = DropsConfig.get();

		if (!config.dropMergeEnabled || level == null || entity == null) {
			return;
		}

		window(level).addLast(new Entry(entity, level.getGameTime()));
	}

	/**
	 * 尝试把 {@code incoming} 并进附近已有的同类掉落物。
	 *
	 * @return true 表示它已经被完全吸收 —— 调用方应当取消这次生成
	 */
	public static boolean absorb(ServerLevel level, ItemEntity incoming) {
		DropsConfig config = DropsConfig.get();

		if (!config.dropMergeEnabled || level == null || incoming == null) {
			return false;
		}

		ItemStack stack = incoming.getItem();

		if (stack.isEmpty()) {
			return false;
		}

		int ownMax = Math.max(1, stack.getMaxStackSize());

		if (stack.getCount() >= ownMax) {
			return false;
		}

		double radius = Math.max(0.0D, config.dropMergeRadius);
		AABB box = incoming.getBoundingBox().inflate(radius, Math.max(1.0D, radius), radius);

		int scanned = 0;

		for (ItemEntity candidate : candidates(level, incoming, box)) {
			if (scanned++ >= config.dropMergeMaxScan) {
				break;
			}

			if (candidate == incoming || candidate.isRemoved()) {
				continue;
			}

			ItemStack target = candidate.getItem();

			if (target.isEmpty() || !ItemEntity.areMergable(stack, target)) {
				continue;
			}

			int targetMax = Math.max(1, target.getMaxStackSize());
			int space = targetMax - target.getCount();

			if (space <= 0) {
				continue;
			}

			int moved = Math.min(space, stack.getCount());
			candidate.setItem(target.copyWithCount(target.getCount() + moved));
			stack.shrink(moved);

			// 刚掉出来的东西通常有拾取延迟（防止玩家自己丢的东西立刻被吸回去）。
			// 并进一堆「已经能捡」的物品时，给这堆补一点延迟，免得刚挖到的东西瞬间回到背包。
			if (incoming.hasPickUpDelay() && !candidate.hasPickUpDelay()) {
				candidate.setPickUpDelay(10);
			}

			MERGED.addAndGet(moved);

			if (stack.isEmpty()) {
				return true;
			}
		}

		return stack.isEmpty();
	}

	/**
	 * 汇总候选：同刻窗口 + 世界查询，按实体同一性去重。
	 *
	 * <p>世界查询在无头服务器上常常返回空，但面对「已经躺在地上很久的那一堆」更可靠，
	 * 所以两条腿都要。
	 */
	private static List<ItemEntity> candidates(ServerLevel level, ItemEntity incoming, AABB box) {
		List<ItemEntity> found = new ArrayList<>();
		Set<ItemEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		for (Entry entry : window(level)) {
			ItemEntity entity = entry.entity();

			if (entity.isRemoved() || entity.level() != level) {
				continue;
			}

			if (entity.getBoundingBox().intersects(box) && seen.add(entity)) {
				found.add(entity);
			}
		}

		for (Entity entity : level.getEntities(incoming, box, entity -> true)) {
			if (entity instanceof ItemEntity item && seen.add(item)) {
				found.add(item);
			}
		}

		return found;
	}

	/** 取该维度的窗口，顺手把过期条目清掉。 */
	private static ArrayDeque<Entry> window(ServerLevel level) {
		ArrayDeque<Entry> window = WINDOWS.computeIfAbsent(level, key -> new ArrayDeque<>());
		long now = level.getGameTime();

		while (!window.isEmpty()) {
			Entry head = window.peekFirst();

			if (head.entity().isRemoved() || now - head.tick() > WINDOW_TICKS) {
				window.pollFirst();
			} else {
				break;
			}
		}

		while (window.size() > WINDOW_CAP) {
			window.pollFirst();
		}

		return window;
	}

	/** 清空所有窗口（关服时调用，别把上一局的实体留在内存里）。 */
	public static void reset() {
		WINDOWS.clear();
	}

	/** 累计合并件数（自检用）。 */
	public static long merged() {
		return MERGED.get();
	}

	/** 当前窗口里的条目数（自检用）。 */
	public static int windowSize(ServerLevel level) {
		return window(level).size();
	}
}
