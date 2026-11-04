# 08 - pi 新基线重新对齐总设计

> 【背景摘要】pi 参考仓已于 2026-10-31 从 `ea448f454` 快进到 `56cae7823`。本文档规定 pi-java 如何以新提交为唯一目标锚点，按依赖层级重新盘点、设计、实施和发布新账面。
>
> **文档状态：2026-10-31 用户已批准总体方案。本文档只授权后续盘点与设计文档，不授权实现代码。**

## 0. 术语与阅读口径

- **锚点（anchor）**：对齐取证时固定不变的 pi commit。地图中的 `file:line` 只有绑定锚点才有意义。
- **换锚（rebaseline）**：把全部能力单元重新对照一个新锚点。换锚不是只整理两个提交之间的 changelog。
- **对齐 / 存疑 / 缺失**：对齐计 1，存疑计 0.5，缺失计 0。判定必须读过两侧当前代码，不能凭类名或 changelog 推断。
- **R1**：pi 已删除的面，pi-java 也评估删除，不为已删除参照物补功能。
- **R2**：Provider 只覆盖已批准的主流范围。长尾 Provider 不自动进入分母。
- **R5**：`bin pi` 主流发布面之外的子系统不做对齐。此次继续排除 codemode、durable、chord。
- **模型可见面**：模型能够读到的提示、工具声明、参数、结果和错误文本。此类契约优先逐字对齐。
- **窄差（D 编号）**：已经登记的行为或形状差异。窄差不等于可以跳过复核。
- **工单（B 编号）**：开放功能缺口。新工单接在 B259 后，从 B260 开始。
- **层闭环**：一层完成逐行重测、设计审核、获批实施包、验证和文档回填。只写完设计稿不叫层闭环。

## 1. 新旧基线

### 1.1 锚点

| 项 | 旧基线 | 新目标基线 |
|---|---|---|
| pi commit | `ea448f454af474f3e2fe9733b331e17b0662f5d6` | `56cae782341c339d3bb820081f0f9425d8c5b1ec` |
| 提交日期 | 2026-10-11 | 2026-10-31 |
| 版本位置 | `v1.1.0-22` | `v1.4.0-265-g56cae7823` |
| pi-java 开始盘点时的 HEAD | 历史各图各自记录 | `28e3f0d9`；每层重测时再记录当层准确 HEAD |

新锚点处 `packages/ai` 和 `packages/coding-agent` 的 `package.json` 版本均为 **1.4.0**。目标锚点包含 v1.4.0 后的未发布改动，不能只按 v1.4.0 release notes 盘点。

### 1.2 已核实的差异规模

```text
ea448f454..56cae7823
468 commits
1671 files changed
206714 insertions(+), 49951 deletions(-)
```

大量变更位于 Chord、Durable、文档和生成目录。文件数与代码行数不能直接折算为主流功能缺口。

### 1.3 取证纪律

1. pi 侧事实一律使用：

   ```bash
   git -C D:/workplaceForai/pi show 56cae7823:<path>
   ```

2. 新旧行为比较使用：

   ```bash
   git -C D:/workplaceForai/pi diff ea448f454..56cae7823 -- <path>
   ```

3. 不把 pi 工作树的后续移动当作新证据。即使再次 pull，目标锚点仍是 `56cae7823`。
4. pi-java 侧以当层开始盘点时的 HEAD 为准，并在该层地图头部记录完整或足够唯一的 SHA。
5. changelog 只负责圈定候选差异。最终判定必须落到新锚点的源码、Schema、固定资源或测试。

## 2. 目标与非目标

### 2.1 目标

1. 对现有七份地图的全部计分单元重新判定，不直接继承旧结论。
2. 把新锚点新增、修改和删除的主流行为纳入对应层。
3. 合并旧锚点尚未完成的 TUI、examples 和扩展 UI 欠账，不建立重复 backlog。
4. 按依赖方向逐层实施，避免上层先适配仍会变动的底层接口。
5. 清理旧地图中滞后、重复和口径不一致的行，最后发布可逐行复算的新总账面。

