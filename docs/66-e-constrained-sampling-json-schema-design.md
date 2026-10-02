# 66 — E：constrained sampling（json_schema strict）对齐

**状态：✅ 已裁决并闭环（2026-10-02，`5606ec9` 设计 ＋ `20737c1` 实现，§12 记录）。**
**创建基准：** pi-java `e8ca465`（D-P1 收尾）
**pi 锚点：** `3390bd936`（取证一律 `git show 3390bd936:<path>`）

> 范围＝pi 的工具侧 **constrained sampling** 中 **json_schema strict** 这一形态：
> `read`/`bash`/`edit`/`write` 默认携带 `{type:"json_schema", strict:"prefer"}`，
> 五条现有车道（completions／responses／anthropic／google／mistral）把工具参数
> 转成 **strict JSON Schema** 并下发各自的 strict 标志。
> **grammar（lark/regex）自定义工具拆到下一包**（R1），本包不做。

---

## 1. pi 事实清单（file:line 已核）

### 1.1 内置工具默认 strict-prefer（主流真触发）

```ts
// coding-agent/src/core/tools/read.ts:77  bash.ts:243  edit.ts:156  write.ts:57
constrainedSampling: { type: "json_schema", strict: "prefer" },
```
`coding-agent/CHANGELOG.md:69`：*Enabled strict-prefer JSON-sampling by default for
built-in read, bash, powershell, edit, and write tools*。扩展可用
`constrainedSampling: false` 重注册关闭（README:501）。

### 1.2 配置类型（`ai/src/types.ts:590-604`）

```ts
export type ConstrainedSamplingConfig =
    | { type: "json_schema"; strict: "prefer" | "require" }
    | { type: "grammar"; variants: GrammarVariants };
export interface Tool {
    name: string; description: string; parameters: TSchema;
    constrainedSampling?: false | ConstrainedSamplingConfig;
}
```

### 1.3 strict schema 转换（`ai/src/api/constrained-sampling.ts:117-127`）

```ts
export function makeStrictJsonSchema(schema: Tool["parameters"]): Record<string, unknown> {
    const cloned = structuredClone(schema);
    makeJsonSchemaNodeStrict(cloned);              // 原地改
    if (cloned.type !== "object") throw new UnsupportedStrictJsonSchemaError("root … object");
    return cloned;
}
```
`makeJsonSchemaNodeStrict`（`:53-114`）逐条：
1. **禁用键**（`:12-29`）：`$ref/$defs/definitions/allOf/oneOf/patternProperties/
   dependentSchemas/dependencies/unevaluatedProperties/propertyNames/contains/prefixItems/
   not/if/then/else` 在场即抛 `UnsupportedStrictJsonSchemaError`；
2. `anyOf`：对象/数组联合抛（`:68`），其余递归；`items` 数组（tuple）抛（`:76`）；
3. object 节点：`additionalProperties` 为 schema/true 抛（`:87`）；
   非 required 且 schema 不允许 null 的属性 ⇒ `{anyOf:[property,{type:"null"}]}`（`:108`）；
   末尾 `required = 全部属性名`、`additionalProperties = false`（`:112-113`）。

### 1.4 每工具的 strict 解析（`constrained-sampling.ts:208-228`）

```ts
export function resolveJsonSchemaStrictSampling(tool, supportsStrictMode): boolean | undefined {
    const config = tool.constrainedSampling;
    if (!config || config.type !== "json_schema") return undefined;
    if (supportsStrictMode) {
        try { makeStrictJsonSchema(tool.parameters); return true; }
        catch (error) {
            if (!(error instanceof UnsupportedStrictJsonSchemaError)) throw error;
            if (config.strict !== "require") return undefined;
            throw new Error(`Tool "${tool.name}" requires … but ${error.message}.`);
        }
    }
    if (config.strict === "require")
        throw new Error(`Tool "${tool.name}" requires … but strict tools are unsupported.`);
    return undefined;
}
```
⇒ **require 在「不支持」或「schema 不可转换」时响亮失败**；prefer 回落 undefined。

### 1.5 五车道各自的门与落点

