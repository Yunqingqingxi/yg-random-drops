package com.yunxigames;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yg-drops 回归测试：钉死历史上真踩过的坑，防止重犯。
 *
 * <p>覆盖的坑：
 * <ul>
 *   <li><b>Gson 不执行字段初始化器</b> —— json 缺项的字段读成 JVM 默认值
 *       （boolean=false / 引用=null）而不是代码默认值，升级后功能静默关闭；</li>
 *   <li><b>旧的「默认 true 读到 false 就补回」写法会把玩家明确写的 false 偷偷改回 true</b>
 *       —— 必须用 JsonObject 存在性检查区分「缺项」与「写明」。</li>
 * </ul>
 */
class DropsRegressionTest {

	@TempDir
	Path configDir;

	@BeforeEach
	void injectConfigDir() {
		YgConfig.configDirOverride = configDir;
	}

	@AfterEach
	void resetConfigDir() {
		YgConfig.configDirOverride = null;
	}

	@Test
	void missingBooleanFieldsFallBackToCodeDefaultTrue() throws Exception {
		Files.writeString(configDir.resolve(DropsConfig.FILE_NAME),
				"{\"enableBlockDrops\": true}");
		DropsConfig cfg = DropsConfig.load();
		assertTrue(cfg.enableJackpot,
				"老配置缺 enableJackpot 时必须补回代码默认 true，而不是 Gson 的 false");
		assertTrue(cfg.enableTntIgnition, "缺项布尔一律补回代码默认值");
	}

	@Test
	void explicitFalseInJsonMustNotBeOverwritten() throws Exception {
		Files.writeString(configDir.resolve(DropsConfig.FILE_NAME),
				"{\"enableBlockDrops\": true, \"jackpotSound\": false}");
		DropsConfig cfg = DropsConfig.load();
		assertFalse(cfg.jackpotSound,
				"玩家明确写 false 的开关必须保持 false（旧盲补写法会偷改成 true）");
	}

	@Test
	void missingListFieldsFallBackToCodeDefault() throws Exception {
		Files.writeString(configDir.resolve(DropsConfig.FILE_NAME),
				"{\"enableBlockDrops\": true}");
		DropsConfig cfg = DropsConfig.load();
		assertFalse(cfg.itemBlacklist.isEmpty(),
				"itemBlacklist 缺项应补回代码默认名单，而不是 null / 空表");
	}

	@Test
	void nanChanceDoesNotSlipThroughLoad() throws Exception {
		// Gson.fromJson 对 NaN 字面量是宽容解析的（历史上真放进来过）
		Files.writeString(configDir.resolve(DropsConfig.FILE_NAME),
				"{\"shatterApplyChance\": NaN}");
		DropsConfig cfg = DropsConfig.load();
		assertEquals(0.0D, cfg.shatterApplyChance, "load() 全链路后 NaN 必须已被钳掉");
	}
}