### 2.2 非目标

1. 不实现 codemode 沙箱、Durable、Chord 或 pi-env。
2. 不因为新增长尾 Provider 而扩大 R2 范围。
3. 不复活 pi 已删除的 experimental harness、旧 server/client/plugin 发布面。
4. 不为 pi-java 独有的 web UI 虚构 pi 对齐分母。web 只做兼容回归。
5. 不在本轮总设计审核后直接写实现代码。
6. 不在全部层重测完成前发布“新基线全仓完成度”。

## 3. 已批准的总体裁决

### 裁决 1：采用逐层滚动换锚

- 【场景】一次性写完所有层的实施设计，会被底层后续的接口调整反复推翻；只做增量 diff 又会继承旧地图错账。
- 【决定】每层先全量重测，再写当层设计，再实施获批工作包。下层闭环后才把其接口作为上层输入。
- 【风险/代价】[内部，模型不可见] 新总完成度发布较晚。换取的是证据一致和较少返工。

### 裁决 2：固定目标锚点，不追逐移动 main

- 【场景】pi 在实施期间仍会持续提交。持续换目标会使证据和测试无法稳定。
- 【决定】本轮始终使用 `56cae7823`。后续新版本另开换锚周期。
- 【风险/代价】[存量约束，非本轮范围] 本轮完成时 pi main 可能更新，但那不使本轮锚点失效。

### 裁决 3：维持 R2/R5，共享主流行为单独判断

- 【场景】新差异同时包含主流面和 R5 面。按文件名整包排除会误伤共享行为。
- 【决定】codemode、durable、chord 不进分母；tool_search、MCP 数据形状、OAuth 等主流可达行为仍逐项复核。G-6/G-7 在新地图中按 R5 移出分母。
- 【风险/代价】[模型可见] 不能只凭“该消费者之一是 codemode”就排除整个单元。例如 `structuredContent` 还要检查 `after_tool` 等主流消费链。

### 裁决 4：新旧账面隔离

- 【场景】旧地图仍指向旧锚点。若立即改写总表锚点，会把未经重测的旧判定冒充成新锚点事实。
- 【决定】旧锚点 89.0% 保留为历史快照。每层新读数先留在当层地图和本文 §9 的进度记录中，全部重测后才替换 docs/05 总表。
- 【风险/代价】[内部，模型不可见] 换锚窗口内需要同时读历史总表和当层新图，不能将两者相加。

### 裁决 5：旧欠账随所属层收编

- 【场景】旧基线还有 TUI、examples、E5-31、B259 等未结工作。另起一套“新基线计划”会产生重复条目。
- 【决定】保留原 B/D 编号、关闭历史和触发条件。新工作与新差异在同一层合并排期。
- 【风险/代价】[存量约束] 旧批次的预计批数失效，但不改变已经闭环批次的史实。

### 裁决 6：每层仍执行人工设计审核门

- 【场景】总设计只能确定顺序和方法，不能替代具体 API、Schema 和行为契约设计。
- 【决定】每层或层内独立工作包必须提供 pi `file:line`、必要的逐字代码、pi-java 现状、关键签名、测试计划和风险。用户批准后才实施。
- 【风险/代价】[内部，模型不可见] 审核次数增加；不得用总稿批准替代工作包批准。

## 4. 层级顺序与交付物

### 4.1 顺序

```text
L0 telemetry
  → L1 ai
  → L2 mcp
  → L3 agent-core
  → L4 session-backend-sqlite / JSONL 会话语义
  → L5 coding-agent
  → L6 evals
  → L7 TUI 与 web 兼容面
  → L8 examples
```

MCP 位于 AI 之后，因为它使用 AI 的消息与结果类型。coding-agent 位于 MCP 和 agent-core 之后，因为它消费两者接口。examples 最后，因为它同时是扩展 API 和交互行为的验收规格。

### 4.2 每层统一交付物

