# 62 — D3：OpenAI 默认 Responses wire 与 item.id/composite ID 对齐

**状态：✅ 已闭环（2026-10-02，`af2eaa9`，9 files +337/−34）**
**创建基准：** pi-java `1bce529`（B103 收尾）

> ⚠️ 本包的要害（`docs/48` Batch D 原注）：**不能以「改一个默认常量」代替设计。**
> pi 默认 Responses，关键不是协议名，而是它从接收流起就把工具调用 id 存为复合
> `call_id|item.id` 并全链路保留。只翻默认协议、不补 item identity，会让 store:false
> 的多轮重放 item 关联/配对出错。

---

## 1. pi 事实清单（file:line 已核）

### 1.1 官方 OpenAI provider 默认且写死 Responses

- provider factory：`packages/ai/src/providers/openai.ts:6-14` —— `api: openAIResponsesApi()`，
  provider 类型 `Provider<"openai-responses">`，baseUrl `https://api.openai.com/v1`。
- 目录扁平化：`packages/ai/src/providers/openai.models.ts:1-8`。
- 生成器显式写 Responses：`packages/ai/scripts/generate-models.ts:1785`
  `api: "openai-responses"`、baseUrl、reasoning 等。
- 运行时按 `model.api` dispatch：`packages/ai/src/models.ts:797-813`；官方 provider 传单个
  responses api ⇒ 该 provider 所有模型走 Responses。

### 1.2 不存在「运行时从 Responses 降级 Completions」的裁决

- 全仓搜 `resolveApi/defaultApi/useResponses/forceCompletions` 无协议裁决函数（只有 `resolveApiKey`）。
- 第三方 OpenAI-compatible provider（deepseek/groq/nvidia/together/vllm 类）被**静态**指定
  `openai-completions`（如 `providers/deepseek.ts:6-14`），不是请求时按能力回退。
- `compat` 只在协议**已确定后**影响 payload 细节（`types.ts:958-991`）。

### 1.3 ★ 工具调用 id 是概念上的复合 `call_id|item.id`

- 接收流时构造复合：`packages/ai/src/api/openai-responses-shared.ts:485-489`
  ```ts
  if (item.type === "function_call") {
      const block = { type:"toolCall", id:`${item.call_id}|${item.id}`, name:item.name, ... }
  ```
  custom tool 同形 `:504-510`。
- 内部只有一个 id 字段，复合串贯穿 ToolCall / 历史重放。
- **两段分工不同**：
  - `call_id`：函数调用 与 `function_call_output` 的关联键。
  - `item.id`：Responses output item identity，参与服务端 item / reasoning(`rs_*`) 配对校验。

### 1.4 出站（重放）时拆分复合

- 重放拆分：`openai-responses-shared.ts:288-293` `const [callId, itemIdRaw] = toolCall.id.split("|")`。
- tool result 只用 call_id：`:331-346` `const [callId] = msg.toolCallId.split("|")`
  ⇒ `function_call_output.call_id = callId`（内部 toolResult.toolCallId 也带复合，出站才拆）。

### 1.5 item id 约束与跨模型/跨模型身份处理

- 形态约束：`:152-160` `normalizeIdPart`（非法字符→`_`、截 64、剥尾部 `_`）
  ＋ `buildForeignResponsesItemId = "fc_"+shortHash`。
- 复合归一：`:163-175` `normalizeToolCallId`；非本 provider/本 api 的 tool call，item.id 走
  foreign hash；item.id 必须 `fc_` 开头，否则补 `fc_`。
- 允许的 provider 集：`openai-responses.ts:31` `Set(["openai","openai-codex","opencode"])`
  （Azure 集多 `"azure-openai-responses"`，`azure-openai-responses.ts:26`）。
- **跨模型/不同模型丢 item.id**：`:253-303` —— different-model 且 id 为 `fc_`、或重放为
  function_call 但 id 非 `fc_`（如 custom tool 的 `ctc_`）时，`itemId = undefined`，避免
  `fc_*` 与 `rs_*` reasoning 配对校验失败。
- item 实体带 id：`:306-324`（function_call/custom_tool_call 的 `id: itemId`）。
- 文本消息 id 经 textSignature 保存/恢复：`:52-55`（encode）、`:267-286`（重放）、`:699-702`（接收）。

### 1.6 Responses 请求是无状态重放（store:false）

- `openai-responses.ts:309-319`：`store:false`、`input` 放历史 item、不依赖
  `previous_response_id` 常规续接；含 prompt_cache_key/retention/options。
- 因此历史 item（function/tool/reasoning）每个都要正确，服务端校验 fc_ 形态、长度、
  fc_↔rs_ 配对、function_call_output.call_id 是否匹配。

### 1.7 显式 override：仅 models.json 的 model/provider `api`

- 优先级：`provider-composer.ts:142` `const api = definition.api ?? providerConfig.api ?? defaults?.api`。
- schema：model.api `model-config.ts:175`、provider.api `:215`；`modelOverrides` **无 api**
  `:189-209`，不能改协议。
