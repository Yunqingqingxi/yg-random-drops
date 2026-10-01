# yg-drops — 随机掉落

| | |
| --- | --- |
| **jar** | `yg-drops-1.15.0.jar` |
| **mod id** | `yg_drops` |
| **配置文件** | `config/yg-drops.json` |
| **自检项** | ①~⑮ + ⑰ + ㉙ ㉝ ㉟（本包自己的编号，装本包才跑） |
| **环境** | **只在服务端做判定**，玩家用原版客户端可直连 |

挖泥土不再只会掉泥土。**每一次掉落都会被替换成一个随机结果** —— 这是本系列的核心玩法包。

---

## 一、核心概率

| 结果 | 默认概率 | 说明 |
| --- | --- | --- |
| 随机物品 | **67%** | 数量 **1~8** 随机，还会被物品自身堆叠上限压住（剑就只有 1 把）。从游戏里**全部已注册物品**中等概率抽取（含泥土自己，也含所有模组添加的物品） |
| 随机生物 | **8%** | 一次最多 **3 只**，按 **敌对 50 / 中立 30 / 友好 20** 的权重抽取；刚爆出来的生物有 **2 秒僵直**（不动不攻击，但可以被你打） |
| 什么都不掉 | **25%** | 生物死亡**不会**空手 —— 那条路把这部分概率并给了物品（`allowNothingOnMobDrop`） |

- **不区分破坏方式**：玩家挖、TNT 炸、活塞推、苦力怕自爆，走的都是同一套随机规则
  （默认如此；`spawnMobsOnlyFromPlayers` 可改成「只有玩家亲手破坏才爆生物」）。
- **生物死亡掉落同样被替换**，但**只有玩家击杀才可能爆出新生物**（`mobSpawnRequiresPlayerKill`）。
- **管理员 / 创造专属物品不会掉出来**：命令方块系列、屏障、光源方块、结构方块/空位、拼图方块、
  调试棒、知识之书、测试方块、基岩、刷怪笼、末地传送门框架等，默认已在 `itemBlacklist` 里。
- **玩家永远不会被本模组影响**：死亡时背包该怎么掉还怎么掉；方块经验值（挖钻石矿的 XP）也保持原版。

### 为什么不会指数爆炸

这是本包最重要的一条安全设计。生物死亡也走随机掉落，就会形成自持循环：
生物死亡 → 爆出敌对生物 → 它去杀别的生物 → 又产生死亡 → 又爆生物……

按默认数值算，每只爆出来的僵尸一生杀 5 只生物、每只死亡期望爆出 `8% × 2 = 0.16` 只，
后代数 `5 × 0.16 = 0.8 < 1`，链条自然收敛。但把 `mobChance` 调高就会越过 1 这条线 ——
所以真正兜底的是 **`mobSpawnRequiresPlayerKill`（默认 true）**：每只新生物都必须由一次玩家击杀换来。
被爆炸 / 火焰 / 摔落 / 其它生物杀死的生物仍然照常掉随机物品，只是不再爆生物。

另外还有两道防线：`mobSpawnRateLimit`（默认 60 秒内最多 40 只的滑动窗口限速）
与 `maxMobsPerTick`（每游戏刻最多生成 30 只，超出的方块退化成掉落物品）。

## 二、保底（按对象分别计数）

| 保底 | 默认 | 说明 |
| --- | --- | --- |
| 方块掉落 | 同一**物品**每 **5** 次 | 键是「这次原本会掉出来的物品」，所以中间穿插挖别的方块不影响进度 —— 连挖 5 次泥土（中间夹着挖石头）时，第 5 次泥土必定掉泥土。`0` = 关闭 |
| 生物掉落 | 同一种**生物**每 **3** 次击杀 | 连杀 3 只牛，第 3 只必定掉回牛肉和皮革；中间杀猪不影响牛的计数。只有玩家击杀才计数 |

保底触发时会有可见反馈（粒子 + 音效 + 动作栏写明拿到了什么，`showPityFeedback`）。

## 三、爽点：暴击大爆 + 击杀赌注

### 暴击大爆

全物品等概率意味着抽到钻石和抽到泥土一样平淡，**暴击**就是补上「哇」的那一刻：

- 每次掉落 **1%**（`jackpotChance`）触发，必定从 **17 项宝藏池**（`jackpotItems`）里出；
- 配金色粒子 + 升级音效 + **全服广播**（`jackpotBroadcast`）；
- 宝藏池：钻石 / 钻石块 / 下界合金碎片 / 下界合金锭 / 远古残骸 / 鞘翅 / 附魔金苹果 /
  不死图腾 / 潜影壳 / 下界之星 / 三叉戟 / 海洋之心 / 附魔书 / 龙息 / 重锤 / 试炼钥匙 / 不祥试炼钥匙。