1. 更新后的当层地图：新锚点、pi-java HEAD、逐行证据、判定、权重和分母。
2. 当层差异清单：新增、修改、删除、继续有效、R1/R5 出分母五类。
3. 一个或多个可独立审核的实施设计稿。
4. docs/04 开放项回填：销号、改注、新 B、新 D。
5. 获批实施后的测试记录和层闭环记录。
6. 本文 §9 的层状态更新。

## 5. 各层盘点重点

本节是**候选差异索引**，不是预先写死的实现方案。每项仍需在当层设计中取证。

### L0 telemetry — `map/01-infra.md`

- 重测原有 telemetry 契约、noop 和 OTel 适配器。
- 核查 AI 新增的 `AI_TELEMETRY_SCHEMA`、`pi.ai.request`、`pi.ai.poll`、request metrics 与现有 Span/Metrics 接口的边界。
- 核查 `provider_error`、取消请求的 `ok + stop: aborted`、错误文本最长 500 字符等语义属于 telemetry 层还是 AI 层。
- 保持 content-free：请求内容不能因对齐遥测而进入 Span。
- 预期先行产出：telemetry 重测结论；若无行为缺口，明确记录“重测后仍对齐”，不制造实施包。

### L1 ai — `map/02-ai.md`

按以下依赖顺序拆分工作包：

1. **流式工具参数与消息形状**
   - `StreamingJsonParser`、`ToolCallArgumentsStream`、`JsonChange`。
   - `appendGrammarToolInputJsonDelta()` 的输入与关闭语义变化。
   - 每个 provider 只消费 delta 一次，避免完整参数重复解析。
   - 原始参数文本、`partialJson`、`partialArgs`、流索引等内部状态不再泄漏到 ContentBlock。
   - malformed final arguments、`argumentsError`、非法 `\u` 和空白工具结果。
2. **模型目录与运行时**
   - models.dev v2 目录和每 provider 规则的新组织方式。
   - GPT-6.1 Terra、MiniMax-M3.1-Flash 及主流模型数据漂移。
   - OpenRouter 按 Key 的动态目录、离线缓存和 baseUrl 覆盖。
   - Kimi Coding 协议变化是否属于 R2 新范围，单独裁决，不默认扩面。
   - 模型切换时丢弃旧模型 thinking 的 transform 语义。
3. **Client Identity 与遥测**
   - `identity`、`ClientIdentity`、`withClientIdentity()`、User-Agent、归因头。
   - `AgentOptions.identity` 所需 AI 层接口。
   - AI 请求 tracing 和 metrics。
4. **Provider 与 OAuth**
   - 会话中 effort 配置、prompt cache 锚定、重试和错误分类。
   - device-code 的 `openBrowserOnConfirm`、`copyCodeOnConfirm`。
   - OAuth URL scheme 白名单、auth 文件权限。
   - 旧 17 条存疑逐项重判；B58 仍需用户单独拍板。

AI 的设计不得只引用 changelog。流式与消息形状工作包必须附关键类型和状态迁移的逐字代码。

### L2 mcp — `map/07-mcp.md`

- `structuredContent` 从对象扩为任意 JSON，包括数组和 `null`。
- 连接时不再枚举 resources，以及 `pi mcp list --json` 的新报告形状。
- OAuth 刷新后的取消/超时传播和 transport `signal`。
- `oauth.clientRegistration: auto | cimd | dcr` 的完整决策树与 CLI 参数。
- OAuth metadata、授权端点和登录 URL 的 http/https 白名单。
- UTF-8 BOM、错误正文生命周期和 Windows 文件行为。
- 旧 14 条 w1 存疑逐条重判。
- B202 拆清两条消费链：codemode 部分按 R5 出面；`after_tool` 等主流消费者不得顺带排除。
- 保持 map/04 与 map/07 的 MCP 粗、细账去重。

### L3 agent-core — `map/03-agent-core.md`

- 旧“100%”只代表旧锚点，全部 90 个未加权单元仍需重测。
- `AgentOptions.identity` / `Agent.identity` 与 AI Client Identity 的接线。
- `streamProxy()` 的增量参数解析和原始参数状态隔离。
- 工具结果、assistant message、错误与取消语义是否受 AI 新类型影响。
- 不复活 pi 已删除的 harness、session storage、pico3 等 R1 面。
- 现有 21 个工具、嵌套调用和工具暴露面必须跑回归，不因模块满分而豁免。

