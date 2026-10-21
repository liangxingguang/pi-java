# 05 - 模块对齐地图（量化总表）

> 【背景摘要】本文档回答一个问题：pi-java 各模块相对 pi 的对齐完成度是多少。它只做**索引＋总表＋P0 队列**；逐模块的逐条能力单元（含两侧 `file:line` 证据、判定、权重）在 `docs/map/01..07-*.md`，开放缺口的权威台账是 [`04-open-items-register.md`](04-open-items-register.md)。历史换账与旧数对比在 git（`git log -- docs/05-module-alignment-map.md`）。

## 术语（首次出现释义，后文直接使用）

- **pi / 锚点 / 对齐 / 闭环 / 工单编号（Bxxx）/ 裁决**：见 [`04-open-items-register.md`](04-open-items-register.md) §0。
- **能力单元**：一个可被外部观察到的行为承诺（一条 CLI 参数 / 一个 slash 命令 / 一个工具 / 一种事件类型 / 一个配置键 / 一个 provider 适配器 / 一个 ContentBlock 变体 / 一个 UI 组件）。
- **判定三档**：**对齐**＝pi 有、pi-java 有、且行为相同（读过两侧代码，不是看类名）；**缺失**＝pi 有、pi-java 无；**存疑**＝两侧都有但形状/语义不同、行为未核、或台账待裁决（计半分）。
- **权重**：用户可观察影响 × 频率（不含排期优先级）。3＝每轮对话都走/默认路径；2＝每次会话走/常用；1＝低频/边缘/内部；0＝非目标（移出分母）。
- **权重 0 的两类**：**R1**＝pi 已删除的面（pi 删掉的，pi-java 也删）；**R5**＝`bin pi` 主流发布版不可达的面（experimental、durable、codemode、chord 等）。
- **加权完成度** ＝ Σ(权重 × 完成系数) / Σ权重。完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0。
- **分母**：计入完成度统计的单元集合；「移出分母」＝不再参与完成度计算。
- **原 docs/NN**：已删除的过程文档编号，内容在 git 历史可查。

## 基准与进度快照

- 基准：pi @ **`ea448f454`**（2026-10-11，v1.1.0-22）；pi-java @ **`edc8749d`**（2026-10-12）。
- 七份地图（含 2026-10-12 新建的 `map/07-mcp.md`）已于 2026-10-12 对两基准逐条重测完毕（原 A16 销号）。
- **ai 层**：批 0–10 全部闭环（2026-10-12 → 10-16）。完成度 85.0% → **94.5%**（207.0/219）。逐批节点：
  - 批 0（2026-10-12，原 docs/08）：11 个单元移出分母、#87 转对齐，完成后 89.7%。
  - 批 1／批 2（2026-10-13 闭环）：90.2%。批 2 为单元 #6 Mistral 窄角修复，加权不变。
  - 批 3a 模型目录数据面（2026-10-14 闭环）：92.2%。
  - 批 5 消息字段/终局计量（2026-10-15 闭环）：92.7%。
  - 批 7 共享回调/ChatGPT 订阅/copy-code（2026-10-16 闭环）：93.8%。
  - 批 9 图片缩放/注记消费链（2026-10-16 闭环）：94.1%。
  - 批 10 federation（2026-10-16 闭环）：94.5%。
  - 批 3b／批 4／批 6／批 8 销号的都是台账行为项，读数不变。
  - 剩余计分母缺失仅 1 条：#8。
