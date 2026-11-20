# docs/63 — L7 W3：会话搜索分级排序＋模糊最优对齐＋同一会话比较键

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游依据：`docs/59` §3 W3（覆盖 map/05 的 **S11、C17**）、`docs/59` §2.1 的 S11/C17 判定、docs/21（会话选择器 L1）、docs/62（W2 已完成）
- 文档状态：**2026-11-20 用户审核通过并实施**（§8 实施记录）
- 编号：59＝L7、60＝W1b、61＝B161、62＝W2；本稿 **docs/63**。

## 0. 范围

| 行 | pi 面 | Java 现状 | 本包 |
|---|---|---|---|
| S11 | `session-selector-search.ts`：名字 > 文本 > 模糊的**分级打分**＋逐字段匹配；`session-selector.ts`：同一会话用**规范化比较键** | `SessionSelectorSearch` 把 `id+name+allMessagesText+cwd` 拼成一个 `searchText` 后按 token 分数**求和**，无分级；树去重/当前会话用 `canonicalizePath`（realpath）裸串比较 | 分级打分＋逐字段匹配；比较键（realpath＋resolve＋Windows 折大小写） |
| C17 | `tui/src/fuzzy.ts`：连续整段**最优对齐**；`fuzzyFilter` 按 `[\s/]+` 切 token 求和 | `FuzzyMatch` 只做贪心子序列；`FuzzyMatcher` 仍是子串位置打分，服务于所有 `SelectList` | `FuzzyMatch` 补最优对齐；`FuzzyMatcher` 收敛为 pi 打分引擎（避免两套） |

不在本包：会话列表虚拟滚动（W2 已覆盖 `maxVisible`/翻页）、树形渲染、删除/重命名等交互面。

## 1. pi 侧算法事实

### 1.1 `packages/tui/src/fuzzy.ts`

