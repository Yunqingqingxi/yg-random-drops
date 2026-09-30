# AGENTS.md — 随机掉落（yg-random-drops）开发规范

> 本包是 yunxigames 系列的玩法包之一。系列总览、公共约定与全系列踩坑速查见
> [yunxigames 文档仓库](https://github.com/Yunqingqingxi/yunxigames) 的 AGENTS.md（必读）。
> 本文件是本仓库开发者（人类与 AI）的入口，开工前通读。

## 1. 本包是什么

**随机掉落**：掉落随机化引擎——掉落的物品 / 生物随机抽换（物品 67% / 生物 8% / 空 25%），
暴击宝藏池、保底、精英怪、击杀赌注、进度 / 维度 / 群系调概率、末影龙通关结算、
五组地面规则（引燃 / 合并 / 播报 / 徒手伐木 / 跌落断肢），附 `/yg` 命令（全系列只有本包有命令）。

- mod id：`yg_drops`，jar：`yg-drops-<版本>.jar`，配置：`config/yg-drops.json`，入口 `YunxiGamesDrops`

### 类地图

| 类 | 职责 |
| --- | --- |
| `DropsConfig` | 本包全部配置项 + `validate()` 钳制（新字段必须加默认值与钳制） |
| `DropRandomizer` | 掉落核心：抽物品/生物、保底、暴击、`makeStack`（附魔书/药水写真实数据）、`randomLootOne` |
| `Progression` | 进度分档（EARLY/MID/LATE）、维度 / 群系池 |
| `SpawnerEggGuard` / `TieredDrops` / `EliteMobs` / `Finale` | 刷怪蛋禁用 / 分层掉落 / 精英怪 / 通关结算 |
| `TntIgnition` / `DropMerger` / `DropTally` / `HarvestEvents` / `FallInjury` / `LimbInjury` | 地面规则 |
| `KillEffects` / `MobStun` / `Feedback` | 击杀药水赌注 / 落地僵直 / 反馈演出 |
| `command/YunxiGamesCommand` | `/yg` 命令 |
| `DropsSelfTest` | 本包自检 |
| `mixin/` | 方块掉落、实体合并、摔落伤害的注入点 |

### 三条设计底线（改功能前先对照）

1. **只在服务端做判定**——玩家用原版客户端直连；2. **一局制、零持久化**；3. **物品不凭空消失**。

### 向后兼容承诺

mod id / jar 名 / 配置文件名 / lang key 永不改；配置字段只增不删（缺项 `mergeMissingFields` 补默认）；
删字段 / 改默认行为升 major；语义化版本 + GitHub Release 附 jar。

## 2. 环境（硬性）

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.159.0+26.2 |
| **JDK** | **25**（本机 `D:\Java\jdk-25`，runServer/build 必须显式指定） |

一切 gradle 命令加 `--offline`（依赖已缓存，联网会卡死）。

## 3. 常用命令

```bash
./gradlew compileJava --offline            # 开发期每个功能写完就跑（~20 秒）
./gradlew test --offline                   # 三层 JUnit 测试
./gradlew smokeTest --offline              # 只跑冒烟
JAVA_HOME='D:\Java\jdk-25' ./gradlew runServer --offline > selftest-<版本>.log 2>&1
JAVA_HOME='D:\Java\jdk-25' ./gradlew build --offline   # jar 落在 build/libs/
```

- runServer 工作目录是本仓库自己的 `run/`（EULA / config / mods / world 都在这里，
  首次跑自动生成 `eula.txt` 要改成 `true`）；
- 自检前把 `run/config/yg-drops.json` 的 `selfTestRolls` 改成 `200`，跑完**改回 `0`**（别提交）；
- 自检完 runServer 不会自退（空转 pausing），必须手动结束 java 进程，否则 `run/` 被锁。

## 4. 代码规范

1. 一个功能一个类，类头 javadoc 写「是什么 + 为什么」（设计取舍比实现更重要）；
2. 一切数值进本包 `DropsConfig`，带中文注释，每个功能独立开关（默认值原则：「爽但不劝退」）；
3. 新配置项必须在 `validate()` 钳制：`!(x >= lo && x <= hi)` 顺带治 NaN；
4. 面向 `ServerLevel` / `LivingEntity` 写逻辑，泛化签名（自检要在无玩家服务器复用）；
5. 中文注释 / 文案（§ 颜色码）/ lang 键值；
6. 26.2 API 不确定：**先查反混淆 jar，别猜**
   （`javap -p -c -cp` 反混淆 jar，方法见系列 AGENTS §7「工具」）。

## 5. 测试节奏

- 每包 `src/test` 带 JUnit5 三层：Smoke（mod json / 配置往返）/ Unit（validate 钳制、黑名单通配）/
  Regression（Gson 缺项、显式 false 不可偷改、NaN 钳制穿透）；批量开发期只跑 `compileJava`，
  攒批后统一 `./gradlew test` + runServer 自检；
- **新增功能必须同步新增自检项**（包内编号递进）并更新本包 README 的自检表；
- 配置测试基建：`YgConfig.configDirOverride` 注入临时目录、基类 `mergeMissingFields`
  （JsonObject `raw.has` 判缺项补回）、`orDefaultIfNaN`；构造器与 `validate()` 包内可见是测试前提，别改回 private。

## 6. 本包专属坑（全系列公共坑见系列仓库 AGENTS §7）

- **第三方兼容靠动态注册表扫描**：物品 / 实体 / 药水 / 效果池全扫 `BuiltInRegistries`
  （mod 物品自动进池）；命名空间黑名单支持 `命名空间:*` 通配（测试要盖通配命中）；
- 玩家破坏方块回调参数是 `Player`，要 `instanceof ServerPlayer sp` 过滤后再当服务端玩家用；
- 掉落物生成记得 `setDefaultPickUpDelay()`；磁石类功能用 `hasPickUpDelay()` 豁免玩家丢弃；
- 实体类型常量别用静态字段：`BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:…"))`；
- 判断实体标签：`Registry.getTagOrEmpty(tag)` 遍历比 `holder.value().equals(type)`；
- 对 `yg:shatter`（more_enchants 的附魔）只做**软引用**（id 字符串 / tag），不做跨包硬依赖；
- 本包 mixin 覆盖方块掉落 / 物品合并 / 摔落伤害三个注入点，改 vanilla 行为前先看现有 mixin 有没有已经挂过同一处。