- 同 id 自定义模型整条替换内置模型（`provider-composer.ts:214-220`）。
- CLI/settings **无**协议 flag（`cli/args.ts:104-109`；`settings-manager.ts:110-116`）。

### 1.8 Azure 差异

- apiName `azure-openai-responses`（`providers/azure-openai-responses.ts:6-13`）；model 字段用
  deployment name（`:43-50/:301`）；baseUrl host/path 归一（`:188-216`）；strict 默认 true
  （官方默认 false，`openai-responses.ts:68-75`）；encrypted_content terminal backfill
  （`shared:533-549`）。Azure 不在本包主流改动面（其 provider 在 java 已独立）。

---

## 2. pi-java 现状差

| # | 点 | 现状 |
|---|---|---|
| 2.1 | **默认协议相反** | `provider/OpenAIProvider.java:23-27` `defaultProtocol=OPENAI_COMPLETIONS`（Responses opt-in）。pi 默认 Responses。内置 gpt 模型（`BuiltinCatalog:57-59`）不带 api ⇒ 走默认 completions。 |
| 2.2 | **接收侧丢 item.id** | `ResponsesStreamProcessor.handleOutputItemAdded:206-212` 只取 `fc.callId()`，FunctionCallState/emitToolCallStart 只带 callId；`handleOutputItemDone:224-228` 同。Response 的 `fc.id()`（item identity）从未被读取。 |
| 2.3 | ToolUse.id 非复合 | `StreamPartialBuilder.emitToolCallStart/End:280-311` 用单一 callId 构造 `ToolUseContent(id,...)`；id 里无 `|`。 |
| 2.4 | ResponsesToolCallIds 复合分支不可达 | `ResponsesToolCallIds.java:14-16` 已带 `call_id|item.id` 拆分/归一逻辑，但 javadoc 自述「生产恒不含 `|` ⇒ 竖线分支不可达」（P33）。接收侧一旦产复合，该逻辑自动激活。 |
| 2.5 | 重放用合成 item.id | `ResponsesMessageConverter.addAssistantItems:459-461`：`.callId(toolUse.id())` + `.id(normalizeItemId(...))="fc_<callId>"`，不是历史真实 item.id。 |
| 2.6 | tool result 未拆分 | `ResponsesMessageConverter.convertMessages:226-230`：function_call_output 直接用 `tool.toolUseId()`（若它变复合，必须先 split 取 call_id）。 |
| 2.7 | 跨模型/不同模型丢 id 逻辑 | 接收侧不产复合 ⇒ shared 的 different-model item.id 丢弃（`shared:253-303`）在 java 无从体现；随复合落地核对是否需要。 |

**结论**：真正的差距是一条贯穿链——**接收构造复合 → ToolUse/ToolResult 带复合 → 重放拆分并恢复真实 item.id → tool output 只取 call_id**。默认协议翻转（2.1）只是触发点。

---

## 3. 改动方案

> 顺序原则：先打通「item identity 复合链」，再翻默认协议；否则翻协议即引入 item 关联错误。

### Step 1 — 接收侧构造复合 id（ResponsesStreamProcessor）

- `handleOutputItemAdded`：Response function_call 同时取 `callId()` 与 `id()`，复合
  `callId + "|" + item.id()` 传给 `emitToolCallStart` 并记入 FunctionCallState。
- `handleOutputItemDone`：用复合 id 调 `emitToolCallEnd`。
- 增量 delta（arguments）期间 callId 部分不变 ⇒ FunctionCallState 增 `compositeId`/`itemId`。

### Step 2 — ToolUse/ToolResult 全链路带复合（不改单字段形状）

- `StreamPartialBuilder` 无需改结构：id 形参即复合串，ToolUseContent.id 带复合。
- 下游宿主（agent-core 工具派发→ToolResultMessage.toolUseId）天然带同一复合串（唯一键匹配不受影响）。
- ⚠️ 核查 agent-core 工具执行/完成处是否对 id 形态有假设（长度/字符）；有则只在该 host 侧处理，不改语义。

### Step 3 — Responses 出站拆分

- `ResponsesMessageConverter.convertMessages` function_call_output：`tool.toolUseId()` 先
  split("|") 取 call_id（新增小 helper，或复用 ResponsesToolCallIds）。
- addAssistantItems function_call：复合 split ⇒ call_id 给 `.callId(...)`、真实 item.id 给
  `.id(...)`（含 fc_ 归一／foreign hash，复用 ResponsesToolCallIds，停用合成 `normalizeItemId`）。
- 核对 different-model/非 fc_ id 丢弃（shared:253-303）在 java 的落点。

### Step 4 — 翻默认协议（触发点，最后做）

- `OpenAIProvider.config()`：`defaultProtocol = OPENAI_RESPONSES`；supported 集两协议都保留。
- 内置 gpt 模型随即走 Responses；completions 仍可经 models.json model/provider `api` 显式选择。
- 更新 OpenAIProvider javadoc（当前写「default completions, responses opt-in」）。

### Step 5 — models.json 显式 override 回归确认