1. 返回 `{matches, score}`，**score 越低越好**（`:4`）；**无命中下标**。
2. `matchQuery`（`:44-86`）先贪心：每个 query 字符取上次命中后的**首个**出现位（`:54-63`）。
3. **最优连续段**（`:70-79`）：用 `textLower.indexOf(query, start + 1)` 枚举 query 的**每一次整段出现**，把 positions 覆盖成 `start + k` 后重新打分，循环里取 `Math.min`。不是 DP，只对"query 整段出现"生效（离散匹配仍用贪心位）。
4. 打分常量（`:12-42`）：连续第 n 个命中 `-5n`（第 2 个 −5、第 3 个 −10，**递增**）；每跳过一个字符 `+2`；词边界命中 `-10`（边界字符集 `/[\s\-_./:]/`，**不含 `\`、`@`、`=`**）；位置 `+0.1*i`（**浮点**）；整串完全相等 `-100`。
5. 字母/数字互换臂（`:97-114`）：原文不匹配时逐个把字母换成对应数字再试，命中分数 `+5`。
6. `fuzzyFilter`（`:121-159`）：query 按 `/[\s/]+/` 切 token（**`/` 也切**），**每个 token 都必须命中**，`totalScore` 为各 token 分求和；排序只用 `totalScore` ⇒ tie-break 依赖输入原序（依赖排序稳定性）。
7. 回归测试（`packages/tui/test/fuzzy.test.ts`）：`:58-65` `opus` 在 `openrouter/anthropic/claude-opus-4.1` 上必须优于 sonnet/gpt-oss 文本；`:67-72` `xxopus opus` 与 `xxxxxx opus` **同分**；`:103-108` `fuzzyFilter(["clone","cl"],"cl")` ⇒ `["cl","clone"]`；`:39-46` 连续 > 离散。

### 1.2 `session-selector-search.ts`

1. 分级常量（`:30-33`）：`TIER_NAME=0 / TIER_TEXT=1 / TIER_FUZZY=2`，`TIER_WEIGHT=1_000_000`，`tierScore = tier*1e6 + score`（`:62-64`）⇒ **档位绝对支配**。
2. `matchLiteralToken`（`:67-74`）：大小写不敏感**精确子串**，字段序 `name → id → allMessagesText → cwd`；命中 `name` 时子分取 `fuzzyMatch` 分，其余字段子分 **0**。
3. `matchPhraseToken`（`:88-95`，引号短语）：字段先 `normalizeWhitespaceLower`（小写＋折叠空白＋trim，`:22-24`），再看 `name → id → 消息 → cwd`。
4. `matchFuzzyToken`（`:76-86`）：先退回精确匹配；未中才在 **`[name, cwd]`** 上 `fuzzyMatch` 取最小分 ⇒ `TIER_FUZZY`。**id / 消息文本只允许精确匹配**（`:66` 注释，回归 #11024）。
5. `matchRegex`（`:97-103`）：`name → id / allMessagesText / cwd`。
6. `matchSession`（`:195-220`）：token 模式逐 token 独立匹配、分数累加，任一 token 不中即整条不匹配。
7. 排序（`:222-260`）：`recent` 只过滤保序（`:237-244`）；`relevance` 按 score 升序、**同分按 `modified` 降序**（`:254-257`）；空查询（trim 后）直接返回 name 过滤后的原序（`:230-231`）。
8. `NameFilter.NAMED` 用 `Boolean(name?.trim())`，在打分前过滤（`:228-229`）。
9. 消息文本缓存用 `WeakMap<SessionInfo, {source, lower, normalized}>`（`:45-60`），按字符串身份失效。

### 1.3 同一会话比较键

- `session-selector.ts:42-44` `getSessionPathKey(p) = getPathComparisonKey(canonicalizePath(p))`。
- `canonicalizePath`（`utils/paths.ts:28-34`）＝`realpathSync`，**失败回落原串**（跟随符号链接）。
- `getPathComparisonKey`（`:46-50`）＝`pathApi.resolve` ＋ **仅 win32 折 `toLowerCase()`**（整串）。
- 用途：树按 key 去重（`:203-211`）、`parentSessionPath` 转 key 后认父（`:212-218`）、当前会话高亮/禁删（`:328`、`:397-408`）。
- **不用于选中恢复**：`setSessions` 用**原始 path 字符串** `findIndex`（`:353-362`）。pi 自身不一致，本包照抄。

## 2. Java 现状

- `FuzzyMatch.fuzzyMatch(query, text)`（`tui/component/FuzzyMatch.java:30`）：只有贪心子序列，常量已与 pi 同（`-5*run`、`+2*gap`、`-10` 边界、`+0.1*i`、整串 `-100`、换位 `+5`），`toLowerCase(Locale.ROOT)`。**缺"枚举整段出现取最小分"**。
- `FuzzyMatcher.rank(...)`（`tui/component/FuzzyMatcher.java:22/36`）：**子串位置**打分（前缀 0，否则 `indexOf` 下标），被 `SelectList.java:239` 唯一调用 ⇒ 所有 `SelectList` 屏（模型/主题/登录/thinking/旧会话列表）都在用旧引擎。
- `SessionSelectorSearch`（`tui/component/session/SessionSelectorSearch.java`）：`searchText = id + " " + name + " " + allMessagesText + " " + cwd`（`:88`）→ token 求和（`:227-268`），`relevance` 排序已按 score 升序＋`modifiedMs` 降序（`:263-268`）✓；**无分级、无逐字段、模糊打到 id/消息上**。
- 路径键：`SessionTreeBuilder.canonicalizePath`（`:169`，realpath 回落原串，**不折大小写/不 resolve**）；`SessionListModel:141` 选中恢复用裸 `equals`（与 pi 一致 ✓）；`:212` 当前会话/删除守卫用 canonicalizePath 裸 `Objects.equals`（缺折大小写）。
- 现成键工具：`com.pijava.coding.agent.pkg.PathComparisonKeys.key/equal`（已做绝对化＋normalize＋Windows 折 `Locale.ROOT` 小写）；tui **已依赖** coding-agent。

## 3. 设计

### 3.1 `FuzzyMatch`：最优连续段（C17 本体）

在 `fuzzyMatch` 的贪心命中之后追加：

```java
int best = scorePositions(textLower, greedyPositions);
for (int start = textLower.indexOf(queryLower);
     start >= 0;
     start = textLower.indexOf(queryLower, start + 1)) {
    best = Math.min(best, scorePositions(textLower, contiguousPositions(start, queryLower.length())));
}
```

- 只在 query **整段出现**时枚举（pi 同）：离散匹配没得选，贪心位即唯一面；不做 DP（pi 明确不是 DP，DP 会给出 pi 没有的分数）。
- 保持 `Result(matches, score)` API 不变；不新增 indices（当前无高亮消费点，D270 登记）。
- 常量与现有实现一致，**不改**（`FuzzyMatchTest` 的既有期望值不变，只在"整段出现且贪心位更差"的情形变小）。

### 3.2 `FuzzyMatcher`：收敛到同一引擎

```java
public static <T> List<T> rank(String query, List<T> items, Function<T, String> label)
```
- 空/空白 query ⇒ 原样返回（pi `:122-124`）。
- query 按 `[\s/]+` 切 token；**每个 token 都必须命中**，`total` 为各 token `fuzzyMatch(label, token).score` 之和；整条不匹配 ⇒ 丢弃。
- `items.stream().filter(...).sorted(comparingDouble(total))`（`List.sort` 稳定 ⇒ tie-break＝输入原序，pi `:157` 同）。
- 语义变化：`SessionListScreen`（`/resume` 旧列表）、模型/主题/登录/thinking 会从"子串位置"切到"pi 分数"。`FuzzyMatcherTest` 现有断言 `"a" ⇒ [alpha, gamma, beta]` 在新算法下仍成立（alpha：边界 −10；gamma：`a` 在 1 位 +0.1；beta：3 位 +0.3）。
- 类保留（调用点零改动），javadoc 改述为"pi `fuzzyFilter` 对位"。

### 3.3 `SessionSelectorSearch`：分级＋逐字段（S11 本体）

新增**纯函数**（同文件内，保持调用点不变）：

```java
static double scoreSession(RichSessionInfo s, ParsedQuery q, boolean regexMode)
```
- 字段序：`name → id → allMessagesText → cwd`（逐 token 独立判定，**同一 token 必须落在一个字段里**，pi `:202-204`）。
- 四类 token 各走一条（照抄 pi）：
  - 字面 token：精确子串（`Locale.ROOT` 小写后 `contains`），`name` 命中 ⇒ `tier0 + fuzzyMatch(name, token)`，其余字段 ⇒ `tier1 + 0`；
  - 引号短语：先折叠空白再比（新增 `normalizeWhitespaceLower`）；
  - 模糊 token：先精确；未中才在 `name`/`cwd` 上 `fuzzyMatch` 取最小 ⇒ `tier2 + score`；**不再**对 id/消息做模糊（回归 #11024）；
  - `re:` 正则：`name → id → 消息 → cwd`，`Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE`（JS 的 `i` 是 Unicode-aware，Java 默认只 ASCII）。
- 常量：`TIER_NAME = 0`、`TIER_TEXT = 1`、`TIER_FUZZY = 2`、`TIER_WEIGHT = 1_000_000`；`tier(t, s) = t * TIER_WEIGHT + s`。
- 排序不变：score 升序、同分 `modifiedMs` 降序；`recent` 保序；空查询保序（现有测试面不动）。
- `searchText` 拼接字段**删除**（`:88` 的 `searchTextComposesIdNameAllTextAndCwd` 断言改为逐字段语义断言）。

### 3.4 同一会话比较键

新增 `tui/component/session/SessionPathKey`：

```java
public static String key(String path) {
    return PathComparisonKeys.key(SessionTreeBuilder.canonicalizePath(path));
}
public static boolean same(String a, String b);
```

- 组合顺序照 pi：**先 realpath（失败回落原串），再 resolve＋（Windows）小写**。
- 消费点：
  - `SessionTreeBuilder` 的 `Map` 键与 `parentSessionPath` 查父（`:50-56`）改用 `SessionPathKey.key`；
  - `SessionListModel:212` 的"当前会话"判定改 `SessionPathKey.same`；
  - **选中恢复保持裸串**（`:141`，pi `:353-362` 同）——这是 pi 的既有不对称，不修，写在 javadoc 里避免后来者"顺手统一"。

### 3.5 已对齐/不动的面

`relevance` 的 score/modified 双键排序、`recent` 保序、`NameFilter` 前置过滤、`re:` 解析失败 ⇒ 空表，Java 均已对齐，只补断言。

## 4. 窄差登记（新增 D267–D269）

| 编号 | 内容 |
|---|---|
| **D267** | 排序文本比较：pi 在若干邻域（`model-selector`/`theme`/`config-selector`/`footer`/`settings-selector`）用 `String.localeCompare`（ICU，依赖运行环境 locale，pi 自身输出不确定）；Java 用 `String.compareTo`（UTF-16 码元序），大小写/重音的相对顺序可能不同。本包不引入 `Collator`，以确定性优先。 |
| **D268** | 消息文本缓存：pi 用 `WeakMap` 按字符串身份缓存小写/折叠结果（`session-selector-search.ts:45-60`）；Java 每次按键重算。会话数×消息体量的最坏情形有性能差，功能面无差异，不移植。 |
| **D269** | 路径键的平台边角：pi 走 Node `path.win32.resolve`＋`realpathSync`；Java 走 `Path.toRealPath`＋`PathComparisonKeys`（绝对化+normalize+Windows 折小写）。UNC 前缀、8.3 短名、`\\?\` 长路径前缀的规范化结果不保证逐字一致；符号链接解析语义相同。 |
| **D270** | `FuzzyMatch` 仍只返回分数、无命中下标：pi 也不返回（`fuzzy.ts:7-10`），本包不额外造 indices（无高亮消费点）。 |

## 5. 测试计划

1. `FuzzyMatchTest`：pi 三条回归——`fuzzyMatch("opus", "…claude-opus-4.1")` 优于 sonnet/gpt-oss 文本；`"xxopus opus"` 与 `"xxxxxx opus"` **同分**；连续（`foo`/`foobar`）优于离散（`foo`/`f_o_o_bar`）；既有五个分数断言不变。
2. `FuzzyMatcherTest`：`rank("cl", ["clone","cl"])` ⇒ `["cl","clone"]`（整串 −100）；空白 query 保序；多 token（`"foo bar"`）需各自命中；同分保序。
3. `SessionSelectorSearchTest`：三档顺序（名字命中 < 消息命中 < 分散模糊）；**同档同分按 `modifiedMs` 降序**；消息正文命中位置不影响分数；同一 token 必须落在一个字段（id 里 `opus` + 名字里 `claude` 的 token 拆分用例）；`re:` 大小写不敏感对非 ASCII 生效（`UNICODE_CASE`）；模糊不再命中 id/消息（#11024 反例）。
4. `SessionPathKeyTest`：大小写差异同键（Windows 分支用显式 `home`/`os.name` 缝，或用 `PathComparisonKeys` 的既有可测缝）、`\` 与 `/` 折同键、不存在路径回落原串后仍能比较、符号链接（可用临时目录 `Files.createSymbolicLink`，无权限则 skip）。
5. `SessionTreeBuilderTest`/`SessionListModelTest`：同一会话两种写法的 path 只出现一个树节点；`parentSessionPath` 大小写不同仍挂父；当前会话高亮在大小写不同时成立；选中恢复仍按裸串（反向断言，防"顺手统一"）。
6. 验证门：`mvn -pl pi-java-tui -am test` 全绿；收尾全仓 `mvn clean verify -Dfrontend.skip=true`。

## 6. 顺序与风险

顺序：`FuzzyMatch` 最优段 → `FuzzyMatcher` 收敛（此处会动所有 `SelectList` 的过滤结果，先跑一遍全量看断言）→ `SessionSelectorSearch` 分级 → `SessionPathKey` → 断言补齐。

风险：
1. `FuzzyMatcher` 换引擎是**全局行为变更**（模型/主题/登录/旧会话列表的过滤顺序都变）；`SelectList` 的 `filter()` 与 `FuzzyMatcherTest` 是主要回归面。
2. 分级打分里 `TIER_WEIGHT=1e6` 与浮点 score 相加，档内分差最大约 ±数百，不会串档；但**必须先算 tier 再相加**，不能先求和再分档。
3. `re:` 加 `UNICODE_CASE` 会改变既有匹配面（此前非 ASCII 大小写不敏感不生效），属对齐而非回归。
4. `SessionListModel:141` 保持裸串比较，是**故意**与 `:212` 不一致（pi 同），测试用反向断言钉住。

## 7. 回填

实施后：`docs/04` 追加 D267–D270；`docs/map/05` 的 S11（存疑 ⇒ 对齐）、C17（存疑 ⇒ 对齐）补证据；`docs/59` §3 W3 行标注已实施；`docs/06` L7 行补 W3；`docs/07` 视 web 面是否连带。

## 8. 实施记录（2026-11-20）

**代码**

| 文件 | 改动 |
|---|---|
| `tui/.../component/FuzzyMatch.java` | 抽出 `scorePositions(text, positions)`；`matchQuery` 改为「贪心取位 → 枚举 query 整段出现的每个起点 → 取最小分」（`pi :70-79`），全等 `-100` 在取 min 之后统一加 |
| `tui/.../component/FuzzyMatcher.java` | 从"子串位置"改为 pi `fuzzyFilter`：`[\s/]+` 分词、每 token 必须命中、分数求和、稳定排序；`rank` 两个重载签名不变 |
| `tui/.../component/session/SessionSelectorSearch.java` | 分级打分（`TIER_NAME/TEXT/FUZZY` ＋ `TIER_WEIGHT=1e6`）＋逐字段匹配（literal／phrase／fuzzy／regex 四条）；模糊只打 `name`/`cwd`；`re:` 加 `UNICODE_CASE`；`searchText` 降级为诊断面 |
| `tui/.../component/session/SessionPathKey.java`（新） | `key`＝`PathComparisonKeys.key(SessionTreeBuilder.canonicalizePath(path))`；`same` |
| `tui/.../component/session/SessionTreeBuilder.java` | 树去重键与 `parentSessionPath` 认父改用 `SessionPathKey.key` |
| `tui/.../component/session/SessionListModel.java` | `isCurrentSessionPath` 改 `SessionPathKey.same`（选中恢复按设计**保持裸串**） |

**测试**：`pi-java-tui` **676 测 0 失败 1 skip**（新增 11 项：`FuzzyMatchTest` +2、`FuzzyMatcherTest` +3、`SessionSelectorSearchTest` +3、`SessionPathKeyTest` +3）。

**实施中发现的两件事**：

1. **旧断言本身是错的**。`relevanceSortsByScoreThenModifiedDesc` 原本期望 `early` 在 `late` 前，理由是"命中位置越靠前越相关"；pi 的回归 #11024/#11220 明确"消息正文命中位置不影响分数"，两者都应落 `TIER_TEXT`＋0 分 ⇒ 同分 ⇒ 按 `modified` 降序（`late` 先）。已按 pi 改断言，`regexHitScoresByMatchIndex`（旧 0.5＝命中下标 ×0.1）同理改为档位分断言。
2. **pi 有一个 index 0 的哨兵怪癖**：`lastMatchIndex` 初值 `-1`，与 `i - 1`（i＝0）相等 ⇒ 首位命中会多算一次 `consecutiveMatches`（额外 −5）。手算期望值时曾漏掉这一项；已在新用例注释中写明，避免后人"修正"成 `i > 0` 判断而改变分数。

**偏离**：`searchText` 未删除（§3.3 原写删除），降级为诊断/契约面并保留其组合断言——`SessionSelectorSearchTest.searchTextComposesIdNameAllTextAndCwd` 同时验证"四段各自可达"，删掉会丢一层语义覆盖。

**未做**：全仓 `mvn clean verify` 属 L7 闭环门，本包只跑到 `pi-java-tui -am test` 全绿。
