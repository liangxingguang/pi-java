# 67 - Batch F：thoughtSignature / 加密推理往返与跨模型剥离

**状态：✅ 已实现并闭环（2026-10-02，commits `648edfd`→`516517a`，R1–R9 全部按建议执行）。**

> 对应台账：B14b/R1（`docs/47 §7-R1`、`docs/48` §5 F 行）＋ B19 剩余（completions
> `reasoning_details` 结构化形状，`docs/32:187`）均随本包关闭。pi 锚点 `3390bd936`。

---

## 1. pi 事实清单

### 1.1 已对齐：Anthropic 车道（包①，本包**只验证不改动**）

签名采集（`anthropic-messages.ts` content_block_start/signature_delta）、重放
（signed `thinking` ＋ `redacted_thinking`）已在包①闭环（`docs/31 §8.33`）。

### 1.2 Google 采集（`google-generative-ai.ts`，vertex 镜像车道排除）

Part 上的 `thoughtSignature` 可出现在**任意**块类型上（`google-shared.ts:113-127`）：

```ts
// :141 retainThoughtSignature：后到的非空值覆盖，空值不擦除
export function retainThoughtSignature(existing, incoming) {
    if (typeof incoming === "string" && incoming.length > 0) return incoming;
    return existing;
}
```

- thinking 块 `:149-152`：`currentBlock.thinkingSignature = retainThoughtSignature(…, part.thoughtSignature)`
- text 块 `:161-164`：同一 retain 写入 `textSignature`
- functionCall `:207`：`...(part.thoughtSignature && { thoughtSignature: part.thoughtSignature })`

### 1.3 Google 重放（`google-shared.ts:228-283`）

```ts
:231  const isSameProviderAndModel = msg.provider === model.provider && msg.model === model.id;
:147  const base64SignaturePattern = /^[A-Za-z0-9+/]+={0,2}$/;
:149  isValidThoughtSignature = sig && sig.length % 4 === 0 && pattern.test(sig)
:158  resolveThoughtSignature = (same, sig) => same && isValidThoughtSignature(sig) ? sig : undefined
```

- text `:234-244`：**空文本但带签名 ⇒ 保留**（空 part 也要回送）；part = `{text, …(sig && {thoughtSignature})}`
- thinking `:245-264`：同身份 ⇒ `{thought:true, text, thoughtSignature}`（空块无签名才跳）；
  跨身份 ⇒ 空块跳、非空转纯文本
- toolCall `:265-275`：`functionCall` part 上挂 `thoughtSignature`（同样经 base64/身份门）

### 1.4 Responses 车道

- **请求 include**（`openai-responses.ts:344-353`，azure `azure-openai-responses.ts:327-337` 同形）：

```ts
if (options?.reasoningEffort || options?.reasoningSummary) {
    params.reasoning = { effort, summary };
    params.include = ["reasoning.encrypted_content"];   // 仅此分支
}
```

`:359` 的 xAI 分支、`openai-codex-responses.ts:560` ＝ 排除（R4）。

- **采集**（`openai-responses-shared.ts:686-697`）：reasoning 块落定时
  `slot.block.thinkingSignature = JSON.stringify(item)`（整个 ResponseReasoningItem）。
- **Azure 回填**（`:533-549`）：`response.output_item.done` 可能不带 encrypted_content，
  在终局事件上从 `response.output` 补：

```ts
const storedItem = JSON.parse(block.thinkingSignature);
if (storedItem.encrypted_content) continue;
block.thinkingSignature = JSON.stringify({ ...storedItem, encrypted_content: item.encrypted_content });
```

- **重放**（`:261-266`）：`const reasoningItem = JSON.parse(block.thinkingSignature); output.push(reasoningItem)`。

### 1.5 Completions `reasoning_details`（B19 剩余）

- **采集**（`openai-completions.ts:664-675`）：

```ts
const reasoningDetails = (choice.delta as { reasoning_details?: unknown }).reasoning_details;
if (Array.isArray(reasoningDetails)) {
    for (const detail of reasoningDetails) {
        if (!isOpenAIReasoningDetail(detail)) continue;
        ensureThinkingBlock("");
        appendOpenAIReasoningDetail(streamedReasoningDetails ??= [], detail);
    }
}
```

