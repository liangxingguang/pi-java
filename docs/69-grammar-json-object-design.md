# 69 — grammar 自定义工具 / JsonObject 对齐设计

> 对齐基准：pi 锚点 `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`
> 归属：docs/48 §5 行 E（A-19 拆分项）；前置 docs/66（json_schema strict 已闭环）。
> 编制日期：2026-10-02；实施日期：2026-10-03。
>
> **✅ 状态：已实施（步骤 1–5 全按设计；commit `9fe8c01`→`f05d772` ＋ `c7d0b7a`）。**
> 实施记录见 §12。

## 0. 范围与结论

docs/66 落了 `constrainedSampling` 的 json_schema 形态；本包落剩余两部分：

1. **grammar 自定义工具**：工具声明从 function 改走 OpenAI **custom tool**（lark/regex 语法），
   覆盖 **openai-completions / openai-responses** 两条现有车道；**生产者只有扩展**
   （内置工具无一使用 grammar）。
2. **JsonObject**：pi 侧是纯编译期 TS 类型约束（types.ts:421-422 及 423-460 的类型机器），
   无任何运行时读点 ⇒ Java 的 `Map<String,Object>` + Jackson 已是等价静态约束 ⇒ **零代码、
   文档说明结案**。

顺带补齐 B16 台账点名的「grammar 2 处」孤对代理净化（B83 已把这两处标为随本包落地）。

**排除（codex 独占 / 无 Java 对应车道）**：`transport` 选择、session-resources 实质生命周期；
openai-codex-responses 整条车道（Java 不携带）。tool-search 触发的 `defer_loading` 交互
（grammar 工具在 toolSearchResult 下带 `defer_loading:true`）随 tool-search 包，不在本包。

## 1. pi 事实（逐字证据）

### P1 — 配置形状（types.ts:579-604）

```typescript
/** OpenAI grammar variants for constrained sampling. */
export type GrammarFormat = "openai_lark" | "openai_regex";
export type GrammarVariants = Partial<Record<GrammarFormat, string>>;

export type ConstrainedSamplingConfig =
    | { type: "json_schema"; strict: "prefer" | "require" }
    | { type: "grammar"; variants: GrammarVariants };

export interface Tool<TParameters extends TSchema = TSchema> {
    name: string;
    description: string;
    parameters: TParameters;
    constrainedSampling?: false | ConstrainedSamplingConfig;
}
```

### P2 — 能力位：默认 false、生成目录标注、models.json 可覆盖

types.ts（OpenAICompletionsCompat 与 OpenAIResponsesCompat 各有同名字段）：

```typescript
/** Whether the provider supports OpenAI custom tools with Lark/regex grammar formats.
 * When false, grammar-constrained tools fall back to normal function tools. Default: false;
 * the generated model catalog enables it for capable models. */
supportsOpenAIGrammarTools?: boolean;
```

生成器 `scripts/generate-models.ts:831-854` —— **照抄谓词**（provider/api/id 三重门）：

```typescript
const OPENAI_GRAMMAR_TOOL_PROVIDERS = new Set([
    "openai", "openai-codex", "azure-openai-responses",
    "github-copilot", "opencode", "cloudflare-ai-gateway",
]);
const OPENAI_GRAMMAR_TOOL_APIS = new Set<Api>([
    "openai-responses", "azure-openai-responses", "openai-codex-responses",
]);

function applyOpenAIGrammarToolCompatMetadata(model: Model<Api>): void {
    if (!OPENAI_GRAMMAR_TOOL_APIS.has(model.api) || !OPENAI_GRAMMAR_TOOL_PROVIDERS.has(model.provider)) return;
    const match = /^gpt-(\d+)/.exec(model.id);
    if (!match || Number(match[1]) < 5) return;
    model.compat = { ...(model.compat as OpenAIResponsesCompat | undefined), supportsOpenAIGrammarTools: true };
}
```

