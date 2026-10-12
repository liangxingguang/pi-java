# 09 ai 批 1 设计稿：流语义收口（6 子件）

> **✅ 已闭环 2026-10-13**：6 子件全落（①–⑤ commit 5d5489a9/e3da4cd2/a1d174c4/
> 5dfb74aa/a629d9d8；⑥ StopReason 枚举化五步 3e9d4084/337702c0/cffd16e8/2bab748b/
> 8ef3de49；B127/B147 夹具 2937dce2）。全 reactor clean test 3371 测绿（20 skip 为
> 既有 provider/门控）；ai 加权 89.7%→90.2%、总表 58.3%→58.4%。以下为开工前设计原文。

> 归属：[`docs/08`](08-ai-layer-alignment-roadmap.md) 批 1。基准 pi `ea448f454`，pi-java 工作树 HEAD。
> pi 证据一律 `git show ea448f454:<path>`。本批全部在 **pi-java-ai** 模块内（B119/B121 枚举化
> 机械涟漪到 agent-core/web/coding-agent，但行为不变）。
> 子件顺序：① #23 → ② #101 → ③ B222 → ④ B125 → ⑤ B118 → ⑥ B119/B121（大件，单独分 5 commit）。
> 前五个无相互依赖，仅 ③④ 同改 ResponsesMessageConverter 故顺序相邻。

---

## ① #23 signature_delta 不再多发空 ThinkingDelta（C8）

**pi 行为**（`api/anthropic-messages.ts:773-779`）：
```ts
} else if (event.delta.type === "signature_delta") {
    const index = blocks.findIndex((b) => b.index === event.index);
    const block = blocks[index];
    if (block && block.type === "thinking") {
        block.thinkingSignature = block.thinkingSignature || "";
        block.thinkingSignature += event.delta.signature;   // append；不 push 任何事件
    }
}
```

**java 现状**：`StreamPartialBuilder.emitThinkingSignature`（:292-298）append 后**返回**
`ThinkingDelta(idx, "", snapshot())`，唯一生产调用方 `AnthropicMessagesApi.java:365` submit 了它
⇒ 每个 thinking 块宿主多收一条空 `thinking_delta`。

**改法**：
1. `StreamPartialBuilder` 新增 `public void appendThinkingSignature(String signature)`：
   `thinkingSigBuf.append(signature == null ? "" : signature)`（append 语义，对位 `||""`＋`+=`），
   `thinkingBlockIndex >= 0` 时用三参构造重写当前块；index<0 no-op（对位 pi findIndex 落空）。
   **不替换**复用 `applyThinkingSignature`（那是 setLength(0) 的替换语义，多段 delta 会丢前缀）。
2. 删除 `emitThinkingSignature`（连同唯一调用点）；`AnthropicMessagesApi.java:359-367` 分支改调
   void 方法后 `return null`（mapEvent 链 :368 本就有 null 先例 ⇒ 不 submit）。
3. 注释保留「undefined 不拼成字面量」的刻意偏差说明。

**测试**：
- `StreamPartialBuilderTest.java:103-117`：两次 append 调新方法；断言块签名 `sig_abc`、**无事件返回**（void）。
- `AnthropicMessagesApiThinkingSignatureTest`：两条 signature 用例改为断言「该事件 mapEvent 产出 null」
  ＋流未终止，再喂后续事件从下一帧 partial 断言签名落块（`sig-456` 用例从 ThinkingEnd.partial 取）。
- conformance 录制无 signature 剧本（已核 `conformance/scripts`），无需重做。

规模 main ~10 行、test ~40 行。commit：`fix(ai): signature_delta updates block without emitting empty thinking_delta`。

---

## ② #101 Anthropic strict 工具：关键字拒绝谓词

**pi 行为**：
- 谓词 `isAnthropicStrictUnsupportedKeyword`（`api/anthropic-messages.ts:1569-1575` 区域；以实施时
  git show 行号为准）：拒绝关键字集与 format 白名单逐字搬入；`minimum/maximum/exclusiveMinimum/
  exclusiveMaximum/multipleOf/maxItems/uniqueItems/minContains/maxContains/minProperties/maxProperties`；
  `minItems` 仅值 `0`/`1` 放行；`format` 仅白名单（`date-time,time,date,duration,email,hostname,uri,
  ipv4,ipv6,uuid`）。
- 两遍转换（`api/constrained-sampling.ts`）：探测遍带谓词——prefer 模式命中不支持关键字 ⇒ 回落**非 strict**；
  require 模式 ⇒ throw；strict=true 的实际发送遍**不带**谓词再转一遍。六车道里**只有 anthropic 带谓词**。