- **校验/合并**：`isOpenAIReasoningDetail :130-147`（公共字段 id?:string、format?:string、
  index?:number；type 三值 `reasoning.summary`/`.encrypted`/`.text` 各带必备字段）；
  `appendOpenAIReasoningDetail :252-266`（相邻 text 合并 text＋signature，相邻 summary 合并，
  其余整条 push）。
- **落定**：finishBlock `:440`（正常）与 catch `:704` 两处
  `block.thinkingSignature = JSON.stringify(streamedReasoningDetails)`。
- **重放**（`:1301-1307` 取、`:1329-1339` 互斥、`:1373-1375` 发）：

```ts
const signedReasoningDetails = thinkingBlocks.map(b => parseOpenAIReasoningDetails(b.thinkingSignature))
    .find(details => details !== undefined);
const legacyReasoningDetails = toolCalls
    .map(tc => parseLegacyEncryptedReasoningDetail(tc.thoughtSignature))
    .filter(d => d !== undefined);
const preservedReasoningDetails =
    signedReasoningDetails ?? (legacy.length > 0 ? legacy : undefined);
// :1330 有 preserved ⇒ 不发裸 reasoning 字段；:1374 assistantMsg.reasoning_details = preserved
```

`parseOpenAIReasoningDetails :215-223`＝非空 JSON 数组且每项合法；
`parseLegacyEncryptedReasoningDetail :225-241`＝JSON 对象、type `reasoning.encrypted`、
id/data 均非空。

### 1.6 剥离与 PiMessages

- `transform-messages.ts:131-134`：**跨模型**且 toolCall 带 thoughtSignature ⇒ delete 该键
  （在 id 归一之前，两变换可叠加）。
- pi-messages 车道：`toolcall_end` 帧携带整个 ToolCall（`pi-messages.ts:70/:261-266`），
  thoughtSignature 随帧走。
- `assistant-message-frame.ts:72/:278/:471-473` 为 pi 的帧编解码内部形状（见 R3）。

---

## 2. Java 缺口清单（现状已逐项核实）

| # | 位置 | 现状 |
|---|---|---|
| G1 | `message/ContentBlock.java` | `TextContent(text)` 无 textSignature；`ToolUseContent(id,name,args)` 无 thoughtSignature |
| G2 | `protocol/GoogleGenerativeAiApi.java:199-237` | Part.thoughtSignature（SDK 1.72 为 `Optional<byte[]>`）三个落点**全部未读**；`StreamPartialBuilder` 无 text 签名槽、`emitToolCallEnd` 无签名通道 |
| G3 | `protocol/GoogleMessageConverter.java:110-141` | 无同身份门、无 base64 校验；ThinkingContent 经 `blockParts:309` 恒丢（同模型也丢） |
| G4 | `protocol/ResponsesStreamProcessor.java:222-233` | reasoning 项未序列化；终局无 azure 回填 |
| G5 | `protocol/ResponsesMessageConverter.java:141-150` | reasoning 已建但**不发 include**；`:360-362` 重放丢 thinking |
| G6 | `protocol/OpenAICompletionsApi.java:262-274` | 只读三个裸字段，`reasoning_details` 未读 |
| G7 | `protocol/OpenAICompletionsMessageConverter.java:405-425` | reasoning_details/legacy 均无；文件 **512 行**（checkstyle warning，docs/66 收口漏网，另有 3 个 unused imports warning） |
| G8 | `api/TransformMessages.java:276-288` | 无 thoughtSignature 剥离（此前判「结构性不可达」，本包随字段落地而翻转） |
| G9 | `protocol/PiMessagesApi.java:135`、`PiToolCall.java` | 工具调用签名被丢；PiToolCall 无该字段 |
| G10 | `agent/session/SessionJson.java:167-204`、`session/jsonl/MessageJsonCodec.java:290/:300` | textSignature/thoughtSignature 两键不写不读 |

---

## 3. 实施步骤（严格 RED → GREEN → 变异 → 回归）

### 步 1：类型加宽 ＋ 落线读写（G1、G10）

