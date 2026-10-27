package com.pijava.ai.message;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;

/**
 * A message in a conversation with an LLM.
 *
 * <p>The four variants are user, assistant, tool-result, and system messages.
 * System messages carry the normalized system instruction and its optional
 * transcript metadata. The legacy {@code Context.systemPrompt} remains a
 * compatibility input and is not changed by this value type.</p>
 */
public sealed interface Message
    permits Message.UserMessage, Message.AssistantMessage, Message.ToolResultMessage,
            Message.SystemMessage {

    /** The role of the message author. */
    String role();

    /** The content blocks that make up this message. */
    List<ContentBlock> content();

    /**
     * A message from the end user.
     *
     * <p>原 docs/71 G1：{@code timestamp} 对齐 pi {@code UserMessage.timestamp}
     * （{@code types.ts:512}，pi 侧**必填**；Java 由兼容构造器放宽为可空 —— 本仓
     * 既有会话文件没有这个键）。</p>
     */
    record UserMessage(List<ContentBlock> content, java.time.Instant timestamp)
            implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public UserMessage {
            content = List.copyOf(content);
        }

        /**
         * 兼容构造器：无时间戳的旧数据解码路径与既有夹具。
         * <b>生产构造点必须给值</b>（{@code HarnessUtils.buildUserMessage} 等）。
         */
        public UserMessage(List<ContentBlock> content) {
            this(content, null);
        }

        @Override
        public String role() {
            return "user";
        }
    }

    /**
     * A normalized system instruction in the ordered transcript.
     *
     * <p>The text constructor stores the instruction as one text content block,
     * preserving the existing {@code Message.content()} contract. Legacy
     * callers may continue to supply {@code Context.systemPrompt}; this variant
     * is the normalized transcript representation.</p>
     */
    record SystemMessage(
        List<ContentBlock> content,
        Instant timestamp,
        Map<String, String> sections,
        List<ToolDefinition> toolsAdded,
        List<ToolReference> toolsRemoved
    ) implements Message {
        /** Normalize nullable fields and defensively copy all collections. */
        public SystemMessage {
            content = List.copyOf(content == null ? List.of() : content);
            sections = orderedSections(sections);
            toolsAdded = toolsAdded == null ? List.of() : List.copyOf(toolsAdded);
            toolsRemoved = toolsRemoved == null ? List.of() : List.copyOf(toolsRemoved);
        }

        /**
         * 保序、拒 null 键、不可变的 section 表。
         *
         * <p>pi 渲染 sections 的顺序是 {@code Object.entries}/{@code Object.values} 的
         * <b>插入顺序</b>（{@code utils/text.ts:17}、{@code :30}、{@code utils/transcript.ts:80}），
         * 而 {@code Map.copyOf} 的迭代顺序**未定义** ⇒ 用它会让「多个 section 的渲染文本」
         * 与 pi 不一致（包 A2 的 F3，{@code 原 docs/49 §4.2}）。</p>
         *
         * <p>值可以为 {@code null}：pi 用 {@code null} 表示**删除**具名段
         * （{@code types.ts:501}、{@code utils/transcript.ts:81-83}）。⚠️ 因此这张表
         * <b>不能</b>喂给 {@code Map.copyOf}/{@code Map.of}/{@code Map.entry} —— 三者在 null 值上
         * NPE（{@code Settings.unknown()} 已被咬过一次）；本类用 {@code LinkedHashMap} ＋
         * {@code Collections.unmodifiableMap}，后者允许 null 值。键仍然必填：pi 的键来自
         * {@code Object.keys}，不存在 null 键。</p>
         */
        private static Map<String, String> orderedSections(Map<String, String> sections) {
            if (sections == null || sections.isEmpty()) {
                return Map.of();
            }
            var ordered = new LinkedHashMap<String, String>(sections.size());
            for (var entry : sections.entrySet()) {
                ordered.put(Objects.requireNonNull(entry.getKey(), "section name"), entry.getValue());
            }
            return Collections.unmodifiableMap(ordered);
        }

        /**
         * Construct a system message from one legacy text instruction.
         *
         * <p>A null text becomes an <b>empty text block</b>, not an empty content list ——
         * pi 的 {@code SystemMessage.content} 是必填的 {@code string | TextContent[]}，
         * 且 {@code createInitialSystemMessage}（{@code ai/src/utils/transcript.ts:12-22}）
         * 在「只有工具、没有系统提示」时写的正是 {@code content: systemPrompt ?? ""}。
         * 空列表会让「系统消息没有内容」这句话在类型上成立、在语义上是假的，下游
         * （A2 的 provider 迁移）还得为它单开一支。</p>
         */
        public SystemMessage(String text, Instant timestamp, Map<String, String> sections,
                             List<ToolDefinition> toolsAdded, List<ToolReference> toolsRemoved) {
            this(List.of(new ContentBlock.TextContent(text == null ? "" : text)), timestamp,
                sections, toolsAdded, toolsRemoved);
        }

        @Override
        public String role() {
            return "system";
        }
    }

    /**
     * A message from the assistant (LLM) —— pi {@code AssistantMessage} 的镜像
     * （{@code packages/ai/src/types.ts:427-449}）。
     *
     * <p>{@code stopReason} is the reason the turn ended ({@code stop} /
     * {@code toolUse} / {@code length} / {@code error} / {@code aborted} /
     * {@code deferred}), or {@code null} for messages that never came from a
     * completed stream. It is the single source of truth for the reason
     * (原 docs/22 D1) — readers must not keep a parallel copy.
     * {@code deferred} is the provider handle carried only when
     * {@code stopReason} is {@code "deferred"}.</p>
     *
     * <p><b>provider 身份三元组 + 计量</b>（对齐 package 3a，原 docs/31 §8.19）：pi 的
     * 每个 provider 在构造消息时就写死 {@code api}/{@code provider}/{@code model}
     * （协议判别字面量 + {@code ModelId}），并把响应的 {@code usage} 和
     * {@code timestamp} 一起落在消息上；harness 的自动压缩估算
     * （{@code estimateContextTokens}）与溢出守卫（{@code _checkCompaction} 的
     * sameModel/stale 判断）全部从这条消息读起 —— 缺了这些字段，压缩触发时机和
     * 溢出恢复行为就和 pi 不一样。生产路径由 {@code AbstractChatApi} 在事件出口挂载、
     * 经 {@link #fromPartial} 转入终局消息。</p>
     *
     * <p>{@code rawStopReason}（⑨，原 docs/31 §8.35.14 裁决 D5）是线格上的**原值**：
     * 五条车道都在观测到它的时候就地写下（Anthropic {@code :744}、Google {@code :217}、
     * Mistral {@code :614}、completions {@code :572}、Responses {@code shared:588}／
     * {@code :747}），Google 还拿它当收尾文案的**唯一来源**（{@code :272-273}）。
     * 它**不是** {@code stopReason} 的反推 —— 前者是线格词汇
     * （{@code "max_tokens"}／{@code "MAX_TOKENS"}／{@code "tool_calls"}），
     * 后者是 pi 的 {@code StopReason}。pi 的消息整体 stringify 落盘 ⇒ 它同样是转录里的键。</p>
     *
     * <p><b>批 5（B148/B234，2026-10-15）</b>：{@code responseModel}、
     * {@code diagnostics}、{@code durationMs} 已落地为组件；消费侧（usage-totals
     * 费用分组、cache-warmer、bug-report、UI 计数）仍在各自模块另案（docs/08 §2.3）。
     * 消息级 {@code thinkingLevel}（agent-loop :409 的请求侧 pi 级别，含 "off"）
     * 同批落地，唯一盖章点是 agent loop 的终局定案（{@code PiLoopRunner}）。</p>
     *
     * <p>仍<b>不移植</b>的两个 pi 字段（逐字证据）：{@code responseId}
     * （{@code types.ts:572}）、{@code endTurn}（{@code :589-593}）——全仓只有
     * codex-responses 车道内的读点（随该车道 R5 出分母，docs/08 §2.1）与一个
     * write-only 生产者，主流七家零消费者。</p>
     * <p>{@code providerThinkingLevel}（{@code :573-574}）随批 4（B110/B99）落地；
     * {@code rawStopReason} 的两个读点都在 Google 车道的<b>生产者层</b>，
     * pi-java 也要实现那一层。</p>
     *
     * <p>可选字段全部「null ≙ pi 的 undefined」：序列化时键主动省略（Jackson 会把
     * null 写出来，JS 的 stringify 会丢 undefined —— 规则同 §8.18 的 A7）。
     * 兼容构造器服务于流式未成的消息和旧数据解码。</p>
     */
    record AssistantMessage(
        List<ContentBlock> content,
        StopReason stopReason,
        DeferredHandle deferred,
        String api,
        String provider,
        String model,
        /**
         * B148（pi {@code types.ts:571}）：provider 实际服务的模型 id；
         * 与请求 {@code model} 不同时才有值（anthropic 服务端回退／openai 路由）。
         */
        String responseModel,
        /**
         * B110（pi {@code types.ts:573-574}）：本次响应的 provider-native effort；
         * 只在 anthropic managed（{@code supportsMidConvoEffort}）消息上有值，
         * 旧盘与其余车道解码为 null（键省略）。B99 marker 重建的唯一语义输入。
         */
        String providerThinkingLevel,
        /**
         * 批 5（pi {@code types.ts:575-576}，agent-loop.ts:409）：agent loop
         * <b>请求侧</b>的 pi 级别（off/minimal/…/max），终局定案时单点盖；
         * 直调 ai（agent loop 外）与旧盘为 null。与 {@link #providerThinkingLevel}
         * （响应侧 provider-native 值）是两个字段。
         */
        String thinkingLevel,
        /** B148（pi {@code types.ts:577}）：provider/运行时诊断列表；无诊断为 null。 */
        List<AssistantMessageDiagnostic> diagnostics,
        com.pijava.ai.Usage usage,
        java.time.Instant timestamp,
        String errorMessage,
        String rawStopReason,
        /** B234（pi {@code types.ts:595}）：响应单调钟毫秒数；只在终局消息上有值。 */
        Long durationMs
    ) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public AssistantMessage {
            content = List.copyOf(content);
            diagnostics = diagnostics == null ? null : List.copyOf(diagnostics);
        }

        /**
         * Compatibility constructor: every pre-existing call site, and data
         * written before stop reasons were recorded, carries neither field.
         */
        public AssistantMessage(List<ContentBlock> content) {
            this(content, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        }

        /** Compatibility constructor for the (content, stopReason, deferred) shape. */
        public AssistantMessage(List<ContentBlock> content, StopReason stopReason,
                                DeferredHandle deferred) {
            this(content, stopReason, deferred,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
        }

        /**
         * 批 5 之前的<b>规范</b>十参构造：{@code responseModel/providerThinkingLevel/
         * thinkingLevel/diagnostics/durationMs} 五件缺席（≙ pi 的 {@code undefined}
         * ⇒ 键省略）。保留它使批 5 之前的全部构造点（合成的中止/摘要消息、
         * 存量夹具）零改签；有值的消息走十五参全参构造（fromPartial、JSONL 解码）。
         */
        public AssistantMessage(List<ContentBlock> content, StopReason stopReason,
                                DeferredHandle deferred,
                                String api, String provider, String model,
                                com.pijava.ai.Usage usage, java.time.Instant timestamp,
                                String errorMessage, String rawStopReason) {
            this(content, stopReason, deferred,
                api, provider, model, null, null, null, null,
                usage, timestamp, errorMessage, rawStopReason, null);
        }

        /**
         * 批 4 的十一参构造（B110：{@code providerThinkingLevel} 在、批 5 四件缺席）：
         * 批 4 夹具与 B99 重放构造点用它，零改签；新字段全 null。
         */
        public AssistantMessage(List<ContentBlock> content, StopReason stopReason,
                                DeferredHandle deferred,
                                String api, String provider, String model,
                                String providerThinkingLevel,
                                com.pijava.ai.Usage usage, java.time.Instant timestamp,
                                String errorMessage, String rawStopReason) {
            this(content, stopReason, deferred,
                api, provider, model, null, providerThinkingLevel, null, null,
                usage, timestamp, errorMessage, rawStopReason, null);
        }

        /**
         * 从流式 partial 快照投影出终局消息 —— pi 的 partial 与终局<b>同一个
         * 对象形状</b>（{@code assistant-message-frame.ts:77-92} 的
         * {@code cloneStartMessage} 逐字段携带），所以投影必须全字段，
         * 不能只搬 content/stopReason（那是 3a 之前的丢点）。
         *
         * <p>{@code thinkingLevel} 恒为 null：partial 流中不盖该字段
         * （pi 只在 agent-loop 终局 {@code Object.assign}），它由 loop 层
         * 在投影之后经 {@link #withThinkingLevel} 单点盖上。</p>
         */
        public static AssistantMessage fromPartial(
                com.pijava.ai.message.AssistantMessage partial) {
            return new AssistantMessage(
                partial.content(),
                partial.stopReason(),
                null,
                partial.api(),
                partial.provider(),
                partial.model(),
                partial.responseModel(),
                partial.providerThinkingLevel(),
                null,
                partial.diagnostics(),
                usageOf(partial.usage()),
                partial.timestamp(),
                partial.errorMessage(),
                partial.rawStopReason(),
                partial.durationMs());
        }

        /**
         * 把 partial 上的 {@link com.pijava.ai.stream.StreamEvent.UsageInfo} 归一为
         * 完整 {@link com.pijava.ai.Usage}：有全量分解用全量（含 cache/cost），只有
         * input/output 计数的合成（cache 0、cost 零）。
         *
         * <p><b>无 UsageInfo ⇒ 兜零值对象，不返回 null</b>（包⑨ B41，{@code 原 docs/36}）。
         * pi 的 {@code AssistantMessage.usage: Usage} 是<b>必填</b>
         * （{@code ai/src/types.ts:439}），而且 pi <b>没有任何一条路径</b>会产出没 usage
         * 的助手消息 —— 11 个 provider 适配器、{@code lazy.ts} 的装配失败、{@code faux}、
         * 中止/错误路（{@code agent.ts:511-527} 的 {@code EMPTY_USAGE}、
         * {@code recovery.ts:28-40} 的 {@code ZERO_USAGE}）<b>全都显式给零值</b>；
         * <b>落盘的助手条目也带 usage</b>（{@code session-manager.ts:1029-1056} 整条
         * stringify）。此前这里返回 null，经 RPC 的 {@code NON_NULL} mixin 与 web 的
         * {@code WebWireJson:75} 双重 null 判断 ⇒ <b>键从线上整段消失</b>。</p>
         *
         * <p>⚠️ <b>只管助手消息</b>：pi 的 {@code ToolResultMessage.usage} 是<b>可选</b>的
         * （{@code types.ts:459}），工具没报用量时线上确实没有该键
         * （{@code createErrorToolResult} 根本不带）⇒ 工具结果那边<b>保持 null ⇒ 省略</b>，
         * 兜零是引入偏差（{@code 原 docs/36 §3-R4}）。</p>
         */
        private static com.pijava.ai.Usage usageOf(
                com.pijava.ai.stream.StreamEvent.UsageInfo info) {
            if (info == null) {
                return com.pijava.ai.Usage.of(0, 0);
            }
            if (info.usage() != null) {
                return info.usage();
            }
            return new com.pijava.ai.Usage(info.inputTokens(), info.outputTokens(), 0, 0,
                null, null, info.inputTokens() + info.outputTokens(),
                com.pijava.ai.Usage.Cost.zero());
        }

        /**
         * The same message with a different {@code stopReason} (all other
         * fields preserved — this is a rewrite, not a reconstruction).
         *
         * <p>Used by the loop to record a turn that was cut short: an abort the
         * provider did not report itself still has to land as {@code aborted},
         * or the interrupted response would be projected into later requests as
         * an ordinary answer.</p>
         */
        public AssistantMessage withStopReason(StopReason newStopReason) {
            return new AssistantMessage(content, newStopReason, deferred,
                api, provider, model,
                responseModel, providerThinkingLevel, thinkingLevel, diagnostics,
                usage, timestamp, errorMessage, rawStopReason, durationMs);
        }

        /**
         * 批 5（pi {@code agent-loop.ts:409}）：盖 agent loop <b>请求侧</b>
         * pi 级别（含 {@code "off"}）的全字段复制；只在终局定案后单点调用。
         */
        public AssistantMessage withThinkingLevel(String newThinkingLevel) {
            return new AssistantMessage(content, stopReason, deferred,
                api, provider, model,
                responseModel, providerThinkingLevel, newThinkingLevel, diagnostics,
                usage, timestamp, errorMessage, rawStopReason, durationMs);
        }

        @Override
        public String role() {
            return "assistant";
        }
    }

    /**
     * A tool execution result message (pi {@code ToolResultMessage}，
     * {@code packages/ai/src/types.ts:452-468}；由 {@code createToolResultMessage}
     * 从结果对象派生，{@code agent-loop.ts:784-797})。
     *
     * <p>{@code details} / {@code usage} 是结果树上同名对象的<b>原样转发</b>
     * （pi 的 {@code finalized.result.details}/{@code .usage}）—— pi 的
     * {@code usage} 是 ai 共享的 {@code Usage} 类型，而 pi-java 的工具 usage 长在
     * {@code ToolResult.UsageInfo}（agent 模块），ai 不能反向依赖 agent，故两者
     * 都取 {@code Object}（{@code null} ≙ pi 的 undefined，线上/库里键缺席）。
     * {@code addedToolNames} 对齐 pi 的「仅有内容才带上」：这里统一存非 null 列表，
     * 空表在序列化时省略。</p>
     *
     * <p><b>provider 投影不读这三项</b> —— pi 的各协议适配器只从 {@code content}
     * 构造工具结果块（{@code details} 是给 UI/日志的结构化载荷，不进模型上下文）。</p>
     *
     * <p>A-20（原 docs/68 J9/J10，2026-10-02）：{@code details} <b>主流有生产者</b>
     * （edit 每次成功必放、bash/read 截断时放）；{@code usage} 两侧主流同无生产者，
     * 留形状不接线。</p>
     *
     * <p>{@code role()} 的 {@code "tool"} 是 pi-java 持久化方言（JSONL/SQLite 里
     * 写作 {@code "tool"}/{@code toolUseId}）；对外的 WS wire 由
     * {@code WebWireJson} 转回 pi 的 {@code "toolResult"}/{@code toolCallId} 拼写。
     * 别把 {@code role()} 改成 {@code "toolResult"} —— 那会连带改破既有会话文件。</p>
     */
    record ToolResultMessage(String toolUseId, String toolName,
                             List<ContentBlock> content,
                             Object details, Object usage, List<String> addedToolNames,
                             boolean isError, java.time.Instant timestamp,
                             /**
                              * B234（pi {@code types.ts:635}）：工具 {@code execute()}
                              * 本体的单调钟毫秒数（不含校验/审批/hook）；未执行的调用
                              * 与旧数据为 null（≙ pi 的 undefined，键省略）。
                              */
                             Long durationMs,
                             /**
                              * 批 27（B162；pi {@code types.ts:630}）：本工具经
                              * {@code ctx.executeTool()} 发起的嵌套调用记录。只为会话
                              * 记录保留，<b>不发给模型</b>（pi 注释逐字 "Kept for the
                              * session record; not sent to the model"）——provider 投影
                              * 只读 {@code content}，天然不携带。null ≙ pi 的缺席。
                              */
                             NestedToolCalls nestedCalls) implements Message {
        /** Compact constructor: defensive copies; empty {@code addedToolNames} ≙ pi 的省略。 */
        public ToolResultMessage {
            content = List.copyOf(content);
            addedToolNames = addedToolNames == null ? List.of() : List.copyOf(addedToolNames);
        }

        /**
         * Compatibility constructor for messages without a structured payload
         * (pi 的对象字面量里这些字段本就可选)。生产路径一律走全参构造，从结果对象
         * 转发 —— 见 {@code PiToolRunner.toOutcome}。
         *
         * <p>原 docs/71 G1：时间戳同理 —— 本构造器给 {@code null}（旧数据/夹具），
         * <b>生产构造点必须用全参构造给值</b>。</p>
         */
        public ToolResultMessage(String toolUseId, String toolName,
                                 List<ContentBlock> content, boolean isError) {
            this(toolUseId, toolName, content, null, null, List.of(), isError, null, null);
        }

        /**
         * 批 5 之前的规范八参构造：{@code durationMs} 缺席（未执行的合成结果、
         * 截断/中止消息、存量构造点零改签用）。
         */
        public ToolResultMessage(String toolUseId, String toolName,
                                 List<ContentBlock> content,
                                 Object details, Object usage, List<String> addedToolNames,
                                 boolean isError, java.time.Instant timestamp) {
            this(toolUseId, toolName, content, details, usage, addedToolNames,
                isError, timestamp, null);
        }

        /**
         * 批 27 之前的规范九参构造：{@code nestedCalls} 缺席（无嵌套调用的结果、
         * 存量构造点零改签用）。
         */
        public ToolResultMessage(String toolUseId, String toolName,
                                 List<ContentBlock> content,
                                 Object details, Object usage, List<String> addedToolNames,
                                 boolean isError, java.time.Instant timestamp,
                                 Long durationMs) {
            this(toolUseId, toolName, content, details, usage, addedToolNames,
                isError, timestamp, durationMs, null);
        }

        /**
         * pi 的挂载点是不可变对象的字段赋值（{@code agent-session.ts:1106-1112}
         * {@code message.nestedCalls = summary.calls}）；java record 不可变，
         * 对位为 wither——<b>必须先于落盘调用</b>（宿主缝在 message_start 事件臂）。
         */
        public ToolResultMessage withNestedCalls(NestedToolCalls calls) {
            return new ToolResultMessage(toolUseId, toolName, content, details, usage,
                addedToolNames, isError, timestamp, durationMs, calls);
        }

        /** 嵌套调用的 usage 并入（pi 同点 {@code message.usage = combineUsage(...)}）。 */
        public ToolResultMessage withUsage(Object mergedUsage) {
            return new ToolResultMessage(toolUseId, toolName, content, details, mergedUsage,
                addedToolNames, isError, timestamp, durationMs, nestedCalls);
        }

        @Override
        public String role() {
            return "tool";
        }
    }
}