| 车道 | 支持门（缺省） | 落点 |
|---|---|---|
| completions | `compat.supportsStrictMode`：**探测**缺省 `!isMoonshot && !isTogether && !isCloudflareAiGateway && !isNvidia && !isCerebras`（`:1664`），model.compat 显式值赢（`:1711`） | `{type:"function", function:{…, parameters: strict?转换:原, strict: strict??false}}`，门为 false 时**整个 strict 键不发**（`:1495-1503`） |
| openai-responses | `model.compat?.supportsStrictMode ?? false`（`openai-responses.ts:74`） | function tool：`strict` 键仅在支持时发（`openai-responses-shared.ts:380-393`） |
| azure-responses | `?? true`（`azure-openai-responses.ts:296`） | 同上 |
| anthropic | **字段名不同**：`model.compat?.supportsStrictTools ?? false`（`:216`） | tool 上 `strict: true`；input_schema＝strict 转换后的 schema（`:1467-1488`） |
| google | **id 谓词**：`getGeminiMajorVersion(id) >= 3`（`google-shared.ts:404-407`，正则 `^gemini(?:-live)?-(\d+)`） | 参数走 `parametersJsonSchema` 转换；任一工具 strict ⇒ function calling mode **VALIDATED**（`:423-436`） |
| mistral | **恒 true**（`mistral-conversations.ts:754`） | `function.strict: strict??false`；parameters strict 转换（`:760-761`） |

### 1.6 生成目录的无条件标注（`scripts/generate-models.ts:820-829`）

```ts
if ((model.provider === "openai" || model.provider === "cloudflare-ai-gateway")
    && model.api === "openai-responses")
    model.compat = { …, supportsStrictMode: true };
else if (model.provider === "anthropic" && model.api === "anthropic-messages")
    mergeAnthropicMessagesCompat(model, { supportsStrictTools: true });
```
⇒ pi 的**全部** openai-responses 模型显式 `supportsStrictMode:true`、
全部 anthropic-messages 模型显式 `supportsStrictTools:true` —— 与 id 无关。

### 1.7 持久化携带该字段（`ai/src/utils/transcript.ts:122-130`）

```ts
export function toToolDeclaration(tool: Tool): Tool {
    return { name, description: tool.description, parameters: clone(tool.parameters),
        ...(tool.constrainedSampling === undefined ? {} : { constrainedSampling: tool.constrainedSampling }) };
}
```

---

## 2. pi-java 现状差

| # | 点 | 现状 |
|---|---|---|
| 2.1 | **工具模型无 constrainedSampling** | `ToolDefinition` 7 组件（`api/ToolDefinition.java`）；`ToolDeclaration` 3 组件（`api/ToolDeclaration.java:26`） |
| 2.2 | **无 strict schema 转换器** | 全树无 `makeStrictJsonSchema` 对应物 |
| 2.3 | **五车道发原始 schema、无 strict** | completions `OpenAICompletionsMessageConverter.toTool:346`；responses `ResponsesMessageConverter.responseTool:333`（strict 键 B88 已接线但 schema 未转换）；anthropic `AnthropicRequestBuilder.toAnthropicTool:213`；google `GoogleMessageConverter.functions:342`（无 toolConfig/mode）；mistral `MistralConversationsApi:475-490`（无 strict 字段） |
| 2.4 | **completions 探测缺 strict 门** | `CompatResolver.forCompletions` 的 strictMode 位传 `null`（`CompatResolver.java:125`）⇒ 恒走「未显式」 |
| 2.5 | **目录无 strict 标注** | `CatalogCompatRules.anthropic:44` 不设 supportsStrictTools；`BuiltinCatalog.openaiModels:55` 用 NONE |
| 2.6 | **内置工具无生产者** | `AgentTool` 无该方法；`ToolRegistry.definitionsOf:131` 不投影 |
| 2.7 | **重放/落线丢字段** | `Transcripts.toToolDefinition:82`、`MessageJsonCodec.decodeToolsAdded:141` 只读三键 |

**用户可观察后果**：默认工具集在 OpenAI/Anthropic 等车道**不进入** provider 的
strict function/tool 校验路径，工具参数可靠性与 pi 不同；`require` 工具无响亮失败。

---

## 3. 改动方案

