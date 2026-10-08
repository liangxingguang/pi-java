# 42 pi-java-agent-core 对齐盘点（剩余缺口清单）

> **状态：📝 盘点稿（2026-10-09）。** 不是设计稿 —— 只回答「agent-core 还剩什么没对齐」，
> 逐包设计在各自文档里出。格式沿用 `docs/41`。
>
> **数据源**：`docs/map/03-agent-core.md`（基准 pi-java `351d199` / pi `200387122`，2026-10-04）
> ＋ `docs/04-open-items-register.md`。
> **本稿新增**：对全部 15 条**缺失**逐条重测（读 pi `git show 200387122:<path>` 与 pi-java 当前工作树）。
> 取证过的标 ✅；沿用地图判定的标 ⤵。

---

## 0. 一句话

**加权 66.8%（Σ权重 184 / Σ(w×c) 123.0），未加权 38.3%。**
结构特征：**权重 3 层 76.2%、零缺失**，但**权重 1 层只有 35.7%** —— 欠账几乎全在长尾。

> ⚠️ 地图基准是 2026-10-04（`5240fb9c`），此后模块仍在动。本稿**实测出 3 条地图记错**，见 §5。

---

## 1. 真缺失（15 条，计分母）

### 1.1 已确认仍在（12 条）

| # | 单元 | 权重 | pi 侧证据 | pi-java 现状 | 台账 |
|---|---|---|---|---|---|
| ✅ **17** | 手动 `/compact` **先 `abort()` 当前运行** | **2** | `core/agent-session.ts:2717-2718`：`async compact(…)` 开头即 `await this.abort()` | `harness/CompactionExecutor.java` 的 `compact(...)` **无 abort 调用**（只有 `enterCompaction()` → `onStart("manual")`） | **A4/F4** |
| ✅ **26** | per-model 压缩设置 | **2** | `core/settings-manager.ts:964` `getCompactionSettings(model?)`；`:32` `modelOverrides`（exact `"provider/modelId"` 键，`:940` 取用 + 逐字段校验） | `AgentHarness.getCompactionSettings()` **无模型参数**（`AgentHarness.java:542`） | **A9** |
| ✅ **24** | branch summary 生成 | 1 | `core/compaction/branch-summarization.ts:108` `collectEntriesForBranchSummary`、`:195` `prepareBranchEntries` | 全仓零命中生成器（只有 `Entry.BranchSummary` 类型 + 文案） | **B1** |
| ✅ **25** | 会话树导航 `navigateTree` | 1 | `core/agent-session.ts:3915` `async navigateTree(…)` | 零命中 | B1 同族 |
| ✅ **27** | branch summary 设置（`reserveTokens`/`skipPrompt`） | 1 | `core/settings-manager.ts:962-967` | 无 | B1 同族 |
| ✅ **48** | `session_info_changed` 生产者 | 1 | `core/agent-session.ts:3895` 发 `{type:"session_info_changed", name: getSessionName()}` | **仍无 emit**：`AgentSession.setSessionName:523-528` 只赋值、不发射（类型 `:73` + mapper `JsonEventMapper.java:103` 都在） | **B30** |
| ✅ **55** | `CompactionEntry.fromHook` / `BranchSummaryEntry.fromHook` | 1 | `core/session-manager.ts:101,115`（`fromHook?: boolean`）＋ `:1266,1282` 透传 | 只在 javadoc 里出现（`CompactionFiles.java:65,68`） | —— |
| ✅ **62** | `formatSkillInvocation` | **2** | `core/skills.ts:355` `formatSkillsForPrompt`（技能调用格式含在内） | 零命中 | —— |
| ✅ **68** | `streamProxy` | 1 | `agent/src/proxy.ts:120` `export function streamProxy(`（**新锚存活**） | 零命中 | —— |
| ✅ **69** | `setDefaultStreamFn` | 1 | `agent/src/stream-fn.ts:11`（**新锚存活**，另有 `getDefaultStreamFn`） | 零命中 | —— |
| ✅ **98** | `abortCompaction` | 1 | `core/agent-session.ts:2867` `abortCompaction()`；调用点 `:1366`、`:2392` | 零命中 | **B36** |
| ⤵ **46** | `AgentSessionEvent` 面 | **3** | pi **24** 变体（`core/agent-session.ts:191-231`） | java **20** 变体 | B30/B34/B35 |

### 1.2 ⚠️ 地图记错 —— **实际已闭**（3 条）

