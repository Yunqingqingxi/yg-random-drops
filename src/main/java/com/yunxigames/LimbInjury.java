package com.yunxigames;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ④-b 断肢受伤（断腿 / 断手）。
 *
 * <p>用<b>属性修饰符</b>实现，而不是药水效果：
 * <ul>
 *   <li>断腿 → {@code MOVEMENT_SPEED} 乘一个负百分比：断一条 -{@code limbOnePenalty}、
 *       断两条 -{@code limbTwoPenalty}；</li>
 *   <li>断手 → {@code BLOCK_BREAK_SPEED} 同样扣百分比（同时影响攻击与挖掘的手感）。</li>
 * </ul>
 * 这样能精确落在配置的 -30% / -80% 上，而不是像缓慢 / 挖掘疲劳那样被等级粒度卡住。
 *
 * <p>状态只存在内存里，到期自动解除；玩家死亡或下线也会当场解除，
 * 所以不需要任何持久化 —— 一局就是一局。
 */
public final class LimbInjury {
	/** 断腿的修饰符 id。 */
	private static final Identifier LEG_ID = Identifier.fromNamespaceAndPath(Yg.MOD_ID, "leg_injury");

	/** 断手的修饰符 id。 */
	private static final Identifier ARM_ID = Identifier.fromNamespaceAndPath(Yg.MOD_ID, "arm_injury");

	private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

	private LimbInjury() {
	}

	/** 一次受伤的记录：断了几条腿、几只手、什么时候到期。 */
	public record State(int legs, int arms, long expireAtMillis) {
		public boolean isEmpty() {
			return legs <= 0 && arms <= 0;
		}

		public boolean expired(long now) {
			return now >= expireAtMillis;
		}

		/** 还剩多少秒（已过期为 0）。 */
		public int remainingSeconds(long now) {
			return expired(now) ? 0 : (int) Math.ceil((expireAtMillis - now) / 1000.0D);
		}
	}

	/**
	 * 给某个活体挂上断肢。
	 *
	 * @param legs 断几条腿（0-2）
	 * @param arms 断几只手（0-2）
	 * @param seconds 持续多少秒
	 */
	public static void injure(LivingEntity entity, int legs, int arms, int seconds) {
		if (entity == null || entity.level().isClientSide() || (legs <= 0 && arms <= 0)) {
			return;
		}

		DropsConfig config = DropsConfig.get();
		UUID id = entity.getUUID();
		long expire = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;

		STATES.put(id, new State(Math.min(2, legs), Math.min(2, arms), expire));
		apply(entity, Math.min(2, legs), Math.min(2, arms), config);

		if (entity instanceof ServerPlayer player) {
			Feedback.limbInjury(player, Math.min(2, legs), Math.min(2, arms), seconds, config);
		}
	}

	/**
	 * 每个游戏刻调用：到期解除、玩家重生 / 换维度后重新贴合修饰符。
	 *
	 * <p>玩家死后是<b>新的实体实例</b>，修饰符不会跟着过来，所以这里要补一次 ——
	 * 否则「重生就痊愈」会让断肢变成笑话。
	 */
	public static void tick(MinecraftServer server) {
		if (server == null || STATES.isEmpty()) {
			return;
		}

		DropsConfig config = DropsConfig.get();
		long now = System.currentTimeMillis();

		for (Map.Entry<UUID, State> entry : STATES.entrySet()) {
			UUID id = entry.getKey();
			State state = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(id);

			if (state.expired(now) || player == null || player.isDeadOrDying() || player.isRemoved()) {
				STATES.remove(id);
				clear(player);
				continue;
			}

			if (!hasModifier(player, LEG_ID)) {
				apply(player, state.legs(), state.arms(), config);
			}
		}
	}

	/** 解除某个实体的断肢修饰符。 */
	public static void clear(LivingEntity entity) {
		if (entity == null) {
			return;
		}

		AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(LEG_ID);
		}

		AttributeInstance mining = entity.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		if (mining != null) {
			mining.removeModifier(ARM_ID);
		}
	}

	public static void clear(UUID id) {
		STATES.remove(id);
	}

	/** 关服时清空。 */
	public static void reset() {
		STATES.clear();
	}

	/** 当前状态（自检用）。 */
	public static State stateOf(UUID id) {
		return STATES.get(id);
	}

	/** 自检用：这个实体的腿有没有挂上修饰符。 */
	public static boolean hasLegModifier(LivingEntity entity) {
		return entity != null && hasModifier(entity, LEG_ID);
	}

	/** 自检用：这个实体的手有没有挂上修饰符。 */
	public static boolean hasArmModifier(LivingEntity entity) {
		return entity != null && hasModifier(entity, ARM_ID);
	}

	/** 自检用：断肢后的移速倍率（1 条腿 → 0.7，2 条腿 → 0.2）。 */
	public static double speedMultiplier(int legs, DropsConfig config) {
		return 1.0D - penalty(legs, config);
	}

	/** 自检用：断肢后的挖掘速度倍率。 */
	public static double miningMultiplier(int arms, DropsConfig config) {
		return 1.0D - penalty(arms, config);
	}

	// ------------------------------------------------------------ 内部

	private static double penalty(int limbs, DropsConfig config) {
		if (limbs <= 0) {
			return 0.0D;
		}

		return limbs >= 2 ? config.limbTwoPenalty : config.limbOnePenalty;
	}

	private static void apply(LivingEntity entity, int legs, int arms, DropsConfig config) {
		AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);

		if (speed != null) {
			if (legs > 0) {
				speed.addOrUpdateTransientModifier(new AttributeModifier(LEG_ID,
						-penalty(legs, config), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			} else {
				speed.removeModifier(LEG_ID);
			}
		}

		AttributeInstance mining = entity.getAttribute(Attributes.BLOCK_BREAK_SPEED);

		if (mining != null) {
			if (arms > 0) {
				mining.addOrUpdateTransientModifier(new AttributeModifier(ARM_ID,
						-penalty(arms, config), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			} else {
				mining.removeModifier(ARM_ID);
			}
		}
	}

	private static boolean hasModifier(LivingEntity entity, Identifier id) {
		AttributeInstance attribute = entity.getAttribute(
				LEG_ID.equals(id) ? Attributes.MOVEMENT_SPEED : Attributes.BLOCK_BREAK_SPEED);

		return attribute != null && attribute.hasModifier(id);
	}
}