- `TextContent` 加第二组件 `String textSignature`，保留 1 参 ctor（`this(text, null)`）。
- `ToolUseContent` 加第四组件 `String thoughtSignature`，保留 3 参 ctor。
- `SessionJson.blockNode`：text 非空写 `textSignature`；tool_use 非空写 `thoughtSignature`。
- `MessageJsonCodec`：两键经 `optionalString` 读入新 ctor。
- **先红**：扩展 SessionJson 读回夹具（block 级 fixture）—— 期望键缺席 ⇒ 红；codec 同理。

### 步 2：Google 采集（G2）

`StreamPartialBuilder` 新增：
- `retainTextSignature(String sig)`：retain 语义（非空覆盖），改写当前 text 块；
- `retainThinkingSignature(String sig)`：同上改 thinkingSigBuf/块；
- `emitToolCallEnd(id, name, thoughtSignature)` 新 3 参重载，2 参版转发 null。

`GoogleGenerativeAiApi`：text/thought 分支对每个 part 调 retain（`Base64.getEncoder().encodeToString(part.thoughtSignature().get())`）；
functionCall 读签名传入 emitToolCallEnd。**先红**：RecordingHttpServer SSE 带
`thoughtSignature` ⇒ 终态消息块签名断言红 ×3（含「后 delta 空值不擦除」）。

### 步 3：Google 重放（G3）

`assistantParts` 内计算 `same = Objects.equals(a.provider(), modelId.provider()) && Objects.equals(a.model(), modelId.modelName())`；
新增纯函数 `ThoughtSignatures.resolve(base64, same)`（length%4 ＋ 正则）；
text/toolUse 两路径补 `Part.builder().thoughtSignature(Base64.getDecoder().decode(sig))`；
thinking 路径移入 assistantParts（同身份 thought part＋签名；跨身份文本/丢块）。
**先红**：GoogleAssistantReplayTest 新增同模型签名回放 3 例 ⇒ 红；负对照（跨模型、
非法 base64）断言键缺席。

### 步 4：Responses 采集 ＋ 回填（G4）

- `handleOutputItemDone` reasoning：`ObjectMappers.jsonMapper().writeValueAsString(item)`
  经 `builder.applyThinkingSignature(json, false)` 于 emitThinkingEnd 前落定；
  同时在处理器本地 `Map<rsId, json>` 记录。
- `finalizeResponse`：遍历 `response.output()` reasoning 项，stored JSON 缺 encrypted_content
  时合并后重新落定（对应 pi `:537-549`）。
- **先红**：reasoning SSE（含 encrypted_content）⇒ 终态签名断言红；azure 形（done 项无
  encrypted_content、completed.output 有）⇒ 回填断言红。

### 步 5：Responses include ＋ 重放（G5）

- `ResponsesMessageConverter:149` 后：`builder.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))`。
- `addAssistantItems`：ThinkingContent 非空签名 ⇒ `ObjectMappers.jsonMapper().readValue(sig, ResponseReasoningItem.class)`
  （解析失败 ⇒ 跳过该块）→ `ResponseInputItem.ofReasoning(item)`，按块序插入。
- **先红**：请求体 `include` 断言红；历史 reasoning 项重放断言红（wire body 观测）。

### 步 6：Completions details 采集（G6）

新建 `protocol/CompletionReasoningDetails.java`（纯函数：`isDetail(Map)`、`append(list, detail)`、
`parseArray(String)`），逐字移植 `:122-147/:252-266/:215-223`。
`OpenAICompletionsApi`：delta 附加属性 `reasoning_details` 经 SDK mapper convertValue 成
Jackson 节点，逐项校验 append；收尾前把 `JSON.stringify(details)` 落定当前 thinking 块
（懒建块：首个 detail 到达时 emitThinkingStart）。
**先红**：OpenRouter 形 details delta 夹具 ⇒ 终态签名红（含相邻 text 合并规则）。

### 步 7：Completions details 重放 ＋ 拆文件（G7）

