# 06 - 模块对齐地图（量化）

> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
> **明细**：每模块逐条能力单元（带 `file:line` 与权重）在 `docs/map/01..06-*.md`；本文件是**索引＋总表＋计划**。
> **基准**：pi-java @ **`351d199`**（2026-10-04）；pi @ **`200387122`**（2026-10-04）。
> **口径修订**：v2 用「加权能力单元」（见 §0.3）—— 用户指出计数口径既不等权、又受切分粒度影响。

> ## ⚠️ 2026-10-04 换锚 —— 本文件的数字与 2026-09-20 版**不可直接相减**
>
> pi 从 `3390bd936` 前进 **251 提交**，其中 `7fd478a2e`（2026-10-01）**删了 105,456 行** ——
> `packages/session-backends` 整包 ＋ `packages/agent/src/harness/**`（108 文件），并把 harness 搬进
> **主流不可达**的 `packages/durable`（换锚裁决记录见 git 历史中已删除的 docs/11）。
>
> 因此本次是**换锚**，不是刷新：**分母（Σ权重）从 1,423 降到 1,273**，主因是 §5.1 的 R1 作废面与
> 「整块不可达 ⇒ 权重 0」的 R5 收紧，**不是 pi-java 变强或变弱**。逐模块的对照见 §1。

---

## 0 口径

### 0.1 判定单位 = 能力单元

一个**可被外部观察到的行为承诺**：一条 CLI 参数 / 一个 slash 命令 / 一个工具 / 一种事件类型 /
一个配置键 / 一个 provider 适配器 / 一个 ContentBlock 变体 / 一张数据库表 / 一个 UI 屏幕。
**不**把内部类、私有方法、实现细节算作单元。

### 0.2 三档

| 档 | 判据 |
|---|---|
| **对齐** | pi 有，pi-java 有，且**行为相同**（读过两侧代码确认语义，不是看类名） |
| **缺失** | pi 有，pi-java 无 |
| **存疑** | 两侧都有但**形状/语义不同**，或行为未核，或台账标为待裁决 |

⚠️ 没有「都有但形状不同」这一档 —— 按判据它**不能算对齐**，一律落**存疑**。

### 0.3 加权完成度（v2 口径）

```
完成度 = Σ(权重 × 完成系数) / Σ权重

完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0 ／ 作废 0
权重 = 用户可观察影响 × 频率（不含排期优先级）
```

| 权重 | 判据 |
|---|---|
| **3** | 每轮对话都走 / 默认路径（每轮 prompt、默认车道、默认工具、默认事件流、默认配置） |
| **2** | 每次会话走 / 常用命令（会话起止、常用子命令与参数、常用配置键） |
| **1** | 低频 / 边缘 / 纯内部（罕见 flag、内部契约形状、遥测细节、一次性路径、测试基建） |
| **0** | 非目标（**排除出分母**）—— 含 pi 已删除的面（R1）与 `bin pi` 不可达的面（R5） |

### 0.4 三个指标，各回答一个问题

| 指标 | 回答 |
|---|---|
| **加权完成度** | 整体面貌 —— 这个模块做了多少 |
| **权重 3 子集完成度** | 用户**今天会不会撞到** |
| **死功能清单**（§4，不计权重） | 哪些功能是**整块不工作**的 |

⚠️ **加权完成度对「单条高频缺失」不敏感**：`coding-agent` 的「上下文文件发现」、`agent-core` 的
「Entry union 形状」，权重都是 2–3，边际影响完全等量。所以它衡量**面貌**，不衡量「某个功能是不是死的」
—— 那是 §4 的职责。

### 0.5 范围裁决（累计）

| # | 裁决 | 日期 | 影响 |
|---|---|---|---|
| R1 | **pi 删掉的，pi-java 也删** | 2026-09-20 | 旧 schemas 协议层 ＋ `SessionHandle` ＋ `writer_leases`（§5.1） |
| R2 | **provider 只做主流** | 2026-09-20 | pi 的 42 个 catalog provider 里 22 个长尾 ＋ `meta`/`radius`/`typesafe` ⇒ 权重 0（`docs/map/02`） |
| R3 | **TUI 优先级最低** | 2026-09-20 | 照常给权重（这是**排期**不是重要性），缺口标 `P3` |
| R4 | native image 优先级最低 | 2026-09-20 | 移出缺口表，标 `—` |
| R5 | **只做 `bin pi` 主流发布版可达的功能** | 2026-09-21 | 不可达 ⇒ 权重 0、不进分母。**本轮把 `durable` 也判为不可达** |
| R1′ | 换锚后新触发的 R1 面 | **2026-10-04** | `session-backends` 整包 · `agent/src/harness/**` · `pico3` · `micro`/`mini` 前端（§5.1） |