- **agent-core 层**：批 1–4 全部闭环（2026-10-17 → 10-18）。
  - 批 1：72.1% → **75.7%**（138.5/183，+6.5）。内容：B158 截断 50KB、B159/#41 finishTurn 裁决形状、#17 /compact abort-first。合计 57.8% → 59.5%（824.5/1385）。
  - 批 2：75.7% → **81.1%**（148.5/183，+10.0）。内容：#34 prepareRequest 写钩子、#46 族会话事件面漏斗（含 #52 本 pass 载荷、agent_settled.aborted 即 B235 销号）、#58 stopReason 剔除删除、#54/#78/#100 三条裁决。权重 3 层 22/22 = 100%。合计 59.5% → 60.3%（834.5/1385）。未加权 57.6%（53/92；批 1 记的 45/92 是误计，实为 46/92，本批按表内点算更正）。
  - 批 3：81.1% → **83.9%**（153.5/183，+5.0）。内容：bash/grep/find/ls 四个工具的模型可见面逐字对齐、glob 改名 find。工具表 71.4% → 95.2%（20.0/21）。权重 3 层含工具 27/27 = 100%。合计 61.5% → 61.8%（851.0 → 856.0/1384）。未加权 57.6% → 62.0%（57/92）。
  - 批 4：83.9% → **91.5%**（166.5/182，+13.0；#68 streamProxy 按裁决① R5 出分母，Σw 183→182）。内容：技能族 #62/#63/#64、branch summary 族 B1（#24/#25/#27＋navigateTree）、工具族 B242/B243/B53、API 面 #48/B30/#55/#69/#79/B55/#98/B36；powershell 本体＋注册＋默认不激活（激活面随 B236）⇒ 工具表 **21/21 = 100%**；**确认缺失清零**；新登记 B244/B245。合计 61.8% → 62.8%（856.0 → 869.0/1383）。未加权 62.0% → 75.8%（69/91）。
  - 批 5：91.5% → **100%**（180/180，+14.5；#60 PendingEntry/InboxItem 按 R1 出分母，Σw 182→180、91→90）。内容：存疑 22 条全收编——#57 bashExecution 全链（Entry 变体＋编解码＋投影逐字＋recordBashResult/pending flush）、#77 navigateTree 工具恢复、#75 fork 消费 `--session-id`＋删 `" (fork)"` 后缀、#87 双 twin thinking 帧＋S16 对拍零 diff、#89/#96 夹具、#94 拆 PiLaneSink/AgentHarness（470/438）、#95 字节精确假 shell、#81 并发审计＋FileMutationQueue 共享单例修真、#83 清点、#85/#92/#93 javadoc；8 裁决（**B49 销号**、#14/#56/#80/#82/#84/#90/#91）。**存疑清零 ⇒ 模块 100%**（未加权 90/90 同）。合计 62.8% → 63.9%（869.0 → 882.5/1381）。
- **telemetry 层**：批 1 闭环（2026-10-17，原 docs/23），完成度 100%。

---

## 0 口径

### 0.1 判定单位与三档

一个能力单元是判定的最小单位。三档判据：

| 档 | 判据 |
|---|---|
| **对齐** | pi 有、pi-java 有，且行为相同（读过两侧代码，不是看类名） |
| **缺失** | pi 有、pi-java 无 |
| **存疑** | 两侧都有但形状/语义不同，或行为未核，或台账待裁决（计半分） |

### 0.2 加权完成度

```
完成度 = Σ(权重 × 完成系数) / Σ权重        完成系数：对齐 1.0 ／ 存疑 0.5 ／ 缺失 0
权重 = 用户可观察影响 × 频率（不含排期优先级）
  3 = 每轮对话都走/默认路径   2 = 每次会话走/常用   1 = 低频/边缘/内部   0 = 非目标（出分母）
```

权重 0 含两类：**R1**（pi 已删除的面）与 **R5**（`bin pi` 主流不可达：experimental、durable、codemode、chord 等）。

### 0.3 范围裁决（累计）

| # | 裁决 |
|---|---|
| R1 | **pi 删掉的，pi-java 也删**（旧 schemas 协议层、session-backends 整包、agent harness 108 文件、micro/mini 前端、image registry 旧三件等） |
| R2 | **provider 只做主流 7 家**（Anthropic/OpenAI/Google/xAI/DeepSeek/Mistral/OpenRouter），22 个长尾＋meta/radius/typesafe 权重 0 |
| R3 | TUI 优先级最低（照常给权重，缺口标 P3） |
| R4 | native image 移出缺口表 |
| R5 | **只做 `bin pi` 主流发布版可达的**；durable/codemode/chord/pico3 不进对齐面（codemode 依赖项 B161/B162/B177 在册触发） |

### 0.4 分母去重

MCP 在两处有账：`map/04` 的 G-1..G-5 是 5 个粗单元（Σw 9／Σwc 7），`map/07` 是 89 个细单元。
**总表按「map/04 剔除 G-1..G-5 ＋ map/07 全量」计**，避免重复。telemetry 计入总表。
protocol/client/server 按 R1 整体排除（未加权数字见 map/01）。pi-java-web 是 pi 无对应物，不计完成度。

---