### 击杀奖励：直接赋予药水效果（一场赌注）

玩家击杀生物有 **15%** 概率**当场获得一个随机药水效果**（不产生药水物品）。
这条线**只能靠打怪拿到** —— 挖方块、爆炸一律不给。

其中有 **25%** 的概率掉进**负面池**，拿到的是 debuff。负面效果默认只持续 **15 秒**、
有益效果 **30 秒**，所以长期是赚的，但每一次都得掂量一下。动作栏用紫色「击杀奖励」/
红色「击杀代价」区分。

负面池刻意**没放**中毒 / 凋零 / 飘浮 —— 那三个在残血或悬崖边会直接要命，
而「击杀奖励」不该是个死亡陷阱。

单项格式支持 `"minecraft:speed;45;1"`（秒数;等级）覆盖全局时长/等级。
往有益池里写有害效果会被跳过并记日志 —— 两个池子各自自洽。

### 精英怪

掉落爆出来的生物有 **8%** 概率变成「精英」：发光、血 ×2、名字带「精英 」前缀，
打死掉 **3 件**宝藏池物品，出生时全服播报。

刻意**不做长期奖励**：精英不掉专属货币、不涨等级、不进存档 ——
它就是一个「看到就该打」的即时爽点。

## 四、进度 / 维度 / 群系

### 开局保护（一局制专用）

一局制的容错率低：前 20 分钟要是因为随机掉不出木头、食物、床，这一局基本就废了。
因为玩法是「打到末影龙就结束」，**世界时间 ≈ 这一局的进度**，直接拿它当判据，完全不需要持久化。

| 参数 | 开局 20 分钟内 | 平时 |
| --- | --- | --- |
| 什么都不掉 | 5% | 25% |
| 敌对生物权重 | 10 | 50 |
| 方块保底次数 | 3 | 5 |

### 按进度调概率

开局保护结束后越往这一局的后半段走，掉落越刺激：中期（> 20 分）暴击 ×1.5，
后期（> 60 分）暴击 ×3.0。分档变化时会在聊天栏提示一次。

### 维度 / 群系影响池子

- **维度**：下界暴击 ×2.0、末地 ×3.0；命中「维度加成」（默认 25%）时改从
  `netherBonusItems` / `endBonusItems` 抽。
- **群系**：海洋 / 沙漠 / 丛林 / 沼泽 / 蘑菇岛 / 恶地 / 海滩等特色群系里，
  默认 15% 改从 `biomeBonusItems` 抽。判定是把群系 id 路径做**子串匹配**
  （`warm_ocean`、`deep_frozen_ocean` 都能被 `ocean` 命中），不依赖硬编码注册表键，跨版本更稳。

### 分层掉落 + 终极物资

- **分层掉落**：某些物品只在对应维度产出。和「维度专属池」是两回事 ——
  专属池是「在下界更可能抽到烈焰棒」，这里是「下界合金碎片在主世界**根本抽不到**」。
- **终极物资**：在 `ultimateDimension`（默认 `the_end`）有 **1%** 概率直接产出
  **末地传送门框架 ×12 + 末影之眼 ×12**，全服广播。凑齐 12 个框架就能开一个传送门 ——
  这是这一局真正的「终点奖励」。

## 五、地面规则

| 规则 | 默认 | 说明 |
| --- | --- | --- |
| 刷怪蛋禁用 | 开 | 刷怪蛋掉出来会直接崩坏玩法（拿到一颗僵尸蛋就能无限刷怪）。判定分三层：物品类是 `SpawnEggItem`、id 以 `_spawn_egg` 结尾、`extraSpawnerEggItems` 名单 |
| TNT 引燃甩射 | 开（2%） | 挖到 `tntIgniteBlocks` 里的方块（默认原木 / 石头 / 蜘蛛网 / 干草块）时，朝四个方向各甩出一枚点燃的 TNT，引信 80 刻（4 秒）。同人冷却 300 秒 |
| 掉落物自动合并 | 开 | 地上的同类物品在生成瞬间并进附近已有的那一堆，直到堆满。一次 TNT 连锁炸掉几百个方块时原本会掉出几百个实体，合并后只剩几个 |
| 定时播报 | 开（120 秒） | 每 2 分钟广播一次这段时间掉了些什么 —— 随机掉落的乐趣有一半是给别人看的 |
| 徒手伐木奖励 | 开（50 个） | 连续不用斧头挖掉 50 个原木，就地补 8 个原木。一拿斧头就从头计数 |
| 高处跌落断肢 | 开（50 格） | 跌落超过 50 格且没缓降时掉木块 + 摔断手脚；75 格以上是双腿双手。断肢用属性修饰符实现：移速/挖掘速度按 30% / 80% 扣，60 / 120 秒后自动恢复 |
| 灭火不掉落 | 开 | 火 / 灵魂火被移除时**不**触发随机掉落（`noDropBlocks`）—— 火原版就没有掉落，凭空掉随机物品是 bug 不是惊喜 |
| 植被不掉落 | 开 | 花草 / 水草 / 枯叶堆等拦截后清空掉落，连原版种子也不掉 |
| 幸运加成 | 开 | 主手附魔总等级 × 0.01/级 提升暴击概率，上限 +25%。锋利 V 效率 V 的钻石镐 = 10 级 → +10% |

