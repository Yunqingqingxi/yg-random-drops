package com.yunxigames;

import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * yunxigames 系列每包独立配置的公共基类。
 *
 * <p>每个玩法包（drops / enchants / events / bingo / mobs）都有自己的 {@code *Config}
 * 继承本类，读自己包的 {@code config/yg-<包名>.json}，相互完全独立 —— 装哪个包就只有哪份配置。
 *
 * <p>基类提供两样所有包通用的东西：
 * <ul>
 *   <li>{@link #debugLog}：调试日志总开关（写进每包各自的 json）；</li>
 *   <li>{@link #selfTestRolls}：开服自检掷骰次数，&gt;0 时开服自动跑一遍自检
 *       （跑完记得改回 0），也可以用各包命令手动触发。</li>
 * </ul>
 *
 * <p>另附 id 列表解析工具（{@link #parseIds} / {@link #parseFilter} 与 {@link IdFilter}），
 * 供子类的 validate() 把字符串列表规范化成可高效匹配的过滤器。
 */
public abstract class YgConfig {
	/** 调试日志总开关。 */
	public boolean debugLog = false;

	/** 开服自检每项掷骰次数；0 = 关闭自检。 */
	public int selfTestRolls = 0;

	/**
	 * 单元测试注入的配置目录；非 null 时 {@link #configPath} 用它而不是 FabricLoader。
	 * 正式运行永远是 null —— 测试与生产共用同一条 load/save 代码路径，只换落盘位置。
	 */
	static volatile Path configDirOverride;

	/** 解析配置目录下的相对路径。 */
	protected static Path configPath(String fileName) {
		Path base = configDirOverride;
		return (base != null ? base : FabricLoader.getInstance().getConfigDir()).resolve(fileName);
	}

	/**
	 * 把 json 里<b>确实缺失</b>的字段补回默认实例的值（各包 Config.load() 在反序列化后调用）。
	 *
	 * <p><b>为什么必须补</b>：Gson 反序列化走 Unsafe 直接建对象、不执行字段初始化器 ——
	 * json 里没写的字段会留在 JVM 默认值上（boolean=false / 数值=0 / 引用=null），
	 * 升级新增的配置项在老配置文件上会静默变成「假默认值」（例如默认 -7.5 的对位偏移读成 0）。
	 *
	 * <p><b>为什么必须留底 raw JsonObject</b>：只看反序列化结果无法区分「json 缺项」和
	 * 「玩家明确写的值」—— 缺项的 false 与写明的 false 读进来都是 false。旧的
	 * 「默认 true 却读到 false 就补回」写法（mobs 包曾经踩过）会把玩家明确关掉的开关
	 * 偷偷打开；这里用 {@code raw.has(字段名)} 判存在性，只补 json 里真的没写的项。
	 * 新增任何配置字段时自动覆盖，不必单独处理。
	 */
	static void mergeMissingFields(Object loaded, JsonObject raw, Object defaults) {
		if (raw == null) {
			return; // 没有留底（文件缺失或损坏走默认实例），无从判断缺项
		}

		for (Class<?> c = loaded.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field field : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || raw.has(field.getName())) {
					continue;
				}
				try {
					field.setAccessible(true);
					field.set(loaded, field.get(defaults));
				} catch (ReflectiveOperationException e) {
					LoggerFactory.getLogger("yunxigames").warn("[yunxigames] 补默认值时跳过字段 {}：{}",
							field.getName(), e.toString());
				}
			}
		}
	}

	/**
	 * NaN 安全回落：NaN 换成 fallback，其余原样返回。
	 * {@code Math.min(hi, Math.max(lo, x))} 钳制链对 NaN 会原样放行（返回 NaN），
	 * 所以 min/max 钳制前先用本方法挡一层 —— 只有 {@code !(x >= lo && x <= hi)} 写法才自带治 NaN。
	 */
	static double orDefaultIfNaN(double value, double fallback) {
		return Double.isNaN(value) ? fallback : value;
	}

	protected static Set<Identifier> parseIds(List<String> raw, String field) {
		Set<Identifier> parsed = new LinkedHashSet<>();

		for (String entry : raw) {
			if (entry == null || entry.isBlank()) {
				continue;
			}

			String text = entry.trim().toLowerCase(Locale.ROOT);
			Identifier id = text.indexOf(':') < 0
					? Identifier.tryParse("minecraft:" + text)
					: Identifier.tryParse(text);

			if (id == null) {
				LoggerFactory.getLogger("yunxigames").warn("[yunxigames] {} 里的 \"{}\" 不是合法 id，已忽略", field, entry);
			} else {
				parsed.add(id);
			}
		}

		return Set.copyOf(parsed);
	}

	protected static IdFilter parseFilter(List<String> raw, String field) {
		Set<Identifier> exact = new LinkedHashSet<>();
		Set<String> namespaces = new LinkedHashSet<>();

		for (String entry : raw) {
			if (entry == null || entry.isBlank()) {
				continue;
			}

			String text = entry.trim().toLowerCase(Locale.ROOT);

			// worldedit:* —— 整个命名空间
			if (text.endsWith(":*")) {
				String namespace = text.substring(0, text.length() - 2);
				if (!namespace.isEmpty()) {
					namespaces.add(namespace);
				}
				continue;
			}

			Identifier id = text.indexOf(':') < 0
					? Identifier.tryParse("minecraft:" + text)
					: Identifier.tryParse(text);

			if (id == null) {
				LoggerFactory.getLogger("yunxigames").warn("[yunxigames] {} 里的 \"{}\" 不是合法 id，已忽略", field, entry);
			} else {
				exact.add(id);
			}
		}

		return new IdFilter(Set.copyOf(exact), Set.copyOf(namespaces));
	}

	/** 一份 id 过滤器：支持精确 id，以及整个命名空间（{@code 模组名:*}）。 */
	static final class IdFilter {
		static final IdFilter EMPTY = new IdFilter(Set.of(), Set.of());

		private final Set<Identifier> exact;
		private final Set<String> namespaces;

		private IdFilter(Set<Identifier> exact, Set<String> namespaces) {
			this.exact = exact;
			this.namespaces = namespaces;
		}

		boolean matches(Identifier id) {
			return id != null && (this.exact.contains(id) || this.namespaces.contains(id.getNamespace()));
		}
	}
}