### L4 session / SQLite — `map/06-session-evals-chord.md`

- 恢复会话时的 active tools 恢复顺序，以及 CLI `+name/-name` 的叠加规则。
- 输出长度截断后的 tool call/tool result 成对修复。
- compaction 指令、thinking 省略、模型输出上限和 reserveTokens 新语义。
- JSONL 中 system prompt、工具变化、usage 和 responseModel 等字段的持久化/投影。
- SQLite 是 pi-java 独立后端：验证同一行为语义，不抄 pi 已删除 schema。
- 重测批 33 的惰性创建、v1/v2 迁移、compaction `systemMessage` 和 D190–D194。

### L5 coding-agent — `map/04-coding-agent.md`

优先处理跨层行为，再处理长尾：

1. `before_agent_start` 的 `messageType: user | custom` 联合、载荷和 prompt 注入。
2. run 启动阶段的 busy/idle、sendMessage 排队、deliverAs 和 abort。
3. `reachesTools`、namespace `aliases`、active tools 稳定顺序。
4. `sessionShareDestination`、`selfUpdateCommand`、扩展包 changelog、`pi.exec(maxBuffer)`。
5. settings/models/keybindings/theme Schema 及主题 `vars`。
6. Markdown transformer 的 `messageId`、`timestamp`。
7. project trust、工具恢复、压缩、HTML 导出和 provider 兼容修复。
8. tool_search 的 Unicode 分词、规范化和排序变化。
9. B259、E2 五项存疑、E5-31 和其他旧开放项。
10. D′、F 整块和 w3 汇总行全部按实际单元去重重算，不能继承旧 1/16、0/13 等滞后汇总。

`sessionShareDestination` 和 `selfUpdateCommand` 会使批 22/31 的部分旧裁决需要重审。新设计必须明确是修订旧裁决还是登记窄差。

### L6 evals — `map/06-session-evals-chord.md`

- 在 coding-agent 新接口稳定后重测 harness、reporter、CLI 和 host eval。
- 核查新增 prompt template documentation eval。
- 核查流式参数、before_agent_start、动态目录等新行为是否需要新 oracle。
- 保留 D182–D189 的窄差状态，不用“评测域已收口”跳过新锚点重测。

### L7 TUI / web — `map/05-tui-web.md`

- 合并旧 TUI 队尾余 3–5 批与新锚点差异，取消重复排期。
- PageUp/PageDown 的选择器/自动补全捕获规则。
- 扩展 dialog/custom overlay 堆叠和完成后的恢复语义。
- Rex、OSC52、终端控制字符清理和退出恢复。
- 模型与会话搜索、模型名称显示、HTML 导出、CJK Markdown 和数学块。
- 旧 S/K/R/B 行与 E5-31 同步重判。
- web 只验证协议和交互兼容，不计入 pi 对齐分母。

### L8 examples — `map/04`、`docs/07`

- 在新锚点重新清点 examples，旧“16/102”保留为历史进度，不假定新分母仍为 102。
- 逐件判断：仍有效、已修改、已删除、R5 出分母。
- 优先移植能钉住新流式、扩展事件、工具加载和 MCP 行为的示例。
- Chord plugin 组按本轮 R5 裁决不进入新分母。
- examples 完成条件按新清单重新定义，不能继续等待一个已经过期的 102 件清单才翻行。

## 6. 跨层依赖链

以下链路必须按顺序设计和验证，不能拆成互不兼容的局部修改：