**java 现状**：`StrictSampling.resolveStrict(tool, supportsStrictMode)` 两参（api 包）；
`StrictJsonSchema.convert` 无谓词重载；`AnthropicToolWire.java:108-123` 用通用子集转换；
默认值已对齐（responses false / azure true，`CompatResolver.java:272-286`）；
mistral 恒 true 不带谓词（保持）。

**改法**：
1. 新增 `pi-java-ai/.../api/UnsupportedStrictKeyword.java`：`@FunctionalInterface boolean test(String key, Object value)`。
2. 新增 `.../protocol/AnthropicStrictKeywords.java`（包私有 final 工具类）：`static final UnsupportedStrictKeyword PREDICATE`，
   11 关键字 Set＋10 format 白名单＋minItems 数值判定（Number 比 doubleValue，非 Number 同拒）。
3. `StrictJsonSchema.convert` 加 `(schema, UnsupportedStrictKeyword)` 重载：null 允许；递归节点在现有
   UNSUPPORTED_KEYS 处理之外逐 entry 调谓词，命中 throw（文案对位 pi `` `${key}: ${JSON.stringify(value)}
   is unsupported``，Jackson 序列化值、失败回落 String.valueOf）；一参版委托 null。
4. `StrictSampling.resolveStrict` 加三参重载；探测遍 convert(schema, predicate)，prefer 命中 ⇒ null 回落，
   require 命中 ⇒ IllegalStateException（保留现有 `Tool "t" requires...` 包装文案）。
5. `AnthropicToolWire.toDefinitionJson`：探测走三参版传 `AnthropicStrictKeywords.PREDICATE`；
   实际 strict 转换仍调一参 `convert`（对位 pi 第二遍）。CompletionToolWire/Google/Mistral/Response
   四处两参调用**不动**。

**测试**：新增 `protocol/AnthropicStrictKeywordsTest`（关键字矩阵、minItems 0/1/2/字符串、format 白/黑名单）；
扩 `api/StrictSamplingTest`（prefer 回落/require throw 文案/不支持 strict 时既有行为）；
扩 `protocol/ConstrainedSamplingWireTest`（Anthropic prefer ⇒ 请求无 strict 键且 schema 原样；require ⇒ build 期抛）；
扩 `api/StrictJsonSchemaTest`（嵌套属性/items/anyOf 里命中也扫到）。规模 main ~110、test ~160。

commit：`feat(ai): anthropic strict-tool unsupported-keyword predicate with prefer fallback`。

---

## ③ B222 Responses 车道推理模型前导消息发 `developer`

**pi 行为**（`api/openai-responses-shared.ts:214-215`，前导落 :223-227；中途系统更新同式）：
```ts
const instructionRole = model.reasoning && compat?.supportsDeveloperRole !== false ? "developer" : "system";
```
缺省 **true**（`!== false`）。注意 completions 车道是相反缺省（显式 true 才行，
`openai-completions.ts:1233`，java `OpenAICompletionsMessageConverter.java:96-98` 已对齐，别抄反）。

**java 现状**：`ResponsesMessageConverter.java:234` 与 `:262` 恒写
`EasyInputMessage.Role.SYSTEM`；`buildParams`（:112-195）已有 `compat` 形参与 `request.model()`；
模型 reasoning 位已有取法（:159 用 `ModelCapability.THINKING`）；SDK 常量
`EasyInputMessage.Role.DEVELOPER` 已验证存在。

**改法**：buildParams 算一次
`boolean dev = request.model()!=null && capabilities.contains(THINKING) && !Boolean.FALSE.equals(compat.supportsDeveloperRole())`；
`convertMessages` 加形参，两处 Role 三元化；抽 3 行 private static 压行数（文件 593 行已超限，
新逻辑放小 helper 不堆主类）。`additional_tools` 的 `role:"developer"` 是 pi `:188` 硬编码，不动。

**测试**：新增 `ResponsesDeveloperRoleWireTest`（照 ResponsesPromptCacheWireTest 的真请求体夹具）：
THINKING＋compat 缺省⇒developer；THINKING＋false⇒system；非 THINKING（compat true 也）⇒system；
中途系统更新同角色。更新 ResponsesToolChangesWireTest:41-45 的 javadoc（缺口已闭，夹具刻意 system）。
规模 main ~35、test ~150。commit：`fix(ai): responses lane sends developer role for reasoning models`。

---

## ④ B125 per-request samplingParams（extra 通道）

**pi 行为**（`api/simple-options.ts:25-33`）：
`resolveSamplingParams(model, level, requestParams)` ⇒ `{...model.samplingParams,
...samplingParamsByThinkingLevel[level], ...requestParams}`，后者覆盖前者；types.ts 注明
**仅 OpenAI 兼容三车道（completions/responses/azure responses）消费**；requestParams 来自
SimpleStreamOptions.samplingParams（≙ java `ApiOptions.extra["samplingParams"]`）。
本批只做 model←request 两层；thinking-level 层随批 3（#91）。

**java 现状**：`SamplingParamsWriter.java:39-49,72` 只读 `SimpleOptions.samplingParamsOf(model)`；
extra 通道先例：`AbstractChatApi.java:288-294` 读 extra["headers"]。

**改法**：
1. `SimpleOptions` 加 `requestSamplingParams(ApiOptions)`：`extra.get("samplingParams")` 为 Map 时
   LinkedHashMap 拷贝，否则空 map（非 Map 静默忽略）。
2. `SamplingParamsWriter` 两方法加第三参：起始 map＝模型值副本后 putAll 请求值（请求同名键压模型；
   现有「先 remove 类型化键再 setter」逻辑不动，因为合并在其之前）；两源皆空 no-op。
3. `ResponsesOptions` record 加 `Map<String,Object> samplingParams` 组件（不可变拷贝），from() 填充；
   `ResponsesMessageConverter.buildParams` 透传到 :191；openai-responses/azure 两车道共用零改。
4. completions：`OpenAICompletionsApi` 仿 sessionId（:108-113/:126）构造器读 extra 存 final 字段，
   :193 传入 converter（直接改签名，生产调用点 1 个）；anthropic/google/mistral 不接。

**测试**：扩 `SamplingParamsWireTest`（请求覆盖模型同名键/独有键透传/空与非 Map 无变化/
reasoning_effort 仍一份；azure 入口一条）。规模 main ~80、test ~150。
commit：`feat(ai): per-request samplingParams override via ApiOptions.extra`。

---

## ⑤ B118 Responses 流终局：未完成工具块守卫

**pi 行为**（`api/openai-responses-shared.ts:764-777`）：循环外，若
`output.stopReason === "toolUse"` 且仍存在挂着 `partialJson !== undefined || customInput !== undefined`
的工具块（即 output_item.done 永不到达），throw：
`` `OpenAI Responses stream completed with an unfinished tool call: ${toolCall.name} (${toolCall.id})` ``
落 error 终局。选取按 `for (const block of output.content)` 块序第一个。

**java 现状**：`ResponsesStreamProcessor.java:182-199` 循环后只查 sawTerminal/pending/error；
`ResponseItemHandlers.done` 完成即从 `ctx.toolCalls`/`ctx.customCalls` 移除（:145,:157）⇒
**两张 map 的残留项就是未完成槽**；残留 id 为复合 id（`call_id|item.id`，与 pi 同形）；
name/真实 callId 需按块序从 `builder.snapshot().content()` 的 ToolUseContent 反查。

**改法**：ResponseItemHandlers 加包级 `record UnfinishedCall(String name, String id)`＋静态
`unfinishedToolCall(ctx)`：块序遍历 snapshot 的 ToolUseContent，块 id 命中两 map 残留复合 id
集合即返回第一个；ResponsesStreamProcessor 在三检之后、emitDone 之前：
`if ("toolUse".equals(stop.reason())) { var u = unfinishedToolCall(ctx); if (u!=null) throw new
IllegalStateException("OpenAI Responses stream completed with an unfinished tool call: "
+ u.name()+" ("+u.id()+")"); }`——throw 落入 :200 既有 catch 成唯一 StreamError（与
「ended before a terminal response event」同路径）。新逻辑放 helper 给处理器文件减行数压力。

**测试**：新增 `ResponsesUnfinishedToolCallTest`（照 ResponsesFunctionCallMissingArgumentsTest 的
RecordingHttpServer SSE 夹具）：function_call args delta 无 done⇒恰好一个 StreamError＋逐字文案＋无 done；
带 done 对照⇒正常 done(toolUse)；custom_tool_call 一支；终局 stop 无工具块不触发。
规模 main ~30、test ~150。commit：`fix(ai): guard against unfinished tool calls at responses stream end`。

---

## ⑥ B119＋B121 StopReason 闭集枚举（大件，5 commit）

**pi 行为**：`packages/ai/src/types.ts:469`（台账旧记 566 是字段行）七值闭集
`"pending"|"stop"|"length"|"toolUse"|"error"|"aborted"|"deferred"`；车道生产端未知值 throw
（anthropic `mapStopReason` default：`` `Unhandled stop reason: ${reason}` ``，
`anthropic-messages.ts:1636-1638`）；**TS 类型运行时擦除，解码侧零校验**——JSON.parse 后直接转型，
故 java 反序列化未知值必须容错（返 null 等价 pi 比较全不命中），只在车道生产端保持 throw。

**java 现状**：`AssistantMessage.java:56` 与 `Message.AssistantMessage:189` 裸 String；
各车道写字面量（GoogleGenerativeAiApi:274、MistralConversationsApi:297…）；
`StreamEvent.isSettled`（:74-76）只排 pending/null；agent-core
`session/ContextEntries.java:51` NON_PROJECTED_STOP_REASONS contains；
JSONL 有 B109 垫片 `JsonlCodec.readStopReason`（旧 `"tool_use"` 归一，StopReasonMigrationTest 守护）；
**注意命名**：`com.anthropic.models.messages.StopReason` 是官方 SDK wire 枚举（AnthropicMessagesApi:13 已用），
新枚举放 `com.pijava.ai.message.StopReason`，import 全限定区分。

**新类**（`message/StopReason.java`）：enum 七值 `PENDING/STOP/LENGTH/TOOL_USE/ERROR/ABORTED/DEFERRED`，
组件绑 wire 字面（camelCase：`toolUse` 等），`@JsonValue wireName()`（先例 `ImageStopReason`）；
`fromWireOrNull(String)` 未知/空返 null（不映射 tool_use——B109 垫片留 JsonlCodec）；
`@JsonCreator fromJson` 额外把遗留 `"tool_use"` 映射 TOOL_USE（Jackson 往返路径不经 JsonlCodec）；
`isSettled(r) = r!=null && r!=PENDING`。

**触点（实施前全量 grep 复核，下列为批 1 取证清点）**：
- ai 生产：两消息 record（组件/wither/兼容 ctor）、StreamEvent（PENDING 常量、StreamDone/StreamError
  reason、settle 工厂、isSettled）、StreamPartialBuilder（字段、emitDone/emitError/forceStopReason）、
  五车道映射（AnthropicMessagesApi MappedStopReason/StopState、GoogleGenerativeAiApi、
  MistralConversationsApi、OpenAICompletionsApi、ResponsesStreamProcessor＋ResponseItemHandlers）；
  消费：TransformMessages、OrphanToolResults、ContextOverflow、Estimate、RetryableError、FauxProvider。
  **增量发现**：`GoogleGenerativeAiApi.java:317-327` 错误枚举比 pi `google-shared.ts:455`
  少 `TOO_MANY_TOOL_CALLS`（pi 16 值 java 15），枚举化时补这一行。
- agent-core：ContextEntries（EnumSet）、SessionJson、jsonl/{JsonlCodec,MessageJsonCodec,EntryJsonCodec}、
  Entry.Usage、harness 十余个（PiLoopRunner.markAborted、PiLaneSink OTel 边界、HarnessUtils、
  PostRunCompactionCheck、CompactionExecutor、LaneState、RunFailure、RunSpanFactory、ActiveRun、
  RecoveryOmissions、ContextUsageEstimator、LlmSummaryGenerator）。
- 下游：web WebWireJson、coding-agent PayloadRecordingStreamFn/SessionRunner/RunSummaryAggregator
  （Summary/exitCode 词表含 "completed"，保持 String，边界 wireName）。
- 测试约 35 文件；conformance 工具 ScriptedStreams/FrameNormalizer。

**分 commit（桥接重载过渡，每步可独立编译）**：
1. `feat(ai): add StopReason closed vocabulary enum`（新类＋单测，不接入）。
2. 五车道映射内部持枚举，builder/event 加枚举入参，**记录组件仍 String**、边界 wireName；补 Google 缺值。
3. 两消息 record＋StreamEvent＋builder 组件翻枚举；保留一轮 deprecated String 重载（内部 fromWireOrNull
   委托）让下游与全部测试当刻仍编译；ai 内生产全切。
4. agent-core/web/coding-agent 消费侧切枚举（Entry.Usage 同翻；OTel/手写字节 JSON 边界 wireName；
   B109 垫片留 JsonlCodec 单一口）。
5. 测试全迁枚举、ScriptedStreams/FrameNormalizer 适配、删 String 桥；StopReason 未知值往返夹具。

风险：存档兼容（旧 JSONL null/未知/`tool_use` 三态夹具必带）；手写字节 JSON 三处必须显式 wireName；
比较一律 `==` 并防 null。规模 main ~1300、test ~1500-1900（含测试迁移）。

---

## 批 1 验证口径

1. 每 commit 后 `JAVA_HOME=D:/soft/jdk/graalvm-jdk-25 mvn -pl pi-java-ai -am test`；
   ⑥ 步 4 后带跑 agent-core/coding-agent/web，批末全量 `mvn clean verify`。
2. 错误文案逐字照 pi；不新增终局机制（⑤ throw 走既有 catch）。
3. 闭环：map/02 #23/#101 存疑→对齐、B222/B125/B118/B119/B121 缺失→对齐并重算加权；
   docs/04 销号、docs/06 同步、docs/05 总表更新；B127/B147 夹具随本批补并销 B127（B147 留 §4）。
4. 文件 ≤500 行（StreamPartialBuilder 531、ResponsesMessageConverter 593 已超，新逻辑只进新 helper）；
   commit `fix(ai)`/`feat(ai)`，显式 add，trailer Co-Authored-By: Claude Code。
