# 07 agent-core 层未对齐清单

> 【背景摘要】本文档只回答一个问题：**pi-java-agent-core 层还有什么要对齐**。一行一条；已闭环的不写（历史在 git：`git log --follow -- docs/07-agent-core-alignment-gaps.md`）。

## 术语与定位

- 通用术语（pi / 锚点 / 对齐 / 闭环 / 工单编号 Bxxx、Axx / 裁决 / 模型可见面 / R5 / 分母 / 原 docs/NN）：见 [`04-open-items-register.md`](04-open-items-register.md) §0 与 [`05-module-alignment-map.md`](05-module-alignment-map.md)。
- **权威台账**是 [`04-open-items-register.md`](04-open-items-register.md)：B/A 编号与裁决以此为准。
- **全量加权盘点**是 [`map/03-agent-core.md`](map/03-agent-core.md)：104 个单元逐条判定、权重、完成度。
- 本稿＝两者的 agent-core 层索引：§1 确认缺口，§2 形状差（存疑、待裁决），§3 要取证/清点的 A 项，§4 已裁决不做。
- **map 单元号（#N）**：`map/03-agent-core.md` 里的能力单元编号。
- **批 agent-core-1/2/3**：2026-10-17 闭环的三个对齐包，实施记录在原 docs/20–22（已删，历史在 git）。

## 基准

- pi 锚点 `ea448f454`（2026-10-11）。七份地图已于 2026-10-12 对该锚点逐条重测完毕（原 A16 销号）；本稿数字随重测同步。
- 整理：2026-10-12（旧版盘点过程稿已删）。

---

## 1. 确认缺口（地图判「缺失」，计分母）

| 地图# | 权重 | 差什么 | 台账 |
|---|---:|---|---|
| #62 | **2** | `formatSkillInvocation`（技能调用格式）：全仓零命中 | —— |
| #8（工具表） | 1 | `powershell` 工具缺失（pi 8 个内置工具，Java 7 个；系统提示词里有名字、无实现） | **B243** |
| #24 | 1 | branch summary **生成器**缺失（只有 `Entry.BranchSummary` 类型＋文案） | **B1** |
| #25 | 1 | 会话树导航 `navigateTree` 缺失 | B1 同族 |
| #27 | 1 | branch summary 设置（`reserveTokens`/`skipPrompt`，注意与压缩阈值的同名 reserve 无关） | B1 同族 |
| #48 | 1 | `session_info_changed` 无生产者：类型＋RPC mapper 都在，零 emit（`setSessionName` 只赋值） | **B30** |
| #55 | 1 | `CompactionEntry.fromHook`/`BranchSummaryEntry.fromHook` 字段缺失（只在 javadoc 出现） | —— |
| #68 | 1 | `streamProxy`（`agent/src/proxy.ts`，新锚存活）零命中 | —— |
| #69 | 1 | `setDefaultStreamFn`/`getDefaultStreamFn`（`agent/src/stream-fn.ts`，新锚存活）零命中 | —— |
| #79 | 1 | 会话列表无 `onProgress`/渐进发布/AbortSignal/并发扫描（Java 同步全量） | **B55** |
| #98 | 1 | 无 `abortCompaction`（per-operation 取消句柄；TUI Esc 换绑被它挡着） | **B36** |

**同层相关、挂在 docs/04 但生产者跨模块的条目**：

- B49 `turn_end` 颗粒度（地图 #53，形状差见 §2）。
- B53 `find()` 缺三级阶梯（地图 #78）。
- B31/B32/B33（get_state 三处 RPC 形状，生产者在 coding-agent）。

---

## 2. 形状差（地图判「存疑」，计半分；待裁决）

这些不是「没有」，而是「形状/行为不同」。**每一条的收口顺序**：先定「算不算偏差」；认账则立 B 号转 §1，不认则进 `docs/04` §4 写裁决与触发条件。

### 2.1 权重 3（✅ 已清零）

权重 3 层 22/22 = **100%**，无存活条目。批 agent-core-1 清 #6/#41/#17/#23，批 agent-core-2 清 #34/#46/#52/#54/#58/#78/#100（2026-10-17）；逐条闭环记录见 map/03 与 git 历史。其中 #54/#58/#78/#100 的裁决仍有效，收在 `docs/04` §4。

### 2.2 权重 1/2（按类归并，编号是 map/03 单元号）

