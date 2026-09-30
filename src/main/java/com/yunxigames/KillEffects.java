package com.yunxigames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * <b>击杀奖励：直接赋予随机药水效果。</b>
 *
 * <p>不产生药水物品 —— 效果当场生效。设计上有三条硬约束：
 *
 * <ol>
 *   <li><b>只能靠击杀生物获得</b>：挖方块、TNT、活塞一律不给。想要 buff 就得去打怪，
 *       这也正好补上「爆怪概率下调」之后打怪的动机；</li>
 *   <li><b>必须是玩家击杀</b>：生物互杀、摔死、烧死、自然死亡都不触发，
 *       否则玩家挂机看刷怪塔互殴就能白拿一身 buff；</li>
 *   <li><b>默认只给有益效果</b>：池子里就算混进了中毒/失明也会被跳过 ——
 *       「击杀奖励」不该变成惩罚。</li>
 * </ol>
 *
 * <p>效果池的每一项支持两种写法：{@code "minecraft:speed"}（用全局时长/等级）
 * 或 {@code "minecraft:speed;45;1"}（单独指定秒数与等级）。解析结果带缓存，
 * 写错的 id 只记一次日志。
 *
 * <p>挂的是 {@code ServerLivingEntityEvents.AFTER_DEATH}，和 {@link Finale}、{@link EliteMobs}
 * 用的是同一套事件，所以不需要再写 mixin。
 */
public final class KillEffects {
	private KillEffects() {
	}

	/** 解析后的一个效果条目。 */
	public record Entry(Holder<MobEffect> effect, int durationTicks, int amplifier, boolean harmful) {
	}

	/** 解析好的一对池子。 */
	public record Pools(List<Entry> beneficial, List<Entry> harmful) {
		boolean isEmpty() {
			return beneficial.isEmpty() && harmful.isEmpty();
		}

		int total() {
			return beneficial.size() + harmful.size();
		}
	}

	/**
	 * 缓存键。
	 *
	 * <p>必须把全局时长 / 等级也纳入键里 —— 只按 id 列表缓存的话，
	 * 玩家用 {@code /yg reload} 改完 {@code killEffectDurationSeconds}
	 * 却拿到旧时长的效果，是个很难查的坑。
	 */
	private record PoolKey(List<String> beneficialIds, List<String> harmfulIds,
			int beneficialSeconds, int beneficialAmplifier,
			int harmfulSeconds, int harmfulAmplifier, int maxSeconds) {
	}

	/** 缓存：配置 → 解析好的一对池子。 */
	private static final java.util.Map<PoolKey, Pools> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/** 配置重载后清空缓存（{@code /yg reload} 会调）。 */
	public static void resetCache() {
		CACHE.clear();
	}

	/** 累计发过多少次有益效果 / 负面效果（自检与结算播报用）。 */
	private static final AtomicLong GRANTED_BENEFICIAL = new AtomicLong();
	private static final AtomicLong GRANTED_HARMFUL = new AtomicLong();

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(KillEffects::onDeath);
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		DropsConfig config = DropsConfig.get();

		if (!config.enableKillEffects || config.killEffectChance <= 0.0D) {
			return;
		}

		// 玩家自己死了不给 —— 否则「被怪打死反而回血」这种荒唐事就出现了
		if (entity instanceof Player) {
			return;
		}

