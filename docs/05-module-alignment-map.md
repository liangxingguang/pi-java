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

## 1 总表（coding-agent 批 23 后值，2026-10-25；agent-core 批 5 后值，2026-10-18；telemetry 批 1 后值，2026-10-17；ai 批 10 后 2026-10-16；批 3b/4/6/8 为台账行为项、加权不变；其余模块为 2026-10-12 重测值）

| 模块（地图） | Σ权重 | Σ(w×c) | **加权完成度** | 未加权 | 权重 3 层 |
|---|---:|---:|---:|---:|---:|
| pi-java-ai（map/02，**批10后**） | 219 | 207.0 | **94.5%** | 85.5%（100/117） | **100%（33/33）** |
| pi-java-mcp（map/07，**批23后**） | 129 | 102.0 | **79.1%** | 66.3%（59/89） | 100%（1/1） |
| pi-java-agent-core（map/03，**批5后**） | 180 | 180.0 | **100%** | 100%（90/90） | **100%（27/27）** |
| session-sqlite ＋ evals（map/06） | 87 | 44.5 | **51.1%** | 50.0%（20/40） | — |
| pi-java-telemetry（map/01，**批1后**） | 34 | 34.0 | **100%** | 100%（16/16） | 100%（21/21） |
| pi-java-tui/web（map/05） | 244 | 122.5 | **50.2%** | 31.3%（46/147） | 67.3% |
| pi-java-coding-agent（map/04，剔 MCP 粗单元） | 485 | 416.0 | **85.8%** | 78.0%（225.5/289） | 87.0%（20/23） |
| **合计（去重后，coding-agent 批23后）** | **1,379** | **1,103.5** | **80.0%** | — | — |

> map/04 原始口径（含 G-1..G-5）：Σw 494 ／ Σwc 420.5 ／ **85.1%**；两处账都在，总表用去重值。
> w3 注记（批 19）：包管理器 #18/#19/#23 清零（批 18 两翻当时漏入 w3 表，本批收平）⇒ 73.9%（17/23）→ 87.0%（20/23）。
> ⚠️ 批 4 复核修正：D 域基线逐行实算 11.5（旧汇总行 10.5，存疑漏计 1.0）＋设计稿 §8 正文 Δ 加总错误（30.5→32.5）⇒ 账面较批 4 设计稿预期（258.5/52.1%）高 3.0。
> protocol/client/server（R1 排除）未加权：map/01 全层 10 对齐 / 40 缺失 / 35 存疑（85 单元）。

**读数**：

- **ai 层权重 3 全齐**：每轮都走的请求/响应/计量主路无缺口。批 10 后计分母缺失仅 1 条（#8 namespace），无半成品——#90 图片缩放消费链已落 agent-core/coding-agent，#98 federation 批 10 闭环。分类器面已裁 R5。
- **mcp 模块本体完成度高（79.1%）**，但会话内接线面是缺口（B221 余 ①＝内置扩展本体批 24 候选：六个 on() 订阅、提示段生产、`/mcp` 命令与管理器视图载荷；**挂载面已随批 16/17/23 齐**——激活门、ui.custom、工具渲染器、registerMcpServer）。G 组 17 单元缺失 15→12——**进程外 `pi mcp` CLI 可用、会话内 MCP 不激活**。
- **coding-agent 批 23 后 85.8%（去重）**：**D 扩展事件 100%（批 23，满分域第十三——#1 project_trust／#11 mcp_servers_change 两缺失清零）**、A CLI/E1/E3/E4/**E6/B/E7** 七域 100%、E2 settings 97.0%、C slash 90.7%；**E5 扩展 API 50.0%（批 23：MCP 三件＋getCommands＋SourceInfo）** 与 G 域新子系统（codemode 3,957 行＋tool-search/virtual-models/nested 766 行仍为 0，B161/B162）是主要余量。
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

残余计分母缺失仅 1 条：#8 namespace（随 MCP 包 B203）。其余欠账全是存疑方言（见 map/02 存疑行；原 docs/06 ai 清单 2026-10-26 删除）。

### 2.2 mcp — 79.1% ｜ map/07（2026-10-12 新建）

配置/协议/传输/OAuth/工具内容/CLI 六组加权 83–96%；**G 组生命周期与接线 26.1%**（批 23：#81/#84/#87 三行翻动 +4.0——激活门/渲染器挂载面批 16 已落收平、registerMcpServer 挂载点本批落地销 B218；余 12 缺失全随 B221① 内置扩展本体批，批 24 候选）。
方言项（B181–B220 族）多判存疑：mcp-auth.json 字节形态、错误文本、WHATWG URL、redirect 平台替代等。
codemode-only 的 4 个单元（exposure/deferred/discovery）权重 0，B161/B162 闭环时重裁。

### 2.3 agent-core — 100% ｜ map/03（批 5 后）

批 1–5 全部闭环（2026-10-17 → 10-18，逐批账面见 map/03，批 1–3 实施记录在原 docs/20–22，批 4/5 设计稿原 docs/09/10）。闭环内容：

- 截断预算 50KB、`finishTurn` 裁决形状、`/compact` abort-first。
- `prepareRequest` 写钩子。
- #46 族会话事件面重构（五帧＋`AgentSettled{aborted}`＋单一漏斗）。
- stopReason 剔除删除、#54/#78/#100 三条裁决。
- bash/grep/find/ls 四工具模型可见面逐字对齐（glob 改名 find）。
- 批 4：技能族（`/skill:` 展开、loadSkills/loadPromptTemplates 面）、branch summary 族（生成器＋navigateTree＋设置，B1 销号）、工具族（PI_\* 注入 B242、powershell B243、find 阶梯 B53 三销号）、API 面（session_info_changed B30、fromHook、setDefaultStreamFn、列表 onProgress B55、abortCompaction B36 五销号）；#68 streamProxy 裁决① R5 出分母。
- 批 5：#57 bashExecution 全链（`Entry.BashExecution` 变体＋jsonl 互读修复＋投影逐字＋`recordBashResult`/pending flush）、#77 navigateTree 工具恢复、#75 fork 消费 `--session-id`＋删 `" (fork)"` 后缀、#87 双 twin thinking 帧＋S16 对拍零 diff、#89/#96 夹具、#94 拆 `PiLaneSink`/`AgentHarness`（470/438，行为零变化）、#95 字节精确假 shell、#81 并发审计＋`FileMutationQueue` 共享单例修真、#83 消费者清点、#85/#92/#93 javadoc；8 裁决（**B49 销号**、#14/#56/#80/#82/#84/#90/#91，入 docs/04 §4）；#60 R1 出分母。

现状：**模块 100%**——加权 180/180、未加权 90/90、权重 3 层含工具 27/27、工具表 21/21、确认缺失与存疑**双清零**、未得分池 = 0。（批 4 登记的 B244/B245 已分别随 coding-agent 批 2/批 8 销号。）

### 2.4 coding-agent — 85.8%（去重）｜ map/04