| 链 | 顺序 | 必须贯通的行为 |
|---|---|---|
| 流式工具参数 | ai → agent-core → coding-agent → RPC/TUI/web | delta 只读一次；最终错误可验证；原始状态不进转录；渲染不重复处理全量参数 |
| Client Identity / telemetry | telemetry/ai → agent-core → coding-agent | User-Agent、归因头、Span、取消和费用语义一致 |
| 动态模型目录 | ai → coding-agent → TUI/RPC/web | 按 Key 可用模型、离线缓存、模型选择和 prompt cache 不互相破坏 |
| MCP 结果与 OAuth | ai/mcp → coding-agent | 任意 JSON structuredContent、取消、注册策略和安全 URL 规则 |
| 扩展启动事件 | coding-agent → evals → examples | user/custom 两臂、busy 窗口、prompt 注入和排队顺序 |
| 会话恢复与压缩 | agent/session → coding-agent → export/web | 工具状态、截断恢复、摘要和投影一致 |

## 7. 地图与开放项修订规则

### 7.1 每一行都重新取证

- “上一批已对齐”不是新锚点证据。
- pi 删除行必须给删除提交或新锚点缺席证据，再按 R1 处置。
- pi-java 扩展件不能仅因“pi 没有”就判缺失；应标记扩展并决定是否计分。
- 行为等价但形状不同的内部实现可登记窄差；模型可见文本和 Schema 不以“形状不同”直接放行。

### 7.2 去重

- map/04 的 MCP 粗单元与 map/07 细单元只计一次。
- D′ 与 E5-31 的扩展 UI 成员按同一成员集合对账。
- F 整块与 E4/E5/E6 等细单元不得重复扣分。
- examples 的“能力单元”和“整包验收规格”只保留一个计分入口，另一处作指针。
- R5 行权重为 0，不以“G 域仍保留旧分母”的方式继续拉低完成度。

### 7.3 编号

- 新 B 从 **B260** 起。
- 新 D 从 **D201** 起。
- 已关闭条目只追加新锚点注记，不改写历史闭环日期。
- 旧 F 类 B58 仍由用户单独裁决；本总稿不擅自选择 OpenAI 默认 wire。

## 8. 实施与验证门

本总稿不涉及实施。后续每个获批工作包遵守以下门槛：

1. RED-first：先落能复现差异的测试，再写实现。
2. 模块验证：

   ```bash
   mvn test -pl <module> -am
   ```

   底层或跨模块改动必须带 `-am`，避免拾取旧构件。

3. 包闭环前运行完整：

   ```bash
   mvn clean verify
   ```

   要求测试零失败、Checkstyle 零错误零警告、SpotBugs 零新增问题。
4. 验证日志不得只放在 `target/`，因为 `clean` 会删除；关键结果回填设计稿。
5. 模型可见文本使用逐字断言。必要时从固定锚点运行 TS 生成 golden，不能以 Java 自产 golden 自证。
6. 流式、工具执行和会话链必须有集成测试。TUI 包另按 docs/00 步骤 5b 做真实终端冒烟。
7. 文件保持不超过 500 行；不得用 `@SuppressWarnings` 掩盖新告警。
8. 每包完成后才回填本层新账面。测试失败或行为未验证时不得翻“对齐”。

## 9. 换锚进度记录

换锚期间按以下规则维护当前状态：

- docs/06 只记录本文定义的层级顺序和闭环状态。
- map/ANCHOR 保留旧“当前锚点”，同时登记新“目标锚点”，避免把旧地图误称为新锚点证据。
- 每层地图完成重测后，在自己的头部明确标注 `PI_PIN=56cae7823`，以该头部覆盖换锚期间的总说明。
- 全部层完成后才切换全局当前锚点。

> 本表只在对应阶段真实完成后更新。“重测完成”不等于“层闭环”；设计待审与未获批准的实施仍明确分开。