		// 必须是玩家击杀
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return;
		}

		if (!(entity.level() instanceof ServerLevel level)) {
			return;
		}

		// 可选：只在打敌对生物时给
		if (config.killEffectHostileOnly && !DropRandomizer.isHostile(entity.getType(), config)) {
			return;
		}

		if (ThreadLocalRandom.current().nextDouble() >= config.killEffectChance) {
			return;
		}

		grant(player, level, config);
	}

	/**
	 * 掷一个效果并直接作用在目标身上。
	 *
	 * <p>先按 {@code killEffectHarmfulChance} 决定这一把是「奖励」还是「代价」，
	 * 再从对应池子里等概率抽一个 —— 这是这个功能的核心赌注。
	 *
	 * <p>参数是 {@link LivingEntity} 而不是 {@link ServerPlayer}，是为了让自检能在
	 * 没有真人在线时用一只僵尸把这条路真的跑一遍 —— 动作栏那一步再单独判类型。
	 *
	 * @return 实际给到的效果条目；两个池子都空（配置写坏）时返回 {@code null}
	 */
	static Entry grant(LivingEntity target, ServerLevel level, DropsConfig config) {
		Pools pools = pools(config);

		if (pools.isEmpty() || target == null) {
			return null;
		}

		boolean harmful = pickHarmful(pools, config);
		List<Entry> pool = harmful ? pools.harmful() : pools.beneficial();

		if (pool.isEmpty()) {
			// 理论上不会走到：pickHarmful 已经保证选中的池子非空
			pool = harmful ? pools.beneficial() : pools.harmful();
			harmful = !harmful;
		}

		if (pool.isEmpty()) {
			return null;
		}

		Entry entry = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));

		// 已有同效果时取「更长的时长、更高的等级」，而不是互相覆盖掉
		MobEffectInstance existing = target.getEffect(entry.effect());
		int duration = entry.durationTicks();
		int amplifier = entry.amplifier();

		if (existing != null) {
			duration = Math.max(duration, existing.getDuration());
			amplifier = Math.max(amplifier, existing.getAmplifier());
		}

		target.addEffect(new MobEffectInstance(entry.effect(), duration, amplifier, false, true));

		if (harmful) {
			GRANTED_HARMFUL.incrementAndGet();
			SessionStats.killEffectHarmful();
		} else {
			GRANTED_BENEFICIAL.incrementAndGet();
			SessionStats.killEffect();
		}

		// 正负反馈要一眼分得清：紫色「击杀奖励」/ 红色「击杀代价」，音效也不同
		if (config.killEffectMessage && target instanceof ServerPlayer player) {
			String prefix = harmful ? "§c击杀代价" : "§d击杀奖励";

			player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
					prefix + " §7· §f" + displayName(entry)
							+ " §7(" + (duration / 20) + "秒)")));
		}

		if (level != null) {
			level.playSound(null, target.blockPosition(),
					harmful ? SoundEvents.WITCH_DRINK : SoundEvents.EXPERIENCE_ORB_PICKUP,
					SoundSource.PLAYERS, 0.8F, harmful ? 0.9F : 1.6F);
		}

		return entry;
	}

	/**
	 * 这一把该走负面池吗。
	 *
	 * <p>某一边空掉时会自动让位给另一边 —— 所以「只想要负面效果」这种极端配置
	 * 只要把有益池清空即可，不需要额外开关。
	 */
	static boolean pickHarmful(Pools pools, DropsConfig config) {
		if (pools.harmful().isEmpty()) {
			return false;
		}

		if (pools.beneficial().isEmpty()) {
			return true;
		}

		double chance = config.killEffectHarmfulChance;

		if (!(chance > 0.0D)) {
			return false;
		}

		if (chance >= 1.0D) {
			return true;
		}

		return ThreadLocalRandom.current().nextDouble() < chance;
	}

	/** 把配置里那两组 id 解析成池子（带缓存）。 */
	static Pools pools(DropsConfig config) {
		List<String> beneficialIds = config.killEffects == null ? List.of() : config.killEffects;
		List<String> harmfulIds = config.killEffectsHarmful == null ? List.of() : config.killEffectsHarmful;

		if (beneficialIds.isEmpty() && harmfulIds.isEmpty()) {
			return new Pools(List.of(), List.of());
		}

		PoolKey key = new PoolKey(List.copyOf(beneficialIds), List.copyOf(harmfulIds),
				config.killEffectDurationSeconds, config.killEffectAmplifier,
				config.killEffectHarmfulDurationSeconds, config.killEffectHarmfulAmplifier,
				config.killEffectMaxSeconds);

		return CACHE.computeIfAbsent(key, k -> {
			List<String> missing = new ArrayList<>();

			List<Entry> beneficial = parseAll(k.beneficialIds(), false, config, missing);
			List<Entry> harmful = parseAll(k.harmfulIds(), true, config, missing);

			if (!missing.isEmpty()) {
				Yg.LOGGER.warn("[yg] 击杀效果池里这些条目被忽略：{}", missing);
			}

			if (beneficial.isEmpty() && harmful.isEmpty()) {
				Yg.LOGGER.warn("[yg] 击杀效果的两个池子都是空的，击杀奖励暂时失效");
			}

			return new Pools(List.copyOf(beneficial), List.copyOf(harmful));
		});
	}

	private static List<Entry> parseAll(List<String> ids, boolean harmful, DropsConfig config,
			List<String> missing) {
		List<Entry> built = new ArrayList<>();

		for (String raw : ids) {
			Entry parsed = parse(raw, harmful, config, missing);

			if (parsed != null) {
				built.add(parsed);
			}
		}

		return built;
	}

	/**
	 * 解析一项。两种写法：
	 * <pre>
	 *   "minecraft:speed"          → 该池的全局时长 / 全局等级
	 *   "minecraft:speed;45;1"     → 45 秒、II 级
	 * </pre>
	 *
	 * <p>两个池子各自自洽：有益池里写了有害效果会被移到提示里，负面池里写了有益效果同理 ——
	 * 这样就不会出现「有益池里混了个中毒」这种事。
	 */
	private static Entry parse(String rawEntry, boolean harmful, DropsConfig config, List<String> missing) {
		if (rawEntry == null || rawEntry.isBlank()) {
			return null;
		}

		String[] parts = rawEntry.split(";");
		String idText = parts[0].trim().toLowerCase(Locale.ROOT);

		if (idText.isEmpty()) {
			return null;
		}

		Identifier id = Identifier.tryParse(idText.indexOf(':') < 0 ? "minecraft:" + idText : idText);

		if (id == null || !BuiltInRegistries.MOB_EFFECT.containsKey(id)) {
			missing.add(rawEntry + "（id 不存在）");
			return null;
		}

		Holder.Reference<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);

		if (holder == null) {
			missing.add(rawEntry + "（id 不存在）");
			return null;
		}

		// 池子自洽：只按 HARMFUL 分类互斥，NEUTRAL（发光、不祥之兆这类）两边都允许
		boolean isHarmful = holder.value().getCategory() == MobEffectCategory.HARMFUL;

		if (harmful != isHarmful) {
			missing.add(rawEntry + (harmful
					? "（这是有益效果，应放进 killEffects）"
					: "（这是有害效果，应放进 killEffectsHarmful）"));
			return null;
		}

		int seconds = harmful ? config.killEffectHarmfulDurationSeconds : config.killEffectDurationSeconds;
		int amplifier = harmful ? config.killEffectHarmfulAmplifier : config.killEffectAmplifier;

		// 单项覆盖：id;秒数;等级
		if (parts.length >= 2) {
			Integer parsedSeconds = tryParseInt(parts[1]);

			if (parsedSeconds != null) {
				seconds = parsedSeconds;
			}
		}

		if (parts.length >= 3) {
			Integer parsedAmplifier = tryParseInt(parts[2]);

			if (parsedAmplifier != null) {
				amplifier = parsedAmplifier;
			}
		}

		seconds = Math.min(config.killEffectMaxSeconds, Math.max(1, seconds));
		amplifier = Math.min(255, Math.max(0, amplifier));

		return new Entry(holder, seconds * 20, amplifier, harmful);
	}

	private static Integer tryParseInt(String text) {
		try {
			return Integer.valueOf(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 效果名字，用于动作栏提示。 */
	static String displayName(Entry entry) {
		return entry.effect().value().getDisplayName().getString();
	}

	/** 本局累计发过多少次有益效果。 */
	public static long grantedBeneficial() {
		return GRANTED_BENEFICIAL.get();
	}

	/** 本局累计发过多少次负面效果。 */
	public static long grantedHarmful() {
		return GRANTED_HARMFUL.get();
	}

	/** 自检 / 重开一局时清零。 */
	public static void reset() {
		GRANTED_BENEFICIAL.set(0L);
		GRANTED_HARMFUL.set(0L);
	}

	/** 自检 / 状态查询用：把池子解析出来看看各有几项。 */
	public static Pools poolSnapshot(DropsConfig config) {
		return pools(config);
	}

	/** 自检用：直接对某个活体发一次效果（绕过概率与「必须玩家击杀」的限制）。 */
	static Entry grantForTest(LivingEntity target, ServerLevel level) {
		return grant(target, level, DropsConfig.get());
	}

	/** 自检用：这个实体算不算「玩家」。 */
	static boolean isPlayerKill(Entity killer) {
		return killer instanceof ServerPlayer;
	}
}