## 六、通关结算

末影龙被击杀时来一场「通关结算」：全服大标题 + 本局战绩播报 + 原地**宝藏雨**（默认 24 件，
散落半径 8 格，内容全部来自宝藏池）。

只给仪式感，**不给任何长期奖励**：没有存档、没有排行榜、没有经济，也就不需要持久化。

## 七、游戏内命令

需要管理员权限（权限等级 2 / `COMMANDS_GAMEMASTER`）：

| 命令 | 作用 |
| --- | --- |
| `/yg` 或 `/yg status` | 查看当前配置（含暴击 / 进度 / 维度 / 通关 / 精英 / 击杀效果 / 地面规则各项） |
| `/yg reload` | 重新读取配置文件 |
| `/yg on` / `/yg off` | **整体启停随机掉落**（方块 + 生物一起切：off 后掉落恢复原样；TNT 引燃 / 断肢等独立事件的开关不受影响） |
| `/yg drops` / `on` / `off` | 同上 —— 系列统一的 `/yg <玩法名>` 子树写法，与其它玩法包的命令布局一致 |
| `/yg selftest` | 立刻在真服务器上跑一遍全部自检，结论同时进聊天栏与日志 |

> v1.1.0 起各玩法包统一往 `/yg` 根下挂「玩法名」子树（`drops` / `enchants` / `events` / `bingo` / `mobs` / `swap` / `faces`），
> Brigadier 自动合并同名根节点 —— 装几个包就有几个子树，`/yg <玩法名> off` 即停该玩法。

## 八、配置字段（`config/yg-drops.json`）

> 完整字段与逐条中文注释以**代码里的 `DropsConfig`** 为准（每个字段上方都有「为什么这么设计」的注释）；
> 这里列出分组与关键项。

<details>
<summary>点开看全部字段</summary>

**总开关**
`enableBlockDrops`（接管方块掉落）、`enableMobDrops`（接管生物死亡掉落表）、
`spawnMobsOnlyFromPlayers`

**概率与权重**
`mobChance` `0.08`、`emptyChance` `0.25`（两者之和超过 1 会按比例缩放并打日志）、
`hostileWeight` `50` / `neutralWeight` `30` / `passiveWeight` `20`、
`pityThreshold` `5` / `mobPityThreshold` `3`、
`mobSpawnRequiresPlayerKill` `true` / `mobSpawnRateLimit` `40` / `mobSpawnRateWindowSeconds` `60`

**开局保护**
`earlyGameMinutes` `20` / `earlyEmptyChance` `0.05` / `earlyHostileWeight` `10` / `earlyPityThreshold` `3`

**按进度调概率**
`progressScaling` / `midGameMinutes` `60` / `midJackpotMultiplier` `1.5` / `lateJackpotMultiplier` `3.0` /
`announceProgressTier`

**暴击**
`enableJackpot` / `jackpotChance` `0.01` / `jackpotItems`（17 项宝藏池）/ `jackpotSound` /
`jackpotParticles` / `jackpotBroadcast` / `showPityFeedback` / `rareDropBroadcast`

**通关结算**
`enableFinale` / `finaleTreasureCount` `24` / `finaleTreasureSpread` `8` / `finaleTitle` /
`finaleBroadcast` / `finaleEffects`

**维度 / 群系**
`dimensionAffectsPools` / `netherJackpotMultiplier` `2.0` / `endJackpotMultiplier` `3.0` /
`dimensionBonusChance` `0.25` / `netherBonusItems` / `endBonusItems` /
`biomeAffectsPools` / `biomeBonusChance` `0.15` / `biomeBonusItems` / `biomeKeywords`