- 确认 `definition.api ?? provider.api ?? default` 优先级在翻默认后仍成立（显式 completions 能压住默认 responses）。

**不做**：Azure（独立 provider）、动态降级（pi 也没有）、为新 provider 开门。

---

## 4. 测试计划（RED 先，逐 Step）

1. **接收复合**：喂脚本化 Responses SSE（function_call 带 call_id＋item id），断言落盘 ToolUse.id
   ＝ `call_id|item_id`（旧实现＝只 call_id ⇒ 先红）。
2. **tool output 拆分**：多轮（历史带复合 toolUse/toolResult）⇒ 出站 function_call_output.call_id
   ＝ call_id（不含 `|`/item.id）。
3. **重放真实 item.id**：断言历史 function_call item.id ＝ 真实 fc_ id（不是 `fc_<callId>` 合成）。
4. **默认 wire**：内置 gpt 模型无显式 api ⇒ 请求打到 `/responses` 路径（RecordingHttpServer 断路径/体）。
5. **显式 override**：models.json 给该模型 `api:"openai-completions"` ⇒ 走 completions，压住默认。
6. **跨模型/不同模型**：构造不同模型历史 ⇒ 该丢的 item.id 不出（按 pi shared:253-303 核对后定断言）。

观测面统一 `RecordingHttpServer`（路径＋头＋体），纯函数（split/归一）单测。Mutation probes：
- 去掉复合分隔 ⇒ 1 红；tool output 不拆分 ⇒ 1 红；默认翻回 completions ⇒ 1 红。

**回归**：`pi-java-ai` 全模块；`pi-java-agent-core`（host 工具派发面）；全 reactor。

---

## 5. 风险与开放项

- **agent-core 工具派发是否假设 id 形态**：Step 2 实施前先取证（唯一 grep），决定 host 侧是否要在「执行工具」时取 call_id 段。
- **跨 provider 车道读同一条复合 id**：同一 transcript 日后走 anthropic/completions 车道时，
  其 ToolCallIdNormalizer 不识别 `|`（净化处理）——属跨 provider 既有行为，确认不回归即可。
- item.id 配对（fc_↔rs_）无真实 OpenAI key 无法端到端验证服务端接受度 ⇒ 以「形状严格照 pi + wire 夹具」为证据，与各包同口径。

---

## 12. 实施记录

**2026-10-02 闭环，`af2eaa9`（9 files changed, +337/−34）。按设计顺序「复合链先行、翻默认最后」。**

- **RED（6 红）**：接收复合 1（wire，旧 id 裸 call_id）；出站拆分 3（output call_id、
  真实 item.id、跨模型/非 fc_ 各 1）；默认协议 1。显式 override 用例零实现即绿
  （该机制 A-02 已存在）。
- **GREEN**：
  - Step 1 `handleOutputItemAdded` 取 `fc.callId()` ＋ `fc.id()` 经 `compositeId`
    构造复合；`FunctionCallState.callId` 存复合（否则增量帧把块 id 退回裸值）。
  - Step 2 `StreamPartialBuilder`/ToolResult 零结构改动，id 形参即复合串
    （agent-core id 是透传字符串，取证无形态假设）。
  - Step 3 新增 `ResponsesToolCallIds.callIdOf/itemIdOf`；function_call_output 用
    call_id 段；`addAssistantItems` 增加 target/apiName 入参，按 pi :289-303 的
    两条规则丢 id（跨模型 fc_、非 fc_）；合成 `normalizeItemId` 删除。
  - Step 4 `OpenAIProvider` defaultProtocol 翻 `OPENAI_RESPONSES`、javadoc 更新。
  - Step 5 `withModelProtocol`（A-02）在翻默认后优先序成立，用例钉住。
- **旧测试更新（3 处，钉的是被替换的老行为）**：`LaneTransformMessagesWiringTest`
  两条（旧断言 `call_id:"call_123|fc_abc"` ⇒ 新：call_id 拆分＋跨模型 id 省略）；
  `ProviderCatalogConformanceTest.openaiProvider…`（默认 completions ⇒ responses）。
- **Mutation**：M1 接收回退裸 callId ⇒ 恰 1 红；M2 output 不拆分 ⇒ 恰 1 红；
  M3 默认翻回 completions ⇒ 恰 1 红；均恢复复绿。
- **回归**：pi-java-ai **1222/1222**（checkstyle 0）；agent-core **525/525**
  （-am）；coding-agent **287/287**（-am，含 sqlite 后端）。**未跑全 reactor verify**
  （tui/evals/web 与本包无调用面；需要可补）。
- **取证备注**：SDK 4.42 的 `Response.validate` 要求 completed 事件带全字段
  （error/incomplete_details/instructions/metadata/parallel_tool_calls/temperature/
  tool_choice/tools/top_p 等），wire 夹具按此补全；此为测试面，生产零偏差。
  `ResponseFunctionToolCall.id` 是 Optional ⇒ 省略 id 可直接表达。
- **无新登记 B 项**：未发现设计 §2 之外的差距。
