# 05 - 模块对齐地图（量化总表）

> **判据**：`bin pi` 主流发布版可达的**行为**与 pi 相同——不是文档、不是 API 形状、不是文件清单。
> **基准**：pi @ **`ea448f454`**（2026-10-11，v1.1.0-22）／pi-java @ **`edc8749d`**（2026-10-12）。
> 七份地图（含新建 `map/07-mcp.md`）已于 **2026-10-12 对两基准逐条重测完毕**（原 A16 销号）。
> **同日 ai 批 0 裁决**（[`docs/08`](08-ai-layer-alignment-roadmap.md)）：map/02 有 11 单位出分母、#87 转对齐，
> ai 85.0%→**94.5%**（219/207.0：批 1/批 2 闭环 2026-10-13 至 90.2%，**批 3a 模型目录数据面闭环 2026-10-14** 至 92.2%，**批 5 消息字段/终局计量闭环 2026-10-15** 至 92.7%，**批 7 共享回调/ChatGPT 订阅/copy-code 闭环 2026-10-16** 至 93.8%，**批 9 图片缩放/注记消费链闭环 2026-10-16** 至 94.1%，**批 10 federation 闭环 2026-10-16** 至 94.5%；批 2 为单元 #6 Mistral 窄角修复，加权不变；批 0 后 89.7%）；**批 3b／批 4／批 6／批 8 销号均为台账行为项，读数不变**；剩余计分母缺失仅 #8。
> **agent-core 批 1 闭环 2026-10-17**：72.1%→**75.7%**（183/138.5，B158 截断 50KB＋B159/#41 finishTurn 裁决形状＋#17 /compact abort-first，+6.5）；合计 57.8%→**59.5%**（824.5/1385）。
>
> **明细**：每模块逐条能力单元（两侧 `file:line` 证据、判定、权重）在 `docs/map/01..07-*.md`；
> 开放缺口的权威台账是 [`04-open-items-register.md`](04-open-items-register.md)。
> 本文件只做**索引＋总表＋P0 队列**。历史换账/旧数对比在 git（`git log -- docs/05-module-alignment-map.md`）。

---

## 0 口径

### 0.1 判定单位与三档

一个**可被外部观察到的行为承诺**＝一个能力单元（一条 CLI 参数 / 一个 slash 命令 / 一个工具 /
一种事件类型 / 一个配置键 / 一个 provider 适配器 / 一个 ContentBlock 变体 / 一个 UI 组件）。

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
| R2 | **provider 只做主流 7 家**（Anthropic/OpenAI/Google/xAI/DeepSeek/Mistral/OpenRouter），22 长尾＋meta/radius/typesafe 权 0 |
| R3 | TUI 优先级最低（照常给权重，缺口标 P3） |
| R4 | native image 移出缺口表 |
| R5 | **只做 `bin pi` 主流发布版可达的**；durable/codemode/chord/pico3 不进对齐面（codemode 依赖项 B161/B162/B177 在册触发） |

### 0.4 分母去重

MCP 在两处有账：`map/04` 的 G-1..G-5 是 5 个粗单元（Σw 9/Σwc 7），`map/07` 是 89 个细单元。
**总表按「map/04 剔 G-1..G-5 ＋ map/07 全量」计**，避免重复。telemetry 计入总表；
protocol/client/server 按 R1 整体排除（未加权数字见 map/01）；pi-java-web 是 pi 无对应物，不计完成度。

---

## 1 总表（agent-core 批 1 后值，2026-10-17；ai 批 10 后 2026-10-16；批 3b/4/6/8 为台账行为项、加权不变；其余模块为 2026-10-12 重测值）

| 模块（地图） | Σ权重 | Σ(w×c) | **加权完成度** | 未加权 | 权重 3 层 |
|---|---:|---:|---:|---:|---:|
| pi-java-ai（map/02，**批10后**） | 219 | 207.0 | **94.5%** | 85.5%（100/117） | **100%（33/33）** |
| pi-java-mcp（map/07，**新图**） | 129 | 98.0 | **76.0%** | 62.9%（56/89） | 100%（1/1） |
| pi-java-agent-core（map/03，**批1后**） | 183 | 138.5 | **75.7%** | 48.9%（45/92） | 86.4%（16/22） |
| session-sqlite ＋ evals（map/06） | 87 | 44.5 | **51.1%** | 50.0%（20/40） | — |
| pi-java-telemetry（map/01） | 35 | 17.5 | **50.0%** | 41.2% | 64.3% |
| pi-java-tui/web（map/05） | 245 | 108.5 | **44.3%** | 25.7%（38/148） | 67.3% |
| pi-java-coding-agent（map/04，剔 MCP 粗单元） | 487 | 210.5 | **43.2%** | 31.7%（92/290） | 50.0%（9/23） |
| **合计（去重后，agent-core 批1后）** | **1,385** | **824.5** | **59.5%** | — | — |

