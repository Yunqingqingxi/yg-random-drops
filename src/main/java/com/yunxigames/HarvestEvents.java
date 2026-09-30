package com.yunxigames;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ④-a 徒手挖木头事件。
 *
 * <p>连续不用斧头挖掉 {@code bareHandLogThreshold}（默认 50）个原木，就地补一份木材。
 * 原木徒手挖极慢，能连挖 50 个的人值得奖励；而一拿上斧头计数就归零，
 * 所以这条只在「还没工具」或「工具没了」的那段时间有意义 —— 它补的正是那段最难受的时间。
 *
 * <p>计数按玩家分别记，挖到别的方块也归零（所以是「连着挖」而不是「累计挖」）。
 */
public final class HarvestEvents {
	private static final Map<UUID, Integer> LOG_STREAK = new ConcurrentHashMap<>();

	private HarvestEvents() {
	}

	/**
	 * 玩家破坏方块时调用。
	 *
	 * @param tool 挖掘时手里的工具（{@code ItemInstance} 形式），可能为 {@code null}
	 */
	public static void onBlockBroken(ServerLevel level, BlockPos pos, BlockState state,
			net.minecraft.world.entity.player.Player player, ItemInstance tool) {
		DropsConfig config = DropsConfig.get();

		if (!config.enableBareHandLogEvent || player == null || state == null || level == null) {
			return;
		}

		// 不是原木 → 断掉连击
		if (!state.is(BlockTags.LOGS)) {
			LOG_STREAK.remove(player.getUUID());
			return;
		}

		// 有斧头 → 也断掉连击（有工具就不算「徒手」了）
		if (hasAxe(tool)) {
			LOG_STREAK.remove(player.getUUID());
			return;
		}

		int streak = advance(player.getUUID());

		if (streak < config.bareHandLogThreshold) {
			return;
		}

		LOG_STREAK.remove(player.getUUID());

		net.minecraft.world.item.Item logItem = state.getBlock().asItem();

		if (logItem == null || logItem == Items.AIR) {
			return;
		}

		ItemStack reward = new ItemStack(logItem, Math.min(64, Math.max(1, config.bareHandLogRewardCount)));
		Block.popResource(level, pos, reward);
		Feedback.bareHandReward(level, pos, reward, player);
	}

	/** 手里拿的是不是斧头（空手 / 别的工具都算「徒手」）。 */
	public static boolean hasAxe(ItemInstance tool) {
		return tool != null && tool.count() > 0 && tool.is(ItemTags.AXES);
	}

	/** 推进某个玩家的连击计数，返回推进后的值。 */
	public static int advance(UUID id) {
		return LOG_STREAK.merge(id, 1, Integer::sum);
	}

	/** 当前连击数（自检用）。 */
	public static int streakOf(UUID id) {
		return LOG_STREAK.getOrDefault(id, 0);
	}

	/** 清掉某个玩家的连击（自检用完清理）。 */
	public static void clearStreak(UUID id) {
		LOG_STREAK.remove(id);
	}

	/** 关服时清空。 */
	public static void reset() {
		LOG_STREAK.clear();
	}
}