### 0.6 ⚠️ 加权口径的结构性盲点（已知，本轮**未修但已量化**）

**单元粒度封顶了权重上限。** 一个整块缺失的子系统在单元表里天然只占 **1 行** ⇒ 最多权重 3，
不论它暴露多少行为面。**实证（2026-10-04）**：

| 项 | 现记 | 应记 | 证据 |
|---|---|---|---|
| `编码代理新增子系统`（`04` G 域） | 5 单元 / **Σ权重 18** | ~40+ w3 单元 | **10,994 行** pi 主流代码（MCP 3,167 ＋ codemode 1,678 ＋ …），pi-java 侧仅 MCP 面已落（`pi-java-mcp` 7,273 行），却只占模块的 3.6% |
| `上下文文件发现`（`04` E4-5） | 1 单元 / w3 | ~10+ w3 单元 | 每轮 prompt 都走的默认路径（5 候选名 / 向上遍历 / `override` 语义 / 注入位置 / `--no-context-files` 联动 / 与 system prompt 的合并顺序…） |
| `主题/色彩系统`（`05`） | 3 单元 / Σ权重 18 | 更大 | pi side **3,176 行** vs pi-java **74 行**（2 个 `.tcss`） |
| `durable drive 运行时` | — | — | **本轮 R5 作废**（不可达），盲点自然消失 |

⇒ 本表的加权完成度对**「整块缺失」偏乐观**。彻底修法仍是：整块缺失按「暴露的行为面个数」计入分母 —— **未执行**。

---

## 1 总表

| 模块 | Σ权重 | Σ(w×c) | **加权完成度** | 未加权 | **权重 3 层** | 旧 Σw | 旧完成度 | **Δ** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `pi-java-ai` | 227 | 183.5 | **80.84%** | 64.6% | **100.0%** | 213 | 54.23% | **+26.6** |
| `pi-java-agent-core` | 184 | 123.0 | **66.8%** | 38.3% | 76.2% | 214 | 62.4% | +4.4 |
| `pi-java-telemetry` | 35 | 17.5 | **50.0%** | 41.2% | 64.3% | 35 | 50.0% | 0 |
| `pi-java-tui` | 241 | 108.0 | **44.8%** | 27.1% | 67.3% | 235 | 45.7% | −0.9 |
| `pi-java-coding-agent` | 499 | 204.5 | **41.0%** | 29.9% | **39.6%** ⚠️ | 455 | 44.0% | −3.0 |
| `pi-java-session`＋`evals` | 87 | 34.0 | **39.1%** ＊ | 40.0% | — | 271 ＊ | 20.8% ＊ | +18.3 ＊ |
| **合计** | **1,273** | **670.5** | **52.67%** | — | — | **1,423** | **44.31%** | **+8.36** |

> ＊ **`session` 那行的新旧值不同口径**：旧 271 含 `chord`(55) ＋ 多进程(11) ＋ `durable`/`pico3`/`micro`(44) ＋
> 防漂移脚本(12) ＝ **122 点不可达/已删除权重**；本轮按 R5/R1 全部移出 ⇒ 旧值**不可比**，见 §1.3。
>
> ⚠️ 本表**不含** `pi-java-protocol`/`client`/`server` 三模块 —— 按用户裁决整体排除出分母
> （§5.1 R1），只有 telemetry 计入。它们的未加权数字见 `docs/map/01`。
> `pi-java-web` 是 pi 无对应物的本仓自有面，不计完成度。

### 1.1 换锚后三条结构性信号

**① `ai` 从全场中游跳到 80.84%，而且不是靠分母缩小 —— 是权重 3 层**32 条全部对齐**（旧图 10 条未对齐、占 43.7% 杠杆）。**

2026-09-22 之后的包把旧图的每条高权缺口都填了：usage 四分量 ＋ `calculateCost`（H1）｜图片四车道（H2）｜
`cache_control`（A-01）｜OpenRouter chat（A-02）｜OpenAI 默认 wire（D3）｜`AuthKind` 凭证链（A-02/B1）｜
`thoughtSignature` 往返（Batch F）｜`thinkingFormat` 十一形状（A-09）｜`maxTokens` 生产者（A-10）｜
provider retry（A-14）｜grammar（E）｜metadata（E）。**剩余缺口全在 w1/w2 的广度。**

**② `coding-agent` 掉到 40.8%（−3.2pp），且权重 3 层（39.6%）仍**低于**权重 1 层（43.9%）。**