> map/04 原始口径（含 G-1..G-5）：Σw 496 ／ Σwc 217.5 ／ **43.9%**；两处账都在，总表用去重值。
> protocol/client/server（R1 排除）未加权：map/01 全层 10 对齐/40 缺失/35 存疑（85 单元）。

**读数**：
- **ai 层权重 3 全齐**，每轮都走的请求/响应/计量主路无缺口；批 10 后计分母缺失仅 1 条（#8 namespace），无半成品（#90 图片缩放消费链已落 agent-core/coding-agent、#98 federation 批 10 闭环）；分类器面已裁 R5。
- **mcp 模块本体高完成度（76%）**，但会话内接线面（B221：六个 on() 事件 0/6、激活门、ui.custom、工具渲染器、提示分节、registerMcpServer 挂载点）是 map/07 G 组 17 单元里 15 缺失——**进程外 `pi mcp` CLI 可用、会话内 MCP 不激活**。
- **coding-agent 最低**，欠账集中在扩展系统（D 域 41 事件加权 12.7%、D′ UI 注入 6.3%、包管理器 16.1%）。
- agent-core 权重 3 零缺失，6 条全是事件/载荷**形状差**（见 §2）。

---

## 2 各模块现状（详见各图）

### 2.1 ai — 94.5% ｜ map/02（批 10 后）
最强：usage 计量 100%、wire 适配器 97.1%、provider 端点 97.3%、元数据 92.3%、ContentBlock 91.7%、横切 93.4%。
**批 10（2026-10-16）**：#98 缺失→对齐（+1）——Anthropic workload identity federation：
`AuthKind.FEDERATION`＋`RecordedCredential.env`＋`Credentials` 末位回落（3 必需＋2 可选，
`resolveApiKey` 滤 blank）、`DefaultProviders` extra 过桥、`AnthropicMessagesApi` federation 车道＋
`AnthropicFederationSupport`（静态基座缓存＋`withOptions` 挂头，3 stream⇒1 交换实测）；协议面
anthropic-java 2.66.0 全包（原「java 要逆协议」裁决作废），守卫＝SDK 凭证链显式 opt-in（夹具钉死）。
**批 9（2026-10-16）**：#90 存疑→对齐（+0.5，B85 销号）——JDK ImageIO 替代 photon 落
`ImageProcessor`/`ImageResizer`/`ExifOrientation`/`WebpDimensions`，`ReadTool` 缩放省略/非视觉注记两路径，
`PiToolRunner` after_tool 后统一归一（MCP/截图大图失败留原图），`ImagePolicy` 晚绑定
`images.autoResize ?? true`＋当前模型 `inputLimits.images.resize`＋视觉位；窄差见 docs/18 §5。
**批 7（2026-10-16）**：#97 存疑→对齐（`LoopbackCallbackServer` 共享回调＋pi 品牌页＋回调/手工竞速）、
#95 缺失→对齐（Sign in with ChatGPT：动态客户端/1455 硬失败/直连 scope 门/签发 clientId 刷新）、
#96 缺失→对齐（Anthropic browser/copy-code select、53692→0 回落、JSON 交换刷新），+2.5。
**批 3a（2026-10-14）**：17 个 npm `pi-ai@1.1.0` 分片（588 chat，391KB）逐字节入资源＋manifest sha256 锚，
#34/#86/#91 转对齐、#90 转存疑（B85 消费链另案），B97/B232 销号；`DefaultModelIds` 落 pi 默认模型表
（B137 防回归切片，glob/scope 主体留批 3b）。
**批 3b（2026-10-14）**：B137 销号——minimatch 10.2.6 子集（`* ? ** [class]`，tarball 静态取证）、
子串/显示名匹配、alias 优先 dated 降序、两码 scope 诊断、`--models ?? enabledModels` → 初始模型＋
Ctrl+P/RPC 轮转全接通；台账行为项、无加权单元 ⇒ 百分比不变。
残余计分母缺失 1 条：#8 namespace（随 MCP 包 B203）。
批 0 出分母：分类器/统一模型类型/图像目录（#92/#94/#33，仅 codemode 消费＝R5）、bedrock/vertex/codex
三 wire（主流 7 家口径外 w0；azure/radius 同口径，B230/B226 在 04 §4）、cf-binding 死代码作废。
路线与账面（批 1–10 已完工 94.5%；残余仅 #8 计分母缺失）见 docs/08。

### 2.2 mcp — 76.0% ｜ map/07（**本次新建**）
配置/协议/传输/OAuth/工具内容/CLI 六组加权 83–96%；**G 组生命周期与接线 8.7%**（B221＝下一包主战场）。
方言项（B181–B220 族）多判存疑：mcp-auth.json 字节形态、错误文本、WHATWG URL、redirect 平台替代等。
codemode-only 的 4 个单元（exposure/deferred/discovery）权 0，B161/B162 闭环时重裁。