### Step 1 — 类型（`pi-java-ai`，`com.pijava.ai.api`）

**R3 建议：sealed ADT，与 pi 的 union 同形；grammar 下包加第二 permit。**

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = JsonSchemaSampling.class, name = "json_schema"))
public sealed interface ConstrainedSampling permits JsonSchemaSampling {}

public record JsonSchemaSampling(StrictMode strict) implements ConstrainedSampling {}

public enum StrictMode {
    PREFER("prefer"), REQUIRE("require");
    private final String wire;
    StrictMode(String wire) { this.wire = wire; }
    @JsonValue public String wire() { return wire; }
}
```
Jackson 序列化逐字＝ `{"type":"json_schema","strict":"prefer"}`。

- `ToolDefinition` 加第 8 组件 `ConstrainedSampling constrainedSampling`（可空；
  compact 不做归一 —— null≡absent≡false）。3 参便捷构造保留（补 null）。
- `ToolDeclaration` 加第 4 组件 `ConstrainedSampling constrainedSampling`（可空；
  SessionJson MAPPER 全局 NON_NULL，null 自动不出键）。

### Step 2 — strict schema 转换器（新文件 `api/StrictJsonSchema.java`）

逐条移植 §1.3：
- 入参 `Map<String,Object>`；先深拷贝（Jackson `objectMapper.convertValue` 或
  `valueToTree`→`treeToValue`），全程在拷贝上改；
- `UnsupportedStrictJsonSchemaException`（受检或非受检？pi 是 Error 子类 ⇒
  **非受检**，在 resolver 层按类型捕获）；
- 禁用键 18 个、anyOf/items 递归、object 节点 required 全量＋null anyOf 包装＋
  `additionalProperties:false`；root 必须 object。
预计 ~180 行。

### Step 3 — 解析器（同文件或 `api/StrictSampling.java`）

```java
/** @return strict=true 进 strict；null ＝ 不进（absent/prefer 回落）。
 *  require 且不支持/不可转换 ⇒ IllegalStateException（文案照 pi §1.4）。 */