单调倒挂 —— 它欠账最重的地方恰是「每轮都走」的地方。**这一轮 −3.2pp 全部来自 pi 新增的缺失面**：
5 块新主流子系统（MCP 9 / codemode 4 / tool-search 2 / virtual-models 2 / nested-tool-calls 1 ＝ **Σ18，全部缺失**）
＋ 折叠进既有单元的新面 13（4 个新扩展事件 ＋ 3 个新 settings 键 ＋ `mcp` 子命令 ＋ RPC disposition 等）
＝ **+31 权重**。**pi-java 侧同期只翻案了 2 条**（`retry`、`--offline`）。

**③ `session` 那行从 20.8% 升到 39.1%，但**升降只由分母驱动**，不是 pi-java 干了什么。**

旧文 20.8% 是**对着已删除/不可达子系统**算的低分。剔除后剩 87 点权重，完成度 39.1%（旧口径 36.2%）。
**存储后端是唯一实质降分处**（54.5% → 48.9%），降因＝新公式把 15 行 harness-only 单元移出、
同时补入 2 行主流新能力（`context_edit`、会话文件惰性创建）且均为缺失。

### 1.2 两条只有加权口径才暴露的信号

| | 未加权 | 加权 | Δ | 读数 |
|---|---:|---:|---:|---|
| `agent-core` | 38.3% | 66.8% | **+28.5** | 权重 3 层 76.2%、**零缺失** —— 核心 loop 对齐得很好 |
| `tui` | 27.1% | 44.8% | +17.7 | 对齐单元平均权重高（默认路径），缺失集中在渲染长尾 |
| `ai` | 64.6% | 80.84% | +16.2 | 抬升全来自「缺的都是长尾」 |
| `telemetry` | 41.2% | 50.0% | +8.8 | 权重 3 的 7 条里零缺失 |
| `coding-agent` | 29.9% | 41.0% | +11.1 | **但权重 3 层 39.6% < 权重 1 层 43.9%** ⚠️ |
| `session`＋`evals` | 40.0% | 39.1% | −0.9 | **唯一加权低于未加权的**（缺的是高权单元） |

### 1.3 换锚的结果（2026-10-04，pi `3390bd936` → `200387122`）

**掉分/涨分要分开读 —— 三类效应混在一次重测里：**

| 效应 | 方向 | 证据 |
|---|---|---|
| **① 分母修正**（R1/R5 收紧） | 抬高加权完成度 | `session` 剔 122 点不可达权重；`04` 剔 `micro`/`mini`；`03` 作废 19 单元 ＋ 1 整块 |
| **② pi 新增缺失面** | 压低加权完成度 | MCP/codemode 等 **10,994 行**进分母且全缺（`04` −3.2pp）｜OKLab/system 主题/滚轮（`05` −0.9pp）｜ai 中段工具、分类器（`02`） |
| **③ pi-java 自身前进** | 抬高加权完成度 | `ai` +26.6pp（w3 层从 10 条未对齐到 0）｜`agent-core` 4 条真闭环 |

**结论**：`ai`/`agent-core` 的涨分**是真的**（②③ 与它们关系小）；`session` 的涨分**基本全来自 ①**；
`coding-agent`/`tui` 的跌分**是 ②**（pi 长出了新面，pi-java 静止）。

### 1.4 ⚠️ 两条旧判定被本轮**证伪**

| # | 旧判定 | 实测 |
|---|---|---|
| 1 | 「SQLite schema 分叉」＝全项目单点最大拖累（Σ权重 25，+16.2pp） | **自始即错** —— `git grep -l session-backends 3390bd936 -- 'packages/*/src/**'` **只命中它自己的 `package.json`** ⇒ 旧锚点即零消费者，从没上过 `bin pi`。整段作废（§5.1） |
| 2 | 「JSONL v4 双向不可读」＝用户可见硬故障 | **参照物已删，但故障仍在、根因换了**：pi 主流是 **v3**（`{type:"session",version:3}`，`coding-agent/src/core/session-manager.ts:41`），pi-java 是 **v4**（`{kind:"header",version:4}`，`JsonlV4Header.java`）⇒ **判别键、版本号、时间戳类型、父级键四处全不同**（§8-1） |

---

## 2 权重 3 层的未对齐清单 —— 这是 P0 队列

跨 6 模块合并，**权重 3 且非对齐**约 **45 条**（Σ权重 ≈ 110）。按模块：

### 2.1 `ai` —— **零条** ✅

32 个权重 3 单元**全部对齐**（上一版：10 条未对齐）。高权面已无差距。

### 2.2 `agent-core`（10 条，**零缺失**，全是「存疑」）