⚠️ completions 车道在 pi **没有任何 provider 被生成器标 true**（grammar 集里没有
openai-completions api）—— 即 pi 主流上 grammar 只在 responses 三车道生效；completions
车道的 grammar 路径只有用户经 models.json 自行打开才可达。

models.json schema（`coding-agent/src/core/model-config.ts:104` 与 `:121`）两处 compat
都接受 `supportsOpenAIGrammarTools: Type.Optional(Type.Boolean())`。

### P3 — 解析：门 false 静默回落；有门但无可用变体则硬错

`packages/ai/src/api/constrained-sampling.ts:230-263`：

```typescript
export function resolveGrammarConstrainedSampling(
    tool: Tool,
    supportsOpenAIGrammarTools: boolean,
): GrammarConstrainedSampling | undefined {
    const config = tool.constrainedSampling;
    if (!config || config.type !== "grammar") return undefined;
    if (!supportsOpenAIGrammarTools) return undefined;

    const larkDefinition = config.variants.openai_lark;
    const regexDefinition = config.variants.openai_regex;
    const hasLarkDefinition = typeof larkDefinition === "string" && larkDefinition.trim().length > 0;
    const hasRegexDefinition = typeof regexDefinition === "string" && regexDefinition.trim().length > 0;
    if (!hasLarkDefinition && !hasRegexDefinition) {
        throw new Error(
            `Tool "${tool.name}" cannot use grammar constrained sampling: no supported grammar variant was provided.`,
        );
    }

    try {
        return {
            format: hasLarkDefinition ? "lark" : "regex",
            definition: hasLarkDefinition ? larkDefinition : regexDefinition!,
            inputProperty: inferGrammarInputProperty(tool),
        };
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        throw new Error(`Tool "${tool.name}" cannot use grammar constrained sampling: ${message}.`);
    }
}
```

**Lark 优先于 regex**（两变体都在时）。

### P4 — 入参属性推断：object 且恰一个必填 string 属性

`constrained-sampling.ts:189-206`：

```typescript
function inferGrammarInputProperty(tool: Tool): string {
    const schema = tool.parameters as JsonSchemaObject;
    if (schema.type !== "object") {
        throw new Error("grammar constrained sampling requires an object parameter schema");
    }
    if (!Array.isArray(schema.required) || schema.required.length !== 1 || typeof schema.required[0] !== "string") {
        throw new Error("grammar constrained sampling requires exactly one required string property");
    }

    const inputProperty = schema.required[0];
    if (!schema.properties?.[inputProperty]) {
        throw new Error(`grammar constrained sampling requires a properties entry for ${inputProperty}`);
    }
    if (schema.properties[inputProperty]?.type !== "string") {
        throw new Error(`grammar constrained sampling property ${inputProperty} must have type string`);
    }
    return inputProperty;
}
```

### P5 — 出站工具声明：两车道 wire 形状

**completions**（`openai-completions.ts:1472-1506` convertTools）：

```typescript
const grammar = resolveGrammarConstrainedSampling(tool, compat.supportsOpenAIGrammarTools);
if (grammar) {
    return {
        type: "custom",
        custom: {
            name: tool.name,
            description: tool.description,
            format: {
                type: "grammar",
                grammar: { syntax: grammar.format, definition: grammar.definition },
            },
        },
    };
}
```

**responses**（`openai-responses-shared.ts:359-380`）：

```typescript
const grammar = resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools);
if (grammar) {
    return {
        type: "custom",
        name: tool.name,
        description: tool.description,
        format: { type: "grammar", syntax: grammar.format, definition: grammar.definition },
        ...(options?.toolSearchResult ? { defer_loading: true } : {}),
    } satisfies OpenAITool;
}
```

### P6 — 入站流：custom 输入按串流到达，重组为 `{<property>:"…"}` JSON

缓冲状态机 `constrained-sampling.ts:139-187`（append-only、单调、闭合幂等）：

