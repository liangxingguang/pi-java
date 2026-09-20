# 40 - 模块对齐地图（量化）

> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
> **明细**：每模块逐条能力单元（带 `file:line` 与权重）在 `docs/map/01..06-*.md`；本文件是**索引＋总表＋计划**。
> **基准**：pi-java @ `34849a2`（2026-09-20）；pi @ 工作树 HEAD。
> **口径修订**：v1 用「能力单元计数」；**v2 改为加权能力单元**（见 §0.3）—— 用户指出计数口径既不等权、又受切分粒度影响。

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

完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0
权重 = 用户可观察影响 × 频率（不含排期优先级）
```

| 权重 | 判据 |
|---|---|
| **3** | 每轮对话都走 / 默认路径（每轮 prompt、默认车道、默认工具、默认事件流、默认配置） |
| **2** | 每次会话走 / 常用命令（会话起止、常用子命令与参数、常用配置键） |
| **1** | 低频 / 边缘 / 纯内部（罕见 flag、内部契约形状、遥测细节、一次性路径、测试基建） |
| **0** | 非目标（**排除出分母**） |

**为什么从计数改成加权**（v1 的三个缺陷，用户指出）：

| v1 缺陷 | v2 怎么解决 |
|---|---|
| 单元不等权 —— `--offline` 缺 1 条与 durable drive 整块缺 1 条在分母里等价 | 权重区分 |
| **切分粒度直接决定百分比** —— `coding-agent` 把扩展系统按 36 事件数 = 34.4%，合并成 1 单元 = 40.2%，**5.8pp 来自切法不是代码** | 粗切不改变加权和 |
| 存疑被当 0，等于二值化 —— 单元 95% 对只差一个字段也计 0 | 存疑 0.5 |

### 0.4 三个指标，各回答一个问题

| 指标 | 回答 |
|---|---|
| **加权完成度** | 整体面貌 —— 这个模块做了多少 |
| **权重 3 子集完成度** | 用户**今天会不会撞到** |
| **死功能清单**（§4，不计权重） | 哪些功能是**整块不工作**的 |

⚠️ **加权完成度对「单条高频缺失」不敏感**：`agent-core` 的 `B15 扩展思考不可达`、`A4 /compact 缺 abort-first`、
`durable drive 整块缺失` 权重全是 2，边际影响完全等量，各只值 **+0.98pp**。所以它衡量**面貌**，
不衡量「某个功能是不是死的」—— 那是 §4 的职责。

### 0.5 范围裁决（已定，2026-09-20）

| # | 裁决 | 影响 |
|---|---|---|
| R1 | **pi 删掉的，pi-java 也删** | 旧 schemas 协议层 ＋ `SessionHandle` ＋ `writer_leases` 机制 ⇒ 权重 0（§5.1） |
| R2 | **provider 只做主流** | 长尾 23 个端点 ＋ 3 条对应鉴权 ⇒ 权重 0 |
| R3 | **TUI 优先级最低** | 照常给权重（这是**排期**不是重要性），缺口标 `P3` |
| R4 | **native image 优先级最低** | 移出缺口表，标 `—` |

### 0.6 ⚠️ 加权口径的结构性盲点（已知，未修）

**单元粒度封顶了权重上限。** 一个整块缺失的子系统在单元表里天然只占 **1 行** ⇒ 最多权重 3，
不论它暴露多少行为面。这与 §0.3 表里 v1 的「切分粒度决定百分比」是**同一类错误**，只是方向相反：
v1 是「细切抬高分母」，这里是「粗切压低权重」。

**实证**（`coding-agent`）：`上下文文件发现` 记为 1 个单元、Σ权重 **6**（模块 446 的 **1.3%**），
全对齐只把模块从 44.8% 抬到 **46.2%（+1.4pp）**。但它是一条**每轮 prompt 都走的默认路径**，
真实分量应等价于 **10+ 个权重 3 单元**（5 个候选名 / 向上遍历 / `override` 语义 / 注入位置 /
与 `--no-context-files` 的联动 / 与 system prompt 源的合并顺序…）。

**受影响项**（都是「1 行代表一整个子系统」）：

| 项 | 现记 | 应记 |
|---|---|---|
| `上下文文件发现`（coding-agent） | 1 单元 / w3 | ~10+ 个 w3 单元 |
| `durable drive 运行时`（agent-core） | 1 单元 / **w2** | 5 类可观察行为（checkpoint/continuation/deferred/reconcile/recovery）各 1 单元 |
| `JSON 主题系统`（coding-agent） | 1 单元 | 选择器 + 热重载 + schema 校验 + 扩展 API 各 1 |
| `多进程子系统` | 4 单元 | 按暴露面展开 |

**影响方向**：这四项被**系统性低估**，其中 `durable drive` 与 `上下文文件发现` 都是默认路径上的整块缺失。
⇒ 本表的加权完成度对**「整块缺失」偏乐观**。§4「死功能清单」是它的补丁（不看权重只看有没有），
但**只覆盖「整块不工作」，不覆盖「整块不存在」**。彻底修法：整块缺失按「暴露的行为面个数」计入分母 ——
**尚未执行**，因为需要重新枚举这四个子系统的行为面。

---

## 1 总表

| 模块 | Σ权重 | Σ(w×c) | **加权完成度** | 未加权 | **权重 3 层** | 优先级 |
|---|---:|---:|---:|---:|---:|---|
| `pi-java-agent-core` | 214 | 133.5 | **62.4%** | 35.1% | **81.8%** | P0 |
| `pi-java-ai` | 213 | 115.5 | **54.23%** | 40.0% | 71.0% | P0 |
| `pi-java-telemetry` | 35 | 17.5 | **50.0%** | 41.2% | 64.3% | P2 |
| `pi-java-tui` | 235 | 107.5 | **45.7%** | 28.1% | 67.3% | **P3** |
| `pi-java-coding-agent` | 455 | 200.0 | **44.0%** | 32.1% | **41.3%** | P0 |
| `pi-java-session-backend-sqlite`＋`evals`＋`chord` | 271 | 56.5 | **20.8%** | — | — | P1 |
| **合计** | **1423** | **630.5** | **44.31%** | | **≈62%** | |
| ＊不含 chord＋多进程 | 1357 | 630.5 | **46.46%** | — | — | — |

> ⚠️ **2026-09-20 重测**（pi `71dca871b` → `3390bd936`，111 提交）：**49.96% → 44.31%（−5.65pp）**。
> **掉分主因不是判定变差，是分母长大** —— pi 新增 **10,275 行零对应**（`durable` 757 · `pico3` 7,994 ·
> `micro` 1,524）与若干新单元，全部落在「缺失」，分子几乎不动。**逐模块的实质变化见 `docs/map/*.md`
> 的 `## 基准漂移` 节**，锚点与取数方法见 `docs/map/ANCHOR.md`。

> 权重 3 层合计是**从 6 份报告的 w3 分层加总**（ai 87/66 · agent-core 78/64.5 · coding-agent 69/28.5 ·
> tui 78/52.5 · telemetry 21/13.5 · session 66/22.5），**不是**单一口径重算，读作量级。

### 1.1 加权 vs 未加权：方向

**除存储层外全线上升** —— 缺的多数是外围小件。唯一的例外是最值得看的：

| 模块 | 未加权 | 加权 | Δ | 读数 |
|---|---:|---:|---:|---|
| `agent-core` | 37.0% | 64.5% | **+27.5** | **权重 3 层 83.3%、零缺失** —— 核心 loop 对齐得很好 |
| `tui` | 30.1% | 47.4% | +17.3 | 对齐单元平均权重 2.25 vs 缺失 1.38 |
| `ai` | 43.5% | 59.75% | +16.3 | 抬升全来自「缺的都是长尾」的域 |
| `telemetry` | 41.2% | 50.0% | +8.8 | 权重 3 的 7 条里零缺失 |
| `coding-agent` | 32.7% | 44.8% | +12.1 | **但权重 3 层 41.3% < 权重 1 层 47.9%** ⚠️ |
| **`session`＋`evals`** | 35.5% | **36.7%** | **+1.2** | **唯一没被救起来的**（见 §1.2） |

### 1.2 两条只有加权口径才暴露的信号

**① `coding-agent`：欠账最重的地方恰是「每轮都走」的地方。**

| 权重层 | 加权完成度 |
|---|---:|
| 权重 3（每轮 / 默认路径） | **41.3%** |
| 权重 2（每次会话 / 常用） | 44.6% |
| 权重 1（低频 / 边缘） | 47.9% |

**单调递增** —— 它补齐的是低频长尾，而 `context` / `before_agent_start` / `input` / `message_update` /
上下文文件发现（AGENTS.md/CLAUDE.md）这些**每轮都走**的全没做。

**② `ai`：`usage` 是唯一没被加权救起来的域。**

| 域 | 未加权 | 加权 | Δ |
|---|---:|---:|---:|
| StreamEvent | 55.6% | 81.0% | +25.4 |
| 鉴权 | 33.3% | 56.3% | +23.0 |
| ContentBlock | 35.7% | 58.3% | +22.6 |
| Wire 适配器 | 58.3% | 78.6% | +20.3 |
| **usage** | 11.1% | **19.0%** | **+7.9** |

其余域被抬高 20pp 是因为缺的是长尾；**usage 缺的全是大件**（7 条缺失里 3 条权重 3 + 3 条权重 2）
⇒ 把长尾裁掉后，**usage 的欠账反而更刺眼**，加权后仍是全场最低。

### 1.3 重测结果（2026-09-20，pi `71dca871b` → `3390bd936`，111 提交）

**锚点与取数方法**见 `docs/map/ANCHOR.md`。**漂移不均匀** —— 集中在 `ai`（119 文件）与
`coding-agent`（149 文件），其余层几乎零变化（`protocol`/`client`/`server` **src 零改动**；
SQLite **逐字节未变**；`chord` 除 `delta/` 逐字节未变）。

#### 掉分的性质：**分母长大，不是判定变差**

| 模块 | 旧 → 新 | 主因 |
|---|---:|---|
| `agent-core` | 64.5% → **62.4%** | 新增 `pico3`/`durable` 两条权重 1 单元 + 4 条行为单元 |
| `ai` | 59.75% → **54.23%** | 9 个新单元（权重 16，全缺失）⇒ Σw 200→213，而 Σ(w×c) 反降 4.0 |
| `tui` | 47.4% → **45.7%** | pi 新增 6 个缺失单元（权重全 1）+ 1 条判定更正 |
| `coding-agent` | 44.8% → **44.0%** | 分母 +9、分子 +0 |
| `session`＋`chord` | 25.7% → **20.8%** | **全部**来自 pi 新增 44 点权重（durable 19 + pico3 19 + micro 6） |
| `telemetry` | 50.0% → **50.0%** | 零变化 |

#### pi 自己造出**两条新缺口**（上一版地图里是「对齐」）

| 单元 | 旧 → 新 | pi 改了什么 |
|---|---|---|
| **`ContextOverflow` 溢出判定** | **对齐 → 缺失** | ① z.ai 模式放宽为 `/prompt (?:is )?too long/i`；② Cerebras 模式**移出** `OVERFLOW_PATTERNS`、改按 provider 门控 ⇒ pi-java 停在旧语义：「Prompt too long」匹配不上、**任意 provider 都命中 Cerebras 模式** |
| **`RetryableError` 分类** | **对齐 → 缺失** | pi 新增 2 个可重试模式：`"currently experiencing high demand"`、`"520"` |

#### 三条台账条目的重新取证（结论有变）

| 条目 | 原记 | 重新取证 |
|---|---|---|
| **A4**（`/compact` 取消） | 「有没有门 —— pi 侧未取证」 | **pi 是 abort-first，不是 gate**：`compact()` 第一句 `await this.abort()`（旧锚点就有）；**本轮新增 4 条取消语义**（abort 信号打进鉴权取件 / `session_before_compact` 新增 `signal` / `aborted` 判定改信号权威 / 清理提到 `compaction_end` 之前）。**pi-java 四者全无** |
| **B53**（`--session` 查找范围） | 「pi 是项目内」 | ⚠️ **被证伪** —— pi 的 `resolveSessionPath` 是**三级阶梯**，**第 ③ 级就是跨项目 `listAll`**。真差距是「pi-java 直接跳到第 ③ 级、缺 ①②」＝**次序与成本**，不是「跨项目＝bug」 |
| **B55**（会话列表） | 「无 `onProgress`」 | 差距从 **1 项扩到 6 项**：进度回调 + **渐进发布部分结果** + **`AbortSignal`** + **并发扫描** + `listAll` 带 signal + 选择器接线 |

#### 与漂移**无关**的一条判定更正

**C17 模糊匹配：对齐 → 存疑** —— pi 是**子序列**匹配 + 按空白/斜杠**分词**；pi-java 是**子串**匹配 + **无分词**。
例：query `abc` vs `a-b-c` —— **pi 命中、pi-java 不命中**。原判**没核算法**就写了「对齐」。

#### pi 侧**删除**（裁决 R1 触发）

**`addedToolNames` 被 pi 整体删除**（`grep -rn addedToolNames packages/` 全仓零命中），
连同 `compat.deferredToolsMode`、`supportsToolReferences`、整个 `utils/deferred-tools.ts` 文件。
机制被「系统消息工具增量」（`SystemMessage.toolsAdded/toolsRemoved`）取代
⇒ **台账 C11 / B4 整条作废**。

---

## 2 权重 3 层的未对齐清单 —— 这是 P0 队列

跨 6 模块合并，**权重 3 且非对齐**共约 60 条（Σ权重 ≈ 150）。按模块：

### 2.1 `ai`（8 条，权重 24 / 全模块 200 = 12%，最高杠杆）

| 单元 | 判定 | 后果 |
|---|---|---|
| `usage.cacheRead` 生产者 | **缺失** | 上下文占用被低估 ⇒ 压缩时机晚于 pi |
| `usage.cacheWrite`/`cacheWrite1h` 生产者 | **缺失** | 同上 |
| `usage.cost` 计算（`calculateCost`） | **缺失** | 成本**永远显示 0** |
| `transformMessages` 跨模型重放闸 | **缺失**（1/5） | 换模型后历史消息重放不对 |
| Anthropic `cache_control` 标记 | **缺失** | 缓存不生效 |
| `openrouter` provider（chat 面） | **缺失** | — |
| `openai` 默认 wire | **存疑** | pi 绑 `openai-responses`，java 绑 `openai-completions` |
| `done` 载荷 | **存疑** | pi 带 `message`，java 带 `usage`+`partial` |

### 2.2 `agent-core`（10 条，**零缺失**）

`bash` 工具（超时语义）· `grep` 工具（缺 4 参数）· `prepareNextTurn`(A3) · `HarnessEvent` 28 类(A6) ·
`AgentSessionEvent` 面(B30/B34/B35) · `agent_end` 载荷(A8) · **Entry union（java 是 pi v3 形状）** ·
`convertToLlm`(A1) · **JSONL v4 存储** · `latest()`/`find()` 范围(B53)

⇒ **默认路径上没有整块缺失**；10 条集中在两类：**事件/载荷形状**（6 条）与**格式代差**（3 条）。

### 2.3 `coding-agent`（10 条，6 条缺失）

`context` · `before_provider_request` · `tool_call` · `tool_result`（4 条引擎钩子**存在但未桥接到扩展**）·
`before_agent_start` · `message_update` · `input`（3 条缺失）· **上下文文件发现（AGENTS.md/CLAUDE.md）** ·
`compaction` 设置(A9) · 扩展示例库

### 2.4 `tui`（11 条，6 条缺失）

`R1` Markdown 渲染 · `R11` 逐工具渲染器 · `K27` 提示历史 · `C5` spinner · `C6` working 指示器 ·
`T2` 主题 token 覆盖 —— **全是「每轮都看得见」的 TUI 能力**

### 2.5 `session`＋`evals`（22 条，13 条缺失）

存储 `commit(writes[])` · `getEntries(ids[])` · 标量值 · 列表值 · JSONL 事务行 kind ·
schema `entries`/`scalar_values`/`list_values`/`branch_meta`/`lanes`/`branch_tips`/2 触发器 · 评测真 harness

---

## 3 各模块：明细与计划

> 完整清单（逐条带 `file:line` 与权重）见 `docs/map/NN-*.md`。

### 3.1 `pi-java-agent-core` — 64.5%｜P0

**加权 64.5% ／ 权重 3 层 83.3%（21 条：14 对齐 / 7 存疑 / 0 缺失）**

**对齐**：agent loop 主体全对齐 —— 10 个 `AgentEvent` 变体 · 双循环 · `runAgentLoop`/`continue` 前置条件 ·
`shouldStopAfterTurn` · `transformContext` · 工具两相端口与 end 次序 · 并行/顺序批次选择 ·
轮内阈值压缩门 · 压缩后重建工作副本 · 上下文计量 · 输出截断 · 核心钩子 · 4 个工具逐字段相同。

**缺失（21）**：🔴 **durable drive 运行时整块缺**（pi `harness/runtime/drive/` 12 文件 / 7,505 行）·
🔴 **`/compact` 缺 abort-first**（pi `agent-session.ts:1967-1968` 第一行就是 `await this.abort()`；java 两者都没有）·
🔴 **B15 扩展思考生产不可达**（`ThinkingLevelMap.of(` 全仓恰 4 个调用者全在 `src/test`；
`AgentSession` builder 链没有 `.thinkingLevelMap(...)` 这一行 ⇒ 请求里永无 `thinking.budgetTokens`）·
branch summary ＋ `navigateTree` · 三个事件无生产者(B30/B34/B35) · `powershell` · effect gate · `sanitizeSurrogates`(B16)

**最大未得分池是权重 2（31.0 / 72.5 = 43%），不是权重 1。** 权重 2 层完成度 62.2%，含 9 条缺失 + 13 条存疑。

**计划**：① 权重 2 层逐条清（最大池）② **B15 打通**（改一行 + 生产侧构造，把死功能救活）③ `/compact` abort-first
④ **Entry v3→v4 ＋ JSONL v4**（与 §3.4 同根因，一起做）⑤ durable drive（最大块，单独设计包）

### 3.2 `pi-java-ai` — 59.75%｜P0

**加权 59.75% ／ 权重 3 层 75.9%**（29 条 w3：21 对齐 / 2 存疑 / 6 缺失）

**对齐**：4 个主流 wire 适配器 · 5 个主流 provider 端点 · 4 种核心 ContentBlock 收发 ·
4 组 StreamEvent · stop reason 映射 · `validateToolArguments`

**缺失（63）**：🔴 **Usage 四分量无生产者**（`cacheRead`/`cacheWrite`/`cacheWrite1h`/`reasoning` 全仓零生产者 ——
五条车道 `emitUsage` 只传两个数 ⇒ `Usage.of(a,b)` 钉死 0；**但下游已在读**：`ContextOverflow.java:110,120`、
`ContextUsageEstimator.java:78`、`SessionState.java:329-330` ⇒ 开 caching 时**压缩时机晚于 pi**）·
🔴 **`calculateCost` 整段无对应物** ⇒ 成本**永远 0** · 🔴 **图片在三条车道静默丢弃**（anthropic ＋ openai-completions ＋
mistral，**且 toolResult 路径也丢** ⇒ `ReadTool` 读图模型完全看不到）· 🔴 **`openai` 默认 wire 不同** ·
`transformMessages` 其余 4 条变换 · Anthropic `cache_control` · `openrouter` provider

**四条 P0 的加权边际**：

| P0 项 | 权重 | 边际 |
|---|---:|---:|
| **Usage 四分量** | 8 | **+4.00pp** |
| **`calculateCost`** | 3 | **+1.50pp** |
| 图片丢弃 | 2 | +1.00pp |
| `openai` 默认 wire | 3 | +0.75pp |
| **四条全修** | — | **+7.25pp** |

**Usage 四分量 + `calculateCost` = +5.50pp，占四条总收益 76%，且同属一个域** ⇒ 单修 usage 域：
域完成度 19.0% → **71.4%**（从全场最低跳到中游）。

**计划**：① usage 域两条（最高杠杆）② 图片三条车道 ＋ toolResult 路径 ③ `openai` 默认 wire
（**投入产出比最高**：改一个枚举值）④ `ANTHROPIC_AUTH_TOKEN`（兼容网关接得上）

### 3.3 `pi-java-coding-agent` — 44.8%｜P0

**加权 44.8% ／ 权重 3 层 41.3%** ⚠️（**低于权重 1 层的 47.9%** —— 欠账最重的地方恰是每轮都走的）

| 域 | 加权完成度 |
|---|---:|
| CLI 参数 | **76.6%** |
| 会话管理 | **72.4%** |
| Slash 命令 | 67.6% |
| 配置文件 / 清单 | 60.0% |
| settings.json 键 | 58.1% |
| RPC 面 | 54.5% |
| 子命令 | 53.8% |
| 资源发现 | 35.7% |
| **扩展 API 面** | **26.1%** |
| **扩展系统 36 事件** | **16.0%** |
| **包管理器** | **16.1%** |
| **扩展 UI 注入面** | **6.2%** |

**扩展系统三域合并**：Σ权重 134 ／ **加权 18.3%**

⚠️ **D 域（扩展 36 事件）单独把模块拉低 13.6pp** —— 它只用 36 个单元就拿到 **Σ权重 72（模块的 16.1%）**，
是 13 个域里最大的一块（第二名 E2 用 51 个单元才拿 80）。若 D 域全对齐，模块 44.8% → **58.4%**。
反过来，把 8 个「未桥接的引擎钩子」按严格口径全判缺失（Σ(w×c) 11.5 → 2.0），模块降到 **42.7%**
⇒ 那 8 个**半信用本身就值 2.1pp**。

**E6 包管理器是唯一「加权 > 未加权却几乎没动」的域**（0% → 16.1%）：非零全部来自 5 个 2 权存疑各计 0.5，
**零个对齐** ⇒ 它的加权分完全建立在「形状存在」的半个信用上。

**缺失（93）**：🔴 **上下文文件发现整块缺失**（pi 按 5 个候选名向上发现并注入系统提示词；java 全仓
`grep "AGENTS` **零命中** ⇒ **每次会话的提示词都少这一段**）· 🔴 扩展 36 事件只对 1 个 ·
🔴 包管理器 0/16 · `examples/` 15,912 行零对应 · JSON 主题系统 1,552 行整块缺 ·
交互式会话选择器 `-r` ~1,200 行缺 · CLI 缺 6 参数 · slash 缺 2

**计划**：① **上下文文件发现**（投入小、每次会话都受影响）② `entry_appended` 消息形状(B51，每轮都发) ·
③ **扩展钩子桥接**（`ExtensionContext.hookSystem()` 打通，再按事件补）④ settings 键批量补 19 个 ·
⑤ 包管理器（单独设计包）

### 3.4 `pi-java-session-backend-sqlite` ＋ `pi-java-evals` — 36.7%｜P1

**加权 36.7%**（Σw 154）／ 含 chord＋多进程则 **25.7%**（Σw 220）

**缺失（32）**：🔴 **SQLite schema 完全分叉**（pi 7 张表 vs java 11(+2)；**同名只有 3 个**；
pi 有 java 无 4 张：`scalar_values`（承载整个 durable operation 状态机）/`list_values`/`usage_ledger`/`branch_meta`；
java 有 pi 无 10 张含 FTS5。**根因是两种存储范式**：pi 的 `Write` 只有 4 kind（`entry`/`usage`/`value`/`list`），
java 的 `SessionMutation` 是 `entry`/`record`/`lane`/`fact`，**交集只有 `entry`**）·
🔴 **JSONL v4 双向不可读**（pi 写 `{v:4, storageVersion:1}`，java 写 `{version:4}`；事务行 kind 交集只有 `entry`
⇒ **pi 读不了 java 的文件，java 读不了 pi 的文件**）· 2 个触发器缺 · 评测缺真 harness 等 9 条

**两条关键差距的性价比**：

| 差距 | 权重 | 修好后（不含 chord） | 每点权重换 |
|---|---:|---:|---:|
| **SQLite schema 分叉** | **25** | 36.7% → **52.9%**（+16.2pp） | 0.65 pp/点 |
| **JSONL v4 双向不可读** | **5** | 36.7% → **39.9%**（+3.2pp） | **0.64 pp/点** |

权重差 5 倍，**边际效率相同** —— 但 JSONL v4 是**用户可见的硬故障**（换机/换工具后会话直接读不出来），
不是形状偏差。**schema 分叉是单点最大拖累**（占本范围 Σ权重的 17.6%，一分不得）。

**计划**：① JSONL v4 头部对齐（投入小、硬故障）② **删 `writer_leases` 与租约机制**（裁决 R1，连带 A17）
③ SQLite schema —— 需先裁决「跟 pi 的 4-kind `Write` 模型，还是保留 java 的 lane/record/fact」

### 3.5 `pi-java-tui` — 47.4%｜**P3（优先级最低）**

**加权 47.4% ／ 权重 3 层 67.3%**（26 条 w3：15 对齐 / 5 存疑 / 6 缺失）

| 域 | 加权完成度 |
|---|---:|
| web 对齐面 | **78.8%** |
| TUI 组件 | 58.8% |
| 键绑定 / 交互 | 47.6% |
| 主题 / 样式 | 43.3% |
| 屏幕 / 面板 | 38.6% |
| **渲染特性** | **16.2%** |

**🔴 两条死功能**（见 §4）：`MarkdownRenderer` ＋ `SyntaxHighlighter` 是生产死代码 ⇒ **TUI 完全不渲染 Markdown**；
**6 个已声明的键位一个都没接线**（`PiTuiApp.java:437` 的 `default ->` 是空的）。

**计划**：按裁决 R3 排最后。**但 §4 那两条死码要先决定**：接线还是删声明 —— 留着会让人以为 Markdown 和高亮是能用的。

### 3.6 `pi-java-telemetry` — 50.0%｜P2

**加权 50.0%**（Σw 35）／ 权重 3 层 64.3%（7 条：2 对齐 / 5 存疑 / **0 缺失**）
**存疑 10 条全是形状差异**（`startSpan` 回调同步 vs Promise · `TelemetrySpan` 有 `end()` vs pi 无 ·
`AttributeValue` 类型闭集放宽 · `setAttributes` 整包合并 vs 单键）
**缺失 5 条全在权重 1–2**（`addEvent` · `setStatus` · 内存实现 · **adapter 一致性套件** · 类型化 schema）
**计划**：P2。adapter 一致性套件值得补（pi 的 9 用例，java 只满足 2 条）—— 它会把形状差异变成红灯。

---

## 4 死功能清单（不计权重，只看「有没有」）

加权完成度**看不见**这一类：一个功能整块不工作，但如果权重是 2，它和「一个罕见钩子没做」等价。
所以单列。

| # | 死功能 | 证据 | 用户感知 |
|---|---|---|---|
| 1 | **扩展思考（thinking）生产不可达** | `ThinkingLevelMap.of(` 全仓恰 4 个调用者**全在 `src/test`**；`AgentSession` builder 链没有 `.thinkingLevelMap(...)` ⇒ `forLevel(Enabled)` 恒 `OFF` ⇒ 请求里永无 `thinking.budgetTokens` | 开了也没用，无报错 |
| 2 | **TUI 完全不渲染 Markdown** | `MarkdownRenderer.java:21` 全仓 grep **只命中自身与测试**；助手消息实际走 `MessageBubble.java:80` 的纯文本 `TextLayout.split` | 表格/代码高亮/mermaid/链接全不可见 |
| 3 | **TUI 6 个键位未接线** | `KeybindingsManager.java:22-29` 定义了 `MODEL_CYCLE`/`THINKING_CYCLE`/`TOOLS_EXPAND`/`THINKING_TOGGLE`/`EXTERNAL_EDITOR`/`DEQUEUE`，`PiTuiApp.java:437` 的 `default ->` 一个都没接 | 按键无反应 |
| 4 | **成本永远显示 0** | `calculateCost` 无对应物 ⇒ `Usage.Cost` 恒零 | 状态栏成本恒 0 |
| 5 | **图片模型看不到** | 三条车道 ＋ toolResult 路径静默丢弃 `ImageContent`；`ReadTool.java:86` 正好返回它 | 读图无报错、无内容 |

---

## 5 整块缺失与作废

### 5.1 作废（裁决 R1 —— 参照物已被 pi 删除）

| 对象 | 依据 |
|---|---|
| **旧 schemas 协议层**（9 命令 / 9 结果 / 4 事件 / 快照族 / 闭集错误码） | pi `e52de91d0`（2026-08-13）换成 service-addressed RPC，删掉 `packages/protocol/src/schemas.ts` |
| `SessionHandle` | 同提交删掉 `client/src/session-handle.ts`(111)、`state.ts`(156) |
| **`writer_leases` 表 ＋ 租约机制** | pi `001_initial.sql` 已无此表；`repo.test.ts:369` **断言它不存在**，`:376` 用例名「ignores a stale writer_lease table」 |

⚠️ **「三模块整块作废」是错的** —— 分界**按层不按模块**：

| 层 | pi 现状 | pi-java | 判定 |
|---|---|---|---|
| CBOR / framing | **仍在**，逐条相同 | 有 | **对齐** |
| 旧 schemas 命令/结果/事件层 | **已删** | 有 | **作废** |
| **service-addressed RPC**（`RpcTarget`/`attachment`/`service_update`/`SessionRouter`/unix listener） | **现有架构** | **无** | **真缺口** |
| `SessionHandle` / `writer_leases` | 已删 | 有 | **作废** |

⇒ 正确动作是：**删旧 schemas 层 ＋ `SessionHandle` ＋ `writer_leases`，然后裁决「RPC 要不要做」**。
pi 是**重写**了 client/server，不是删掉。
**三模块消费者为零**（只被彼此 ＋ 1 个集成测试 import；CLI 零 `--serve`/`--connect`）⇒ 删除零连带。

### 5.2 整块缺失（子系统级，不进能力单元分母）

| 子系统 | pi LOC | pi-java | 影响 | 优先级 |
|---|---:|---|---|---|
| **`examples/` 可执行验收规格** | **15,809** | 0 | 101 个 `.ts` / 78 个扩展示例 —— 它同时是 pi 扩展 API 的**可执行定义** | **P1** |
| **`pico3`**（**2026-09-20 新增**） | **7,994** | 0 | 硬化的会话内核（Harness / Scheduler / 9 种 kinds / ViewManager / Membrane…）。⚠️ **pi 侧也只被 `micro` 用，而 `micro` 无人用** | **待裁** |
| **pi 防漂移脚本** | **8,325**（40 文件） | 0（部分替代） | 其中 **8 个是真漂移门**；pi-java 用 Checkstyle/SpotBugs/enforcer ＋ **L5 差分**（pi 没有，比 pi 任何脚本都强） | P2 |
| **`chord` RPC 基座** | **6,503**（+4,376 测试） | 0 | facet / 复制状态 / 服务 RPC / delta 词汇 | **要做**（F9） |
| **多进程子系统** | ~3,000 | 0 | coordinator / session-worker / Unix socket 三层拓扑 | **要做**（F9） |
| **`micro`**（**新增**） | **1,524** | 0 | 单进程 Pico3 编码代理（含 `proper-lockfile` 文件锁）。⚠️ **pi 全仓无人 import** | **待裁** |
| **`durable`**（**新增**，独立包） | **757** | 0 | 会话/任务/文档持久运行时（四表模型 / 三份文档 / `ContextEdit`）。⚠️ **pi 全仓无人 import** | **待裁** |

> **2026-09-20 新增三块合计 10,275 行零对应**。⚠️ **三者在 pi 自己那边也没接线**：
> `durable` 全仓无人 import；`pico3` 只被 `micro` 用；`micro` 全仓无人 import
> ⇒ **一条自闭合的链，从 CLI 不可达**。**pi 包数 11 → 12。**

**待裁项对总分的影响**：~~裁掉 chord＋多进程把存储层从 25.7% 抬到 36.7%（+11.0pp）~~
⚠️ **2026-09-20 作废**：F9 已裁「对齐 pi ⇒ 做」⇒ 这笔账**不再成立**（它们要做，不是裁掉）。

---

## 6 LOC 的位置：审计列，不是分数

**LOC 不作为完成度**（v1 的错，用户指出）。它唯一站得住的用途是**检验单元枚举有没有漏** ——
两个方向都报警，而这两个信号比完成度本身更有价值：

| 模块 | LOC 比 | 加权完成度 | 信号 |
|---|---:|---:|---|
| `sqlite` | **146%** | 36.7% | java **写的代码比 pi 多，但对上的少** ⇒ 两种存储范式，不是「做得更多」 |
| `coding-agent` | **15%** | 44.8% | pi 体量远超已枚举的单元 ⇒ **有整块没被枚举**（`examples/` 15,912） |
| `agent-core` | 65% | 64.5% | 接近 —— 枚举覆盖得住 |
| `tui` | 17% | 47.4% | pi 的 39,010 行里 **18,107 是 TUI 框架层**，pi-java 侧由外部库 TamboUI 顶替 ⇒ **不该进分母** |

| 模块 | pi LOC（旧 → **新**） | java LOC |
|---|---:|---:|
| `coding-agent` | 70,052 → **73,781** | 10,325 |
| `tui`（＋app 层） | 39,010 → **41,219** | 6,712 |
| `agent-core` | 25,305 → **33,353**（+31.8%） | 16,386 |
| `ai` | 24,383 → **25,096** | 11,590 |
| `chord`（**新增行**） | 5,822 → **6,503** | **0** |
| `durable`（**新增行**，新包） | — → **757** | **0** |
| `session-backends` | 1,973（未变） | 2,875 |
| `evals` | 1,964 → **1,446** | 773 |
| `telemetry` | 935（未变） | 787 |
| **合计（同口径 7 行）** | **163,622 → 177,803**（+8.7%） | 48,661 |

> **2026-09-20 重测**。⚠️ `agent` 的 +8,048 行里 **7,994 行是未接线的 `harness/pico3/`**（见 §5.2），
> 其余各 `harness/*` 子目录**逐文件行数全等**；`evals` 的 −518 行是**换架构**（能力净增，见 `docs/map/06`）。
> `protocol`/`client`/`server` 三模块 **src 零改动**（869 / 1,135 / 1,966）。

---

## 7 台账纠错（实测 19 条）

### 7.1 反向误报：源码**已修**，台账还挂着（5 条）

| 条目 | 台账 | 实际 |
|---|---|---|
| **B22** | 仍必修 | **已修**（`ResponsesMessageConverter.java:212` 已设 `.id("msg_pi_"+msgIndex)`） |
| **A16** | 登记，单独一包 | **已修**（`FauxChatProvider` 已 `extends AbstractChatApi`） |
| **A13** | 待裁 | **已修** |
| **H-9.3 根 entry `parentId`** | OPEN | **已修**（`JsonlCodec.java:149-152` 显式 `putNull`） |
| **H-9.3 `/import` `/export` 未实现** | 扫描标 CLOSED，台账判「应为 OPEN」 | **台账这格错了 —— 应为 CLOSED**（`MiscCommands.java:38,61` 已接线） |

### 7.2 范围 / 问法写错（9 条）

| 条目 | 台账 | 实际 |
|---|---|---|
| **B17** | 「Anthropic 车道独缺」 | **四条车道丢三条**（＋openai-completions ＋mistral），**且 toolResult 路径也丢** |
| **B16** | 「全仓 54 处」 | 实测 **57 处**（结论不变：java 零处） |
| **B24** | 「`choice.usage` 回退」 | **表述低估**：java 也**不读 `cached_tokens`**，且 `cost` **永不计算** |
| **A4** | 「运行中 `/compact` 有没有门」 | **问法错**：pi 本来就没有门，`compact()` **第一行是 `await this.abort()`** ⇒ 真缺口是「缺 abort-first」 |
| **A5** | 「并发 prompt 有没有门」 | **问法错**：pi-java **有门**；真差的是 pi 的两条**路由语义**（`streamingBehavior` ＋ 压缩中禁 prompt） |
| **B55** | 引 `session-manager.ts:772` | 行号偏移，`SessionListProgress` 在 **`:768`** |
| **B46** | 「生产上永不发射」 | 需限定：**会话层有出口**，真命题是「**无生产数据源**」 |
| **F6** | 归属 `coding-agent` | 实现落在 `pi-java-ai`，症状在本模块可见 |
| **H-9.3 JSONL 搜索后端** | 「pi 有 java 无」 | **口径错**：pi **没有任何搜索实现**（只有接口）⇒ pi-java 自设目标，**非对齐缺口** |

### 7.3 行号漂移 / 结论过期（5 条）

`E4`（`AgentHarness` 502→**514** 行）· `H-9.2-⑨`（行号漂到 `:63-65`，结论仍成立）·
`H-9.2-⑦`（「思考内容按 TextContent 处理」**已过期**，包①后已发 `ThinkingStart`）·
`H-9.2-⑧`（**已定但结论与设计稿相反**：落地的是不给消息带 timestamp）·
`E6`（`SqliteSessionStorage` 已压到 **463** 行，只剩 `AgentHarness`）· `E7`（重复的是**三处**不是两处）

---

## 8 台账**未登记**的真缺口（本次实测撞出，10 条）

| # | 缺口 | 后果 | 优先级 |
|---|---|---|---|
| 1 | **Usage 四分量无生产者** | 开 caching 时上下文被低估 ⇒ **压缩时机晚于 pi** | **P0** |
| 2 | **`calculateCost` 整段缺失** | 成本**永远显示 0** | **P0** |
| 3 | **`openai` 默认 wire 不同** | 同一模型两侧发不同 wire | **P0** |
| 4 | **SQLite schema 完全分叉**（3/7 同名） | 两种存储范式，`Write` 交集只有 `entry` | P1 |
| 5 | **JSONL v4 双向不可读** | pi 与 pi-java **互相读不了会话文件** | P1 |
| 6 | **Entry 面是 pi 的 v3 形状**（java 8 类 vs pi v4 4 类） | 与 #5 同根因 | P1 |
| 7 | **`chord` 5,822 行零对应零文档** | 5 个 pi 包的真依赖 | 待裁 |
| 8 | **防漂移脚本 40 文件零对应** | 8 个真漂移门 | P2 |
| 9 | **9 处 javadoc 引用不存在的 pi 文件** | 最严重：`WriterLease.java:8` 引 `writer-leases.ts`，**pi 明文删掉了它** | P2 |
| 10 | **`client/src/unix.ts:35` 在 Windows 抛错** | 该子系统在开发环境本就不可用 | — |

---

## 9 后续计划

> ⚠️ **2026-09-20 重写**：F 类九条已全部裁定（`docs/32 §7`）—— **F1/F2/F4/F6–F9 对齐 pi**、F3 先不发。
> 本节据此重写：**原 A 阶段「删三模块」不成立**（F7/F9 裁「对齐 pi」⇒ 三模块改为**重建**），
> 原 E 阶段「待裁项」全部有答案，原「裁掉 chord＋多进程 ⇒ 存储层 +11.0pp」那笔账**作废**（它们要做）。

| 阶段 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| **A. 删旧层** | 旧 schemas 协议层 ＋ `SessionHandle` ＋ `writer_leases` 机制（连带 A17 那个查不到的 flake） | —— | 待出设计文档 |
| **B. 台账重建** | 修 19 条错 ＋ 补 14 条漏 ＋ 4 处结构缺陷 | —— | ✅ 已完成（`17a3c86`） |
| **C. 小裁决闭环** | F6 取证（pi 侧 `baseUrl` 谁赢）＋ F1/F2/F4 的行为改动 | —— | 待出设计文档（**四条都小，可快速闭环**） |
| **D. `chord` 地基** | `chord` 5,822 行（+3,553 测试）—— **F7/F9 的前置，依赖顺序第一位** | A | 待出设计文档（**本项目最大的一块**） |
| **E. RPC 三模块重建** | `protocol` / `client` / `server` 按 pi **现架构**（service-addressed RPC） | D | —— |
| **F. 多进程子系统** | 三层进程拓扑（coordinator / session-worker / Unix socket，~3,000 行） | E | —— |
| **G. SQLite 重写** | 跟 pi 的 4-kind `Write` 模型（F8）⇒ ⚠️ **连带 durable drive 7,505 行**（`scalar_values` 承载的正是 durable operation 状态机） | 可与 E/F 并行 | —— |
| **H. P0 修行为** | usage 域两条（+5.50pp，四条 P0 里 76% 的收益）· 图片三车道 ＋ toolResult 路径 · 上下文文件发现 · 扩展钩子桥接 · B15 打通 | 独立，**可随时插队** | —— |
| **I. P3** | TUI（裁决 R3 排最后）。**但 §4 的两条死码先决定：接线还是删声明** | —— | —— |

**D–G 合计 ≈ 22,300 行 pi 参照 ≈ pi 全库（152,511 行）的 15%**，且是 pi 自己标为 `experimental/` 的那一块。
**H 与 A–G 完全独立** —— 建议先走 H 与 C（小、行为可见、能快速闭环），再动 D。

### 9.1 验证面（与 A–H 并行）

**L5 差分 14/14 绿**是硬证据，但是**窄而深的切片**：provider 是脚本化的（**五条真实车道一条都不测**）、
`ConformanceRunner` **直连 `PiLoop` 绕过 `PiLaneEngine`**、剧本里 **0 个 thinking 块**、
`pi-out` 是 **2026-09-14 的录像而 pi 已前进 67 个提交**。

⇒ ① 把可脚本化的位置下移到 **wire 层**（让五条车道进差分）② **把文档里那 55–65 条变异探针变成能自动跑的测试**
（现在只存在于文档，不随代码回归）。

---

## 10 未覆盖 / 未做

- 未改任何生产代码；未动 `docs/32`（纠错只记在本文件与 `docs/map/`）。
- `docs/01`/`02`/`04` 的过期未修。~~`phase1-pi-code-mapping`~~ ⚠️ **2026-09-20：该对照表已删**（映射到 pi 已删目录、其「~92%」不可通约）。
- `web` 模块（pi 无对应物，属 pi-java 独有）未计完成度；17 条非对齐项见 `docs/map/05`。
- 权重 3 层合计（≈62%）是**从 6 份报告的分层加总**，不是单一口径重算。
- 各报告在过程中自查改正了自己的计数错误（agent-core 2 处、session 3 处、ai 1 处文件损坏重建），
  最终数字均经 `awk` 从文件重算核对。