`bash` 工具（超时语义）· `grep` 工具（缺 4 参数）· **`finishTurn`**（pi 已从布尔改成 `{action:"continue"|"end"}`）·
**输出截断**（pi 主流 50KB vs 本仓 100KB —— 模型可见文案不同）· `should_stop`/`prepare_next_turn` 钩子 ·
`AgentSessionEvent` 面 · `agent_end` 载荷 · **Entry union**（pi 主流 11 类 vs 本仓 7 类）·
`convertToLlm` · JSONL 存储（v4 vs v3）· `latest()`/`find()` 范围 · 系统提示作为 system 消息

⇒ **默认路径上没有整块缺失**；10 条集中在**事件/载荷形状**与**格式代差**。

### 2.3 `coding-agent`（18 条，11 缺失 / 7 存疑）

**引擎钩子**（`context_with_system`／`before_agent_start`／`message_update`／`input` 4 条**缺失**；
`context`／`before_provider_request`／`tool_call`／`tool_result` 4 条存疑）·
**上下文文件发现（AGENTS.md/CLAUDE.md）** · 扩展示例库 · 包管理器（npm 源 ＋ `settings.packages`）·
RPC 命令集与事件线格式（存疑）· `compaction` 设置 · **`examples/` 可执行验收规格**

### 2.4 `tui`（9 条，6 缺失）

`R1` Markdown 渲染 · `R11` 逐工具渲染器 · `K27` 提示历史 · `C5` spinner · `C6` working 指示器 ·
`T2` 主题 token 覆盖 —— **全是「每轮都看得见」的 TUI 能力**（其中 `MarkdownRenderer`/`SyntaxHighlighter`
**存在但是死代码**，§4-2）

### 2.5 `session`＋`evals`

**JSONL 头部线格式**（`{type:"session",version:3}`）· `context_edit` 条目 · list 进度/部分结果/中止/并发扫描

---

## 3 各模块：明细与计划

> 完整清单（逐条带 `file:line` 与权重）见 `docs/map/NN-*.md`。

### 3.1 `pi-java-ai` — **80.84%**｜P0

**权重 3 层 100.0%（32 条全对齐）**

| 域 | 加权完成度 |
|---|---:|
| token 计量 / usage | **100.0%** |
| Provider 端点（19，剔 25 长尾） | **91.9%** |
| ContentBlock 变体 | **91.7%** |
| StreamEvent 类型 | 88.1% |
| Wire 适配器 | 78.6% |
| 横切能力 | 76.4% |
| 鉴权 / env | 75.0% |
| **模型目录 / 元数据** | **57.1%** |
| **模型类型统一 / 分类器** | **0.0%** |

**剩余差距的边际**：5 条 w2 缺失（xai / 目录规模 / `ContextOverflow` / `RetryableError` / **Anthropic 中段工具内联**）
⇒ +4.40pp；全部 w1 缺失 ⇒ +6.60pp。**全部补完 +19.16pp。**

**最大簇 = 「目录规模」**（本仓内置目录 vs pi 的 42 provider）**与「分类器基础设施」**（#9948 统一 image/classifier）。

**计划（2026-10-04 快照；执行情况见 docs/04）**：目录规模、
`ContextOverflow`/`RetryableError` 新增匹配模式等 —— 后续按台账条目落地
③ Anthropic **`inline-tools-2026-09-15`**（pi 已把中段工具从 `tool_reference+defer_loading` 换成工具定义内联，
可表达同名重定义）④ 分类器面（`typesafe` 仍权 0）

### 3.2 `pi-java-agent-core` — **66.8%**｜P0

**权重 3 层 76.2%（21 能力单元 ＋ 5 工具：11 对齐 / 10 存疑 / 0 缺失）**

**锚点本轮改指**：参照物从被删除的 `agent/src/harness/**` 改为
① agent loop 本体 `packages/agent/src/{agent-loop,agent,types,proxy,stream-fn,index}.ts`
② harness 的**主流副本** `coding-agent/src/core/*`（`agent-session`/`session-manager`/`compaction`/`messages`/`tools`…）。

**作废 19 单元 ＋ 1 整块**（R1/R5）：pico3 · durable drive · effect gate · `run_suspend` · `HarnessEvent` ·
harness 专有钩子 · 存储套件 · 搜索类型。

**真闭环 4 条**（旧图记缺失、现况已落）：`declareToolChanges` · `prepareNextTurn` 返 messages ·
`formatSkillsForSystemPrompt` · 扩展思考目录→请求投送。

**新增/翻案为存疑 3 条**：`finishTurn`（语义变了）· 输出截断（字节预算差）· Entry union（缺口扩大）。

**计划（快照）**：JSONL v3 线格式、Entry union 11 类、`finishTurn` 语义
—— 已在包 12–23 落地，剩余项见 docs/04（如 B159）
④ 截断字节预算对齐 ⑤ 事件/载荷形状那一簇