### 2.3 agent-core — 75.7% ｜ map/03（批 1 后）
**批 1（2026-10-17）**：B158（#23 截断字节预算 100KB→50KB，描述/注记全链现算）、B159＋#41
（`finishTurn` 裁决形状：`TurnDecision`＋`FinishTurnHook`、turn_end 前调用、错误路照调、
`explicitContinuation` 三段清零、`NextTurnContext.newMessages`、`fireFinishTurn` 合并 END 先赢）、
#17（手动 `/compact` abort-first＋waitForIdle）三口闭环，+6.5。
权重 3 的 6 条存疑（零缺失）：`prepareRequest` 返回替换三件套、
AgentSessionEvent 面（pi 24/Java 20）、agent_end 本 pass 载荷、Entry union（pi 11/Java 9 record）、
`convertToLlm`、find() 分级（B53）、系统提示载体。**#73 JSONL v3 已转对齐**（PiV3Wire 原生线＋双向互读夹具）。
确认缺失仅 10 条：#62 formatSkillInvocation（w2），余 w1
（branch summary 族 B1、B30、B36、B55、fromHook、streamProxy、setDefaultStreamFn）。
工具：7/8（powershell 缺），bash 默认超时/grep 参数/find/ls 存疑。

### 2.4 coding-agent — 43.2%（去重）｜ map/04
A CLI 参数 78.2%、C slash 63.2%、E3 会话管理 68.8%；**D 扩展事件 12.7%、D′ UI 注入 6.3%、E6 包管理器 16.1%、
E5 扩展 API 24.1%** 四块拉低整体。G 域新子系统：MCP 明细在 map/07；codemode 3,957 行 ＋
tool-search/virtual-models/nested 766 行仍 0（B161/B162）。新缺口：B236（`--tools` glob/modifier）、
B237（RPC `handled` disposition）。

### 2.5 tui/web — 44.3% ｜ map/05
web 对齐面 78.8% 最高；**渲染特性 14.5%**：MarkdownRenderer/SyntaxHighlighter 是生产死代码（B65）、
6 键位空接（B66）、逐工具渲染器缺（B47）。主题系统 pi 3,105 行 vs Java 74 行（B163）。
新锚点 4 条新行为（B238）：Ctrl+Home/End 视口键、安装变更重启警告、OSC 7501 程序状态、APC 假光标。

### 2.6 session ＋ evals — 51.1% ｜ map/06
存储后端 **72.7%**（重测最大跃升：投影/context_edit/会话名/标签/JSONL v3 头五条转对齐；旧 48.9%）；
残余＝条目信封扩展键存疑（w3）、不迁 pi v1/v2、会话文件惰性创建。评测 29.1%（20 行多缺失，无独立台账项）。
chord R5 权 0；sqlite 模块保留但参照后端已删（B59 裁决）。

### 2.7 telemetry / protocol / client / server — map/01
telemetry 50.0%（w3 64.3%；adapter 一致性套件未做，可把形状差变红）。
protocol/client/server 按 R1 整体出分母：protocol 是已删 pi 代码的移植，client/server 无消费者孤岛。

---

## 3 P0 队列：权重 3 的未对齐项

**ai：零条** ✅。

**agent-core（6 存疑/0 缺失）**：prepareRequest 替换三件套 ·
AgentSessionEvent 面（含 B30） · agent_end 本 pass 载荷 · Entry union（B1 族 label/session_info） ·
convertToLlm · B53 find() 分级 · 系统提示 transcript 载体。

**coding-agent（w3 层 50%）**：D 域扩展事件 7–8 条缺失/存疑（引擎钩子存在但未桥接）、examples/ 可执行验收规格。

**tui（w3 层 67.3%）**：Markdown 渲染死路（B65）、逐工具渲染器（B47）、spinner/working 指示器、
主题 token 覆盖（B163）等渲染长尾。

**session**：JSONL 条目信封形状（map/06 #16，w3 存疑）。

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

- **R1 已删**：旧 schemas RPC 层/SessionHandle/writer_leases（`e52de91d0`）；session-backends 整包与
  agent harness 108 文件（`7fd478a2e`，harness 内容搬进 R5 不可达的 durable）；experimental micro/mini；
  image registry 旧三件（被 #9948 统一模型设施取代）；active_tools_change/公开 compact-failed 事件
  （重测确认 pi 主流从不发射，map/03 #86/#88）。
- **R5 不可达**：durable 22,473 行、codemode 2,597 行（quickjs-wasi；B161）、chord 8,817 行、pico3、
  tool-search/virtual-models/nested-tool-calls（B162）、22 个长尾 provider 及 4 条对应鉴权（R2）、
  4 个边缘 wire 适配器（map/02 #9–12）。
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
| telemetry | 935 | 787 | 接近 |
| R5 面外：durable / codemode / chord | 22,473 / 2,597 / 8,817 | 0 | 不进分母 |

---

## 7 维护

1. 两侧基准写在各图头部；pi 取证只走 `git show ea448f454:<path>`，不读 pi 工作树。
2. `bash docs/map/check-drift.sh` 圈 pi 侧漂移（basename 分【确定】/【可能】，只圈范围不判结论）。
3. 地图数字是快照；新缺口先在包文档立项再入 `docs/04`，闭环后从台账移除、地图翻判定。
