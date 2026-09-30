package com.yunxigames;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yg-drops 单元测试：配置钳制与 id 过滤器的纯逻辑验证（不需要 Minecraft 运行时）。
 * 钳制规则遵循 AGENTS.md §5：!(x >= lo && x <= hi) 写法自带治 NaN。
 */
class DropsUnitTest {

	private DropsConfig freshConfig() {
		return new DropsConfig();
	}

	@Test
	void nanChanceFallsBackToClampedDefault() {
		DropsConfig cfg = freshConfig();
		cfg.shatterApplyChance = Double.NaN;
		cfg.validate();
		assertEquals(0.0D, cfg.shatterApplyChance, "NaN 必须落到钳制下界 0");
	}

	@Test
	void oversizedChanceIsClamped() {
		DropsConfig cfg = freshConfig();
		cfg.shatterApplyChance = 5.0D;
		cfg.validate();
		assertEquals(1.0D, cfg.shatterApplyChance);
	}

	@Test
	void nullBlacklistsAreRepaired() {
		DropsConfig cfg = freshConfig();
		cfg.itemBlacklist = null;
		cfg.entityBlacklist = null;
		cfg.validate();
		assertNotNull(cfg.itemBlacklist);
		assertNotNull(cfg.entityBlacklist);
	}

	@Test
	void itemBlacklistSupportsNamespaceWildcard() {
		DropsConfig cfg = freshConfig();
		cfg.itemBlacklist = new ArrayList<>(List.of("somemod:*", "minecraft:muck"));
		cfg.validate();

		assertTrue(cfg.isItemBlacklisted(Identifier.parse("somemod:cool_item")),
				"命名空间通配应拦下整个 somemod");
		assertFalse(cfg.isItemBlacklisted(Identifier.parse("othermod:cool_item")),
				"通配只作用于声明过的命名空间");
		assertTrue(cfg.isItemBlacklisted(Identifier.parse("minecraft:muck")),
				"精确 id 应被拦下");
	}

	@Test
	void idFilterMatchesExactIdsAndNamespaces() {
		YgConfig.IdFilter filter = YgConfig.parseFilter(List.of("abc:*", "x:y", "  ", "bad id!!"), "test");
		assertTrue(filter.matches(Identifier.parse("abc:anything")));
		assertFalse(filter.matches(Identifier.parse("abc")),
				"裸 id 归 minecraft 命名空间，不在 abc:* 通配范围内");
		assertTrue(filter.matches(Identifier.parse("x:y")));
		assertFalse(filter.matches(Identifier.parse("x:z")));
		assertFalse(filter.matches(Identifier.parse("other:y")));
		assertFalse(filter.matches(null));
	}
}