### 3.3 `pi-java-coding-agent` — **41.0%**｜P0

**权重 3 层 39.6%** ⚠️（**低于权重 1 层的 43.9%**）

| 域 | 加权完成度 |
|---|---:|
| A. CLI 参数 | 77.4% |
| E3. 会话管理行为 | 68.8% |
| C. Slash 命令 | 65.8% |
| E1. 配置文件 / 清单 | 60.0% |
| E2. settings.json 键 | 55.2% |
| E7. RPC 面 | 50.0% |
| B. 子命令 | 46.7% |
| **E4. 资源发现** | **35.7%** |
| **E5. 扩展 API 面** | **24.1%** |
| **E6. 包管理器** | **16.1%** |
| **D. 扩展系统 41 事件** | **13.9%** |
| **D′. 扩展 UI 注入面** | **6.3%** |
| **G. 主流新增子系统** | **5.6%** |

**D 域（扩展 41 事件）单独把模块拉低 14.3pp** —— 它只用 41 个单元就拿到 **Σ权重 83（模块的 16.6%）**，
是 15 个域里最大的一块。若全对齐，模块 41.0% → **55.3%**。

**扩展系统三域合并（D ＋ D′ ＋ E5）**：Σ权重 157 ／ 加权 **16.9%**。

**G 域（6 块新子系统）＝ 本轮换锚带来的新大陆**：MCP（Σw 9）· codemode（4）· tool-search（2）·
virtual-models（2）· nested-tool-calls（1）＋ 分散 13 —— **共 10,994 行 pi 主流代码；pi-java 侧已落 MCP 面 7,273 行（包①–⑧），codemode / tool-search / virtual-models / nested-tool-calls 仍 0**。

**计划（快照）**：上下文文件发现（B64，未做）、扩展钩子桥接；现状见 docs/04
③ settings 键批量补 ④ **MCP**（新大陆里最大的一块）⑤ 包管理器（单独设计包）

### 3.4 `pi-java-session` ＋ `pi-java-evals` — **39.1%**｜P1

**存储后端 48.9%**（20 行）／ **评测 29.1%**（20 行）

**两条关键差距的边际**：

| 差距 | Σ权重 | 现 Σ(w×c) | 修好后 | 对本范围（分母 87） |
|---|---:|---:|---:|---:|
| **JSONL 格式分叉（v3 vs v4）** | **8** | 0 | 8 | 39.1% → **48.3%（+9.2pp）** |
| `context_edit` 上下文改写 | 3 | 0 | 3 | 39.1% → **42.5%（+3.4pp）** |
| 两条同时修 | 11 | 0 | 11 | 39.1% → **51.7%（+12.6pp）** |

**JSONL 格式分叉是当前单点最大拖累**（占本范围 9.2%），且是**用户可见的硬故障**（换机/换工具后会话读不出）。

⚠️ **`pi-java-session-backend-sqlite` 模块**：参照物（pi `session-backends`）**已整包删除**（R1），
本包只标注、**不主张删除模块** —— 删模块是独立裁决（涉及 35 个测试与整条存储链）。

**计划（快照）**：JSONL v3 双向可读、`context_edit` 已落地；B55
（list 进度/部分结果/中止/并发，**权重只有 1，
但决定大目录扫描时选择器能否边扫边显示**）

### 3.5 `pi-java-tui` — **44.8%**｜**P3（优先级最低）**

| 域 | 加权完成度 |
|---|---:|
| web 对齐面 | **78.8%** |
| 键绑定 / 交互 | 46.9% |
| TUI 组件 | 56.2% |
| 屏幕 / 面板 | 36.2% |
| 主题 / 样式 | 36.1% |
| **渲染特性** | **14.5%** |

**🔴 两条死功能**（§4）：`MarkdownRenderer` ＋ `SyntaxHighlighter` 是生产死代码 ⇒ **TUI 完全不渲染 Markdown**；
**6 个已声明的键位一个都没接线**（`PiTuiApp.java:437` 的 `default ->` 是空的，K6–K11）。

**主题/色彩系统是最大单块缺口且仍在扩大**：pi side **3,176 行**（`theme.ts` 1,159 ＋ `system-theme.ts` 655 ＋
`colors.ts` 367 ＋ `oklab.ts` 233 …），pi-java **74 行**（2 个 `.tcss`）。本轮 pi 新增 OKLab/OKHSL 色值系统与
由终端调色板派生的 system 主题 —— **不是补上旧缺口，是又加了两条缺失单元**。

**计划（快照）**：TUI 按 R3 排最后；死码「接线还是删声明」见 docs/04（B65/B66）。

### 3.6 `pi-java-telemetry` — **50.0%**｜P2

