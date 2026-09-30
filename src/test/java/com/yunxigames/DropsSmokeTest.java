package com.yunxigames;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yg-drops 冒烟测试：不启动 Minecraft，验证「这个包立不立得起来」的最小事实 ——
 * mod 描述文件合法、配置能从零生成并写回、改动能落盘再读回。
 * 与生产共用同一条 load/save 代码路径，只是把落盘位置换成了临时目录。
 */
@Tag("smoke")
class DropsSmokeTest {

	private static final Gson GSON = new Gson();

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
	void fabricModJsonIsValidWithCorrectModId() throws Exception {
		try (var in = getClass().getResourceAsStream("/fabric.mod.json")) {
			assertNotNull(in, "fabric.mod.json 必须在 jar 资源里");
			JsonObject json = GSON.fromJson(
					new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			assertEquals("yg_drops", json.get("id").getAsString());
			assertEquals(1, json.get("schemaVersion").getAsInt());
			assertNotNull(json.get("entrypoints"), "必须有 entrypoints（服务端入口）");
			assertNotNull(json.get("entrypoints").getAsJsonObject().get("main"));
		}
	}

	@Test
	void loadCreatesDefaultConfigFileOnDisk() {
		DropsConfig cfg = DropsConfig.load();
		assertTrue(Files.isRegularFile(configDir.resolve(DropsConfig.FILE_NAME)),
				"load() 后配置文件必须已写回磁盘");
		// 核心玩法默认值（改动默认值时这里会提醒你同步 README）
		assertTrue(cfg.enableBlockDrops, "方块掉落随机化默认开");
		assertTrue(cfg.enableMobDrops, "生物掉落随机化默认开");
		assertTrue(cfg.enableJackpot, "暴击宝藏默认开");
	}

	@Test
	void modifiedValuesSurviveSaveLoadRoundtrip() {
		DropsConfig cfg = DropsConfig.load();
		cfg.shatterApplyChance = 0.66D;
		cfg.jackpotSound = false;
		cfg.save();

		DropsConfig reloaded = DropsConfig.load();
		assertEquals(0.66D, reloaded.shatterApplyChance);
		assertFalse(reloaded.jackpotSound, "写盘的 false 必须原样读回");
	}
}