**精英怪**
`enableEliteMobs` / `eliteChance` `0.08` / `eliteHealthMultiplier` `2.0` / `eliteTreasureCount` `3` /
`eliteGlowing` / `eliteBroadcast` / `eliteNamePrefix` `"精英 "`

**击杀奖励（药水）**
`enableKillEffects` / `killEffectChance` `0.15` / `killEffects`（有益池 18 项）/
`killEffectsHarmful`（负面池 8 项）/ `killEffectHarmfulChance` `0.25` /
`killEffectDurationSeconds` `30` / `killEffectHarmfulDurationSeconds` `15` /
`killEffectAmplifier` / `killEffectHarmfulAmplifier` / `killEffectHostileOnly` /
`killEffectMessage` / `killEffectMaxSeconds` `600`

**物品 / 生物**
`itemCountMin` `1` / `itemCountMax` `8` / `allowNothingOnMobDrop` `false` /
`spawnedMobStunTicks` `40` / `mobCountMin` `1` / `mobCountMax` `3` / `maxMobsPerTick` `30` /
`itemBlacklist` / `neutralMobs` / `entityBlacklist` / `extraPassiveMobs`

**地面规则**
`blockSpawnerEggDrops` / `spawnerEggBroadcast` / `extraSpawnerEggItems` /
`enableTntIgnition` / `tntIgniteChance` `0.02` / `tntIgniteCooldownSeconds` `300` /
`tntIgniteFuseTicks` `80` / `tntIgniteSpreadBlocks` `2.0` / `tntIgniteDirections` `4` /
`tntIgniteBlocks` / `tntIgniteDebris` / `tntIgniteDebrisItems` / `tntIgniteBroadcast` /
`dropMergeEnabled` / `dropMergeRadius` `2.5` / `dropMergeMaxScan` `24` /
`dropSummaryEnabled` / `dropSummaryIntervalSeconds` `120` / `dropSummaryMaxLines` `5` /
`enableBareHandLogEvent` / `bareHandLogThreshold` `50` / `bareHandLogRewardCount` `8` / `bareHandLogBroadcast` /
`enableFallInjury` / `fallInjuryHeight` `50` / `fallInjurySevereHeight` `75` / `fallInjuryHurtsArms` /
`fallInjuryDropItems` / `limbOnePenalty` `0.30` / `limbTwoPenalty` `0.80` /
`limbInjurySeconds` `60` / `limbInjurySevereSeconds` `120` / `limbInjuryBroadcast` /
`enableTieredDrops` / `netherExclusiveItems` / `endExclusiveItems` / `exclusiveRerollAttempts` `8` /
`enableUltimateDrops` / `ultimateChance` `0.01` / `ultimateDimension` `"the_end"` /
`ultimatePortalFrameCount` `12` / `ultimateEnderEyeCount` `12` / `ultimateBroadcast` /
`noDropBlocks`

**幸运 / 跨包联动**
`enableLuckBonus` / `luckBonusPerLevel` `0.01` / `luckBonusCap` `0.25` /
`enableShatterAttach` / `shatterApplyChance` `0.06`（需要另外安装 `yg-enchants`；
附魔 `yg:shatter` 不存在时自然跳过 —— 包与包零依赖）

</details>

## 九、自检

把 `selfTestRolls` 设成 `200`，开服时本包会在**真实的 `overworld`** 上把每一项功能实际跑一遍，
结论（✅ / ❌ + 实测数字）直接写进日志；也可以随时用 `/yg selftest` 手动触发。

当前覆盖（本包自己的编号；① 拆成 a/b/c 三个子项）：