**权重 3 层 64.3%（7 条：2 对齐 / 5 存疑 / 0 缺失）**

**存疑 10 条全是形状差异**（`startSpan` 回调同步 vs Promise · `TelemetrySpan` 有 `end()` vs pi 无 ·
`AttributeValue` 类型闭集放宽 · `setAttributes` 整包合并 vs 单键）
**缺失 5 条全在权重 1–2**（`addEvent` · `setStatus` · 内存实现 · **adapter 一致性套件**（pi 的 9 用例）· 类型化 schema）

**计划（快照）**：adapter 一致性套件（未做，可把形状差异变成红灯）。

### 3.7 `protocol` / `client` / `server` — **排除出分母**（R1）

三模块的未加权数字：protocol 46.4% ／ client 19.4% ／ server 18.2%。**没有一条「接线对上了」** ——
protocol 的 RPC 层是**已删除 pi 代码的移植**，client/server 是**无消费者的孤岛**（`PiServerService` 无实现类，
CLI 零 `--serve`/`--connect`）。详见 `docs/map/01` §0.2/§0.3。

---

## 4 死功能清单（不计权重，只看「有没有」）

加权完成度**看不见**这一类：一个功能整块不工作，但如果权重是 2，它和「一个罕见钩子没做」等价。

| # | 死功能 | 证据 | 用户感知 | 状态 |
|---|---|---|---|---|
| 1 | **上下文文件发现整块不存在** | `04` E4-5：pi `resource-loader.ts:185`（5 候选名）＋ `loadProjectContextFiles:232`；pi-java 全仓零对应，`--no-context-files` **零消费者** | **每次会话的系统提示词都少这一段**，且一个 flag 静默无效 | 🆕 本轮点名 |
| 2 | **TUI 完全不渲染 Markdown** | `MarkdownRenderer.java:21` 全仓 grep **只命中自身与测试**；助手消息实际走 `MessageBubble.java:78` 的纯文本 `TextLayout.split` | 表格/代码高亮/mermaid/链接全不可见 | 仍在 |
| 3 | **TUI 6 个键位未接线** | `KeybindingsManager.java:22-29` 定义了 `MODEL_CYCLE`/`THINKING_CYCLE`/`TOOLS_EXPAND`/`THINKING_TOGGLE`/`EXTERNAL_EDITOR`/`DEQUEUE`，`PiTuiApp.java:437` 的 `default ->` 一个都没接 | 按键无反应 | 仍在 |
| 4 | **语法高亮的代码块路径是死的** | `SyntaxHighlighter.java:16` 只 3 色；调用点只有 `EditorElement.java:123`（输入编辑器当前行），**代码块路径 `MarkdownRenderer.java:98` 无人调** | 代码块无高亮 | 🆕 本轮拆出 |
| 5 | ~~扩展思考生产不可达~~ | — | — | ✅ **已闭环**（H5，2026-09-23） |
| 6 | ~~成本永远显示 0~~ | — | — | ✅ **已闭环**（H1，2026-09-22） |
| 7 | ~~图片模型看不到~~ | — | — | ✅ **已闭环**（H2，2026-09-22） |

---

## 5 整块缺失与作废

### 5.1 作废（裁决 R1 —— 参照物已被 pi 删除）

| 批 | 对象 | 依据 |
|---|---|---|
| **2026-09-20** | 旧 schemas 协议层（9 命令 / 9 结果 / 4 事件 / 快照族 / 闭集错误码） | pi `e52de91d0`（2026-08-13）换成 service-addressed RPC，删掉 `packages/protocol/src/schemas.ts` |
| 2026-09-20 | `SessionHandle` | 同提交删掉 `client/src/session-handle.ts`(111)、`state.ts`(156) |
| 2026-09-20 | `writer_leases` 表 ＋ 租约机制 | pi `001_initial.sql` 已无此表；`repo.test.ts:369` **断言它不存在** |
| **2026-10-04** | **`packages/session-backends` 整包**（pi 旧 1,973 行 src） | `7fd478a2e`（2026-10-01）整包删除；**且旧锚点即零消费者**（唯一命中是它自己的 `package.json`） |
| 2026-10-04 | **`packages/agent/src/harness/**`**（108 文件）、`search/`、`node.ts` | 同提交。`agent` 包 33,353 → **2,513 行（−92.5%）** |
| 2026-10-04 | `coding-agent/src/experimental/{micro,mini}/`（23 文件） | 同提交（experimental 前端） |
| 2026-10-04 | `ai/src/{images-models.ts, image-models.generated.ts, providers/openrouter-images.ts}` | 被 `#9948 unify image and classifier model infrastructure` 取代 |
| 2026-10-04 | `harness/*` 专有钩子（9 个）、pico3、effect gate、`run_suspend`、`HarnessEvent` | 随 harness 删除；主流替身是 coding-agent 的扩展事件（属 `04` 的域） |

