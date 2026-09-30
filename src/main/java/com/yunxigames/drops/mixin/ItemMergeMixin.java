package com.yunxigames.drops.mixin;

import com.yunxigames.DropMerger;
import com.yunxigames.DropsConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ③-a 掉落物自动合并：挂在「实体进入世界」的那一刻。
 *
 * <p>为什么挂 {@code ServerLevel#addFreshEntity} 而不是别的点：
 * 这是所有掉落物（玩家丢的、生物掉的、方块掉的、模组掉的）唯一的共同入口，
 * 挂在这里才能做到「地上同类物品真的会并成一堆」。
 *
 * <p>只有<b>被完全吸收</b>的一件才会取消生成（返回 true），
 * 所以合并失败或只并进去一部分时，剩下的照常生成 —— 物品不会凭空消失。
 */
@Mixin(ServerLevel.class)
public class ItemMergeMixin {

	@Inject(
			method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
			at = @At("HEAD"),
			cancellable = true)
	private void yg$mergeIntoNeighbor(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!(entity instanceof ItemEntity item)) {
			return;
		}

		if (!DropsConfig.get().dropMergeEnabled) {
			return;
		}

		ServerLevel self = (ServerLevel) (Object) this;

		if (DropMerger.absorb(self, item)) {
			// 已经被完全并进别的堆里，这一件不用再生成。
			// 返回 true 而不是 false：物品确实到手了，调用方不该把它当成失败。
			cir.setReturnValue(true);
			return;
		}

		// 没并光（或没得并）：登记进同刻窗口，让这一批后续的掉落物能并到它身上。
		DropMerger.track(self, item);
	}
}