## 1 总表（coding-agent 批 9 后值，2026-10-22；agent-core 批 5 后值，2026-10-18；telemetry 批 1 后值，2026-10-17；ai 批 10 后 2026-10-16；批 3b/4/6/8 为台账行为项、加权不变；其余模块为 2026-10-12 重测值）

| 模块（地图） | Σ权重 | Σ(w×c) | **加权完成度** | 未加权 | 权重 3 层 |
|---|---:|---:|---:|---:|---:|
| pi-java-ai（map/02，**批10后**） | 219 | 207.0 | **94.5%** | 85.5%（100/117） | **100%（33/33）** |
| pi-java-mcp（map/07，**新图**） | 129 | 98.0 | **76.0%** | 62.9%（56/89） | 100%（1/1） |
| pi-java-agent-core（map/03，**批5后**） | 180 | 180.0 | **100%** | 100%（90/90） | **100%（27/27）** |
| session-sqlite ＋ evals（map/06） | 87 | 44.5 | **51.1%** | 50.0%（20/40） | — |
| pi-java-telemetry（map/01，**批1后**） | 34 | 34.0 | **100%** | 100%（16/16） | 100%（21/21） |
| pi-java-tui/web（map/05） | 245 | 108.5 | **44.3%** | 25.7%（38/148） | 67.3% |
| pi-java-coding-agent（map/04，剔 MCP 粗单元） | 487 | 286.0 | **58.7%** | 46.9%（136/290） | 73.9%（17/23） |
| **合计（去重后，coding-agent 批9后）** | **1,381** | **958.0** | **69.4%** | — | — |

> map/04 原始口径（含 G-1..G-5）：Σw 496 ／ Σwc 290.5 ／ **58.6%**；两处账都在，总表用去重值。
> ⚠️ 批 4 复核修正：D 域基线逐行实算 11.5（旧汇总行 10.5，存疑漏计 1.0）＋设计稿 §8 正文 Δ 加总错误（30.5→32.5）⇒ 账面较批 4 设计稿预期（258.5/52.1%）高 3.0。
> protocol/client/server（R1 排除）未加权：map/01 全层 10 对齐 / 40 缺失 / 35 存疑（85 单元）。

**读数**：

- **ai 层权重 3 全齐**：每轮都走的请求/响应/计量主路无缺口。批 10 后计分母缺失仅 1 条（#8 namespace），无半成品——#90 图片缩放消费链已落 agent-core/coding-agent，#98 federation 批 10 闭环。分类器面已裁 R5。
- **mcp 模块本体完成度高（76%）**，但会话内接线面是缺口（B221：六个 on() 事件 0/6、激活门、ui.custom、工具渲染器、提示分节、registerMcpServer 挂载点）。这批缺口对应 map/07 G 组 17 单元里的 15 个缺失——**进程外 `pi mcp` CLI 可用、会话内 MCP 不激活**。
- **coding-agent 最低**：批 9 后欠账集中在扩展系统剩余面（D 域加权 88.0%——存疑清零，缺失 6 行各随 B221/E5/子系统；D′ UI 注入 6.3%、E5 27.6%、包管理器 16.1%）。
- **agent-core 批 5 后 180/180 = 100%**（第二个满分模块）：权重 3 层 27/27、工具表 21/21、确认缺失与存疑**双清零**（批 5 收编 22 条存疑：13 做、8 裁决、#60 R1 出分母），未得分池 = 0。

---

## 2 各模块现状（详见各图）

### 2.1 ai — 94.5% ｜ map/02（批 10 后）

最强的一层：usage 计量 100%、wire 适配器 97.1%、provider 端点 97.3%、元数据 92.3%、ContentBlock 91.7%、横切 93.4%。

批 0–10 全部闭环（2026-10-12 → 10-16，逐批账面见 map/02 头部，实施记录在原 docs/08–19）：

- 11 个单元移出分母：分类器/统一模型类型/图像目录按 R5 移出，bedrock/vertex/codex 权重 0，cf-binding 作废。
- npm 目录 17 分片逐字节入资源＋manifest sha256 锚。
- thinking/effort 三层通道。
- OAuth 伪装＋共享回调。
- ChatGPT 订阅特判。
- 图片缩放消费链（ImageIO 替代 photon）。
- federation 接线（anthropic-java 2.66.0 代劳协议面）。