新建 `protocol/CompletionsReasoningWire.java`：从 AssistantMessage 提取
signed（thinking 签名 JSON 数组）＋legacy（toolCall 签名 JSON 对象），产出
`reasoning_details` SDK JsonValue（**经树**：mapper.convertValue(node, JsonValue.class)，R11），
并把 converter 中 addAssistantMessage 的相关分支整体搬入。
搬移后 `OpenAICompletionsMessageConverter` 回 ≤500；顺带删 3 个 unused imports。
**先红**：replay 请求体 `reasoning_details` 断言红；有 details 时裸字段不发（互斥）断言红；
legacy 单独路径 1 例。

### 步 8：剥离 ＋ PiMessages（G8、G9）

- `TransformMessages.gateBlock` ToolUse 分支：`!same && sig 非空` ⇒ 四参 ctor 重建（签名置 null），
  随后照旧跑 id 归一（两变换叠加）。
- `PiToolCall` 加第四组件（保留 3 参 ctor）；`PiMessagesApi:135` 传 `tc.thoughtSignature()`。
- **先红**：跨模型剥离夹具红；PiMessages toolcall_end 透传夹具红。

---

## 4. 裁决点

| # | 裁决 | 建议 |
|---|---|---|
| R1 | B19 剩余（completions `reasoning_details`）是否并入本包 | **并入**：与 thoughtSignature 共用签名槽/落定/重放函数，分包会重复改 addAssistantMessage；闭环后更新 docs/32 B19 剩余登记 |
| R2 | Anthropic 签名面 | 包①已闭环，本包**只验证、零生产改动** |
| R3 | `assistant-message-frame.ts` 帧编解码是否移植 | **不移植**：pi-java 事件携带完整 partial 快照，签名随 ContentBlock 自动走；帧是 pi 的存储传输形状，不是行为 |
| R4 | 排除项 | xAI include（`:359`）、codex include（`:560`）、Vertex 镜像车道 —— 沿用「不新增 provider」裁决 |
| R5 | legacy toolCall.thoughtSignature 读路径 | **移植**（`:225-241`，随字段落地近乎免费）；当前唯一生产者是 Google base64 串（JSON.parse 不成立 ⇒ 不触发），仅存量 JSON 对象会话触发 |
| R6 | Google 同身份门的比较口径 | 照 pi 只比 **provider ＋ model**（不比 api） |
| R7 | SDK byte[] 处理 | Google SDK 1.72 Part.thoughtSignature 为 `Optional<byte[]>`（wire base64）；pi-java 存 base64 字符串，重放 decode 回 byte[] |
| R8 | 文件 ≤500 | 新增 `CompletionReasoningDetails`＋`CompletionsReasoningWire`，converter 回 ≤500；删 unused imports；ResponsesMessageConverter（474）只增 ≤10 行，超限则把 reasoning item 重放一并搬入新 helper |
| R9 | details JSON 上 SDK 的路径 | 一律经 SDK mapper 树路（convertValue/JsonNode），不手搓 Map 喂 JsonValue（docs/58 R11 教训） |

---

## 5. 测试与变异探针计划

新增/扩展测试（全部 wire 观测，RecordingHttpServer）：

1. SessionJson/MessageJsonCodec：四键（textSignature/thoughtSignature 读写）
2. `GoogleGenerativeAiApiTest`/新 helper：采集 ×3 ＋ retain
3. `GoogleAssistantReplayTest`：重放 ×3 ＋ 跨模型/非法 base64 负对照
4. Responses：采集/回填 ×2、include ×1、重放 ×1
5. Completions：details 采集/重放 ×各 2、legacy ×1
6. TransformMessages 剥离 ×1；PiMessages 透传 ×1

变异探针（每次落地后 grep 复核，即刻复原）：

| 探针 | 变异 | 预期红集 |
|---|---|---|
| M1 | Google 采集三处不读签名 | 恰 3 红（retain 对照组不计） |
| M2 | Google 重放同身份门恒真 | 恰 1 红（跨模型负对照） |
| M3 | 去掉 base64 校验 | 恰 1 红（非法 base64 对照） |
| M4 | Responses 不发 include | 恰 1 红 |
| M5 | Responses 不序列化 reasoning item | 重放/采集共 2 红 |
| M6 | 去掉 azure 回填 | 恰 1 红 |
| M7 | Completions 不采集 details | 恰 2 红 |
| M8 | Completions 重放不发 details／去互斥 | 恰 2 红 |
| M9 | Transform 不剥离 thoughtSignature | 恰 1 红 |