```typescript
export interface GrammarToolInputJsonBuffer { input: string; started: boolean; closed: boolean; }

export function appendGrammarToolInputJsonDelta(
    buffer: GrammarToolInputJsonBuffer,
    inputProperty: string,
    nextInput: string,
    close: boolean,
): string | undefined {
    if (buffer.closed) {
        if (close && nextInput === buffer.input) return undefined;
        throw new Error(`grammar tool input for property "${inputProperty}" changed after it was closed`);
    }
    if (!nextInput.startsWith(buffer.input)) {
        throw new Error(`grammar tool input for property "${inputProperty}" changed non-monotonically`);
    }

    const inputDelta = nextInput.slice(buffer.input.length);
    if (!close && inputDelta.length === 0) return undefined;

    let delta = "";
    if (!buffer.started) {
        delta += `{${JSON.stringify(inputProperty)}:"`;
        buffer.started = true;
    }
    delta += JSON.stringify(inputDelta).slice(1, -1);
    buffer.input = nextInput;

    if (close) {
        delta += '"}';
        buffer.closed = true;
    }
    return delta;
}
```

取串（缺串即错，逐字文案）`constrained-sampling.ts:145-155`：

```typescript
export function getGrammarToolInput(
    toolName: string,
    arguments_: Record<string, unknown>,
    inputProperty: string,
): string {
    const input = arguments_[inputProperty];
    if (typeof input !== "string") {
        throw new Error(`Grammar tool call "${toolName}" requires argument "${inputProperty}" to be a string.`);
    }
    return input;
}
```

**completions** 入站（`openai-completions.ts:502-511, 651-654`）：delta tool_call 带
`custom:{name?,input?}`；首块即建 `arguments={[property]:""}` 与 jsonBuffer；每帧拼
`getCustomToolCallInput(block) + toolCall.custom.input`；finishBlock（`:447-454`）以
`close=true` 收尾。unknown 名字的回落 property 是 `"input"`（`:505/:541`）。

**responses** 入站：`response.output_item.added/done` 的 item 为 `custom_tool_call`
（`openai-responses-shared.ts:504-522`）；增量事件
`response.custom_tool_call_input.delta`（`:670-677`）与 `.done`（`:678-681`）；终局在
output_item.done（`:726-740`）发 toolcall_end 并删 customInput 缓冲。

### P7 — 历史回放：两车道 custom 形状（且**这里**做净化）

**completions**（`openai-completions.ts:1351-1363`）：

```typescript
assistantMsg.tool_calls = toolCalls.map((tc): ChatCompletionMessageToolCall => {
    const customInputProperty = options?.grammarToolInputProperties?.get(tc.name);
    if (customInputProperty !== undefined) {
        return {
            id: tc.id,
            type: "custom",
            custom: {
                name: tc.name,
                input: sanitizeSurrogates(getGrammarToolInput(tc.name, tc.arguments, customInputProperty)),
            },
        };
    }
    /* …function 分支… */
});
```

**responses**（`openai-responses-shared.ts:289-322, 335-343`）：助手块落
`{type:"custom_tool_call", id, call_id, name, input: sanitizeSurrogates(...)}`；配对的
toolResult 落 `{type:"custom_tool_call_output", call_id, output}`（不是 function_call_output）。

### P8 — grammar 能力表在请求起点一次算出

`createGrammarToolInputProperties`（constrained-sampling.ts:265-277）：遍历 tools，
resolve 成功者入表 `toolName → inputProperty`；两车道在构建请求体时各调一次
（completions :338、responses openai-responses.ts:147），历史回放也读同一张表。

### P9 — 执行侧无特殊路径

pi 的 grammar 工具在执行层就是普通扩展工具：参数经 TypeBox schema 校验后注入 execute()，
无 custom 专用分支（`getGrammarToolInput` 全部读点都在 ai 层 wire/stream 内）。

### P10 — JsonObject 无运行时读点

`types.ts:421-460` 的 `JsonValue`/`JsonObject`/`IsJsonCompatible` 等全部是**编译期类型**；
全仓没有任何 `instanceof`/字符串分支消费它们。`ToolCall.arguments: JsonObject`（types.ts:390）
在运行时就是普通对象。

## 2. Java 现状

| 面 | 现状 | 缺口 |
|---|---|---|
| `ConstrainedSampling`（ai/api） | sealed，仅 permit `JsonSchemaSampling`；Jackson `@JsonTypeInfo(property="type")` | 无 grammar 变体 |
| `ModelCompat`（ai/catalog，**665 行已超 500**） | 28 组件洋葱 record | 无 `supportsOpenAIGrammarTools` |
| `CompatResolver` | resolved() 15 探测参；forResponses/forCompletions/forAnthropic/forMistral | 新字段无通道（默认须 false） |
| `CatalogCompatRules.openaiResponses` | 仅 supportsStrictMode:true | 未照抄 gpt-5+ grammar 谓词 |
| models.json：`ModelsJsonSchema.CompatDef`（单 record 两用） | 有 supportsStrictMode | 无 grammar 字段 |
| `ModelsJsonCompat`（两处 `new ModelCompat(`）/ `ModelJsonMerge` | — | 需携带/合并新字段 |
| completions 出站：`CompletionToolWire.toTool` | 仅 function | 无 custom 分支 |
| completions 回放入口：`OpenAICompletionsMessageConverter.addAssistantMessage`（:345-422） | 仅 function tool_calls | 无 custom 分支 |
| completions 入站：`OpenAICompletionsApi`（476 行）+ `ToolCallAccumulator`（仅 function） | — | custom chunks 无路径 |
| responses 出站：`ResponseToolWire.responseTool` | 仅 function | 无 custom 分支 |
| responses 转换器 `ResponsesMessageConverter`（493 行） | function_call / function_call_output | 无 custom 形状 |
| responses 入站：`ResponsesStreamProcessor`（424 行，FunctionCallState） | — | 无 custom item/事件 |
| agent-core `MessageJsonCodec.decodeConstrainedSampling`（:154-168） | 仅 json_schema 节点 | grammar 节点解码 |
| PiMessages 映射（`PiMessagesApi:279-282`） | `valueToTree(constrainedSampling)` 通用 | 新变体自动可序列化，无需改 |
| 执行侧 `ToolArgumentsValidator`/`prepareArguments` | 通用 schema 校验 | 无（对齐 P9） |

SDK 表达能力边界（openai-java-core **4.42.0**，已核实类清单）：

- completions **出站声明**：`com.openai.models.chat.completions.ChatCompletionCustomTool`
  （`$Custom`、`$Custom$Format$Grammar`、syntax 枚举 lark/regex）—— 类型化可用。
- completions **入站 chunk**：`ChatCompletionChunk.Choice.Delta.ToolCall` **无 typed custom**
  访问器（仅 id/function/type）⇒ custom 对象从 `_additionalProperties()` 原始 JsonValue 取。
- completions 回放：`ChatCompletionMessageCustomToolCall` 类型化可用。
- responses：`CustomTool`（出站）、`ResponseCustomToolCall`（item）、
  `ResponseCustomToolCallInputDeltaEvent`/`InputDoneEvent`（均在 `ResponseStreamEvent` 联合内，
  含 `customToolCallInputDelta()/Done()` 访问器）—— 类型化可用。

## 3. 逐项裁决

| # | 事项 | 裁决 |
|---|---|---|
| J1 | grammar 变体承载 | 新建 `record GrammarSampling(Map<String,String> variants)`，加 permit 与 Jackson subtype `grammar`；`Map.copyOf` 防御复制，**空表不抛**（抛错只在 resolve 且门开时，照 pi） |
| J2 | 能力位组件 | `ModelCompat` 末尾加第 29 组件 `Boolean supportsOpenAIGrammarTools`（null≙false，门用 `Boolean.TRUE.equals`）。洋葱 ctor 仅改直调 canonical 的一处 |
| J3 | 能力位通道 | `resolved()` 加末尾一个 `Boolean` 形参；forResponses/forCompletions 传 `c.supportsOpenAIGrammarTools()`（显式-only，无探测），forAnthropic/forMistral 传 `null`（两车道无读点） |
| J4 | 内置目录标注 | `CatalogCompatRules.openaiResponses` 内照抄谓词（provider openai ＋ `/^gpt-(\d+)/` 组 ≥5）⇒ gpt-5/mini/nano 三条置 true，与 supportsStrictMode 合并返回 |
| J5 | models.json | `CompatDef` 加 `@JsonProperty("supportsOpenAIGrammarTools") Boolean`（单 record 同时覆盖两车道 compat）；`ModelsJsonCompat` 两处构造透传；`ModelJsonMerge` 合并照「override≠null则取override」同形 |
| J6 | 两车道出站/入站/回放 | 见 §4 步骤 3–5；grammar 缓冲类新建在 protocol 包（包私有 `GrammarInputBuffer`），两车道共用 |
| J7 | 执行侧 | **零改动**（对齐 P9），在 AgentTool/相关 javadoc 注明 |
| J8 | JsonObject | **零代码结案**：Java 侧 `ToolUseContent.arguments`/`ToolDefinition.inputSchema` 已是 `Map<String,Object>` 对象形状；在 ModelCompat/相关类 javadoc 说明与 pi 编译期约束等价 |
| J9 | 孤对净化 | 两车道 custom 入参串必须走 `SanitizeUnicode.surrogates`（仅历史回放路径，照 P7）；入站重组不净化（pi 也不） |
| J10 | 错误文案 | 四处文案逐字：`no supported grammar variant was provided`、`Grammar tool call "…" requires argument "…" to be a string.`、`grammar tool input for property "…" changed after it was closed`、`…changed non-monotonically`；schema 四错经 `Tool "…" cannot use grammar constrained sampling: …` 包装 |

## 4. 实施步骤（每步 commit 后全 reactor 相关模块须绿）

1. **grammar 变体形状**：`GrammarSampling` ＋ ConstrainedSampling permits/SubTypes ＋
   agent-core `MessageJsonCodec` grammar 节点解码。无生产者 ⇒ 零行为变化。证据：编译 + 模块回归。
2. **能力位贯通**：ModelCompat 组件 ＋ CompatResolver 通道 ＋ CatalogCompatRules 谓词 ＋
   models.json schema/Compat/Merge。无消费者 ⇒ wire 零变化（夹具钉「gpt-5 内置条目门已开」）。
3. **completions 出站＋回放（RED 先行）**：`CompletionToolWire` custom 分支（用
   `ChatCompletionCustomTool` typed builder）＋ converter addAssistantMessage custom
   tool_calls（含净化）；起点建 `GrammarInputProperties`（新 api 层 helper）。
4. **completions 入站（RED 先行）**：`ToolCallAccumulator` 加 custom 路 ＋
   `OpenAICompletionsApi` 从 delta 附加属性读 custom ＋ protocol 包 `GrammarInputBuffer`
   移植状态机；finish 以 close 收尾。
5. **responses 全路径（RED 先行）**：`ResponseToolWire` custom（CustomTool typed）＋
   converter custom_tool_call/custom_tool_call_output（含净化）＋
   `ResponsesStreamProcessor` custom item/input delta/done 事件。
6. **文档闭环**：docs/69 §12 回填 + docs/48/41/32/43 等对账（清单 §7）。

文件大小约束：新逻辑优先进新类（`GrammarInputBuffer`、`GrammarInputProperties`、
`CustomToolWire` 如需）；`OpenAICompletionsApi`（476）、converter（493/496）只许减行不许净增。
`ModelCompat` 665 行属存量超限，本包净增应 ≤5 行，拆分另包登记，不在本包做。

## 5. 请裁决（R 项，建议值）

- **R1**：J1–J10 是否全按上表？（建议：是）
- **R2**：能力位放 ModelCompat 末尾、null≙false，而不新建旁路类型？（建议：是，沿用法式洋葱）
- **R3**：completions 车道虽无 pi 生成器标注，仍落全路径（models.json 打开即可达）？（建议：是，docs/48 行 E 明文要求）
- **R4**：入站 custom chunk 经 SDK 附加属性原始 JsonValue 解析（typed 路径不存在）？（建议：是，配原始 JSON 观测夹具）
- **R5**：tool-search 的 `defer_loading` 明确不做？（建议：是）
- **R6**：JsonObject 零代码结案？（建议：是）
- **R7**：错误文案全部逐字移植（含句号/引号形态）？（建议：是）

## 6. 夹具与变异计划

新夹具（RED，按步骤）：

1. completions 出站：RecordingHttpServer 抓原始 body —— 门开＋lark ⇒ tools[0]
   `{type:"custom",custom:{…format:{type:"grammar",grammar:{syntax:"lark",definition}}}}`；
   门关 ⇒ function 形状（回归对照）。
2. responses 出站同形（`{type:"custom",name,description,format:{type:"grammar",syntax,definition}}`）。
3. 构建期错误（门关时不抛、门开时抛，文案逐字）：空 variants；schema 四错
   （非 object／required 非恰 1／properties 缺项／属性非 string）。
4. completions 入站：custom chunks 序列（name/input 拆分、id 独立帧）⇒ 终局
   ToolUseContent.arguments=`{property: 完整串}`；toolcall_delta 拼回可 JSON.parse。
5. responses 入站：output_item.added（input 初值）＋ input.delta ＋ input.done ＋
   output_item.done ⇒ 同上；闭合后重复 done ⇒ delta undefined（不事件）。
6. 历史回放：第二轮请求体含 custom tool_calls（completions）/ custom_tool_call ＋
   custom_tool_call_output（responses），input 串已净化。
7. models.json：compat 显式 true ⇒ 门开；缺省 ⇒ 门关。
8. agent-core：JSONL 编码/回读 grammar 节点（variants 两键）。

变异探针（GREEN 后，记录红集即还原）：

- M1：lark/regex 选择反转为 regex 优先 ⇒ 语法/变体断言红。
- M2：门强制 false（忽略 compat） ⇒ 出站 custom 用例红。
- M3：缓冲去掉单调/闭合检查 ⇒ 入站非单调/闭合后变更用例红。
- M4：回放路径去掉净化 ⇒ custom input 净化断言红（B16/B83 台账关闭证据）。

模块回归：`mvn -o -pl pi-java-ai test` ＋ 跨模块改动处 `agent-core`/`coding-agent -am`；
checkstyle 14 模块；RED 数实测填 §12（不预设计数）。

---

## 12. 实施记录（2026-10-03）

### 12.1 提交序列（5 步 6 commit）

| 步 | commit | 内容 |
|---|---|---|
| 1 | `9fe8c01` | `GrammarSampling` record（api）＋ ConstrainedSampling permit；agent-core codec 节点解码；JSONL round-trip |
| 2 | `417133c` | `ModelCompat` 第 29 组件＋resolver 显式-only 通道＋catalog gpt-5+ 谓词＋models.json 键 |
| 3 | `22ecd93` | `GrammarInputProperties`（resolve/create/infer）；completions custom 出站＋回放（净化） |
| 4 | `9eaeacb` | `GrammarInputBuffer` 状态机；completions custom 入站重组（含提取器、reasoning 字段抽出） |
| 5 | `f05d772` | responses 全路径（出站/回放/入站）；`ResponseEventContext`＋`ResponseItemHandlers`＋`ResponsesCustomCalls` |
| 补 | `c7d0b7a` | M1 夹具：两变体并存 lark 优先 |

### 12.2 RED 实测

- 步骤 1 编译红（codec 节点缺类）；步骤 2 编译红（accessor 缺）。
- 步骤 3 行为红 **7/8**（门关回落一条本就绿）；步骤 4 编译红→实现后绿；
  步骤 5 行为红：出站 2＋回放 1＋入站 2（门关回落与幂等一条本就绿）。
- 全量：ai **1313/1313**（基线 1285 ＋ 28 新用例）。

### 12.3 变异探针（全部命中）

| 探针 | 红测试数 | 实测红集 |
|---|---:|---|
| M1 lark/regex 反转 | **1** | `GrammarToolsWireTest.larkWinsWhenBothVariantsArePresent`（c7d0b7a 补的夹具；原有夹具均单变体 ⇒ 若不补此条 M1 零红） |
| M2 门强制 false | **12** | completions wire 7 ＋ responses 4 ＋ inbound 1（所有 custom 出站/回放/入站正向全红） |
| M3 去单调/关闭检查 | **2** | `aNonMonotonicNextInputIsAnError`、`changingTheInputAfterCloseIsAnError` |
| M4 回放去净化 | **2** | 两车道 `replaysCustomToolCall*` 各一 |

### 12.4 偏差与更正（相对本设计）

1. **grammar 能力位无探测**：实现确认 gate 只有显式来源，两车道都读
   `compat.supportsOpenAIGrammarTools()`，与 pi 一致；pi 生成器只标 responses api，
   但 Java 的 catalog 标注函数两车道共用（`CatalogCompatRules.openaiResponses`），
   已在 `CatalogCompatRulesTest` 的门测试注释说明。
2. **`historyToolResult` 的 output 类型转换**：SDK 把 function/custom 两种 output
   建成两个 Java 类型（wire 同形）⇒ 经 JSON 树 `treeToValue` 转换，受检异常包成
   IllegalStateException。
3. **非单调守卫的可达性**：正常逐帧 append 结构上单调（s_n = s_{n-1}+d_n 恒为
   前缀），「changed non-monotonically」是防御 server 发非增量帧的守卫 ⇒ 测试在
   GrammarInputBuffer 单测层构造，不走端到端。
4. **首帧只带 id 的 custom chunk**：名字缺席时 pi 的属性回落 `"input"`，后续名字
   到达不改属性（缓冲已建）—— Java 照抄并钉了夹具
   `firstFrameWithOnlyIdFallsBackToTheInputProperty`。
5. **文件大小**：设计约束全部满足——`ResponsesStreamProcessor` **424→378**
   （context/handlers 抽出反减）、`ResponsesMessageConverter` **493→492**、
   `OpenAICompletionsApi` **476→432**、`OpenAICompletionsMessageConverter`
   **496**（持平）、`ModelCompat` **665→670**（净增 5，达到设计上限；拆分另立）。

### 12.5 台账与后续

- docs/48 §5 行 E 标闭环、A-19 行更新；docs/41 §1.3 grammar/JsonObject 除名；
  docs/32 B16/B83 加更新；docs/43 D2 两半合一；docs/66 R1 指针补上。
- **无内置工具生产者**：grammar custom tool 在主流程不可达（扩展-only），与 §0
  预判一致；能力位/形状/线格全部就绪，扩展声明 `constrainedSampling` 即生效。
- B142 不涉及本包（clamp 是请求侧差异）；J7 执行侧结论无需改动。

## 7. 闭环文档回填清单

- docs/69：banner 翻已实现 + §12 实施记录（commit、RED 实红集、变异结果、偏差）
- docs/48：banner 行 ＋ §5 行 E grammar/JsonObject 列标记
- docs/41：:91 缺失清单 grammar/JsonObject 条目
- docs/32：B16/B83「grammar 2 处随 grammar 包」标注结案；必要时新登记项
- docs/43：§9-1 grammar 两处差额回填
- docs/66：§范围中「grammar 后续包」指针更新
- docs/32/ModelCompat 存量超限行：若有新信息一并更新