⚠️ **harness 的内容并未消失，而是搬进了 `packages/durable`**（实锤 rename：`harness/tools/edit.ts`→`durable/src/tools/edit.ts` 等）。
`durable` 现 **17,716 行 / 61 个 src 文件**，但**它的全部 import 者都在 `coding-agent/src/experimental/**`**，
非 experimental 主源码一处都不 import ⇒ **R5 不可达 ⇒ 权重 0**。

### 5.2 整块不可达（子系统级，不进能力单元分母）

| 子系统 | pi LOC | pi-java | 裁决 |
|---|---:|---|---|
| **`durable`**（harness ＋ session storage 的新家） | **17,716** | 0 | **R5 不可达**（import 者全在 experimental） |
| **`chord` RPC 基座** | **8,817**（+35.6%） | 0 | **R5 不可达** |
| **多进程子系统** | ~3,000 | 0 | **R5 不可达** |
| **`examples/` 可执行验收规格** | 15,809 | 0 | **P1**（它同时是 pi 扩展 API 的可执行定义） |
| **pi 防漂移脚本** | 9,060（`scripts/`） | 0（部分替代） | **口径外** —— pi-java 用 Checkstyle/SpotBugs/enforcer ＋ **L5 差分**（pi 没有） |
| ~~`pico3`~~ / ~~`micro`~~ | — | 0 | **已删除（R1′）** |

### 5.3 🆕 本轮新增的主流面（pi 有、pi-java 全 0）

| 面 | pi 载体 | 规模 | 在 `04` 的权重 |
|---|---|---:|---:|
| **MCP**（客户端 / stdio·streamable-http·in-memory 传输 / OAuth 全套） | `packages/mcp` ＋ `extensions/mcp/*` ＋ `core/mcp-servers.ts` | 3,167 | 9 |
| **codemode**（quickjs-wasi 沙箱代码执行） | `packages/codemode` ＋ `extensions/codemode/*` | 1,678 | 4 |
| **tool-search** | `extensions/tool-search/*` | — | 2 |
| **virtual-models** | `core/virtual-models.ts` | — | 2 |
| **nested-tool-calls** | `core/nested-tool-calls.ts` | — | 1 |

两者在 `coding-agent/src/extensions/index.ts` 注册为 **`builtin: true`** ⇒ 每次会话加载，**确在 `bin pi` 主流路径上**。

---

## 6 LOC 的位置：审计列，不是分数

**LOC 不作为完成度。** 它唯一站得住的用途是**检验单元枚举有没有漏**：

| 模块 | pi 侧 | pi-java 侧 | 比 | 信号 |
|---|---:|---:|---:|---|
| `coding-agent` | 84,990 | 11,188 | 13% | pi 体量远超已枚举的单元 ⇒ **有整块没被枚举**（`examples/` 15,809 ＋ 新子系统 10,994）；新子系统的 MCP 面已在**新模块** `pi-java-mcp` 落地 **7,273 行**，按 `docs/28` §2 的裁决**不并入本模块 Java LOC** |
| `ai` | 26,341 | 24,141 | **92%** | 接近 ⇒ 枚举覆盖得住，与 80.84% 的完成度互证 |
| `agent-core` | 2,513（**−92.5%**） | 17,599 | **700%** | java 写的比 pi 多 7 倍 —— 因为 **harness 的主流副本在 `coding-agent/src/core/*`**，不在 `agent` 包里 |
| `tui` | 19,277 | 6,707 | 35% | pi 的 TUI 框架层由外部库 TamboUI 顶替 ⇒ **不该全进分母** |
| `session` | **0（参照物删除）** | 2,875 | — | 参照物已删；两种存储范式从未对上 |
| `telemetry` | 935 | 787 | 84% | 接近 |

**pi 规模表（src `.ts` 行数）见 `docs/map/ANCHOR.md`** —— 本轮 `agent` −92.5%、`session-backends` −100%、
`durable` +23×、新增 `mcp`/`codemode` 两个包。

---

## 7 台账纠错（2026-10-04 复核）

### 7.1 反向误报：源码**已修**，旧图还挂着（本轮清了 8 条）

`calculateCost` 恒零（H1）· usage 四分量（H1）· 图片三车道（H2）· Anthropic `cache_control`（A-01）·
`openrouter` provider（A-02）· OpenAI 默认 wire（D3）· `AuthKind` 凭证链（A-02/B1）· `thoughtSignature`（Batch F）·
`SanitizeSurrogates`（A0）· `maxTokens` 生产者（A-10）· `thinkingFormat`（A-09）· provider retry（A-14）