残余计分母缺失仅 1 条：#8 namespace（随 MCP 包 B203）。其余欠账全是存疑方言（见 docs/06 §3）。

### 2.2 mcp — 76.0% ｜ map/07（2026-10-12 新建）

配置/协议/传输/OAuth/工具内容/CLI 六组加权 83–96%；**G 组生命周期与接线 8.7%**（B221 是下一包主战场）。
方言项（B181–B220 族）多判存疑：mcp-auth.json 字节形态、错误文本、WHATWG URL、redirect 平台替代等。
codemode-only 的 4 个单元（exposure/deferred/discovery）权重 0，B161/B162 闭环时重裁。

### 2.3 agent-core — 100% ｜ map/03（批 5 后）

批 1–5 全部闭环（2026-10-17 → 10-18，逐批账面见 map/03，批 1–3 实施记录在原 docs/20–22，批 4/5 设计稿 docs/09/25）。闭环内容：

- 截断预算 50KB、`finishTurn` 裁决形状、`/compact` abort-first。
- `prepareRequest` 写钩子。
- #46 族会话事件面重构（五帧＋`AgentSettled{aborted}`＋单一漏斗）。
- stopReason 剔除删除、#54/#78/#100 三条裁决。
- bash/grep/find/ls 四工具模型可见面逐字对齐（glob 改名 find）。
- 批 4：技能族（`/skill:` 展开、loadSkills/loadPromptTemplates 面）、branch summary 族（生成器＋navigateTree＋设置，B1 销号）、工具族（PI_\* 注入 B242、powershell B243、find 阶梯 B53 三销号）、API 面（session_info_changed B30、fromHook、setDefaultStreamFn、列表 onProgress B55、abortCompaction B36 五销号）；#68 streamProxy 裁决① R5 出分母。
- 批 5：#57 bashExecution 全链（`Entry.BashExecution` 变体＋jsonl 互读修复＋投影逐字＋`recordBashResult`/pending flush）、#77 navigateTree 工具恢复、#75 fork 消费 `--session-id`＋删 `" (fork)"` 后缀、#87 双 twin thinking 帧＋S16 对拍零 diff、#89/#96 夹具、#94 拆 `PiLaneSink`/`AgentHarness`（470/438，行为零变化）、#95 字节精确假 shell、#81 并发审计＋`FileMutationQueue` 共享单例修真、#83 消费者清点、#85/#92/#93 javadoc；8 裁决（**B49 销号**、#14/#56/#80/#82/#84/#90/#91，入 docs/04 §4）；#60 R1 出分母。

现状：**模块 100%**——加权 180/180、未加权 90/90、权重 3 层含工具 27/27、工具表 21/21、确认缺失与存疑**双清零**、未得分池 = 0。（批 4 登记的 B244/B245 已分别随 coding-agent 批 2/批 8 销号。）

### 2.4 coding-agent — 58.7%（去重）｜ map/04