| # | 地图原文 | 实测 | 结论 |
|---|---|---|---|
| ✅ **47** | 「`queue_update` 生产者**缺失**」 | `emitQueueUpdate()` 已落地，`AgentSession.java:649/654/700/721` **四处发射**；提交 `05b1a798`（**2026-10-07**，晚于地图基准） | **已闭** ⇒ `B34` 该销 |
| ✅ **49** | 「`thinking_level_changed` 生产者**缺失**」 | `AgentSession.java:507` `emitSessionEvent(new ThinkingLevelChanged(level))`；提交 `b75450f1`（**2026-09-23**，**早于**地图基准） | **已闭** ⇒ `B35` 该销；地图当时就没测准 |
| ✅ **65** | 「`sanitizeSurrogates` …… **全仓零命中**」 | `com.pijava.ai.utils.SanitizeUnicode.surrogates` 存在（`pi-java-ai`），生产调用点 `AnthropicMessageConverter.java:55,135` 等 | **口径错** ⇒ 地图只搜了 `agent-core`，属跨模块；`B16` 该改判 |

---

## 2. 存疑 42 条（形状差）

### 2.1 权重 3 的十条（这一档真正要紧的）

| # | 单元 | 形状差 | 台账 |
|---|---|---|---|
| **6** | `finishTurn`（原 `shouldStopAfterTurn`） | pi 从布尔改 `{action:"continue"\|"end"}`（`agent/src/types.ts:146-171,264`）⇒ **能强制多跑一轮**；java 仍 `StopHook` 返布尔（`PiLoop.java:235-237`） | —— |
| **23** | 输出截断字节预算 | pi 主流 **50KB**（`core/tools/truncate.ts:11-12`）；java **100KB**（`TruncationUtils.java:17` `DEFAULT_MAX_BYTES = 100_000L`）⇒ **模型可见文案即不同** | B158 同族 |
| **34** | 钩子 `before_request` ⇒ `prepareRequest` | pi 返 context/model/thinking 三样替换（`agent/src/types.ts:271`）；java `HookSystem` 老形状 | —— |
| **41** | 钩子 `finishTurn`/`prepareNextTurn` | #6 同根 | —— |
| **46** | `AgentSessionEvent` 面 | 见 §1.1（java 20 vs pi 24） | B30/B34/B35 |
| **52** | `agent_end` 载荷 | pi = **本 pass** `newMessages`（`agent-loop.ts:254,290,320`）；java **会话层**发 `accumulatedMessages()` | **A8** |
| **54** | Entry union | java **10**（`entry/Entry.java`：Message/ModelChange/ThinkingLevelChange/Compaction/BranchSummary/Usage/Custom/CustomMessage/ContextEdit/Replacement）vs pi **11**（缺 `LabelEntry`/`SessionInfoEntry`；java 另有 `Replacement`） | —— |
| **58** | `convertToLlm` | 形状差（`core/messages.ts:148-190`） | **A1** |
| **73** | JSONL 存储 | pi 主流 **v3 平铺**（`session-manager.ts:41` `CURRENT_SESSION_VERSION = 3`）vs java **v4 平铺** ⇒ **互读不了** | —— |
| **78** | `latest()` / `find()` 范围 | pi `findMostRecentSession` 只读一个项目目录（`session-manager.ts:749`）；java 走 `list(all())` | **B53** |
| **100** | 系统提示作为 transcript 的 system 消息 | pi 的 `TranscriptContext` **只有 messages**（`ai/src/types.ts:748-751`）；java 走 `Context.systemPrompt` 字段 ⇒ **首条等价、会话中变更不等价** | 新 |

> （w3 共 21 条能力单元 —— 上表 11 条；其余 10 条为对齐。）

### 2.2 权重 2 / 1 的存疑（31 条，按类归并）

| 类 | 条目 |
|---|---|
| 队列 / 宿主 | #14 `QueueMode.All` 宿主语义（**F1**）· #80 `streamingBehavior`（**A5/F4**）· #84 两道门行为裁决（**F4**） |
| 事件 | #53 `turn_end` 颗粒度（**B49**）· #86 `_emitSessionCompactFailed`（**F3**）· #88 `ActiveToolsChange` 发射（**C1**） |
| 存储 / 会话 | #56 `EntryProjector`（**A1**）· #75 session fork · #77 恢复 seed/replay（**A2**）· #79 列表 `onProgress`（**B55**） |
| prompt / 技能 | #63 `loadSkills` · #64 `loadPromptTemplates`（都在 coding-agent） |
| API 面 | #81 工具线程安全契约（**A10/A11**）· #82 `retry.provider.*` ↔ `RetryPolicy`（**A7**）· #83 `transcript()` 消费者（**A2**）· #85 `recordEvent` 兜底（**F2**） |
| 并发 / 可见性 | #89 批内 join 源序（**C2**）· #90 transcript 可见性（**C3**）· #91 `reset` 不关 runSpan（**C4**） |
| 夹具 / 文档 | #87 L5 thinking 块不对称（**E8**）· #92 遥测 javadoc（**D2/D3**）· #93 `SummaryGenerator` javadoc 过期（**D6**）· #94 文件超限（**E3/E4**）· #95 集成层夹具无牙（**A14**）· #96 错误路 `AgentSettled` 无夹具（**A15**） |
| 结构债 | `docs/04` §5 E 类：`PiLaneSink.java` **562**、`AgentHarness.java` **554**（本模块两条，超 500 行） |