| 编号 | 检查内容 |
| --- | --- |
| ①-a | 物品数量实测落在 `itemCountMin~itemCountMax`，且不超堆叠上限 |
| ①-b | 生物掉落掷 N 次，「什么都不掉」必须为 0 次 |
| ①-c | 爆出的生物 `isNoAi=true`，推进 `spawnedMobStunTicks` 刻后自动解锁 |
| ② | 暴击概率拉到 100% 掷 N 次，落点**全部**来自宝藏池，且暴击计数 +N |
| ③ | 保底在第 `pityThreshold` 次触发；`cause=null` 时反馈不抛异常 |
| ④ | 鞘翅判为稀有、泥土判为非稀有；广播路径实际执行不抛异常 |
| ⑤ | 通关结算实际跑一遍，宝藏雨件数 == `finaleTreasureCount`，战绩报表非空 |
| ⑥ | 开局保护 / 进度分档**边界**逐点验证（20/60 分 → EARLY/EARLY/MID/MID/LATE）；无世界时退化为 EARLY ×1 |
| ⑦ | 三个专属池都能解析出物品；主世界无维度池；下界/末地倍率正确；**在下界实掷一轮，掉出的全部来自下界池** |
| ⑧ | 非玩家来源拒绝生成精英；升级后已登记/发光/带名/血更厚/不消失 |
| ⑨ | 爆怪概率确已下调且三项比例合计为 1；有益/负面两池都能解析且分类正确；负面池时长短于有益池；非玩家击杀被拒；错池条目会被拒绝；`id;秒数;等级` 单项覆盖生效；负面率 0%/100% 两个极端选对池子；负面率拉满后真的发一次 |
| ⑩ | 刷怪蛋按**类**和按 **id** 两条路径都判为禁用、普通物品放行；实扫整张随机池，里面一个刷怪蛋都不剩；全是蛋的池子被拒；关掉开关后放行 |
| ⑪ | 引燃真的甩出 `tntIgniteDirections` 枚（并核对半径与引信刻数）；冷却挡得住、设 0 放行；方块匹配表（原木 ✓ / 石头 ✓ / 蜘蛛网 ✓ / 泥土 ✗）；`gravel;2` 能解析、假 id 被忽略 |
| ⑫ | 3 个 + 5 个泥土并成一堆 8 个且**来源那件被完全吸收**；石头不并入；关掉开关完全不并；同刻窗口登记与清空都符合预期 |
| ⑬ | 徒手连挖在第 `bareHandLogThreshold` 次触发；斧头判定正确；跌落分档边界验证；50 格 → 1 腿 1 手 / 60 秒、75 格 → 2 腿 2 手 / 120 秒；属性修饰符真的挂上并能解除；移速实测 `0.23 → 0.16 → 0.05 → 0.23` |
| ⑭ | 主世界剔除下界/末地专属物品、普通物品放行、下界内放行下界专属；关掉开关后放行；终极物资只认配置维度；**在末地实掷一轮，掉出的全部是终极物资** |
| ⑮ | 修「空壳附魔书」bug：附魔书掉落时存了 1~3 条真实附魔；药水掉落时带真实 `PotionContents` |
| ⑰ | 碎裂附着概率两个极端验证：`shatterApplyChance=1` 时随机武器/工具**全部**带碎裂、`=0` 时全不带；苹果（非武器）始终干净。**没装 yg-enchants 时报「软引用按预期静默跳过」并算通过**（不是失败） |
| ㉙ | 灭火不掉落·黑名单 |
| ㉝ | 植被不掉落·花草/水草/枯叶堆 |
| ㉟ | 幸运加成·附魔等级换暴击 |

> 准确说，本包注册的是**两个步骤**：`随机掉落全项（①~⑮ + 植被 + 幸运加成）`
> （内部一次性跑完 ①~⑮ 与 ㉙ ㉝ ㉟）与 `⑰ 碎裂附着·随机掉落武器/工具`。

### 编号说明

**自检编号是「包内局部」的，不是全局唯一的。** 拆成五个玩法包之后，每个包只保证自己的编号连续；
装了多个包时日志里可能出现两个 ⑫（本包的「掉落物自动合并」与 yg-enchants 的「附魔突破·注册」）。
按步骤名而不是编号去读日志即可。

## 十、已知限制

- 掉落表是「整体替换」，**不叠加时运/精准采集**：附魔工具不会让随机结果变得更好。
- 生物死亡只替换掉落表那一层，所以生物身上的装备和死亡经验仍是原版行为。
- **掉落合并只在「掉落的那一瞬间」判定**，不做事后全场扫描。它降低的是「一次爆炸瞬间炸出几百个实体」
  这种尖峰；「地上躺着的两堆慢慢并到一起」是原版 `ItemEntity` 自带的行为。
- **TNT 引燃默认开启且不看工具**：拿斧子砍原木、拿镐子挖石头一样可能炸。
- **击杀奖励的负面池**每项都是「难受但不致命、而且会自己结束」的；想要更刺激自己往列表里加。
- 与其它模组共存：掉落表是整体替换 + 保底放行，其它模组对掉落表的改动会被保留
  （保底那一次走的正是原版路径）；若某个模组自己也拦 `Block#getDrops`，
  两边注入会同时生效，`RETURN` 阶段更靠后的那个决定最终结果。

---

## 相关链接

- 系列总览与公共开发规范：[yunxigames](https://github.com/Yunqingqingxi/yunxigames)
- 归档（1.15.0 之前的历史）：[random-drops](https://github.com/Yunqingqingxi/random-drops)