static Boolean resolveStrict(ToolDefinition tool, boolean supportsStrictMode)
```
`Boolean` 三态（只有 true 与 null 两值实际出现，与 pi `true|undefined` 同）。

### Step 4 — 五车道接线（观测面均为 RecordingHttpServer）

1. **completions**：`toTool(td, supportsStrict)`；`resolveStrict`；
   strict==true 时 parameters 走 `StrictJsonSchema.convert`；
   门 true ⇒ `FunctionDefinition.strict(Boolean.TRUE.equals(s)?true:false)`；
   门 false ⇒ 不调用 setter（整个键不发，与现有 B88 形状一致）。
   `kimiToolSystemMessage` 同路（它也调 toTool）。
2. **responses/azure**：`responseTool(td, supportsStrictMode,…)`：strict==true 时
   parameters 走转换；`strictField` 行为不变（B88）。
3. **anthropic**：`toAnthropicTool(td,…, supportsStrictTools)`：strict==true ⇒
   inputSchema 从转换后的 map 建 ＋ `toolBuilder.strict(true)`；不支持不调 setter。
4. **google**：`functions(tools, supportsStrict)` 按工具转参数；buildConfig：
   任一工具 strict（且 toolChoice 非 none/any）⇒ `toolConfig.functionCallingConfig.mode
   =VALIDATED`（`com.google.genai.types`，SDK 1.72 已核存在）；谓词
   `gemini major>=3` 放 `GoogleMessageConverter`（逐字正则）。
5. **mistral**：工具转换点 parameters strict 转换 ＋ `function.put("strict", …false)`。

### Step 5 — compat 解析与目录标注

- `ModelCompat` 加第 29 组件 **`boolean supportsStrictTools`**（二态、缺席≡false，
  primitive 与 allowEmptySignature 同形）；旧 28 参规范构造降级便捷（补 false）。
- `CompatResolver.forAnthropic`：supportsStrictTools 缺省 **false**（解析层恒显式，
  显式值来自目录/models.json）。
- `CompatResolver.forCompletions`：计算 §1.5 的五否定检测值，喂 strictMode 位
  （现在传 null 的那一处）；models 的显式值继续 `pick` 赢。
- `CatalogCompatRules.anthropic`：provider=="anthropic" 时 **supportsStrictTools=true**
  （§1.6，无条件、与 id 无关）。
- 新方法 `CatalogCompatRules.openaiResponses(provider,id)`：provider=="openai" ⇒
  compat 带 `supportsStrictMode=TRUE`，否则 NONE；`BuiltinCatalog.openaiModels` 三个
  chat 模型改用它（embedding 不动）。api 门（responses）由调用方承担。

### Step 6 — agent-core：生产者与落线

- `AgentTool` 加 `default ConstrainedSampling constrainedSampling() { return null; }`。
- 内置 **Read/Bash/Edit/Write** 返回 `new JsonSchemaSampling(StrictMode.PREFER)`；
  Glob/Grep/Ls 保持 null。
- `ToolRegistry.definitionsOf:133` 投影第 8 组件。
- `Transcripts.toToolDefinition:83`／`toToolDeclaration:66` 双向携带。
- `MessageJsonCodec.decodeToolsAdded`：解析 `constrainedSampling` 节点（按 type/strict
  两键），传入 ToolDefinition；旧会话无该键照常读。
- R5：扩展以 `constrainedSampling:false`（null）重注册的工具 ⇒ 投影自然为 null，
  零额外代码（补夹具）。

### Step 7 — 拆分（守住 ≤500）

- `MistralConversationsApi` 521 行已超限：工具转换抽到新 `protocol/MistralTools.java`。
- `OpenAICompletionsMessageConverter` 592：工具转换抽到新
  `protocol/CompletionToolWire.java`（toTool＋kimi 共用）。
- `ResponsesMessageConverter` 599：responseTool 抽到新 `protocol/ResponseToolWire.java`。
- 目标：被抽文件行数下降，新文件 ≤200。

**不做**：grammar/lark/regex（custom 工具、custom_tool_call 接收与流式 JSON 重建）
——下一包；`cloudflare-ai-gateway` 的目录标注（无此内置 provider）；任何新 provider。

---

## 4. 待裁决点（实施前）

- **R1 拆包**：本包只做 json_schema strict，grammar 自定义工具另立一包。
  建议接受（两者形状与接收侧完全不同；本包是默认工具触发、grammar 仅扩展触发）。
- **R2 目录标注**：照 §1.6 给全部内置 anthropic 模型标 supportsStrictTools、
  全部内置 openai chat 模型标 supportsStrictMode（谓词法不是硬编值）。建议接受。
- **R3 类型形状**：sealed `ConstrainedSampling`（单 permit 起步）＋enum StrictMode
  ＋Jackson type/strict 两键。备选：只造一个 record。建议 sealed（grammar 落地时
  类型上闭合，不产生第二真相）。
- **R4 异常/文案**：require 失败文案照 pi 逐字（含工具名与原因），非受检异常。
- **R5 扩展关闭**：`constrainedSampling:false` 经重注册生效，零生产代码、补夹具。
  建议接受。
- **R6 拆分**：Step 7 的三处抽工具类。建议接受（三个被抽文件本就超限）。

---

## 5. 测试计划（RED 先）

观测面统一 `RecordingHttpServer`（路径＋头＋体），按键取值（SDK 键序与 pi 不同）。

1. **StrictJsonSchema**：移植 pi `constrained-sampling.test.ts` 要点 —— 禁用键逐个、
   anyOf null 包装、嵌套 items、root 非 object、prefer/require 区别。
2. **resolver 单测**：absent／prefer 可转换／prefer 不可转换回落／require 不可转换
   响亮抛／不支持＋require 响亮抛／不支持＋prefer 回落。
3. **五车道 wire**（每车道 ≥1 正 1 门控对照）：
   - completions：strict 工具 `function.strict=true`、parameters 含
     `additionalProperties:false`＋required 全量；moonshot baseUrl ⇒ **无 strict 键**；
   - responses：gpt-5 strict 键 true＋参数转换；require＋不可转换 schema ⇒ 建参即抛；
   - anthropic：tool.strict true、input_schema 已转换；
   - google：`gemini-3-pro` id ⇒ toolConfig VALIDATED＋参数转换；`gemini-2.5-pro`
     ⇒ **无 toolConfig**（对照组）；
   - mistral：function.strict true、参数转换。
4. **投影/落线**：ToolDefinition↔ToolDeclaration 携带；JSON 形状逐字
   `{"type":"json_schema","strict":"prefer"}`；SessionJson 写/读 round-trip；
   旧 toolsAdded（无该键）读回不报错。
5. **agent-core**：Read/Bash/Edit/Write 定义与本体都带 prefer（对照
   pi `builtin-tool-strict-mode.test.ts`）；扩展以 false 重注册 ⇒ 定义为 null。
6. **coding-agent**：默认工具集打桩服务器 ⇒ openai 车道请求工具带 strict（1 条接线用例）。

**Mutation（预期红集）**：
- M1 某车道跳过 schema 转换（发原始 schema）⇒ 该车道 wire 红；
- M2 completions 门恒真（忽略检测）⇒ moonshot 对照红；
- M3 去掉 require 抛错 ⇒ resolver 红；
- M4 去目录标注（anthropic/openai）⇒ 对应车道 wire 红；
- M5 去 agent-core 投影 ⇒ builtin 用例红。

**回归**：`pi-java-ai -am test`、`agent-core -am test`、`coding-agent -am test`；
checkstyle 0、`git diff --check`、无 `System.out`、文件 ≤500；全 reactor 视需要。

---

## 6. 风险

- 纯函数（转换/解析）＋车道接线，不碰凭据；风险集中在五车道形状，wire 夹具全覆盖。
- google/mistral 两车道对**内置目录 id**（gemini-2.5／mistral）实际触发：
  google 谓词对 2.5 为 false（对照组即证明），mistral 恒 true ⇒ mistral 默认工具
  请求形状变化最大，按 pi 对齐并留 wire 证据。
- 拆分属移动不改行为，由同包测试护航。

---

## 12. 实施记录

**2026-10-02 闭环**：用户裁决「按照建议实施」⇒ R1–R6 全按建议。
提交：`4cbfb49`（类型＋转换器＋解析器）、`20737c1`（五车道接线＋目录＋生产者＋拆分）。

- **RED**：五车道 wire 夹具首跑 **6 红 2 对照绿**（completions/responses/azure/anthropic/google/mistral 正向；moonshot 与 gemini-2.5 对照）；agent-core 生产者夹具撤 ReadTool 覆盖验证恰 1 红后恢复。
- **Mutation（实测，均 grep 复核、逐条复原）**：
  - M1 completions 跳过 schema 转换 ⇒ 恰 1 红（completions 正向）；
  - M2 completions 门恒真 ⇒ 恰 1 红（moonshot 对照）；
  - M3 去 require 抛错 ⇒ 恰 1 红（requireUnconvertible）；
  - M4 去目录 supportsStrictTools 标注 ⇒ 2 红（CatalogCompatRules ＋ anthropic wire）；
  - M5 去 agent-core 生产者（ReadTool）⇒ 1 红。
- **实施期纠正（两处，比设计当时认知更重要）**：
  1. responses function tool 的 gate false 必须**显式 `JsonMissing.of()`**：跳过 setter 会被 `checkRequired("strict")` 判未设置（请求空、`` `strict` is required ``）—— B88 的同一约束在抽方法时复现；
  2. PiMessagesApi 默认 mapper 是 ALWAYS 包含，ToolDeclaration 直接 `valueToTree` 会写出 `constrainedSampling:null`；改逐项写、仅在场时发键（与 SessionJson NON_NULL 字节一致）。
- **回归**：pi-java-ai **1259/1259**、agent-core **528/528**、coding-agent **290/290**（均 -am）；checkstyle 0、`git diff --check` clean、无新增 `System.out`、文件 ≤500。
- **拆分（R6）**：新增 CompletionToolWire／CompletionImageWire／ResponseToolWire／ResponseInputWire／MistralTools／MistralContent；三个超限文件（592/599/521）回落到 ≤496。
- **无新登记 B 项**；未跑全 reactor verify（tui/evals/web 无调用面）。