---

## 6. 风险与未覆盖

- Google 真实 SSE 的签名到达时机（首 delta only）本环境只能以录制 SSE 模拟；retain 逻辑
  是唯一防护点。
- Completions 车道的 details 生产者主要是 OpenRouter/llama.cpp 后端；无固定金标，断言只钉
  「pi 代码规则」不钉具体厂商数据。
- 不触碰 Google functionCall args 那条独立存量面（不在本包范围）。
- 闭环时全量对账：docs/48 banner＋§5（F 行＋B19 剩余措辞）、docs/32（B14b/B19 剩余）、
  docs/47（R1 登记）、docs/41 等，grep 复核无残留「待做」描述。

---

## 12. 实施记录

### 12.1 步骤与 RED/GREEN 实测

| 步 | Commit | 内容 | RED | GREEN |
|---|---|---|---|---|
| 1 | `648edfd` | 类型加宽＋落线读写 | RED① 编译失败 → RED② 5 红（3 fail+2 err／7） | 7/7 |
| 2 | `777f400` | Google 采集三处 | 4/4 | 4/4 |
| 3 | `f6dc09b` | Google 重放门 | 4/13（见偏差 a） | 13/13 |
| 4 | `97150c8` | Responses 采集＋Azure 回填（提取 ResponsesReasoningCapture、ToolArgumentParser） | 2 errors | 2/2 |
| 5 | `5cdb01f` | include 门＋reasoning 项重放 | 2/3 | 3/3 |
| 6 | `9003a67` | completions details 采集 | 2 errors | 2/2 |
| 7 | `24c4895` | completions details 重放；converter 512→496 | 3/3 | 3/3 |
| 8 | `f4562f8` | 跨模型剥离＋PiMessages 透传 | 2/12 | 12/12 |
| — | `080bb09` | 两个旧 oracle/夹具更新 | — | — |
| — | `516517a` | TUI 余两处 record pattern | — | — |

### 12.2 变异探针实测

| 探针 | 实测红集 | 设计预测 |
|---|---|---|
| M1 Google 三处不读 | **4** 红（text/thinking/functionCall＋retain 对照） | 3 |
| M2 重放门恒真 | **1**（crossModelStripsSignatureFromText） | 1 ✓ |
| M3 去 base64 校验 | **1**（invalidBase64SignatureIsDropped） | 1 ✓ |
| M4 不发 include | **1** | 1 ✓ |
| M5 不序列化 reasoning item | **1**（capture；backfill 经 state json 仍绿） | 1 ✓ |
| M6 去 Azure 回填 | **1** | 1 ✓ |
| M7 completions 不采集 | **2** | 2 ✓ |
| M8 去 legacy 解析 | **2**（B/C；signed A 绿） | 2 ✓ |
| M9 去剥离 | **2**（basic＋stacking） | 1 |

### 12.3 偏差与说明

a. **步 3 RED 实为 4 红而非 5**：`crossModelDowngradesThinkingToPlainText` 在测试编写时即绿——前置的
`TransformMessages` 闸（gateBlock d2）已把跨模型 thinking 转成文本。pi 也先跑 transform，
故其 google-shared `:260-269` 跨身份分支同样**结构性不可达**；按 pi 逐字保留并在 javadoc 标注。
b. **M1/M9 红数与预测差 1**：分别因 retain 对照组（M1）与 id 叠加组（M9）也断言该行为；探针真实有效。
c. **畸形签名**：thinking 签名无法解析为 ResponseReasoningItem 时**跳过该块**（设计第 5 步裁决）；
pi 的 `JSON.parse` 会抛——无畸形签名的生产路径，差异不可达。
d. **ToolArgumentParser 提取**（计划外的一处拆分）：StreamPartialBuilder 加签名方法后达 528 行，
lenient 解析整体搬到 stream 包新类，builder 490 行；ToolCallBuilder 复用之。
e. **模块回归**：ai 1284、agent-core 535、coding-agent 290、web 48、evals 44（18 条 smoke 跳过为常态），
全模块 checkstyle 零违规。