A CLI 参数 **100%**（批 21）、E1 配置文件 **100%**（批 21）、E3 会话管理 **100%**（批 21）、E4 资源发现 **100%**（批 21）、**E6 包管理器 100%（批 22，自更新终裁）、B 子命令 100%（批 22）、E7 RPC 面 100%（批 20）、D 扩展事件 100%（批 23，满分域第十三——缺失清零）**、C slash 90.7%（批 16 后）、E2 settings 97.0%（批 21 后，E2-9 翻）；**E5 扩展 API 50.0%（批 23 后）** 拉低整体。
G 域新子系统：MCP 明细在 map/07；codemode 3,957 行＋tool-search/virtual-models/nested 766 行仍为 0（B161/B162）。
- 批 1（2026-10-18 闭环，B236/docs/11）：`--tools` 三层语义（`*` 通配/`+name`/`-name` 修饰符/`mcp__` 保留）＋`--exclude-tools` 通配 denylist＋settings `defaultTools`（merge/resolve）＋注册面门接线（`createAllTools` 八件经 `_isAllowedTool` 过滤）＋默认激活集 **7→4**（裁决①）；map/04 A-20/A-21/E2-35 翻对齐（+3.5）；**B236 销号**、B246 新登记（reload 臂＋SDK 抛错面）；43.2% → **43.9%**（未加权 31.7% → 32.8%）。合计 63.9% → 64.2%（882.5 → 886.0/1381）。
- 批 2（2026-10-19 闭环，B244/docs/12）：`/skill:` 命令注册面三层——settings `enableSkillCommands` accessor（缺省 true）＋设置页字段、TUI 补全目录 skill 臂（u/p/t 来源标签）、`CommandRegistry.dispatch` `/skill:` 透传（批 4 展开器由此接通）＋RPC `get_commands` skill 臂；map/04 C-#25/E2-31/E4-2 翻对齐（+5.0）；**B244 销号**、B247 新登记（prompt template 注册面＋RPC 形状对齐）；43.9% → **45.0%**（未加权 32.8% → 33.8%；map/04 原始口径 44.6% → 45.6%）。合计 64.2% → 64.5%（886.0 → 891.0/1381）。
- 批 3（2026-10-19 闭环，B247/docs/13）：prompt template 注册面两层（补全目录模板臂——pi 无门＋u/p/t 标签＋argumentHint；dispatch 已注册模板名透传）＋模板 `sourceInfo` 加载期打标（新叶子包 `core.source`）＋RPC `get_commands` 线格式一次收平（`{commands}` 包键、`source`/`sourceInfo`、去内置、删 `argumentHint`；扩展臂随 B221＝窄差 D5）；**B247 销号**；无计分母行翻动（模板注册面折叠进 map/04 E4-3 台账注，RPC 注记挂 E7-1），账面不变 **45.0%**（原始口径 45.6%），合计不变 64.5%（891.0/1381）。
- 批 4（2026-10-20 闭环，docs/14）：**D 域地基**——`ExtensionContext.on()` 订阅面（spec-token 单方法＋退订＋错误归属，`ExtensionEventBus`）＋双源桥接：引擎钩子族 3 适配器（`context` 两阶段·引用相等快照·restoreSystemMessages、`tool_call` block 短路·异常向上传播、`tool_result` 逐字段合并·structuredContent 丢弃规则）＋宿主事件族 14 事件（`SessionEventBridge`：turnIndex/previousLevel 桥内跟踪、CompactionEnd 两支分发）；错误通道 `AgentSessionEvent.ExtensionError` → RPC `extension_error` 线逐字；顺带修真 `applyAfterToolPatch` 洗 `isError`/`structuredContent` 存量 bug。map/04 D 域 16 行翻对齐＋2 行翻存疑（#8 compactionEntry 缺、#32 替换臂）、E5-35 翻对齐（逐行实算 +34.5：D +32.5〔设计稿正文 +30.5 系加总错误〕＋E5 +2.0；另 D 基线逐行修正 10.5→11.5 补 +1.0）；**B30/B35/F3/B46 销号**（map/04 台账纠错区），**B248/B249/B250 新登记**；闭环评审两修——`tool_execution_end` 扩展事件误加的 `args` 组件删除（pi types.ts:1086-1097 本无此字段，设计 §1.5「上＋」误读）、扩展处理器抛 `Error` 的捕获链补齐（pi catch 一切；tool_call 包 RuntimeException 走错误结果链、其余族 Throwable 逐处理器上报）；45.0% → **52.3%**（原始口径 45.6% → 52.7%；未加权 33.8% → 39.7%；w3 50.0% → 65.2%）。合计 64.5% → **67.1%**（891.0 → 926.5/1381）。
- 批 5（2026-10-20 闭环，docs/15，B249＋B250 合包）：**结果臂事件族＋会话生命周期**——message_end 替换走引擎新钩（裁决①：`PiLaneSink` 前换引用，宿主事件在 flush 后的唯一保语义缝；宿主桥 case 删防双发）；boundary 子系统（裁决②：turn_end 复用 `should_stop_after_turn` 并修真载荷、agent_before_settle 新钩 firing 于 drive settle 前门；`BoundaryBridge` 落 emitBoundary 逐字＋草稿四型 commit＋预览/canContinue＋`<boundary>` 归属上报）；`SessionEventFirer`（裁决③：input 变换/handled·user_bash 首发言者＋重抛·before_agent_start 累积＋systemPrompt 链式·before_switch/fork cancel 短路）；before_compact 三臂扩形（cancel＞supplied＞keepEntries，裁决⑥）＋`compactionEntry` 穿透（批 4 窄差 D4/D6 销号）；生命周期（session_start 恒 startup＋createReplacing/switchTo/fork 序列、close/closeForReplacement）。map/04 D 域 12 行翻转（9 缺失→对齐、3 存疑→对齐，逐行实算 **+22.0**，D 域 53.0% → **79.5%**）；**B249/B250 销号**、**B251**（forked 不加载扩展）/**B252**（旧会话 close 治理）新登记；窄差 D9–D23（D11 模型可见：systemPrompt 覆盖是 harness 全局态）；引擎钩子 13→15；52.3% → **56.8%**（原始口径 52.7% → 57.2%；未加权 39.7% → 43.8%；w3 65.2% → 69.6%）。合计 67.1% → **68.7%**（926.5 → 948.5/1381）。
- 批 6（2026-10-21 闭环，docs/16，B251＋B252 合包）：**fork 扩展装配＋会话关闭治理**——`wireSession` 抽取（pi createRuntime 对位），fork 三路全走它（fresh 注册表＋fresh 会话绑定闭包：压缩/重试事件、重试中止旗、图片策略、PI_* 身份五组串线同修），forked `loadExtensions` 全量重装＋`session_start{fork, previous}`（pi 逐字序 teardown→rebind）；`closeWith` 三参化（abort 对位＋句柄开关＋幂等）＋fork 内部 `closeForFork`＋/new·RPC new_session `closeForReplacement`＋原地 attach 关旧 storage；`PiTuiApp.exit()`/`RpcDispatcher.close()` 关当前会话（`session_shutdown{quit}` 在 TUI/RPC 退出路径发射）；`AgentHarness.fork()` 删除。**B251/B252 销号**（map/04 D-3/D-10 摘「挂 B251/B252」帽）；行翻转 0——D 域 79.5%、模块 56.8%（原始口径 57.2%）、合计 68.7%（948.5/1381）**均不变**（缺陷闭环，同批 3 口径）；窄差 D24–D30（均内部/边缘）；引擎钩子 15 不变。
- 批 7（2026-10-21 闭环，docs/17，B248）：**provider 请求面扩展事件四件**——ai 缝 `ProviderHooks`（`ApiOptions.extra` 三键逐字对位 pi StreamOptions 键；裁决①）＋七车道 `onPayload`（车道原生载荷＋同型守卫，裁决⑤）与 `onProviderStreamEvent`（原始事件、归一化前）；`onResponse` 六车道（SDK 四车道 `withRawResponse`＋自备两车道 `SseResponseObserver` 终态面含错误状态；**google 不发＝pi 同缺**，裁决④）；`transformHeaders` 在 createApi 前缝消费、钩本体剥离（pi models.ts:866-871 对位）＋`ProviderEventBridge`（pi `extensionRunnerRef`＋四闭包；`hasHandlers` 调用时现查；裁决②③）；Azure 补 `putExtraHeaders`、PiMessages 补 `extra["headers"]` 合并（裁决⑦）。map/04 D 域 4 行翻转（#17 w3 存疑→对齐、#18/#19 存疑→对齐、#20 缺失→对齐，逐行实算 **+5.5**，D 域 79.5% → **86.1%**）；**B248 销号**、**B253**（attribution 头层）新登记；窄差 D31–D35（均内部/边缘）；引擎钩子 15 不变；56.8% → **57.9%**（原始口径 57.2% → 58.3%；未加权 43.8% → 45.2%；w3 69.6% → 73.9%——D 域 w3 欠账清零）。合计 68.7% → **69.1%**（948.5 → 954.0/1381）。
- 批 8（2026-10-21 闭环，docs/18，B245）：**tree 两扩展事件＋TUI 摘要导航流**——`session_before_tree`（结果臂：cancel 短路/扩展摘要 summarize 门/三覆盖，通用 emit 逐字）＋`session_tree`（观察型，导航后当前叶载荷）；navigateTree 全序重构（模型门、无条件先收集、token 事件前建、fromHook=fromExtension 透传、`TreeNavigation.aborted` 区分扩展取消与摘要中止）；死引擎钩 `onBeforeNavigation` 三件套删除（裁决①）；TUI `/fork`·`/tree` marker 拆分＋`TreeNavigationFlow`（skipPrompt 门、三选⇄编辑器 Esc 回退链、worker 上 abort+waitForIdle+重查、指示器成对、结局四分）＋`ChatScreen.rebuildTranscript`＋overlay 先清位再 apply；**闭环评审三修**——`isCompacting()` 含分支摘要在飞（pi getter 承重互斥语义）、`hasExtensionSummary` 生成器门＋pi truthiness、generate 前 abort 预检。map/04 D 域 2 行翻转（#12 存疑→对齐、#13 缺失→对齐，逐行实算 **+1.5**，D 域 86.1% → **88.0%**——**存疑清零**，缺失 6 行各随 B221/E5/子系统）；**B245 销号**；窄差 D36–D42（D39 模型可见 UI 形态、D37 存量约束 S12，余内部/边缘）；57.9% → **58.2%**（原始口径 58.3% → 58.6%；未加权 45.2% → 45.9%；w3 73.9% 不变）。合计 69.1% → **69.2%**（954.0 → 955.5/1381）。
- 批 9（2026-10-22 闭环，docs/19，D37 收编）：**树选择器本体（map/05 S12＋S13）**——T1 数据面（`SessionState` label 时间戳＋分支复制重发保留原值〔SessionState/sqlite 双点修真〕、`SessionTreeBuilder` 逐字 `getTree()`、`AgentSession.getTree/getLabel/setLabel` 三面、RPC/Web `get_tree` 改走全分支＋label 修真）→ T2 `TreeListModel`（pi TreeList 状态机逐字：平铺/可见结构重算/五过滤/搜索 AND/折叠传染/环绕/段跳，`TreeStructure` 拆分保 500 行）→ T3 `TreeListRenderer`（span 行＋水平视口按列切片＋全类型文案表＋`formatToolCall`＋label 时间戳三档）→ T4 `TreeSelectorOverlay`（pi handleInput 分派序＋label 编辑臂）→ T5 接线（`TreeNavigationFlow.openTree` 改道、空树不开、filterMode 初值读 settings、copy 三结局逐字、`PiTuiApp` ResizeEvent 跟踪、`ClipboardSupport` AWT、`KeybindingsManager` 多键＋12 tree 动作；**treeLeafId 折算**——usage 记账行叶置空主流 UX 分叉）。**闭环评审三修**——C1 tree 键污染全局 resolve（ctrl+l/u/a 主屏失效）⇒ 全局/tree 两表分离；I1 TamboUI 不报字母 SHIFT ⇒ `toStroke` 落 pi legacy 规则（大写即 shift+letter）；I2 `--no-session` label 提交异常 ⇒ flow 转 showError。map/05 S12 存疑→对齐（+1.0）、S13 缺失→对齐（+1.0）、map/04 #3 `/tree` 存疑→对齐（+0.5）；**D37/D40 销号**、**B254** 新登记（`/fork`＝user-message 选择器）；窄差 D43–D46/D48（D47 作废——sqlite facts 有 timestamp 列）；58.2% → **58.7%**（未加权 45.9% → 46.9%；w3 73.9% 不变；D 域 88.0% 不动）。合计 69.2% → **69.4%**（955.5 → 958.0/1381）。
- 批 10（2026-10-22 闭环，docs/21，B254＋S20 合包）：**`/fork`＝user-message 选择器＋dequeue 链**——T1 `getUserMessagesForForking` 修真（`ForkMessage{entryId,text}` 收平、`accumulatedEntries` 全分支源、空串拼接、空文本滤除）＋RPC 两点（`fork` 带 `selectedText`、`get_fork_messages` 直用会话表，`forkMessageText` 删）→ T2 `UserMessageSelectorScreen`（pi `UserMessageList`＋容器层逐字：环绕导航、10 条滑窗、单行化＋列宽截断、DynamicBorder）→ T3 `/fork` 改道＋**`TreeSelectorScreen` 删除**（裁决⑧零用户）→ T4 `InteractiveMode.restoreQueuedMessages`（pi `clearAllQueues` 序逐字，`RestoredQueue{text,count}`）＋DEQUEUE 臂（文案逐字＋复数形）＋`PendingQueueView` hint 行（键名随绑定表大写形）＋dequeue 平台键（Windows/WSL `alt+q`，裁决③）→ T5 **D41 重裁两臂**（Esc 流式／树导航 restore 先行于 abort，合并式收 `ChatScreen.restoreToEditor`；扩展臂残留改述保留登记）。map/04 #15 存疑→对齐（+1.0）＋**E3-8 同翻**（+1.0，设计对账表漏列、闭环逐行实算补）、map/05 S20 缺失→对齐（+1.0）＋**K11 同翻**（+1.0，同前）、R28 证据更新不翻；**B254 销号**；窄差 D49–D51（followUp 平台键未落／带参 forkCopy 独有／RPC fork 不校验 user 目标）；**B66 核账 6→4**（DEQUEUE 本批接、MODEL_CYCLE ai 批 3b 早接）；58.7% → **59.1%**（未加权 46.9% → 47.6%；w3 73.9% 不变；D 域 88.0% 不动）；tui/web 44.3% → **45.1%**（总表行；map/05 域表 45.9%，旧账漂移 2.0 未随批追）。合计 69.4% → **69.7%**（958.0 → 962.0/1381）。E1 涨到 2,083、E2 667、E6 615。
- 批 11（2026-10-22 闭环，docs/22，settings 键批量第一批·E2 域）：**核销×2＋消费链×5＋声明面×14**——T1 零代码核销账面滞后两行（E2-38 `thinkingBudgets` B128 三件俱齐、E2-11 `branchSummary` B1 已销）→ T2 `modelThinkingLevels`（字段＋四访问器＋**三消费点**：`SessionSetup.thinkingLevelFor` 第四臂〔**B15-残留-5 销号**〕、`AgentSession.setModel` 包装＋`thinkingLevelForModelSwitch` 三臂〔RPC/Web/TUI 切模型三点改道〕、`cycleModelForward` 走解析链）→ T3 `httpProxy`（`HttpProxySupport` 通道换 JVM 系统属性〔裁决③〕，`SettingsManager.load` 唯一漏斗应用）→ T4 `httpIdleTimeoutMs`（`TimeoutSettings` 解析＋`SettingsNetworkAccessors`＋`DefaultProviders.apiOptions` 链序对齐，**120s 自造兜底删除⇒300s**）→ T5 `deviceId`（`SettingsInstallAccessors.getOrCreateDeviceId` 只读写全局域＋生成即 flush〔裁决⑥〕，`AuthCommand` ChatGPT 臂弃随机改持久）→ T6 `blockImages`（`ImageBlockFilter` 逐字＋`EngineHookBridge.transformContext` 尾部殿后动态现读＋`--no-extensions` 裸注册〔pi 过滤独立于扩展面〕）→ T7 声明面 14 键（字段块＋`Warnings` record＋三域访问器〔裁决⑧拆分 Network/Install/UiPrefs，`SettingsManager` 三入口〕）。map/04 E2 域 **22 行翻动**（对齐 22→29、缺失 22→2、存疑 11→23，逐行实算 **+15.5**，E2 域 60.5% → **80.4%**）＋E2-45 `codemode` **出分母**（裁决②，R5 面外 B161；模块 Σ 487→485、全仓 Σ 1381→1379）；窄差 D52–D54（httpProxy 系统属性通道／httpIdleTimeoutMs 单臂／切模型全无配置不写 entry）；59.1% → **62.6%**（未加权 47.6% → 50.2%；w3 73.9% 不变；D 域 88.0% 不动）。合计 69.7% → **70.9%**（962.0 → 977.5/1379）。新文件 6（`HttpProxySupport`/`TimeoutSettings`/`ImageBlockFilter`＋访问器拆分 Network/Install/UiPrefs）。
- 批 12（2026-10-22 闭环，docs/23，settings 消费点批·E2 域＋TUI 接线）：**核销×3＋TUI 行为键×5＋声明面消费点×7＋setter TUI 入口**——T1/T8 零代码核销三行（E2-37 `treeFilterMode` 批 9 账面滞后〔裁决②〕、E2-23/E2-24 pi 侧零消费⇒声明面即全量〔**裁决③口径先例**〕）→ T2 `doubleEscapeAction`（accessor 默认回落 tree＋空编辑器双击 Esc 500ms 窗，pi :3067-3097）→ T3 `externalEditor`（accessor 回落链＋`ExternalEditorSupport`＋Ctrl+G alt 屏往返，**B66 核账 4→3**）→ T4/T5/T6（`editorPaddingX` 双侧留白／`autocompleteMaxVisible` 默认 **8⇒5**〔裁决④〕＋`SlashCompleter` live-read／`outputPad` 烘焙链 `ChatMessage.Padded`〔窄差 D62〕）→ T7 changelog 流（`ChangelogSupport`＋内置 CHANGELOG.md〔裁决⑦〕＋启动两臂＋/changelog 全量 reverse 不回写）→ T9 硬件光标（`createRunner` 参数化）→ T10 退出两臂（`TranscriptDump` 倾倒默认／resume-hint＋恢复提示〔D64〕）→ T11 滚动条三态（hidden 真实落地、auto/always 同形常驻〔D61 裁决⑧〕）→ T12 滚轮数字臂（`ScrollConfig` 第三参；auto 维持 Codex 加速〔D60 裁决⑥〕）→ T13 订阅凭证警告（`AnthropicExtraUsageWarning`＋四时机接线）→ T14 `setModelThinkingLevel` TUI 入口（`ModelThinkingLevelScreen` 两级子菜单＋SettingsScreen 实例化重构＋消费键 5 新行）；**裁决① E2 表头口径修订**（TUI 消费点计入）。map/04 E2 域 **15 行翻动**（存疑⇒对齐，逐行实算 **+7.5**，E2 域 80.4% → **89.3%**〔75.0/84〕）＋6 行证据更新（E2-14/E2-49 随 cache-warmer 子系统批、E2-32 D56、E2-43 D57、E2-50 D58、E2-22 D55、E2-54 D59）；窄差 D55–D64；62.6% → **64.1%**（未加权 50.2% → 55.4%；w3 73.9% 不变；D 域 88.0% 不动）。合计 70.9% → **71.4%**（977.5 → 985.0/1379）。行为变化注记：双击 Esc 默认开树选择器／fullscreen 退出默认倾倒 transcript（pi 默认同向）。新文件 7（`ExternalEditorSupport`/`ChangelogSupport`/`AnthropicExtraUsageWarning`＋CHANGELOG.md＋`TranscriptDump`/`ModelThinkingLevelScreen`＋测试 5）；E6 涨到 793（+178，设计预估 ~675）。评审回补：SkillCommandsTuiTest 反射面随实例化 fields、appendUserText 不烘焙 pad、启动 changelog 两字段 volatile（spotbugs AT_STALE）。夹具教训：settings 懒解析——rig user.home 必须保持到 tearDown（本批曾污染真实 settings.json，已复原）。
- 批 13（2026-10-23 闭环，docs/24，cache-warmer 子系统批）：**CacheWarmer 本体＋CacheStats＋装配四面＋decide 钩＋TUI 三门＋/session 段**——T1 `CacheWarmer` 状态机逐字（pi cache-warmer.ts:162-401：三纯函数/双安全窗/deadline/经济闸门 $0.05/停止理由全集；`WarmScheduler` 调度缝＋手动时钟测试基建〔裁决④〕）→ T2 `CacheStats` miss 统计全量（噪声地板 1024/sticky reportedCache/compaction 复位/cache_warm 充 prev）→ T3 `CacheWarmingBridge`（D 域 #16：last-override-wins＋emitError 逐字）→ T4/T5 harness 三缝（`getMessages` 活视图〔快照使用〕/`appendDeferredLaneEntry` 双态/`addTokens`）→ T6 装配（**裁决①分包门**：warming wrapper 只包 harness 注入点，摘要/压缩保持 recordingStreamFn）→ T7 暖路径请求面（maxRetries 钉 0/跳三钩/保头变换）→ T8 四面接线（settled 双发射点/dispose cancel/setCacheWarmingMode＋onModeChanged/cacheWarmingStatus）→ T9 设置页两行（`Cache warming` 走会话入口〔pi :5005-5008〕、`Cache miss notices`＋检出 rebuild）→ T10 暖用量行（文案逐字〔原自造 "Cache warming: N tokens" 删〕＋门＋:3449 即时臂＋onEntry 去重）→ T11 miss 通知双臂（`CacheMissTracker` 增量形〔裁决⑤〕＋判定后推进 prev〔评审 I1 对位 pi scan 本 run 可见性〕＋同实例去重）→ T12 `/session` Cache Warming 段＋Cache Re-billed 挂段尾（D68 结构差）。map/04 **D 域 #16 缺失→对齐**（+2.0，D 域 88.0% → **90.4%**〔75.0/83，缺失 5〕）、**E2-49 存疑→对齐**（+1.0）、**E2-14 存疑→对齐**（+0.5，两门无载体登 D67，裁决⑥）；map/05 **R24 存疑→对齐**（+0.5，tui/web 45.9% → **46.1%**）；窄差 D65–D68（abort＝iterator.close／暖选项现读／两门无载体／/session 结构差）；64.1% → **64.9%**（未加权 55.4% → 56.7%；w3 73.9% 不变）。合计 71.4% → **71.7%**（985.0 → 989.0/1379）。行为变化注记：**`cacheWarming` 默认 "streaming" 生效**——Anthropic 系模型 agent 运行期间自动发送 maxTokens=1 缓存刷新（每笔落 `cache_warm` 用量条目，pi 同形可跨读）；设置页新增两行；`showCacheMissNotices=true` 时聊天区出现 miss 警告行与暖用量行。新文件 8（`CacheWarming`/`CacheWarmer`/`CacheWarmingEconomics`/`CacheWarmingFormatting`/`WarmScheduler`/`CacheStats`/`CacheWarmingBridge`/`CacheMissTracker`＋测试 7）；**评审抓回三修**——C1 isCurrent 活视图前缀比对恒真（lane.messages 就地重写，设计稿「整表替换」前提错误）⇒ 捕获改 `List.copyOf` 快照；I1 即时臂 prev 冻结（多 turn run 数值偏）⇒ 判定后推进＋身份集跳过；I2 `CacheWarmer` 571 行破规 ⇒ 拆 `CacheWarmingEconomics`。环境性既有红：`ModelsJsonOverrideWireTest`×2（真实 user-home openai 凭据解析，基线同红，与本批无关——**批 14 闭环已修真**：静态冻结 user.home，见下）。
- 批 14（2026-10-23 闭环，docs/25，CLI 参数尾＋`-r` 会话选择器）：**选择器子系统＋会话解析 pi 全序＋CLI 旗面十件**——T1 `FuzzyMatch`（pi fuzzy.ts 子序列打分逐字：词界奖/连击奖/间隙罚/全等 −100/互换臂）→ T2 `SessionSelectorSearch`（re: 三臂/引号 phrase/未闭合回落/relevance 平分 modified 降序）→ T3 键位表（选择器六键入 overlay 表〔批 9 C1 同构〕＋ app.session.new/tree/fork/resume 四空绑定，map/05 **K16 对齐**）→ T5 `RichSessionInfo.parentSessionPath`（threaded 树认父）→ T4 `RepositoryHandle` 富列表/findLocalById/createWithId/findWithScope 四口＋sqlite 臂拆文件（E3-9）→ T6–T9 `resolvePersistent` 按 pi `createSessionManager` 全序重排（fork→session→resume→continue→sessionId→create）＋`SessionResolutionExit` 干净错误面（裁决②，废栈 trace）＋`validateForkFlags`/`validateSessionIdFlags`/`assertValidSessionId` 正则三校验＋**-c 空臂静默新建**（裁决③，**B255 即登即销**）＋`--session` 全局臂（提示＋promptConfirm fork／`Aborted.` exit 0）＋`--session-id` warning＋带 id 新建＋`--fork` 目标查重 → T10 选择器本体七件（`SessionTreeBuilder`/`SessionListModel`/`SessionListRenderer`/`SessionSelectorHeader`/`SessionSelectorComponent`＋`SessionText`＋搜索复用 `EditorComponent`；pi 1,294 行域对位：双 scope 懒载/isActive 弃旧/删除流/三档排序/进度回调/状态消息 deadline）→ T11 `SessionSelectorProvider` SPI＋tui 实现注册＋`SessionSelectorRunner` 启动壳（裁决①，四模式同获；SPI 缺席报 D70）→ T12 `--` 预分流 → T13 `--export` 位置输出参＋文案逐字 → T16 `--use-theme`（缺值/`-` 起头两诊断同文＋themeFrom 优先＋选择器 SPI 覆盖）→ T14 web `--no-session` 赢（裁决⑤，**B54 销号**）→ T15 `-e` 显式 JAR 恒加载（`-ne` 旁路）→ T17 A-17 核销（链早已通）＋**裁决④ `--offline` 判对齐**（面＝java 已实现操作全集，批 12 裁决③同族）。map/04 **A 域 11 行翻动**（+10.5，A 域 80.6% → **97.6%**〔60.5/62，余 A-29 重排批 20、A-43 重排批 15〕）＋**E3 域 7 行翻动**（+8.5：E3-3/4/5/6/9 设计内＋**E3-19 对账表漏列同翻**〔批 10 E3-8 先例〕＋**E3-24 批 13 漏列核销**，E3 域 71.3% → **92.5%**〔37.0/40，余 E3-15/E3-23 各挂 B1/B30〕）；map/05 **K16 缺失→对齐**（+2.0）＋**K15 同翻**（+1.0，漏列同前）＋**S11 缺失→存疑**（+0.5，rename 面板留白裁决⑦）；窄差 D69–D78（trash 臂无/SPI 缺席/校验诊断出口/rename 留白/键位 helper/sqlite 富扫描退化/正则错误文案/ctrl+backspace 死键/品牌名/builtin: 不收）；64.9% → **68.9%**（未加权 56.7% → 60.7%；w3 73.9% 不变；D 域 90.4%/E2 91.1% 不动）；tui/web 46.1% → **47.6%**（113.0 → 116.5/245）。合计 71.7% → **73.3%**（989.0 → 1,011.5/1379）。行为变化注记：**`-r` 裸敲弹全屏会话选择器**（此前抛栈）、**`-c` 空库静默新建**（此前报错）、`--session` 跨项目命中需确认 fork（此前直挂）、`-e` 显式扩展加载、套件性红 `ModelsJsonOverrideWireTest`×2 **修真**（真因＝auth 三存储 `static final STORE_PATH` 类初始化冻结 user.home，临时 home 夹具先触发即全 JVM 钉死；批 13「凭据修复」系误因——基线复跑证红、隔离跑证绿、修后 979/979 绿）。新文件 19（tui 选择器 7＋测试 6、coding-agent `SqliteRepositoryHandle`/`SessionResolutionExit`/SPI×2＋测试 4、ai 修真 3 改）；**回归抓回两修**——`/hotkeys` 空绑定 NPE（`strokeFor` null ⇒ `""`，pi `formatKeys([])` 对位）、`AgentSessionTest.continueInFreshProcessFailsClearly` 改钉 B255 新行为。E1 涨到 2,341（T15 +10）、`SessionPersistence` 447（近线）。
- 批 14 补注（评审先例沿用）：对账表漏列三处按**逐行实算同翻**（E3-19/E3-24/K15），map/04 未加权汇总表与 E2 行同步收平（对齐 44→46、分母 290→288 逐行实算）。

- 批 15（2026-10-23 闭环，docs/26，slash 尾 C 域）：**`/bug` 故障上报全链＋crash-log 子系统＋C 域存疑修**——T1 `CrashLog`/`CrashRecord`（末 5 条/7 天窗/全量 notified 回写逐字）→ T2 集成（启动提示/未捕获钩〔裁决③死终端面不做〕/**`/import` 修真 fatal**〔裁决⑤，**B256 即登即销**〕/`/bug` 建议门序逐字＋`CrashIntegration` 文案件）→ T3 `BugReport` 核心（SENSITIVE_KEY 逐字/redactUrl 裁决⑨手写解析/收集裁决⑧子集/尾选）＋T3b 摘要缝（`LlmSummaryGenerator` systemPrompt 参化＋`BugReportSummarizer` 四守卫逐字）→ T4 `SessionBranchSerializer`（pi.share trailing）→ T5 `ZipArchives`（裁决① JDK 形）→ T6 `BugReportUpload`（multipart/网关/裁决⑩恒匿名）→ T7 `/bug` TUI 流（三屏＋`BugReportFlow`，裁决⑦ hint 从输入行切）→ T8 `/thinking`（空参选择器裁决⑥简版/有参直设，可用集过滤逐字）→ T9 `/model` 直设臂（`ModelExactMatch`＋scoped 收口＋15s 刷新回落）→ T10 `/export` 默认臂＋引号解析 → T11 `/session` 全段（`SessionStats`/`UsageCostBreakdown`，**D68 销号**）→ T12 `/changelog` `/compact` 核销核验（`SlashChangelogCompactTest` 双钉）。map/04 **C 域 7 行翻动**（+7.0 ⇒ 34.0/38 ≈ **89.5%**，余 6：设置/scoped-models 选择器、Radius 分享/登录、reload〔批 16〕）＋E8-2/E8-3 注记行随翻；map/05 **S29/S30 缺失→对齐**（+2.0；**R29 挂起注记**〔裁决④〕）；**B79 销号**；窄差 D79–D88（ZIP 字节/JAR 名针/死终端/schema 子集/dim 文本/简版选择器/恒匿名/裸选择器/无子进程清理/无外部编辑器）；68.9% → **70.3%**（未加权 60.7% → 62.3%；w3 73.9%/D 域 90.4%/E2 91.1% 不动）；tui/web 47.6% → **48.4%**（116.5 → 118.5/245）。合计 73.3% → **74.0%**（1,011.5 → 1,020.5/1379）。行为变化注记：**`/import` 失败 ⇒ 记 crash＋exit 1**（pi 语义，file-not-found 除外）、**`/export` 无参 ⇒ `session-<ts>.html` 落 cwd**、`/model <ref>` 直设生效、**崩溃后下次启动提示「Run /bug」**、非重试错误后 `/bug` 建议行（每进程一次）、`/session` 全段。**回归回补三钉**（命令数 24⇒26／/export Usage 钉废／Re-billed 位置钉）＋**前存红修真**（`TreeNavigationFlowTest.queuedMessagesRestore…`——根因＝steer 提交早于 run 首个 provider 调用的测试时序缺陷，callCount 门修）。排障注记：真 home `compaction.enabled=false` 被不设 compaction 的测试继承（套件性环境影响——本批 T12 排障所得）。新文件 15＋测试 14；E1 涨到 2,392、E6 912、E7 734。
- 批 16（2026-10-23 闭环，docs/27，E5 扩展 API 面第一批）：**激活门＋提示分节＋registerToolRenderer＋扩展 CLI flag＋`/reload`＋model_select**——T1 flag 面（`ExtensionFlag`/`ExtensionFlagBinder` 三态配对与绑定逐字＋`startupDiagnostics` 落点〔裁决⑧〕＋`--help` 轻量装载 Extension CLI Flags 段〔裁决⑩〕）→ T2 激活门三件套（裁决① `setActiveTools` 删 clear/registerAll＋装配后注册即激活臂〔isAllowed 门〕＋窄化投影 D91）→ T3 提示分节接线（`BeforeAgentStartEvent` 可变 sections〔裁决⑪〕写回 harness→装配→diff 补丁全链）→ T4 `registerToolRenderer`（文本级责任链〔裁决⑦〕＋内置 MCP resolver＋TUI `ToolRenderers`/MessageBubble 消费缝，**B204 销号**）→ T5 `/reload`（`SessionReload` 七步序＋provider 重置烘焙臂〔裁决④〕＋skills/prompts 替换重扫新原语＋defaultTools diff 三分支，**B246 销号**；窄差 D90/D93）→ T6 `model_select`（两发射点＋equals 门）。map/04 **E5 域 5 行翻动**（16.0→22.0/58 ≈ **37.9%**）＋A 域 A-43 翻（**99.2%**）＋C 域 C-23 翻（**90.7%**）＋D 域 #36 翻（**92.8%**；#11 裁决⑤随 E5-23 批）；E8-7 注记行随翻。B221 收编 ②④⑤（余 ①③⑥）；窄差 D89–D94。**评审回补（修真）**：裁决① 连带——工具执行查找面接活跃集活视图（`PiToolRunner`＋`PiLaneEngine`，pi agent-loop.ts:716 对位，失活即 not found）＋四处旧夹具修形（PromptLoadoutTest 注册面补齐/AgentLoopL1/CrossLayerLengthGate/HarnessToolExecutionSpans 激活面=注册面/SessionFailurePathTest 注册+激活分离）；**责任链语义自纠**——pi resolver 返回值直返，「不管」须显式 `next()`（首版测试误写成自动落空，取证 runner.ts:789-795 后改钉 pi 逐字）。70.3% → **72.3%**（未加权 62.3% → 64.7%；w3 73.9% 不动）。合计 74.0% → **74.7%**（1,020.5 → 1,030.0/1379）。行为变化注记：**`/reload` 全量七步序**（settings/extensions 事件/skills/prompts/providers/defaultTools diff）、**扩展工具装配后即激活**（B221② 现状修复）、**失活工具执行即 not found**（pi 语义）、未识别 `--flag` 产启动诊断、`--help` 尾部可出现扩展 flag 段。新文件 11＋测试 7（47 用例＋agent-core 3）；E1 涨到 2,532、E6 924、E7 734 不动。
- 批 17（2026-10-24 闭环，docs/28，E5 扩展 API 面第二批·扩展 UI 注入层）：**`ExtensionUI` 28 成员全面化＋晚绑定修真＋`ui_prompt` 事件对＋RPC 九方法通道＋TUI 对话四件套/custom 挂载面＋widget/footer/header/状态段注入层**——T1 接口面（default 逐字对位 pi `noOpUIContext`＋`NOOP` 单例恒等＋14 件新类型〔裁决③ 文本级 SPI〕＋`DefaultExtensionContext` 持 Supplier 现读〔裁决②——E5-32 存疑实质修复〕＋`mode()/hasUi()`）→ T2 `ui_prompt_start/end`（`UiPromptEventExtensionUI` 装饰器：只包五件阻塞方法、深度计数嵌套只发最外层、同步抛先 finish、`queueMicrotask`⇒FIFO 单虚拟线程执行器）→ T3 `RpcExtensionUI`（九方法 typed＋参数键平铺顶层〔pi spread 逐字〕＋signal/timeout 本地解除〔裁决⑦〕＋setWidget 工厂臂静默忽略〔pi 同缺〕）→ T4 四件套 overlay（selector/input/editor 逐字行形＋阻塞面护栏〔裁决⑤/D98〕＋confirm＝selector 特化）→ T5 `custom` 挂载面（done 幂等闸＋dispose 吞异常＋handle 窄化三件〔D99〕）→ T6 注入层（widget 两区/截断 10 行/footer/header 换槽/状态段 sanitize 逐字）→ T7 装配（`create(args,ui,mode)` 重载＋launcher 重排＋`onSessionInvalidated` 三路径＋reload 首步前＋**修真** `--no-extensions` 早退臂跳过 UI 烘值）。map/04 **E5 域 2 行翻动**（22.0→23.5/58 ≈ **40.5%**）＋**D 域 #26/#27 翻**（**95.2%**，余缺失 2 行＝#1/#11）；map/05 **S22/S23 缺失→对齐**（tui/web 118.5→**120.5/245 ≈ 49.2%**）。**B221 收编 ③**（余 ①⑥）；**B214 改注不销号**（挂载面落、载荷随 MCP 工厂批）；窄差 D95–D101。**评审回补**：**I-1** RPC 应答按 id 路由（`pendingUiRequests`＝pi `pendingExtensionRequests` 对位——孤儿通道线程偷应答修真）＋**I-2** 会话替换重绑扩展 UI（pi rebindSession 对位：RpcDispatcher new_session＋switchTo 注入＋PiTuiApp.switchSession 携带缝）＋Minor×2 随修（custom 清账/装饰器透传）＋spotbugs 回补（verify 阶段 4 钉）。72.3% → **73.0%**（未加权 64.7% → 65.7%；w3 73.9% 不动）。合计 74.7% → **75.1%**（1,030.0 → 1,035.5/1379）。行为变化注记：**TUI 下扩展可弹对话/挂 widget/换 footer/header**（此前静默 noop）、**RPC 九方法通道接通**、/reload·/new·/fork·/resume 前复位扩展 UI 态。新文件 22＋测试 12（69 用例）；E1 涨到 2,630、E2 679、E6 涨到 1,016、E7 涨到 1,031。

- 批 18（2026-10-24 闭环，docs/29，E6 包管理器第一批·resolve 内核）：**settings 两键＋源解析三型＋npm HTTP registry＋git spawn＋resolve() 五档优先级/过滤/忽略＋会话装配接线（裁决⑨）**——T1 `packages`/`npmCommand`（`PackageSource` union 写回不胀形＋校验文案逐字）→ T2 `ParsedSource`/`GitUrlParser`/`SourceResolver`（分派逐字＋hosted-git-info 裁通用臂 D105＋dedupe project 赢/delta 双留）＋`Semvers`（裁决⑥ semver4j 引库——hyphen 实测覆盖，**D106 改注销号**）→ T3 `NpmResolver` seam＋`HttpNpmResolver`（裁决③④：精简 packument/SRI sha512/tar.gz 剥层/pinned 直拉/registry 四级来源）＋`InstallLayout`（temporary sha256-8 逐字/越根防护）→ T4 `GitRunner`＋`GitPackageStore`（裁决② spawn 系统 git 命令表逐字；npm 依赖臂裁 D103）→ T5 `PackageManager.resolve()`（五档 rank 逐字/first-wins/canonicalize 去重/四符代数/`MinimatchLite` D110/`PackageIgnore` D107/`PiManifest`/扫描族 .jar 翻译 D111/自动发现双信任门/onMissing 三臂/`withProgress` 发射面裁决⑪）→ T6 会话四类装配接 resolve（旧直扫臂退役；`-e` 走 temporary resolve；`--no-*` 只裁 resolve 臂；/reload 同入口重放）。map/04 **E6 域 9 行翻动**（5.0→**17.0/31 ≈ 54.8%**——零对齐域消除；E6-15 改注存疑、E6-16 降级缺失）＋**E2-20/E2-26 翻**（E2 域 **95.8%**，缺失清零）。**评审回补**：**I-1** prompts 同名 first-wins 修真（注册表后注册胜出曾把 resolve 序反转）＋**I-2** Windows 冒号条目启动崩修真（纯串化＋parsePathOrNull）＋M-1（`**` 两语义差实测对拍修）/M-2（校验接线）/M-3（host 小写）/M-4（装配单遍 resolve）/M-7（测试 homeDir 缝）随修；M-5 登 D112；窄差 D102–D105/D107–D112；spotbugs 12 钉回补。73.0% → **76.3%**（未加权 65.7% → 68.9%；w3 73.9% 不动）。合计 75.1% → **76.3%**（1,035.5 → 1,051.5/1379）。行为变化注记：**settings 四数组＋packages 键生效**（project 压 user）、**npm:/git: 包真实安装**、**-e 收 npm:/git:**、-ne 下 project 资源过信任门。新文件 21＋测试 16 件（146 用例）；E1 涨到 2,681、E2 706（批 17 滞后更正值）。
- 批 19（2026-10-24 闭环，docs/30，E6 包管理器第二批·CLI 面＋写回链＋update 机制＋config TUI）：**T2 SettingsManager 写回面**（setPackages/setProjectPackages＋四类数组双域 setter——pi :1080-1154 对位，字段级 merge 落盘〔裁决③：withLock 读-改-写 JSON tree，未知键保留〕；**修真 `setProjectTrusted(false)` 旧实现整本抹项目 settings 文件 ⇒ pi 对位纯内存清空**）→ **T1 写回链＋公开安装面**（install/remove 三型分派逐字＋local 校验文案＋`PackageConfigWriter` 身份去重/原位替换保过滤/追加序＋`HttpNpmResolver.uninstall` 目录删除〔D113〕）→ **T3 update 机制**（`PackageUpdater`：Did-you-mean 逐字/npm pinned 跳过·git pinned 参与/npm 查询失败 shouldUpdate⇒true vs checkAvailable⇒false 反向钉死/并发 4 闸自游标 worker-pool〔裁决⑥ 首错抛＋其余 detached〕）→ **T4 CLI 面重裁**（pi parsePackageCommand 逐字手写解析器＋四 handler 文案逐字＋--models 15s 短路臂〔settings/trust 之前〕＋self 恒不可用臂〔裁决②/D115〕＋信任面非交互〔D114〕；**裁决④删 `ExtensionPackageManager`**——`--help` 装载/crash-log/bug 上报枚举源迁 resolve 装配）→ **T5 config enable/disable 改 pi pattern 代数**（`+`/`-` 前缀写回，自动发现条目自此可关停）→ **T6 config TUI 本体**（裁决①全做：`ConfigResourceWriteBack` 四函数＋三态循环逐字＋tui `ConfigSelectorModel/Component`＋SPI 壳——双份 resolve/Tab 切域门/复选框三态/搜索保头）→ **T7 启动包更新通知**（`PackageUpdateNotifier` fire-and-forget，文案逐字，dim 系统文本 D83 同族）。map/04 **E6 域 9 行翻动**（17.0→**30.0/31 ≈ 96.8%**——缺失清零，余 E6-10 存疑挂 R29）＋**B 域 5 行翻动**（9.0→**14.0/15 ≈ 93.3%**，余 B-4 存疑）；E6-15 终判对齐（pi 无独立 manifest 展示标记）；**onMissing TUI 三选取证否定**（pi 锚点不存在该 UI——docs/06 序 18 注记修正）；窄差 D113–D116；spotbugs 回补 1 钉（toggleProject Optional）。76.3% → **80.0%**（未加权 68.9% → 72.0%；**w3 73.9% → 87.0%**〔20/23——批 18 的 #18/#23 翻当时漏入 w3 表，本批连 #19 一并收平〕）。合计 76.3% → **77.6%**（1,051.5 → 1,069.5/1379）。行为变化注记：**`pi-java install <https-url>` 不再下载 JAR**（按 git 源解析，pi 同结局）、**`pi-java config` 无参改开 TUI**（旧只读列表退役）、**`pi-java update` 裸跑恒不可用提示＋exit 1**（pi unknown 安装形态同结局）、**config enable/disable 写回 `+`/`-` 前缀**（自动发现条目可关停）、**启动时后台查包更新并在聊天区插通知块**、**`--help` 的扩展 flag 收集改走 resolve 装配**（旧 JAR 目录扫描退役）。新文件 14＋测试 12 件（131 用例）；E6 涨到 1,036。**评审回补 6 条全实**（I-1 config 无终端 exit 码／I-2 toggle 光标吸附／I-3 通知回调吞错臂＋M-1 死 API／M-2 空串门／M-3 locale）。
- 批 20（2026-10-25 闭环，docs/31，RPC/协议尾合包）：**T1 B237 disposition 三态全链**——CommandRegistry EXTENSION 标签＋撞名旁表（pi 双注册面对位）＋processPrompt 扩展命令臂（pi :1978-1984，出错也是 handled＋extension_error{command:name,command}）＋steer/followUp 全序（throwIfExtensionCommand 错文逐字→input 事件→展开→排队→QueuedInputDisposition 返回）＋RPC disposition/images 透传＋InteractiveMode 重路由（裁决①②；TUI steer 自此过扩展 input 钩——行为变化）→ **T2 get_state 三字段**——`RpcModel` 全对象（B31；api/baseUrl 生效值〔裁决⑥〕、cost −1⇒0〔裁决④〕、compat NONE⇒省略〔裁决③/D118〕）＋sessionName stored 缺席化＋--name 写 session_info（B32，裁决⑧）＋messageCount 工作副本口径（B33）→ **T5 new_session 修形**（B257 即登即销：`{cancelled}` 响应形＋parentSession 落 header＋before_switch 取消臂）→ **T6 attribution 头层**（B253：`ProviderAttribution` 逐字四家头＋`InstallTelemetry` 门＋streamBlocking 恒执行接线、钩见合并全集、暖路径同）→ **T3/T4 核销**（E7-2 批 17 生产者已落、E7-4 的 B27–B29 包④ 早闭——parity 测试钉死翻行）。map/04 **E7 域 4 行翻动**（8.0→**12.0/12＝100%**——存疑清零，**第六个满分域**）；**B237/B253/B31/B32/B33 销号＋B257 即登即销**；D33 改注（余 auth.headers/显式头两层）；窄差 D117–D122。80.0% → **80.8%**（未加权 72.0% → 72.7%；w3 87.0% 不动）。合计 77.6% → **77.8%**（1,069.5 → 1,073.5/1379）。行为变化注记：**TUI steer/followUp 经扩展 input 事件**（pi 同形）、**TUI 扩展斜杠命令改走 processPrompt 统一执行**（返回值不再上屏——扩展经 ctx.ui 输出，pi void 同形）、**get_state.model 改对象形/sessionName 未命名省略/messageCount 工作副本口径**、**new_session 响应改 `{cancelled}`**、**--name 落盘 session_info**、**openrouter/nvidia/cloudflare/opencode 请求带 attribution 头**（遥测门默认开）。新文件 3＋测试 10 件（63 用例）＋存量修形 3 件（RpcCommandTest/RpcDispatcherTest/SessionTeardownTest）。**评审回补 1C＋4I**（I-1 扩展命令独立虚拟线程〔同步 join 死锁 RPC 读线程——`SessionResult.handledBy`＋响应推迟到命令完成后〕＋I-2 --name 限定启动装配链＋I-3 input 事件 behavior 流式门〔批 15 遗留〕＋I-4 get_state 读装配期目录快照＋I-5 压缩重放命令臂；M-1 E1 拆分挂起）；E1 涨到 2,911、E2 涨到 780、E6 涨到 1,049。

- 批 21（2026-10-25 闭环，docs/32，E1/E3/E4 尾＋JSON 主题系统）：**主题域总攻**——pi 2,417 行域整体移植：T4 色数学（`ThemeColor` ADT＋`Oklab` MIT 移植＋`ThemeColorMath`，golden 经 pi 锚点 tsx 实跑钉死）→ T5 主题模型（`ThemeToken`×56/`ThemeJsonValidator` 手写恒装〔裁决②〕/`Theme`）→ T6 `SystemTheme` 生成器（14 族/15 曲线全表逐字；裁决③ 无 OSC 面恒 tier-3，D123）→ T7 `Themes` 注册表＋`ThemeWatcher` 热重载＋`ThemeController`（裁决⑤ 懒 dark 回落 D126）→ T8 发现接线（`ThemeLoader`＋`AgentSession.reloadThemes`＋SessionReload ⑤＋模式层 reload 监听者收尾〔裁决⑩〕）→ T9 A-29 `--theme` 重裁纯路径（裁决⑦ 删 .tcss；`Main` headless initTheme）→ T10 TUI 适配（`TuiThemeBridge`/`ThemeApplicator` 生成 TCSS/`.Screen` 底色移除〔裁决⑥〕/`SemanticColors` 委托活跃主题〔裁决⑨，130 调用点机械改写〕）→ T11 S9 `ThemeSubmenuScreen`（single/automatic＋预览＋Esc 还原）→ T12 HTML 导出主题变量（`deriveExportColors` 逐字含 0.03928 阈值）＋T2 auth.json/models-store.json 读改同锁修真＋T3 SYSTEM.md/APPEND_SYSTEM.md 发现＋T1 四核销（E1-5/E3-15/E3-23/E1-4——F6 症状过期）。map/04 **九行翻动 +12.5**（**E1/E3/E4/A 四域同批满分**＝满分域第七至第十；E2 97.0%）；map/05 **S9 缺失→对齐**（+1.0）。**B163 销号**；窄差 D123–D133。80.8% → **83.4%**（未加权 72.7% → 75.1%；w3 87.0% 不动）。合计 77.8% → **78.8%**（1,073.5 → 1,087.0/1379）。行为变化注记：**默认主题 dark⇒system**（tier-3 外观）、**--theme 变 JSON 路径来源**（.tcss 删）、**SemanticColors 换真主题色**、**.Screen/.StatusBar 底色移除**、**HTML 导出跟随主题**、**--system-prompt 系 file-or-literal**、**SYSTEM.md/APPEND_SYSTEM.md 发现生效**。新文件 20（theme 包 14＋tui 3＋export 1＋测试辅助 2）＋测试 11 件（~78 用例）＋存量修形 2 件（SettingsScreenTest/UserMessageSelectorScreenTest 的 isSameAs⇒isEqualTo）；排障钉：TamboUI `loadStylesheet(name,String)` 第二参是 classpath 资源路径（文本变体＝`addStylesheet`）。E1 涨到 2,980、E6 涨到 1,072。

- 批 22（2026-10-25 闭环，docs/33，会话管理尾＋自更新终裁——docs/06 §3 计划表清空）：**T1 W15 web 渐进会话列表**——协议 `sessionsProgress`/`cancelSessions`＋`AgentSession.listSessionsSummary(cwd, progress, signal)` 重载（JSONL 臂透传 listRich 渐进发布）＋WebDispatcher 虚拟线程富列表＋代次闸（裁决④）＋前端渐进渲染/取消（picker 关闭发 cancelSessions；bundle 随 frontend-maven-plugin 重建）→ **T2 自更新终裁**（裁决①：无 npm/managed 分发形态 ⇒ pi 未知形态臂＝终局——E6-10/B#4 翻对齐，D115 保留）→ **T3 R29 终裁不做**（裁决②：C 类＋出分母 245→244）。map/04 **E6/B 两域同批满分**（满分域第十一、十二——**存疑全清零**）；map/05 **W15 缺失→对齐**（+1.0）。无新 B/窄差。83.4% → **83.8%**（未加权 75.1% → 75.8%；w3 87.0% 不动）。合计 78.8% → **79.0%**（1,087.0 → 1,090.0/1379）。测试新增 2 件 6 用例；排障钉：夹具自身会话落盘计数 +1／await 轮询须收集非匹配帧／无 -am 拾旧 jar 再撞（NoSuchMethodError 逃出 catch 静默死线程）。

- 批 23（2026-10-25 闭环，docs/34，触发条件型欠账首批立项——B221⑥＋D 域余 2 缺失＋E5 扩展 MCP 三件）：**T1 扩展 MCP API 三件**（`registerMcpServer/unregisterMcpServer/getMcpServers`——loader.ts:471-498 三守卫逐字；注册表安家 `DefaultExtensionContext`；`forExtension(name,path)` 路径身份〔裁决⑥ `builtin:<name>`〕；**B218 销号**、map/07 #87 翻）→ **T2 `mcp_servers_change`**（D#11：事件变体＋firer 广播＋装配尾 changeListener 接线〔pi bindCore 相对序〕＋`UnhandledMcpServerReporter` 无处理器臂逐字错文按名去重）→ **T4 RPC `get_commands` 扩展臂**（登记目＋`:N` 撞名编号〔runner.ts:805-840 逐字——裸名撞名不可调〕＋buildCommands 第一臂〔**批 3 窄差 D5 销号**〕＋E5-20 getCommands 三源序＋`ExtensionSourceInfos` 四规则〔E5-34 翻〕；**B221 收编 ⑥，余 ①**）→ **T3 `project_trust` 全链**（D#1：first-wins firer＋`ProjectTrustStore`〔trust.json 最近祖先继承/null 删键/逐字节线格式/FileChannel 锁——**删 `TrustManager` 标记文件**，裁决②〕＋`ProjectTrustResources` 敏感资源门＋七级 `ProjectTrustResolver`＋预信任装配＋TUI `TrustPromptRunner` 启动壳；行为变化：**`defaultProjectTrust:"ask"` 从「当信任」变 pi 逐字语义**——有 `.pi-java/` 敏感资源的项目 TUI 弹窗、print/rpc 不信任）。map/04 **七行翻动 +9.5**——**D 域 79.0→83.0/83＝100%（满分域第十三，缺失清零）**、E5 域 23.5→**29.0/58＝50.0%**、E7-1 注记随收；map/07 **三行 +4.0**（#87 本批＋#81/#84 批 16 回填收平，G 组 2.0→6.0/23 ≈ 26.1%）98.0→**102.0/129 ≈ 79.1%**。83.8% → **85.8%**（未加权 75.8% → 78.0%；w3 87.0% 不动）。合计 79.0% → **80.0%**（1,090.0 → 1,103.5/1379）。窄差 D134–D137；新文件 13＋测试 11 件 59 用例＋存量修形 9 件；排障钉：信任判定 cwd 须取 settings 存储面项目目录（判定消费同根）／spotbugs 七钉全是 `Path.getParent()` 空路径／pi 撞名编号自增从 occurrence 起（`dup:2:2`）；verify 收口 exit 0 BUILD SUCCESS（5,179 测 0 失败，spotbugs 零钉）。E1 涨到 3,067、E2 767。

### 2.5 tui/web — 50.2%（总表行；map/05 域表 50.2%）｜ map/05

web 对齐面 78.8% 最高；**渲染特性 14.5%** 最低：MarkdownRenderer/SyntaxHighlighter 是生产死代码（B65）、4 键位空接（B66，批 10 核账 6→4：DEQUEUE/MODEL_CYCLE 已接）、逐工具渲染器缺（B47）。主题系统 pi 3,105 行 vs Java 74 行（B163）。
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
| 2 | TUI 3 个声明键位空接（THINKING_CYCLE/TOOLS_EXPAND/THINKING_TOGGLE；~~MODEL_CYCLE~~ ai 批 3b 已接、~~DEQUEUE~~ 批 10 已接、~~EXTERNAL_EDITOR~~ 批 12 已接） | 按键无反应 | B66 |
| 3 | 语法高亮代码块路径死（唯一调用点是输入编辑器当前行） | 代码块无高亮 | B65 族 |
| 4 | 会话内 MCP 不激活（注册表/传输/OAuth/CLI 全落，但六个 on() 事件 0/6 等接线面缺） | `pi mcp` 进程外可用，对话中无 MCP 工具 | B221（余 ①；~~B218~~ 批 23 销号） |

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
