package com.pijava.ai.catalog;

import java.util.Map;

/**
 * Per-model provider compatibility flags (pi {@code Model.compat}).
 *
 * <p>pi 有**五个** per-api 的 compat 接口（{@code types.ts:674} 的
 * {@code OpenAICompletionsCompat} 27 字段、{@code :754} 的 {@code OpenAIResponsesCompat} 10、
 * {@code :778} 的 {@code AnthropicMessagesCompat} 13、{@code :843} 的 {@code BedrockCompat} 1、
 * {@code :849} 的 {@code MistralConversationsCompat} 1 —— 共 52）。本仓把五者**合一**成一个
 * 类型（依据：除 {@code supportsMidConvoSystemMessages} 外每个字段只被一条车道读，而那一个在
 * 四条车道上缺省同为 {@code false}；逐字段核对见 {@code docs/53 §2 P8}），并只携带 pi-java
 * 真正**消费**的字段 —— 其余四十余个按 {@code docs/53 §4.4} 的归属表留给各自的包，**不投机加**。
 * 字段是具名 record 组件而不是 {@code Map}：pi 的 compat 是 typed interface，用 Map 会把类型错误
 * 推到读点。{@code docs/31 §8.34.4} 决策 2。</p>
 *
 * <p>⚠️ <b>本记录有两个生命周期，读点必须拿「解析后」的那一份</b>：目录标注与 models.json 给出的
 * 是<b>部分</b> compat（「用户/目录写了什么」），而 pi 的车道读的是
 * {@code explicit ?? detected ?? 字面量缺省} 之后的<b>全确定</b>形状。合一那一步是
 * {@link CompatResolver}（pi 的 {@code detectCompat}/{@code getCompat}/{@code getAnthropicCompat}）。
 * 直接读 {@code model.compat()} 只对「探测的默认值恰好等于字面量缺省」的字段安全 —— 这是
 * {@code docs/53 §8} 第 1 条验收 grep 要钉的事。</p>
 *
 * <p>⚠️ <b>各标志的「缺席」语义各不相同</b> —— 这是本记录最容易读错的地方，逐个写清：</p>
 *
 * <table border="1">
 *   <caption>absent-case semantics</caption>
 *   <tr><th>标志</th><th>类型</th><th>缺席 ≙</th><th>为什么</th></tr>
 *   <tr><td>{@code allowEmptySignature}</td><td>{@code boolean}</td><td>{@code false}</td>
 *       <td>pi {@code ?? false}（{@code anthropic-messages.ts:215}）⇒ 二态</td></tr>
 *   <tr><td>{@code requiresReasoningContentOnAssistantMessages}</td><td>{@link Boolean}</td>
 *       <td><b>探测</b>（provider/baseUrl）</td>
 *       <td>pi 的探测结果**依赖模型**（{@code isDeepSeek}，{@code detectCompat:1601}）⇒ 三态</td></tr>
 *   <tr><td>{@code supportsFinishReason}</td><td>{@code boolean}</td><td>{@code true}</td>
 *       <td>pi 的探测结果是**常量 `true`**（{@code detectCompat:1640}，无任何分支）⇒
 *           {@code explicit ?? true} 塌缩成二态、且方向与第一个标志**相反**</td></tr>
 *   <tr><td>{@code forceAdaptiveThinking}</td><td>{@code boolean}</td><td>{@code false}</td>
 *       <td>pi 的判据是 {@code === true}（{@code anthropic-messages.ts:878/1165}）⇒ 二态，
 *           缺席与 {@code false} 不可区分</td></tr>
 *   <tr><td>{@code supportsMidConvoSystemMessages}</td><td>{@link Boolean}</td>
 *       <td><b>探测</b>（生成的模型目录）</td>
 *       <td>pi 的默认是 {@code ?? false}，但「生成的模型目录会对有能力的模型开启它」
 *           （{@code types.ts:731-732}）⇒ 缺席与显式 {@code false} 将来要分开 ⇒ 三态</td></tr>
 *   <tr><td>{@code supportsMidConvoToolAdditions}</td><td>{@link Boolean}</td>
 *       <td>{@code false}（二态）</td>
 *       <td>包 A3 加。读点写死 {@code === true}（{@code api/openai-completions.ts:809}）</td></tr>
 *   <tr><td>{@code supportsMidConvoToolChanges}</td><td>{@link Boolean}</td>
 *       <td>{@code false}（二态）</td>
 *       <td>包 A3 加。读点是真值判断（{@code api/anthropic-messages.ts:1051}），
 *           且注释明写 <i>Requires</i> {@code supportsMidConvoSystemMessages}</td></tr>
 *   <tr><td>{@code supportsAdditionalTools}</td><td>{@link Boolean}</td>
 *       <td>{@code false}（二态）</td>
 *       <td>包 A3 加。与下一个是 **Responses 的两个独立机制**，命中其一即可锚定</td></tr>
 *   <tr><td>{@code supportsToolSearch}</td><td>{@link Boolean}</td>
 *       <td>{@code false}（二态）</td>
 *       <td>包 A3 加。{@code additional_tools} 缺席时的替身：合成一对
 *           {@code tool_search_call}/{@code tool_search_output}</td></tr>
 *   <tr><td>{@code supportsTemperature}</td><td>{@code boolean}</td><td>{@code true}</td>
 *       <td>包 A7 加。与 {@code supportsFinishReason} **同形**（探测值是常量
 *           {@code true}）⇒ 二态，方向是「缺席即发」</td></tr>
 *   <tr><td>{@code maxTokensField}</td><td>{@link MaxTokensField}</td>
 *       <td><b>探测</b>（端点）</td>
 *       <td>包 A7 加。探测按 provider/baseUrl 的八个谓词（{@code useMaxTokens}）⇒ 三态</td></tr>
 *   <tr><td>{@code supportsStore}</td><td>{@link Boolean}</td><td><b>探测</b>（端点）</td>
 *       <td>包 A7 加。探测是 {@code !isNonStandard}（十三个谓词）⇒ 三态</td></tr>
 *   <tr><td>{@code supportsDeveloperRole}</td><td>{@link Boolean}</td>
 *       <td><b>探测</b>（端点 ＋ 模型 id）</td>
 *       <td>包 A7 加。探测是 {@code isOpenRouterDeveloperRoleModel || (!isNonStandard && !isOpenRouter)}
 *           —— **同时看 provider 与 {@code model.id} 的前缀** ⇒ 三态</td></tr>
 *   <tr><td>{@code supportsStrictMode}</td><td>{@link Boolean}</td>
 *       <td><b>车道的缺省</b>（两侧相反）</td>
 *       <td>包 A7 加。responses {@code ?? false}（{@code openai-responses.ts:74}）、
 *           azure {@code ?? true}（{@code azure-openai-responses.ts:296}）⇒「缺席」不是单个
 *           值而**取决于车道**，故缺省由 {@link CompatResolver#forResponses} 的形参给</td></tr>
 * </table>
 *
 * @param allowEmptySignature pi {@code compat.allowEmptySignature}. When {@code true}, a thinking
 *        block that has **no** signature is replayed as a {@code thinking} block carrying
 *        {@code signature:""} instead of being downgraded to plain text — some
 *        Anthropic-compatible providers emit and accept empty signatures. Absent ≡ {@code false}
 *        (pi normalizes with {@code ?? false}, {@code anthropic-messages.ts:215}), so this is a
 *        **two-state** flag, not tri-state: {@code undefined} and {@code false} are
 *        indistinguishable in behavior (docs/31 §8.34.4 决策 3).
 * @param requiresReasoningContentOnAssistantMessages pi
 *        {@code compat.requiresReasoningContentOnAssistantMessages}
 *        ({@code openai-completions.ts:1356-1362}): when set, every assistant history message
 *        that carries no reasoning is sent with an **empty** {@code reasoning_content}, because
 *        DeepSeek-style relays reject the turn (400) without it.
 *
 *        <p>⚠️ Unlike {@code allowEmptySignature}, the absent case is **not** "false": pi
 *        resolves it against a detection ({@code detectCompat:1601} — provider {@code deepseek}
 *        or a baseUrl containing {@code deepseek.com}), so this component is a **three-state**
 *        {@link Boolean}: {@code null} = detect, {@code true}/{@code false} = explicit user
 *        override from {@code models.json} ({@code getCompat:1700} does
 *        {@code explicit ?? detected}). Reading it as a plain boolean would silently disable
 *        the whole deepseek path.</p>
 * @param supportsFinishReason pi {@code compat.supportsFinishReason}
 *        ({@code openai-completions.ts:685-692}, openai-completions lane only): when {@code true},
 *        a stream that ends **without** any {@code finish_reason} is an error
 *        ({@code "Stream ended without finish_reason"}) rather than a silent success; when
 *        {@code false}, the lane falls back to {@code toolCall ? "toolUse" : "stop"}. Absent ≡
 *        {@code true} — the opposite default from {@code allowEmptySignature}.
 *
 *        <p>⚠️ **为什么它能塌缩成普通 `boolean`，而上面那个组件必须三态**：`explicit ?? detected`
 *        要保三态的前提是 **detected 随模型变**（上一个组件的 detected 是 {@code isDeepSeek}）。
 *        本标志的 detected 是 `detectCompat:1640` 里的字面量 `true` —— 函数体内**没有任何分支**
 *        碰过它 ⇒ `explicit ?? true` 里 `undefined` 与 `true` 行为完全相同，没有第三种状态可保。
 *        于是「缺席 ≙ {@code true}」被归一在 {@link com.pijava.ai.provider.ModelsJsonConfig} 的
 *        {@code compatOf} 里，读侧永远拿到一个非空的布尔。
 *        反过来，正因为默认是 `true`，**唯一**能放宽严格检查的途径就是用户显式写 {@code false}。</p>
 * @param forceAdaptiveThinking pi {@code compat.forceAdaptiveThinking}
 *        ({@code anthropic-messages.ts:878, 1165}): Anthropic 车道的<b>思考形态分流开关</b>
 *        —— {@code true} 时用 {@code {type:"adaptive", display}} ＋ {@code output_config.effort}
 *        （由 {@code mapThinkingLevelToEffort} 定 effort）；{@code false} 时用
 *        {@code {type:"enabled", budget_tokens}}。缺席 ≡ {@code false}
 *        （pi 的判据是 {@code === true}）⇒ <b>二态</b>。
 *
 *        <p>⚠️ 它的值来自 pi 的<b>生成目录数据</b>（{@code generate-models.ts:1043} 的
 *        {@code isAnthropicAdaptiveThinkingModel}）—— 那是**代码推导**的
 *        （{@code generate-models.ts:585} 的纯字符串谓词，`fable-5`/`mythos-5`/`opus-4-6`…），
 *        故包 A7 按同一份谓词把它标到内置目录上（{@code docs/53 §4.2}）。</p>
 * @param supportsMidConvoSystemMessages pi {@code compat.supportsMidConvoSystemMessages}
 *        ({@code types.ts:731-732}): when {@code true}, the transcript keeps system messages that
 *        arrive **mid-conversation** instead of folding them into the leading one, and each lane
 *        renders them in place (Anthropic text blocks / completions+mistral instruction messages /
 *        Responses input items). Read by {@link com.pijava.ai.api.Transcripts#resolveTranscript}
 *        ({@code docs/49 §5.4}）。
 *
 *        <p>⚠️ **三态**：pi 的读取点写 {@code model.compat?.supportsMidConvoSystemMessages}
 *        （{@code anthropic-messages.ts:517} 等），{@code undefined} 走折叠支；但该字段的探测
 *        默认值来自**生成的模型目录**（「会对有能力的模型开启它」）⇒ 缺席 ≠ 显式 {@code false}。
 *        包 A2 只加字段与消费点；生产者由包 A7 补上（{@code docs/53 §4.2}）。</p>
 * @param supportsMidConvoToolAdditions pi {@code compat.supportsMidConvoToolAdditions}
 *        ({@code types.ts:733-734}, completions lane only): whether a mid-conversation system
 *        message may carry its own {@code tools} — rendered as a Kimi-shaped
 *        {@code {role:"system", tools:[…]}} message ({@code api/openai-completions.ts:1240-1246}).
 *        注释明写 *Requires* {@code supportsMidConvoSystemMessages}，而读点把两个都写成
 *        {@code === true}（{@code :809}、{@code :1223} 两处同式）⇒ 缺席与 {@code false} 行为相同
 *        ⇒ **二态**。
 * @param supportsMidConvoToolChanges pi {@code compat.supportsMidConvoToolChanges}
 *        ({@code types.ts:832-833}, Anthropic lane only): whether the model accepts
 *        mid-conversation {@code tool_addition}/{@code tool_removal} content blocks.
 *        读点是**真值判断**（{@code anthropic-messages.ts:1052}），且与
 *        {@code supportsMidConvoSystemMessages} 相与，还与两个结构性条件（初始工具非空、
 *        无工具重定义）一起构成 {@code nativeToolChanges} 的**四条件门**（{@code docs/51 §2 P8}）。
 *        缺席 ≡ {@code false} ⇒ **二态**。
 * @param supportsAdditionalTools pi {@code compat.supportsAdditionalTools}
 *        ({@code types.ts:768}, Responses lanes): whether the model supports
 *        **message-anchored** {@code additional_tools} input items — one
 *        {@code {type:"additional_tools", role:"developer", tools:[…]}} item per system message
 *        that declares tools ({@code api/openai-responses-shared.ts:185-191}）。
 *
 *        <p>⚠️ 与下一个标志是 **Responses 的两个独立机制**，不是同一个开关的两档：命中
 *        **其一**就能锚定（{@code openai-responses.ts:295} 的
 *        {@code supportsAdditionalTools || supportsToolSearch}）。{@code additional_tools}
 *        优先。</p>
 * @param supportsToolSearch pi {@code compat.supportsToolSearch} ({@code types.ts:769-770},
 *        Responses lanes): the model's stand-in when {@code additional_tools} is unavailable —
 *        the lane synthesizes a **client-executed tool search pair**
 *        ({@code tool_search_call} + {@code tool_search_output}, both
 *        {@code execution:"client"}/{@code status:"completed"}) whose {@code call_id} is a
 *        deterministic hash of the seed and the tool names ({@code openai-responses-shared.ts:193-208}）。
 * @param supportsTemperature pi {@code compat.supportsTemperature}
 *        ({@code anthropic-messages.ts:1105-1110}, Anthropic lane only): when {@code false} the
 *        lane **omits** the {@code temperature} field. Claude Opus 4.7+ (and the
 *        {@code forceAdaptiveThinking} generation) reject a non-default temperature, so pi's
 *        generated catalogue writes {@code false} for those ids
 *        ({@code generate-models.ts:604} 的 {@code isAnthropicTemperatureUnsupportedModel}）。
 *
 *        <p>⚠️ 它的读点是**四重合取**（{@code temperature !== undefined} ＋ 未开思考 ＋
 *        {@code supportsMidConvoEffort !== true} ＋ 本标志），只有**最后一个**由本组件管 ——
 *        前三项在 {@link com.pijava.ai.protocol.AnthropicRequestBuilder} 的读点里。（本仓没有
 *        {@code supportsMidConvoEffort}，见 {@code docs/53 §10 B99}。）</p>
 *
 *        <p>⚠️ 与 {@code supportsFinishReason} 同形：探测值是常量 {@code true}
 *        （{@code getAnthropicCompat:214} 的 {@code ?? true}，而 {@code isOpenRouter} 只影响
 *        另外两个 java 不携带的字段）⇒ 二态，{@code explicit ?? true} 塌缩在 {@code compatOf} 里。</p>
 * @param maxTokensField pi {@code compat.maxTokensField} ({@code openai-completions.ts:836-841},
 *        completions lane only): which request field carries the output cap —
 *        {@code max_tokens} or {@code max_completion_tokens}. OpenAI's own field is the default;
 *        the non-standard endpoints (vLLM/Qwen/DeepSeek/Moonshot/Together/NVIDIA/Ant Ling/z.ai/
 *        Cloudflare gateway/chutes.ai) take the plain name.
 *
 *        <p>⚠️ **三态**：探测按 provider/baseUrl（{@code useMaxTokens}，八个谓词，
 *        {@code detectCompat:1619}），显式值赢（{@code getCompat:1695}）。</p>
 * @param supportsStore pi {@code compat.supportsStore} ({@code openai-completions.ts:832-834},
 *        completions lane only): when {@code true} the lane sends {@code store:false} —
 *        i.e. it explicitly opts **out** of server-side retention. (⚠️ 读点写的是 {@code false}
 *        这个值，不是把开关原样发出去 —— 标题是「支持这个字段」而不是「要存」。)
 *
 *        <p>探测是 {@code !isNonStandard}（十三个谓词，{@code detectCompat:1603}）⇒ **三态**。</p>
 * @param supportsDeveloperRole pi {@code compat.supportsDeveloperRole}
 *        ({@code openai-completions.ts:1225}, completions lane only): whether the leading
 *        instruction message goes out with {@code role:"developer"} instead of {@code "system"} —
 *        gated additionally on {@code model.reasoning}（非推理模型一律 {@code system}）。
 *
 *        <p>探测是 {@code isOpenRouterDeveloperRoleModel || (!isNonStandard && !isOpenRouter)}
 *        （{@code detectCompat:1636}）：OpenRouter 上只有 {@code anthropic/*} 与 {@code openai/*}
 *        走 developer 角色，而其**自有的**模型与所有非标准端点都退回 {@code system}
 *        ⇒ 判据**同时看 provider 与模型 id 前缀** ⇒ 三态。</p>
 * @param supportsStrictMode pi {@code compat.supportsStrictMode} (Responses lanes): whether
 *        function tools carry the {@code strict} field. 包 B88 落的读点让「不支持」被表达成
 *        **整个键不发**、支持则显式发一个值（{@code openai-responses-shared.ts:391-393}）。
 *
 *        <p>⚠️ 它是本记录里**唯一**「缺席语义随车道变」的组件：responses 的缺省是
 *        {@code false}（{@code openai-responses.ts:74}）、azure 是 {@code true}
 *        （{@code azure-openai-responses.ts:296/319}）⇒ 由
 *        {@link CompatResolver#forResponses} 的形参把车道缺省喂进来，本组件保持三态
 *        （{@code null} ＝「按车道缺省」）。</p>
 * @param supportsLongCacheRetention pi {@code compat.supportsLongCacheRetention}（包 A-01）：
 *        是否允许 `cacheRetention:"long"` 落成 Anthropic 的 {@code ttl:"1h"}。缺席
 *        （{@code null}）≙ pi 的 {@code ?? true} —— 本组件**不**参与「断点发不发」的判断，
 *        只控 ttl（{@code anthropic-messages.ts:79-82}）。⚠️ pi 的生成目录里**没有任何
 *        anthropic 车道的模型**写过这个键（实测 `anthropic.json` 零命中，写它的全是
 *        completions 车道：baseten / together / xai 等）⇒ 内置目录无需标注，
 *        {@code models.json} 仍可覆盖。
 * @param supportsCacheControlOnTools pi {@code compat.supportsCacheControlOnTools}（包 A-01）：
 *        是否把断点挂到工具表末项上。缺席 ≙ {@code ?? true}。置 {@code false} 时**只**撤
 *        工具那一处，{@code system} 与消息表照挂（pi {@code :1114} 的门只包住
 *        {@code toolCacheControl}）。
 * @param thinkingTokenBudgetField pi {@code compat.thinkingTokenBudgetField}（包 A-10）：
 *        顶层预算字段名（vLLM／Qwen／llama.cpp 三种拼写）。{@code null} ≙ pi 的
 *        {@code undefined}（不发该字段）。⚠️ 探测面**只产出 {@code null} 与
 *        {@code false}**（{@code detectCompat:1662-1663}，pi 的注释明写「not set on the
 *        generated catalog」）⇒ 内置目录一个都不标，只有 {@code models.json} 可达。
 * @param supportsThinkingTokenBudget pi {@code compat.supportsThinkingTokenBudget}（包 A-10）：
 *        {@code thinkingTokenBudgetField} 的布尔别名，≙ {@code "thinking_token_budget"}
 *        （vLLM）。探测面的值是 {@code false}；显式取值优先于它
 *        （pi {@code openai-completions.ts:1004-1010}）。
 * @param supportsMaxOutputTokens pi {@code compat.supportsMaxOutputTokens}（包 A-10）：
 *        Response 端点是否接受 {@code max_output_tokens} —— 缺席 ≡ {@code true}
 *        （pi {@code openai-responses.ts:79} 的 {@code ?? true}，{@code types.ts:773-774}
 *        的注释：「某些 Codex 协议网关会拒绝这个参数」）。
 *
 *        <p>⚠️ <b>本记录里唯一「门只被一条车道读」的组件</b>：pi 的 {@code azure-openai-responses.ts:309}
 *        那份副本**没有这道门**（`if (options?.maxTokens)` 光秃秃）。⇒ 两条车道今天共用
 *        {@link com.pijava.ai.protocol.ResponsesMessageConverter}，那条区别由**车道名**分支实现，
 *        而**不是**由本组件的取值 —— 在 azure 车道上它根本不被读（显式写 {@code false} 也无效，
 *        与 pi 一致）。⇒ 车道名分支是这条约束的唯一实现处，见 {@code docs/57 §11}。</p>
 * @param thinkingFormat pi {@code compat.thinkingFormat}（包 A-09，
 *        {@code openai-completions.ts:873-970}，completions 车道独有）：思考开关的十一种
 *        线格形状。{@code null} ≙「按端点探测」（六段三元链，回落 {@code openai}，
 *        {@code detectCompat:1646-1656}），显式值赢 ⇒ **三态**；其余三条车道不读它。
 * @param chatTemplateKwargs pi {@code compat.chatTemplateKwargs}（包 A-09，{@code types.ts:708}）：
 *        {@code chat-template} 形状声明的 kwargs（{@code $var} 在请求期解析）。探测恒给空表
 *        ⇒ {@code null} 被 compact 构造器归一成 {@code Map.of()}（pi 的 {@code ?? {}}）⇒ 二态。
 * @param chatTemplateArgs pi {@code compat.chatTemplateArgs}（包 A-09，{@code types.ts:710}）：
 *        {@code baseten} 形状声明的 args，语义同上一键。
 * @param supportsReasoningEffort pi {@code compat.supportsReasoningEffort}（包 A-09）：端点是否吃
 *        {@code reasoning_effort}。{@code null} ≙ 探测（七谓词否定合取，
 *        {@code detectCompat:1637-1638}）⇒ **三态**。
 * @param cacheControlFormat pi {@code compat.cacheControlFormat}（包 A-02，
 *        {@code openai-completions.ts:1632}/{@code :1073}，completions 车道独有）：线上
 *        {@code cache_control} 的形状。{@code null} ≙「按端点探测」（判据
 *        {@code provider === "openrouter" && id.startsWith("anthropic/")}，
 *        {@code detectCompat:1632}），显式值赢 ⇒ **三态**；其余三条车道不读它。
 * @param openRouterRouting pi {@code compat.openRouterRouting}（包 A-02，
 *        {@code openai-completions.ts:980-982}，{@code types.ts:860} 的
 *        {@code OpenRouterRouting}）：原样发成请求体的 {@code provider} 键。⚠️ **三态且
 *        {@code null} 不归一成空表**（docs/59 R6）—— pi 的读点是 raw {@code model.compat}
 *        的真值判断，缺席（{@code null}）不发这个键、空表（{@code {}} 为真值）发
 *        {@code provider:{}}，二者**线格可区分**。⚠️ 读点在 pi 是 **raw compat**、不是
 *        resolved（{@code :981} 写 {@code model.compat?.openRouterRouting}）；本仓照抄这条
 *        不对称（探测面 {@code :1657} 的 {@code {}} 无读者，故 {@link CompatResolver} 对它
 *        只做透传、不做 {@code explicit ?? detected}）。
 */