### 7.2 口径错 / 结论过期

| 条目 | 旧记 | 本轮 |
|---|---|---|
| **SQLite schema 分叉** | 「全项目单点最大拖累 Σ权重 25、+16.2pp」 | **自始即错** —— 参照物旧锚点即零消费者 ⇒ 整段作废（§1.4-1） |
| **JSONL v4** | 「参照物是 pi 的 v4 格式」 | **参照物已删**；pi 主流是 **v3**，本仓的 v4 是**第三种格式**（§8-1） |
| **输出截断字节预算** | 记 pi 为 100KB ⇒ 判「对齐」 | **实测 pi 主流 50KB**（`core/tools/truncate.ts:12`）vs 本仓 100KB ⇒ 降为**存疑**（§8-2） |
| **Entry union** | 「pi 4 类、本仓 8 类」 | pi 主流已 **11 类**，本仓 7 类 ⇒ **缺口扩大** |
| **`shouldStopAfterTurn`** | 「对齐」 | pi 已更名 **`finishTurn`** 且语义变为 `{action:"continue"\|"end"}` ⇒ **存疑** |

### 7.3 行号漂移（本轮全量重取）

**pi 侧**：`docs/map/*` 的每条引用已对新锚 `200387122` 重取。
**pi-java 侧**：**253 个提交使行号普遍漂移** —— 六份地图共更正 **~130 处**（`01` 约 10 · `02` 12 · `03` ~14 ·
`04` 21 · `05` 53 · `06` 12）。**教训：地图两侧的证据都会过期，只核 pi 一侧是不够的。**

---

## 8 台账**未登记**的真缺口（本轮实测撞出）

| # | 缺口 | 后果 | 优先级 |
|---|---|---|---|
| 1 | **JSONL 线格式分叉（pi v3 vs 本仓 v4）** | pi 与 pi-java **互相读不了会话文件**（判别键 `type`/`kind`、版本 3/4、时间戳 ISO/epoch、父级键都不同） | **P1** |
| 2 | **输出截断字节预算 100KB vs pi 主流 50KB** | **模型看到的截断提示文案不同** | P1 |
| 3 | **Entry union 11 类 vs 7 类** | 与 #1 同根因 | P1 |
| 4 | **`finishTurn` 语义** | pi 能**强制多跑一轮**（`{action:"continue"}`），本仓旧布尔做不到 | P1 |
| 5 | **MCP / codemode / tool-search / virtual-models 零对应** | 10,994 行主流能力全缺 | P1 |
| 6 | **上下文文件发现整块缺失** | 每次会话的提示词都少一段（§4-1） | **P0** |
| 7 | **主题/色彩系统 74 行 vs 3,176 行** | 主题 token 覆盖、OKLab 色值、system 主题全无 | P3 |
| 8 | **分类器基础设施（#9948）** | 统一 image/classifier 类型系统零对应 | P2 |
| 9 | **Anthropic 中段工具内联（`inline-tools-2026-09-15`）** | 本仓停在 `tool_reference+defer_loading` 旧形状，表达不了同名重定义 | P1 |
| 10 | **pi-java 侧自身也在漂** | 六份地图的 pi-java 行号平均漂 ~20 处/图 | 维护面 |

---

## 9 计划项的处置

> 本节是 2026-10-04 快照时的后续计划。其中 A–C 的绝大部分已在**包 12–23**
> 执行完毕（JSONL v3 双向可读、context_edit、压缩切点投影、split-turn、
> 摘要 prompt、思考级别透传、customInstructions 通道等），对应台账行 B155–B174
> 均已销号（见 docs/04）。D/E/F 中未做的项（MCP、codemode、tool-search、
> 扩展事件面、TUI 接线等）已迁入 docs/04 台账继续追踪。
>
> 换锚时旧的 chord/多进程/SQLite 重写阶段计划**整块作废**（R5 不可达，见 §5.2）。
>
> **验证面现状**：L5 差分仍是窄而深的切片（脚本化 provider、直连 PiLoop、
> 无 thinking 剧本）；真实车道的覆盖主要由各包变异探针承担。
> **地图的维护性**：pi/pi-java 两侧行号都会随提交漂移，`check-drift.sh`
> 只覆盖 pi 一侧；本文件数字是快照，不作活状态使用。

---

## 10 未覆盖

- `pi-java-web` 模块（pi 无对应物，本仓独有）未计入完成度；17 条非对齐项见 `docs/map/05`。
- **`pi-java-session-backend-sqlite` 模块的存废未裁**（pi 的参照后端已删，但删模块需独立裁决）。