| 类 | 条目 |
|---|---|
| 队列/宿主 | #14 `QueueMode.All` 宿主语义（F1）· #80 `streamingBehavior` 两道门（A5/F4）· #84 两道门行为裁决（F4） |
| 事件 | #53 `turn_end` 颗粒度（**B49**） |
| 存储/会话 | #56 `EntryProjector`（A1）· #57 四个消息类型（A1）· #60 `PendingEntry`/`InboxItem` 队列契约 · #75 session fork · #77 恢复 seed/replay（A2） |
| prompt/技能 | #63 `loadSkills`、#64 `loadPromptTemplates`（生产者都在 coding-agent） |
| API 面 | #81 工具线程安全契约（**A10**）· #82 `retry.provider.*` ↔ RetryPolicy（A7）· #83 `transcript()` 消费者清点（A2）· #85 `recordEvent` 兜底（F2） |
| 并发/可见性 | #89 批内 join 源序（C2）· #90 transcript 可见性（C3）· #91 `reset` 不关 runSpan（C4） |
| 夹具/文档/结构债 | #87 L5 thinking 块不对称（E8）· #92 遥测 javadoc（D2/D3）· #93 SummaryGenerator javadoc（D6）· #94 文件超限（**E3/E4**，另 E1/E2 在 coding-agent）· #95 集成层夹具无牙（**A14**）· #96 错误路 AgentSettled 无夹具（**A15**） |

**跨层挂账提醒**：JSONL 条目信封 #16 仍存疑（见 map/06）；`--tools` glob/modifier 模式面在册 **B236**（docs/04 §3.3，归 coding-agent 批）。

**2026-10-17 批 agent-core-3**（详稿原 docs/22（批 3 设计稿））：内置工具四条存疑**清零**：

- bash 超时面逐字（无默认值／上限 2147483.647 s／毫秒粒度／isError 收尾）。
- grep 补七参＋gitignore＋输出形（纯 Java 引擎；**裁决①**：rg/fd 外壳与 tools-manager 不移植）。
- `glob`→`find` 改名＋fd 语义。
- ls `recursive`→`limit` 参数集换血。
- 窄差 D1–D8 入 map/03 工具表备注。
- 新登记：**B243**（powershell 实现＋默认激活集，挂 B236 族；设计稿原编号 B241，与 telemetry 批 1 撞号）、**B242**（bash 的 `PI_*` 会话环境变量面）——均在 docs/04 §3.3。

**编号空间提醒**：旧 A/C/D/F 编号（如 A1/A2/C2/F1）来自各过程稿（docs/31 族），其中一部分已随包闭环；它们与 `docs/04` §2 的 A 类（A10/A14/A15/A16）**不是同一编号空间**。A16 重测时统一清理标签。

---

## 3. 要取证 / 清点才能定案（docs/04 §2 中归属本层的）

| # | 事项 |
|---|---|
| A10 | 内置工具线程安全审计（read/write/edit/bash/find…）：pi 契约「工具必须可并发执行」，审计未开始 |
| A14 | shell 集成夹具无牙：多字节字符在 8 KiB 边界被切断，需字节精确的假 shell |
| A15 | 错误路 `AgentSettled` 形状无夹具（非空 toolResults 的断言守护缺失） |

（A16＝六图换锚重测，跨所有层，已销号；见 `docs/04` §2。）

---

## 4. 已裁决不做（不展开）

- **R1 作废 19 个单元 ＋ drive 运行时整块**：参照物（`agent/src/harness/**` 108 文件）已被 pi 删除；完整清单见 `map/03-agent-core.md`「作废清单」。
- **R5 不可达**：pico3、durable（import 者全在 `experimental/`）。
- 替身归属：`before_payload`/`after_response`/`before_compaction` 的对应物是 coding-agent 扩展事件，不属本模块。
- **B59**（SQLite schema 分叉）：pi 的 session 后端已整包删除且从未进 `bin pi`，见 `docs/04` §4。

---

## 5. 取证口径

1. pi 侧一律 `git -C /d/workplaceForai/pi show ea448f454:<path>`，不读 pi 工作树。
2. pi-java 侧读当前 HEAD 工作树。
3. **事件类条目要 grep 到发射点**，不能只确认类型定义存在（B30 的坑：类型＋mapper 都在，缺的只是 emit）。
4. 行号会漂：引用优先给**文件 ＋ 符号/关键行**。
