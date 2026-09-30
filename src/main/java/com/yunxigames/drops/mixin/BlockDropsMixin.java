package com.yunxigames.drops.mixin;

import com.yunxigames.DropRandomizer;
import com.yunxigames.Feedback;
import com.yunxigames.HarvestEvents;
import com.yunxigames.DropsConfig;
import com.yunxigames.TntIgnition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 接管方块掉落：原版掉落表算完之后被整个丢弃，换成我们掷出来的结果。
 *
 * <p>{@code Block#getDrops} 有两个互不调用的重载，各自对应一条调用链：
 * <ul>
 *   <li>{@code getDrops(state, level, pos, blockEntity)} —— 由 {@code dropResources} 调用，
 *       爆炸、活塞之类没有「破坏者」的路径走这条；</li>
 *   <li>{@code getDrops(state, level, pos, blockEntity, entity, tool)} —— 同样由
 *       {@code dropResources} 调用，玩家挖掘走这条，{@code entity} 就是玩家。</li>
 * </ul>
 *
 * <p>两个都注入（而不是注入更底层的 {@code popResource}），是为了保证「一次破坏只掷一次骰子」：
 * 原版一个方块可能掉落多个物品，如果拦在 {@code popResource} 上，一次挖掘会被替换成好几份随机结果。
 */
@Mixin(Block.class)
public class BlockDropsMixin {

	/** 无破坏者的那条路径（爆炸 / 活塞 / 其它）。没有玩家，所以不参与保底。 */
	@Inject(
			method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)Ljava/util/List;",
			at = @At("RETURN"),
			cancellable = true)
	private static void yg$replaceDrops(BlockState state, ServerLevel level, BlockPos pos,
			BlockEntity blockEntity, CallbackInfoReturnable<List<ItemStack>> cir) {
		if (!DropsConfig.get().enableBlockDrops
				|| DropRandomizer.isDroplessBlock(state)) {
			return;
		}

		// 植被类（花草 / 枯叶堆 / 水草）：什么都不掉 —— 连原版的小麦种子也不掉
		if (DropRandomizer.isNoLootPlant(state)) {
			cir.setReturnValue(java.util.List.of());
			return;
		}

		cir.setReturnValue(DropRandomizer.rollBlockDrop(level, pos, null));
	}

	/**
	 * 玩家挖掘的路径，能拿到破坏者实体。
	 *
	 * <p>注入在 {@code RETURN} 而不是 {@code HEAD}：这样能拿到原版算好的掉落结果，
	 * 用它当作保底的键 —— 于是别的模组改了掉落表时，保底跟随的仍然是「真正的原物品」。
	 */
	@Inject(
			method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;",
			at = @At("RETURN"),
			cancellable = true)
	private static void yg$replaceDropsWithBreaker(BlockState state, ServerLevel level, BlockPos pos,
			BlockEntity blockEntity, Entity breaker, ItemInstance tool, CallbackInfoReturnable<List<ItemStack>> cir) {
		if (!DropsConfig.get().enableBlockDrops
				|| DropRandomizer.isDroplessBlock(state)) {
			return;
		}

		// 植被类：什么都不掉
		if (DropRandomizer.isNoLootPlant(state)) {
			cir.setReturnValue(java.util.List.of());
			return;
		}

		Player player = breaker instanceof Player p ? p : null;

		// v1.11 ②：挖到「能引燃」的方块时，有几率朝四周甩出点燃的 TNT。
		// 只认玩家破坏 —— TNT 炸掉的方块 breaker 是 null，这样就不会「炸 → 掉 → 再炸」无限连锁。
		if (player != null) {
			TntIgnition.tryIgnite(level, pos, state, player, level.getRandom());
		}

		// v1.11 ④-a：徒手挖木头计数（拿斧头或挖到别的方块就断掉连击）
		HarvestEvents.onBlockBroken(level, pos, state, player, tool);

		// 保底：这一把轮到该物品的「原物品」，什么都不做，让原版掉落照常执行
		List<ItemStack> vanilla = cir.getReturnValue();

		if (DropRandomizer.isPityDropForItem(level, breaker, DropRandomizer.firstItemId(vanilla))) {
			// 保底原本是个「暗机制」—— 玩家不知道再挖两下就能拿到原物品，期待感全浪费了。
			// 这里补一层可见反馈。
			if (vanilla != null && !vanilla.isEmpty() && !vanilla.get(0).isEmpty()) {
				Feedback.pity(level, pos, vanilla.get(0), breaker);
			}
			return;
		}

		cir.setReturnValue(DropRandomizer.rollBlockDrop(level, pos, breaker));
	}
}