/**
 * @param sendSessionAffinityHeaders pi {@code compat.sendSessionAffinityHeaders}（包 B103，
 *        types.ts:736-743/:789-798）：anthropic/completions 车道是否从 {@code sessionId} 发
 *        会话亲和头。{@code null} ≙ 车道探测缺省（openrouter 端点 true，否则 false）。
 *        responses 车道<b>无此开关</b>、不读该组件。
 * @param sessionAffinityFormat pi {@code compat.sessionAffinityFormat}（包 B103）：亲和头形状
 *        （{@code openai}/{@code openai-nosession}/{@code openrouter}）。{@code null} ≙
 *        车道探测（openrouter 端点 openrouter，否则 openai；anthropic 未设 ⇒ x-session-affinity）。
 * @param supportsStrictTools pi {@code compat.supportsStrictTools}（docs/66，
 *        {@code anthropic-messages.ts:216}）：Anthropic 车道是否接受 strict tool。
 *        缺席 ≡ {@code false}（二态）；生成目录对全部 anthropic-messages 模型无条件
 *        写 true（{@code generate-models.ts:826-828}）。
 */
public record ModelCompat(boolean allowEmptySignature,
                          Boolean requiresReasoningContentOnAssistantMessages,
                          boolean supportsFinishReason,
                          boolean forceAdaptiveThinking,
                          Boolean supportsMidConvoSystemMessages,
                          Boolean supportsMidConvoToolAdditions,
                          Boolean supportsMidConvoToolChanges,
                          Boolean supportsAdditionalTools,
                          Boolean supportsToolSearch,
                          boolean supportsTemperature,
                          MaxTokensField maxTokensField,
                          Boolean supportsStore,
                          Boolean supportsDeveloperRole,
                          Boolean supportsStrictMode,
                          Boolean supportsLongCacheRetention,
                          Boolean supportsCacheControlOnTools,
                          ThinkingTokenBudgetField thinkingTokenBudgetField,
                          Boolean supportsThinkingTokenBudget,
                          Boolean supportsMaxOutputTokens,
                          ThinkingFormat thinkingFormat,
                          Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                          Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                          Boolean supportsReasoningEffort,
                          CacheControlFormat cacheControlFormat,
                          Map<String, Object> openRouterRouting,
                          Boolean sendSessionAffinityHeaders,
                          SessionAffinityFormat sessionAffinityFormat,
                          boolean supportsStrictTools) {

    /** Compact constructor（包 A-09）：两个模板 map 的 {@code null} 归一成空表（pi 的
     *  {@code ?? {}}，{@code getCompat:1714-1715}）⇒ 二态、读点免判空；防御性复制
     *  与本仓其它 record 一致。
     *
     *  <p>⚠️ {@code openRouterRouting}（包 A-02）**不归一**：缺席与空表线格可区分
     *  （docs/59 R6）。复制用 {@code LinkedHashMap} 而非 {@code Map.copyOf} —— routing 的
     *  值可空（如 {@code sort.partition: null}，{@code types.ts:884}），而 {@code Map.copyOf}
     *  拒绝 {@code null} 值。</p> */
    public ModelCompat {
        chatTemplateKwargs = chatTemplateKwargs == null
            ? Map.of() : Map.copyOf(chatTemplateKwargs);
        chatTemplateArgs = chatTemplateArgs == null
            ? Map.of() : Map.copyOf(chatTemplateArgs);
        openRouterRouting = openRouterRouting == null
            ? null
            : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(openRouterRouting));
    }

    /**
     * 二十五参便捷构造（包 B103 之前的**规范**构造 —— 那时组件就这二十五个）：包 B103 新增的
     * 两个缺席（{@code null} ≙「按车道探测」）。旧规范形态降级为便捷构造 ⇒
     * {@code CompatResolver.resolved} 与存量构造点**零改签**（与各包同一手法）。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode,
                       Boolean supportsLongCacheRetention,
                       Boolean supportsCacheControlOnTools,
                       ThinkingTokenBudgetField thinkingTokenBudgetField,
                       Boolean supportsThinkingTokenBudget,
                       Boolean supportsMaxOutputTokens,
                       ThinkingFormat thinkingFormat,
                       Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                       Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                       Boolean supportsReasoningEffort,
                       CacheControlFormat cacheControlFormat,
                       Map<String, Object> openRouterRouting) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             cacheControlFormat, openRouterRouting, null, null, false);
    }

    /**
     * 二十七参便捷构造（docs/66 加 {@code supportsStrictTools} 之前的**规范**构造）：
     * 新组件缺席（{@code false} ≙ pi 的 {@code ?? false}）。旧规范形态降级为便捷构造 ⇒
     * 既有 27 参构造点零改签。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode,
                       Boolean supportsLongCacheRetention,
                       Boolean supportsCacheControlOnTools,
                       ThinkingTokenBudgetField thinkingTokenBudgetField,
                       Boolean supportsThinkingTokenBudget,
                       Boolean supportsMaxOutputTokens,
                       ThinkingFormat thinkingFormat,
                       Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                       Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                       Boolean supportsReasoningEffort,
                       CacheControlFormat cacheControlFormat,
                       Map<String, Object> openRouterRouting,
                       Boolean sendSessionAffinityHeaders,
                       SessionAffinityFormat sessionAffinityFormat) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             cacheControlFormat, openRouterRouting,
             sendSessionAffinityHeaders, sessionAffinityFormat, false);
    }

    /**
     * 二十三参便捷构造（包 A-02 之前的**规范**构造 —— 那时组件就这二十三个）：包 A-02 新增的
     * 两个缺席（{@code cacheControlFormat} 传 {@code null} ≙「按车道探测」，
     * {@code openRouterRouting} 传 {@code null} ≙「无路由表」）。旧规范形态降级为便捷构造器 ⇒
     * {@code CompatResolver} 之外的全部存量构造点与夹具**零改签**（与包 A-01/A-09/A-10 同一手法）。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode,
                       Boolean supportsLongCacheRetention,
                       Boolean supportsCacheControlOnTools,
                       ThinkingTokenBudgetField thinkingTokenBudgetField,
                       Boolean supportsThinkingTokenBudget,
                       Boolean supportsMaxOutputTokens,
                       ThinkingFormat thinkingFormat,
                       Map<String, ChatTemplateKwargValue> chatTemplateKwargs,
                       Map<String, ChatTemplateKwargValue> chatTemplateArgs,
                       Boolean supportsReasoningEffort) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             thinkingFormat, chatTemplateKwargs, chatTemplateArgs, supportsReasoningEffort,
             null, null);
    }

    /**
     * 十九参便捷构造（包 A-10 之后的**规范**构造 —— 那时组件就这十九个）：包 A-09 新增的
     * 四个缺席（{@code thinkingFormat}/{@code supportsReasoningEffort} 传 {@code null}
     * ≙「按车道探测」，两个模板 map 归一成空表）。旧规范形态降级为便捷构造器 ⇒
     * {@code CompatResolver.resolved} 与若干夹具（{@code CompatResolverTest:214}）
     * **零改签**（与包 A-01/A7/A-10 同一手法）。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode,
                       Boolean supportsLongCacheRetention,
                       Boolean supportsCacheControlOnTools,
                       ThinkingTokenBudgetField thinkingTokenBudgetField,
                       Boolean supportsThinkingTokenBudget,
                       Boolean supportsMaxOutputTokens) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, supportsMaxOutputTokens,
             null, null, null, null);
    }

    /**
     * 十八参便捷构造（包 A-10 之前的**规范**构造 —— 那时组件就这十八个）：
     * 包 A-10 的第 6 步新增的 {@code supportsMaxOutputTokens} 缺席（{@code null}
     * ≙ 「按车道缺省」）。
     *
     * <p>保留该形态的理由与下面几条相同（旧规范形态降级为便捷构造器）——
     * 包 A-10 的第 5 步把组件数从 16 推到 18 时留的是**十六参**那一档，而
     * {@code CompatResolver} 与第 5 步的夹具（{@code ThinkingTokenBudgetWireTest}）用的是
     * 十八参那一档 ⇒ 加这一档让它们**零改签**。</p>
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode,
                       Boolean supportsLongCacheRetention,
                       Boolean supportsCacheControlOnTools,
                       ThinkingTokenBudgetField thinkingTokenBudgetField,
                       Boolean supportsThinkingTokenBudget) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             thinkingTokenBudgetField, supportsThinkingTokenBudget, null);
    }

    /**
     * 十六参便捷构造（包 A-10 之前的**规范**构造 —— 那时组件就这十六个）：
     * 包 A-10 新增的两个缺席（{@code null} ≙ pi 的 {@code undefined} ≙「探测值」
     * —— {@code detectCompat:1662-1663} 恰好就是 {@code null}／{@code false}）。
     *
     * <p>保留该形态的**实证理由**：四个既有夹具（{@code AnthropicCacheControlTest:118}／
     * {@code :168}、{@code AnthropicCacheControlWireTest:204}／{@code :227}）用的正是它
     * —— 与包 A-01 留 14 参、包 A7 留 9 参是同一手法：**旧规范形态降级为便捷构造器**，
     * 让新增组件「加在末尾」真的等于零改签。</p>
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode,
                       Boolean supportsLongCacheRetention,
                       Boolean supportsCacheControlOnTools) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, supportsLongCacheRetention, supportsCacheControlOnTools,
             null, null, null);
    }

    /**
     * 十四参便捷构造（包 A-01 之前的**规范**构造 —— 那时组件就这十四个）：
     * 包 A-01 新增的两个缺席 —— 两者 pi 的缺省都是 {@code true}，但这里保持 {@code null}
     * （≙ pi 的 {@code undefined}）而**不是** {@code true}，因为缺席会被
     * {@link CompatResolver} 补成车道缺省；在解析层之外直接读 {@code null} 的代码会
     * NPE —— 这正是不许在车道里直接读 compat 的理由之一。保留该形态使包 A-01 之前的
     * 构造点**零改签**。包 A-10 的两个新组件同样缺席（{@code null}）。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch,
                       boolean supportsTemperature,
                       MaxTokensField maxTokensField,
                       Boolean supportsStore,
                       Boolean supportsDeveloperRole,
                       Boolean supportsStrictMode) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             supportsTemperature, maxTokensField, supportsStore, supportsDeveloperRole,
             supportsStrictMode, null, null, null, null);
    }

    /**
     * 九参便捷构造（包 A7 之前的**规范**构造 —— 那时组件就这九个）：
     * 包 A7 新增的五个缺席 —— {@code supportsTemperature} 缺席 ≙ {@code true}
     * （pi 的探测值是常量 true）、其余四个 ≙ pi 的 {@code undefined}（走探测或车道缺省）。
     * 保留该形态使包 A7 之前的 18 个构造点**零改签**。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages,
                       Boolean supportsMidConvoToolAdditions,
                       Boolean supportsMidConvoToolChanges,
                       Boolean supportsAdditionalTools,
                       Boolean supportsToolSearch) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             supportsMidConvoToolAdditions, supportsMidConvoToolChanges,
             supportsAdditionalTools, supportsToolSearch,
             true, null, null, null, null);
    }

    /**
     * 五参便捷构造（包A3 之前的形状）—— 工具增删那四个标志缺席 ≙ pi 的 {@code undefined}
     * ≙ {@code false}（四个读点全是真值判断或 {@code === true}）⇒ 单态塌缩是安全的。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking,
                       Boolean supportsMidConvoSystemMessages) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, supportsMidConvoSystemMessages,
             null, null, null, null);
    }

    /**
     * 四参便捷构造（包A2 之前的形状）—— {@code supportsMidConvoSystemMessages} 缺席 ≙ pi 的
     * {@code undefined} ≙ 折叠支。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason,
                       boolean forceAdaptiveThinking) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, forceAdaptiveThinking, null);
    }

    /**
     * 三参便捷构造（包H5 之前的老形状）—— {@code forceAdaptiveThinking} 缺席 ≙ pi 的
     * {@code undefined}，而 pi 的判据是 {@code === true} ⇒ 与 {@code false} 同义。
     */
    public ModelCompat(boolean allowEmptySignature,
                       Boolean requiresReasoningContentOnAssistantMessages,
                       boolean supportsFinishReason) {
        this(allowEmptySignature, requiresReasoningContentOnAssistantMessages,
             supportsFinishReason, false, null);
    }

    /**
     * The default for models that declare nothing.
     *
     * <p>⚠️ 注意第三位是 {@code true}：{@code NONE} 的意思是「用户没写任何 compat」，
     * 不是「所有标志都关」——{@code supportsFinishReason} 的探测默认值就是开。
     * 写成 {@code false} 会让**每一条**没写 compat 的 models.json 模型静默退回容忍版。
     * 同理第十位（{@code supportsTemperature}）也是 {@code true}。</p>
     */
    public static final ModelCompat NONE = new ModelCompat(false, null, true, false, null);

    /** Flags with {@code allowEmptySignature} set, the rest left to detection. */
    public static ModelCompat of(boolean allowEmptySignature) {
        return new ModelCompat(allowEmptySignature, null, true, false, null);
    }
}