| 层 | 新地图重测 | 详细设计审核 | 实施闭环 | 新层账面 |
|---|---|---|---|---|
| L0 telemetry | 已完成（map/01 A 节，2026-11-01） | 已通过（docs/09，2026-11-01） | 已闭环（B260–B262，2026-11-01） | **36/36＝100%** |
| L1 ai | 已完成（map/02，2026-11-01） | docs/10 B264、docs/11 行为面、docs/12 目录数据与运行时、docs/13 请求遥测、docs/14 Client Identity、docs/15 usage 与账单、docs/16 存疑复核、docs/17 provider wire、docs/18 retry/overflow、docs/19 OAuth／device／env＋CLI 权限、docs/20 Faux／deferred 均已通过 | 已闭环（B264、docs/11–docs/20 全部闭环，2026-11-04） | **232.0/232＝100%** |
| L2 mcp | 已完成（map/07，2026-11-04：重测 96.0/131＝73.28%） | 已通过（docs/21＋docs/22，2026-11-04） | 已闭环（W1–W12＋#85，2026-11-04） | **122.0/122＝100%**（对齐 82／存疑 0／缺失 0；9 条跨层方言转出分母） |
| L3 agent-core | 未开始 | 未开始 | 未开始 | 不发布 |
| L4 session | 未开始 | 未开始 | 未开始 | 不发布 |
| L5 coding-agent | 未开始 | 未开始 | 未开始 | 不发布 |
| L6 evals | 未开始 | 未开始 | 未开始 | 不发布 |
| L7 TUI/web | 未开始 | 未开始 | 未开始 | 不发布 |
| L8 examples | 未开始 | 未开始 | 未开始 | 不发布 |

全部层完成后才执行：

1. 将 `docs/map/ANCHOR.md` 的当前锚点改为 `56cae7823`，并保留本次换锚历史。
2. 重算 docs/05 总表和权重 3 层。
3. 确认 docs/06 中所有层均已闭环。
4. 在 docs/04 记录换锚销号、开放项和仍未消除的窄差。
5. 发布新的全仓完成度，并注明 pi 与 pi-java 双侧 SHA。

## 10. 风险与控制

| 风险 | 控制 |
|---|---|
| 把 changelog 当实现事实 | 详细设计必须附新锚点源码证据 |
| 旧地图错账进入新分母 | 每行重测，D′/F/examples 强制去重 |
| R5 误伤共享主流行为 | 按消费链判定，不按文件名整包跳过 |
| AI 改动导致上层连续返工 | 除 docs/10 B264 明示的窄纵贯迁移外，AI 未闭环前不实施 agent/coding-agent 适配 |
| 新旧百分比混算 | 全部层完成前不发布新总表 |
| pi main 继续前进 | 固定 `56cae7823`，后续变化另开周期 |
| 旧 TUI/examples 计划重复 | 所属层统一收编，原顺序仅保留历史 |
| 为新差异引入外部依赖 | 依赖变更必须在对应设计稿单列并经用户批准 |

**B264 窄纵贯例外（2026-11-02 已随 B264 闭环）**：只允许了 AI 新 record 所需的上层编译适配、`argumentsError`/`responseId` 机械传播，以及 malformed 工具调用在执行前被拒绝的安全行为。未实施其他 L3/L4/L5 行为，也未翻这些层的账面。docs/10 是该例外边界的详细说明。

## 11. 下一步

1. L0 telemetry 与 B264 已闭环；B264 的窄纵贯适配已收口。
2. L1 AI 已全闭环（docs/11–docs/20，2026-11-04）；账面 232/232＝100%，见 `map/02-ai.md`。其设计批准不外推到其它层。
3. L2 mcp 已闭环（docs/21＋docs/22，2026-11-04）：账面 122.0/122＝100%（对齐 82／存疑 0／缺失 0；9 条跨层/平台方言转出分母），见 `map/07-mcp.md`；D217 已销。下一步按 docs/23 启动 L3 agent-core 重测与首个详细设计。
4. 任一工作包未取得明确批准前，不写对应实现代码。

## 12. 总体完成定义

本次换锚只有同时满足以下条件才算完成：

- 七份地图全部以 `56cae7823` 重测，pi-java 侧 SHA 明确；
- R1/R2/R5 和去重口径一致；
- 获批的主流缺口已实施并通过验证；
- 未做项均有 B/D/F 或范围裁决，不存在无归属缺口；
- docs/04、docs/05、docs/06、map/ANCHOR 和本文件进度一致；
- 新总完成度可以由逐行权重重新计算得到。