---

## 3. 作废 19 条（R1／R5，权重 0，不进分母）

`before_run` · `before_drive` · `before_payload` · `after_response` · `before_compaction` ·
`before_navigation` · `before_resume` · effect gate · HarnessEvent · `run_suspend` ·
`LaneConfiguration` · `AdaptivePublisher` · output-capture/shell-output · `SessionSearchService` ·
`reduceLaneSnapshot` · `TaggedError` · 存储 conformance 套件 · pico3 · durable · drive 运行时（整块）。

> ⚠️ **替身在别处**：`before_payload`/`after_response`/`before_compaction` 的对应物是**扩展事件**
> （`before_provider_request`/`after_provider_response`/`session_before_compact`），
> **归属 `04-coding-agent` 的 D 域**，不是本模块。

---

## 4. 建议的推进次序

| 序 | 内容 | 权重 | 理由 |
|---|---|---|---|
| **①** | **#17 手动 `/compact` 先 abort** | w2 | **今天可达的行为差**：pi 先中止在飞的 agent 操作再压缩，java 不会。与 `docs/31 §8.25.5-6/-7`（运行中压缩无门）**同一路径**，可合并处理 |
| **②** | **#98 `abortCompaction`** | w1 | 小，但**卡着** `B39`（Esc 换绑）；`B36` 点名 |
| **③** | **#48 `session_info_changed` 发射** | w1 | 补一行 emit 即可（类型、mapper 均已就位）⇒ `B30` 销号 |
| **④** | **清过期账** | — | 地图 3 条记错（#47/#49/#65），`B34`/`B35`/`B16` 应销或改判；**建议在 ①–③ 之前或并行**做，避免重做 |
| —— | #24/#25/#26/#27（压缩族） | w2/w1 | 可合成一个「压缩设置与分支摘要」包 |
| —— | 权重 3 的三条形状差（#6 `finishTurn` / #34 `prepareRequest` / #100 system 消息） | w3 | **勾连 agent-loop 协议面**，改动面大，宜单独立项并先出设计稿 |

> **①②可合成一包**（压缩的中止面），**③ 单独一行改动**，**④ 是文档活**。

---

## 5. ⚠️ 实测与地图不一致的三处（本稿新增）

| # | 地图基准（2026-10-04） | 实测（2026-10-09） | 该做什么 |
|---|---|---|---|
| 47 | `queue_update` 生产者缺失 | `05b1a798`（10-07）已闭 | 地图回填 + `B34` 销号 |
| 49 | `thinking_level_changed` 生产者缺失 | `b75450f1`（**09-23**）已闭 —— **早于地图基准**，说明地图当时没测准 | 地图回填 + `B35` 销号 |
| 65 | `sanitizeSurrogates` 全仓零命中 | 存在（`pi-java-ai/utils/SanitizeUnicode`） | `B16` 改判为「对齐」或改口径 |

> **教训**：`docs/map/03` 的「缺失」是在 **pi-java `351d199`** 上测的，而本模块此后仍有提交
> （`agent-core` 最近改动含 `85a8b00c`/`699a9bbe`）。**照地图开工前，缺失项必须用当前工作树复测一遍** ——
> 本稿 15 条缺失里已有 3 条这样被推翻。

---

## 6. 台账影响

| # | 条目 |
|---|---|
| `B30` | 本稿确认仍缺（#48）⇒ **保留** |
| `B34` | **应销号**（#47 已闭，`05b1a798`） |
| `B35` | **应销号**（#49 已闭，`b75450f1`） |
| `B36` | 保留（#98 确认仍缺） |
| `B16` | **应改判**（#65 口径错，`SanitizeUnicode` 存在） |
| `B1` | 保留（#24/#25/#27 仍缺） |
| `A4/F4` | 保留（#17 确认仍缺） |
| `A9` | 保留（#26 确认仍缺） |
| —— | 本稿**不新增**台账条目（盘点稿）。 |

---

## 7. 取证方法（复现用）

1. **pi 侧一律 `git show 200387122:<path>`**（本地工作树可能停在别的提交，直接读会静默取到旧状态）。
2. **pi-java 侧直接读工作树**（即当前 HEAD）。
3. 逐条用 `grep -rn` 对读两侧的**构造名 / 事件名 / 方法名**；**事件类条目要 grep 到「发射点」**，
   不能只确认「类型定义存在」（`B30` 就是这个坑：类型 + mapper 都在，缺的只是 emit）。
4. ⚠️ 行号会漂：pi 侧引用优先给**文件 + 关键行**；无法稳妥定位的只给文件。
