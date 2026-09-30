package com.yunxigames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yunxigames 系列的公共常量与日志（yg core）。
 *
 * <p>core 是打进每个玩法包 jar 里的纯类库（不是独立 mod），所以这里只有常量与工具，
 * 没有 {@code ModInitializer} 入口 —— 入口在各玩法包自己的 {@code YunxiGames<包名>} 里。
 */
public final class Yg {
	public static final String MOD_ID = "yg_drops";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Yg() {
	}
}