A CLI 参数 80.6%、C slash 68.4%、E3 会话管理 68.8%；**D 扩展事件 88.0%（批 8 后）、D′ UI 注入 6.3%、E6 包管理器 16.1%、E5 扩展 API 27.6%** 拉低整体。
G 域新子系统：MCP 明细在 map/07；codemode 3,957 行＋tool-search/virtual-models/nested 766 行仍为 0（B161/B162）。
新缺口：B237（RPC `handled` disposition）。
- 批 1（2026-10-18 闭环，B236/docs/11）：`--tools` 三层语义（`*` 通配/`+name`/`-name` 修饰符/`mcp__` 保留）＋`--exclude-tools` 通配 denylist＋settings `defaultTools`（merge/resolve）＋注册面门接线（`createAllTools` 八件经 `_isAllowedTool` 过滤）＋默认激活集 **7→4**（裁决①）；map/04 A-20/A-21/E2-35 翻对齐（+3.5）；**B236 销号**、B246 新登记（reload 臂＋SDK 抛错面）；43.2% → **43.9%**（未加权 31.7% → 32.8%）。合计 63.9% → 64.2%（882.5 → 886.0/1381）。
- 批 2（2026-10-19 闭环，B244/docs/12）：`/skill:` 命令注册面三层——settings `enableSkillCommands` accessor（缺省 true）＋设置页字段、TUI 补全目录 skill 臂（u/p/t 来源标签）、`CommandRegistry.dispatch` `/skill:` 透传（批 4 展开器由此接通）＋RPC `get_commands` skill 臂；map/04 C-#25/E2-31/E4-2 翻对齐（+5.0）；**B244 销号**、B247 新登记（prompt template 注册面＋RPC 形状对齐）；43.9% → **45.0%**（未加权 32.8% → 33.8%；map/04 原始口径 44.6% → 45.6%）。合计 64.2% → 64.5%（886.0 → 891.0/1381）。
- 批 3（2026-10-19 闭环，B247/docs/13）：prompt template 注册面两层（补全目录模板臂——pi 无门＋u/p/t 标签＋argumentHint；dispatch 已注册模板名透传）＋模板 `sourceInfo` 加载期打标（新叶子包 `core.source`）＋RPC `get_commands` 线格式一次收平（`{commands}` 包键、`source`/`sourceInfo`、去内置、删 `argumentHint`；扩展臂随 B221＝窄差 D5）；**B247 销号**；无计分母行翻动（模板注册面折叠进 map/04 E4-3 台账注，RPC 注记挂 E7-1），账面不变 **45.0%**（原始口径 45.6%），合计不变 64.5%（891.0/1381）。
- 批 4（2026-10-20 闭环，docs/14）：**D 域地基**——`ExtensionContext.on()` 订阅面（spec-token 单方法＋退订＋错误归属，`ExtensionEventBus`）＋双源桥接：引擎钩子族 3 适配器（`context` 两阶段·引用相等快照·restoreSystemMessages、`tool_call` block 短路·异常向上传播、`tool_result` 逐字段合并·structuredContent 丢弃规则）＋宿主事件族 14 事件（`SessionEventBridge`：turnIndex/previousLevel 桥内跟踪、CompactionEnd 两支分发）；错误通道 `AgentSessionEvent.ExtensionError` → RPC `extension_error` 线逐字；顺带修真 `applyAfterToolPatch` 洗 `isError`/`structuredContent` 存量 bug。map/04 D 域 16 行翻对齐＋2 行翻存疑（#8 compactionEntry 缺、#32 替换臂）、E5-35 翻对齐（逐行实算 +34.5：D +32.5〔设计稿正文 +30.5 系加总错误〕＋E5 +2.0；另 D 基线逐行修正 10.5→11.5 补 +1.0）；**B30/B35/F3/B46 销号**（map/04 台账纠错区），**B248/B249/B250 新登记**；闭环评审两修——`tool_execution_end` 扩展事件误加的 `args` 组件删除（pi types.ts:1086-1097 本无此字段，设计 §1.5「上＋」误读）、扩展处理器抛 `Error` 的捕获链补齐（pi catch 一切；tool_call 包 RuntimeException 走错误结果链、其余族 Throwable 逐处理器上报）；45.0% → **52.3%**（原始口径 45.6% → 52.7%；未加权 33.8% → 39.7%；w3 50.0% → 65.2%）。合计 64.5% → **67.1%**（891.0 → 926.5/1381）。
- 批 5（2026-10-20 闭环，docs/15，B249＋B250 合包）：**结果臂事件族＋会话生命周期**——message_end 替换走引擎新钩（裁决①：`PiLaneSink` 前换引用，宿主事件在 flush 后的唯一保语义缝；宿主桥 case 删防双发）；boundary 子系统（裁决②：turn_end 复用 `should_stop_after_turn` 并修真载荷、agent_before_settle 新钩 firing 于 drive settle 前门；`BoundaryBridge` 落 emitBoundary 逐字＋草稿四型 commit＋预览/canContinue＋`<boundary>` 归属上报）；`SessionEventFirer`（裁决③：input 变换/handled·user_bash 首发言者＋重抛·before_agent_start 累积＋systemPrompt 链式·before_switch/fork cancel 短路）；before_compact 三臂扩形（cancel＞supplied＞keepEntries，裁决⑥）＋`compactionEntry` 穿透（批 4 窄差 D4/D6 销号）；生命周期（session_start 恒 startup＋createReplacing/switchTo/fork 序列、close/closeForReplacement）。map/04 D 域 12 行翻转（9 缺失→对齐、3 存疑→对齐，逐行实算 **+22.0**，D 域 53.0% → **79.5%**）；**B249/B250 销号**、**B251**（forked 不加载扩展）/**B252**（旧会话 close 治理）新登记；窄差 D9–D23（D11 模型可见：systemPrompt 覆盖是 harness 全局态）；引擎钩子 13→15；52.3% → **56.8%**（原始口径 52.7% → 57.2%；未加权 39.7% → 43.8%；w3 65.2% → 69.6%）。合计 67.1% → **68.7%**（926.5 → 948.5/1381）。
- 批 6（2026-10-21 闭环，docs/16，B251＋B252 合包）：**fork 扩展装配＋会话关闭治理**——`wireSession` 抽取（pi createRuntime 对位），fork 三路全走它（fresh 注册表＋fresh 会话绑定闭包：压缩/重试事件、重试中止旗、图片策略、PI_* 身份五组串线同修），forked `loadExtensions` 全量重装＋`session_start{fork, previous}`（pi 逐字序 teardown→rebind）；`closeWith` 三参化（abort 对位＋句柄开关＋幂等）＋fork 内部 `closeForFork`＋/new·RPC new_session `closeForReplacement`＋原地 attach 关旧 storage；`PiTuiApp.exit()`/`RpcDispatcher.close()` 关当前会话（`session_shutdown{quit}` 在 TUI/RPC 退出路径发射）；`AgentHarness.fork()` 删除。**B251/B252 销号**（map/04 D-3/D-10 摘「挂 B251/B252」帽）；行翻转 0——D 域 79.5%、模块 56.8%（原始口径 57.2%）、合计 68.7%（948.5/1381）**均不变**（缺陷闭环，同批 3 口径）；窄差 D24–D30（均内部/边缘）；引擎钩子 15 不变。
- 批 7（2026-10-21 闭环，docs/17，B248）：**provider 请求面扩展事件四件**——ai 缝 `ProviderHooks`（`ApiOptions.extra` 三键逐字对位 pi StreamOptions 键；裁决①）＋七车道 `onPayload`（车道原生载荷＋同型守卫，裁决⑤）与 `onProviderStreamEvent`（原始事件、归一化前）；`onResponse` 六车道（SDK 四车道 `withRawResponse`＋自备两车道 `SseResponseObserver` 终态面含错误状态；**google 不发＝pi 同缺**，裁决④）；`transformHeaders` 在 createApi 前缝消费、钩本体剥离（pi models.ts:866-871 对位）＋`ProviderEventBridge`（pi `extensionRunnerRef`＋四闭包；`hasHandlers` 调用时现查；裁决②③）；Azure 补 `putExtraHeaders`、PiMessages 补 `extra["headers"]` 合并（裁决⑦）。map/04 D 域 4 行翻转（#17 w3 存疑→对齐、#18/#19 存疑→对齐、#20 缺失→对齐，逐行实算 **+5.5**，D 域 79.5% → **86.1%**）；**B248 销号**、**B253**（attribution 头层）新登记；窄差 D31–D35（均内部/边缘）；引擎钩子 15 不变；56.8% → **57.9%**（原始口径 57.2% → 58.3%；未加权 43.8% → 45.2%；w3 69.6% → 73.9%——D 域 w3 欠账清零）。合计 68.7% → **69.1%**（948.5 → 954.0/1381）。
- 批 8（2026-10-21 闭环，docs/18，B245）：**tree 两扩展事件＋TUI 摘要导航流**——`session_before_tree`（结果臂：cancel 短路/扩展摘要 summarize 门/三覆盖，通用 emit 逐字）＋`session_tree`（观察型，导航后当前叶载荷）；navigateTree 全序重构（模型门、无条件先收集、token 事件前建、fromHook=fromExtension 透传、`TreeNavigation.aborted` 区分扩展取消与摘要中止）；死引擎钩 `onBeforeNavigation` 三件套删除（裁决①）；TUI `/fork`·`/tree` marker 拆分＋`TreeNavigationFlow`（skipPrompt 门、三选⇄编辑器 Esc 回退链、worker 上 abort+waitForIdle+重查、指示器成对、结局四分）＋`ChatScreen.rebuildTranscript`＋overlay 先清位再 apply；**闭环评审三修**——`isCompacting()` 含分支摘要在飞（pi getter 承重互斥语义）、`hasExtensionSummary` 生成器门＋pi truthiness、generate 前 abort 预检。map/04 D 域 2 行翻转（#12 存疑→对齐、#13 缺失→对齐，逐行实算 **+1.5**，D 域 86.1% → **88.0%**——**存疑清零**，缺失 6 行各随 B221/E5/子系统）；**B245 销号**；窄差 D36–D42（D39 模型可见 UI 形态、D37 存量约束 S12，余内部/边缘）；57.9% → **58.2%**（原始口径 58.3% → 58.6%；未加权 45.2% → 45.9%；w3 73.9% 不变）。合计 69.1% → **69.2%**（954.0 → 955.5/1381）。
- 批 9（2026-10-22 闭环，docs/19，D37 收编）：**树选择器本体（map/05 S12＋S13）**——T1 数据面（`SessionState` label 时间戳＋分支复制重发保留原值〔SessionState/sqlite 双点修真〕、`SessionTreeBuilder` 逐字 `getTree()`、`AgentSession.getTree/getLabel/setLabel` 三面、RPC/Web `get_tree` 改走全分支＋label 修真）→ T2 `TreeListModel`（pi TreeList 状态机逐字：平铺/可见结构重算/五过滤/搜索 AND/折叠传染/环绕/段跳，`TreeStructure` 拆分保 500 行）→ T3 `TreeListRenderer`（span 行＋水平视口按列切片＋全类型文案表＋`formatToolCall`＋label 时间戳三档）→ T4 `TreeSelectorOverlay`（pi handleInput 分派序＋label 编辑臂）→ T5 接线（`TreeNavigationFlow.openTree` 改道、空树不开、filterMode 初值读 settings、copy 三结局逐字、`PiTuiApp` ResizeEvent 跟踪、`ClipboardSupport` AWT、`KeybindingsManager` 多键＋12 tree 动作；**treeLeafId 折算**——usage 记账行叶置空主流 UX 分叉）。**闭环评审三修**——C1 tree 键污染全局 resolve（ctrl+l/u/a 主屏失效）⇒ 全局/tree 两表分离；I1 TamboUI 不报字母 SHIFT ⇒ `toStroke` 落 pi legacy 规则（大写即 shift+letter）；I2 `--no-session` label 提交异常 ⇒ flow 转 showError。map/05 S12 存疑→对齐（+1.0）、S13 缺失→对齐（+1.0）、map/04 #3 `/tree` 存疑→对齐（+0.5）；**D37/D40 销号**、**B254** 新登记（`/fork`＝user-message 选择器）；窄差 D43–D46/D48（D47 作废——sqlite facts 有 timestamp 列）；58.2% → **58.7%**（未加权 45.9% → 46.9%；w3 73.9% 不变；D 域 88.0% 不动）。合计 69.2% → **69.4%**（955.5 → 958.0/1381）。

### 2.5 tui/web — 45.1% ｜ map/05

web 对齐面 78.8% 最高；**渲染特性 14.5%** 最低：MarkdownRenderer/SyntaxHighlighter 是生产死代码（B65）、6 键位空接（B66）、逐工具渲染器缺（B47）。主题系统 pi 3,105 行 vs Java 74 行（B163）。
新锚点带来 4 条新行为缺口（B238）：Ctrl+Home/End 视口键、安装变更重启警告、OSC 7501 程序状态、APC 假光标。

### 2.6 session ＋ evals — 51.1% ｜ map/06

存储后端 **72.7%**（重测最大跃升：投影/context_edit/会话名/标签/JSONL v3 头五条转对齐；旧值 48.9%）。
残余缺口：条目信封扩展键存疑（权重 3）、不迁 pi v1/v2、会话文件惰性创建。评测 29.1%（20 行多缺失，无独立台账项）。
chord 按 R5 权重 0；sqlite 模块保留但参照后端已删（B59 裁决）。

### 2.7 telemetry / protocol / client / server — map/01

telemetry **100%**（批 1 闭环，2026-10-17，原 docs/23：契约 T1–T10 移植/裁决、扩展 T12–T17 确认、T11 移出分母；adapter 一致性套件 7 用例 × InMemory/Jsonl 两挂在跑，形状差已可变红）。
protocol/client/server 按 R1 整体移出分母：protocol 是已删 pi 代码的移植，client/server 是无消费者的孤岛。

---

## 3 P0 队列：权重 3 的未对齐项

- **ai：零条** ✅。
- **agent-core：零条** ✅（批 5 后模块 100%——权重 3 层 27/27＋确认缺失清零＋存疑清零）。
- **coding-agent（权重 3 层 73.9%）**：~~D 域扩展事件 provider 请求面~~（批 7 销，B248）；剩余：扩展示例库（map/04 w3 #17）、examples/ 可执行验收规格、包管理器三件（#18/#19/#23，E6 域）。
- **tui（权重 3 层 67.3%）**：Markdown 渲染死路（B65）、逐工具渲染器（B47）、spinner/working 指示器、主题 token 覆盖（B163）等渲染长尾。
- **session**：JSONL 条目信封形状（map/06 #16，权重 3 存疑）。

---

## 4 死功能清单（不计权重，只看「有没有」）

| # | 死功能 | 用户感知 | 台账 |
|---|---|---|---|
| 1 | TUI 不渲染 Markdown（MarkdownRenderer 生产零调用，消息走纯文本 split） | 表格/代码高亮/mermaid/链接全不可见 | B65 |
| 2 | TUI 6 个声明键位空接（MODEL_CYCLE/THINKING_CYCLE/TOOLS_EXPAND/THINKING_TOGGLE/EXTERNAL_EDITOR/DEQUEUE） | 按键无反应 | B66 |
| 3 | 语法高亮代码块路径死（唯一调用点是输入编辑器当前行） | 代码块无高亮 | B65 族 |
| 4 | 会话内 MCP 不激活（注册表/传输/OAuth/CLI 全落，但六个 on() 事件 0/6 等接线面缺） | `pi mcp` 进程外可用，对话中无 MCP 工具 | B221/B218 |

---

## 5 R1/R5 排除面（不进分母）

- **R1 已删**：旧 schemas RPC 层/SessionHandle/writer_leases（`e52de91d0`）；session-backends 整包与 agent harness 108 文件（`7fd478a2e`，harness 内容搬进 R5 不可达的 durable）；experimental micro/mini；image registry 旧三件（被 #9948 统一模型设施取代）；active_tools_change/公开 compact-failed 事件（重测确认 pi 主流从不发射，map/03 #86/#88）。
- **R5 不可达**：durable 22,473 行、codemode 2,597 行（quickjs-wasi；B161）、chord 8,817 行、pico3、tool-search/virtual-models/nested-tool-calls（B162）、22 个长尾 provider 及 4 条对应鉴权（R2）、4 个边缘 wire 适配器（map/02 #9–12）、`streamProxy`（map/03 #68，批 agent-core-4 裁决①：pi 侧存活但 bin pi 主流不可达）。
- pi-java-web（pi 无对应物）与 sqlite 模块存废不计完成度（B59：保留模块、不主张删）。

---

## 6 LOC 审计列（不是分数；用途＝检验单元枚举有没有漏）

| 层 | pi 侧（src） | pi-java 侧（main） | 信号 |
|---|---:|---:|---|
| ai | 27,242 | 26,095 | 0.96，枚举覆盖得住 |
| agent-core | 2,519（agent）＋ core/* 主流副本 | 19,795 | harness 副本在 coding-agent/core，java 全收进 agent-core |
| coding-agent（不含 MCP 集成） | 86,152 | 11,931 | pi 体量远超枚举：examples 16,033＋codemode 等 4,723 行未计 |
| mcp（含 coding-agent 集成面） | 3,244 ＋ 4,586 | 10,853 ＋ 2,955 | 新图 map/07 全量枚举 89 单元 |
| tui（框架＋交互） | 19,505 ＋ 21,895 | 6,799（web 另 1,912） | 框架由外部库 TamboUI 顶替，31.1% |
| session/evals | session-manager 2,013 ＋ evals 1,446 | sqlite 2,924 ＋ evals 785 | 参照后端已删，两种范式 |
| telemetry | 935 | 1,682 | 超出：契约移植＋一致性套件/测试基建＋扩展子系统 |
| R5 面外：durable / codemode / chord | 22,473 / 2,597 / 8,817 | 0 | 不进分母 |

---

## 7 维护

1. 两侧基准写在各图头部；pi 取证只走 `git show ea448f454:<path>`，不读 pi 工作树。
2. `bash docs/map/check-drift.sh` 圈 pi 侧漂移（basename 分【确定】/【可能】，只圈范围不判结论）。
3. 地图数字是快照；新缺口先在包文档立项再入 `docs/04`，闭环后从台账移除、地图翻判定。
