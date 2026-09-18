package com.pijava.ai.message;

import java.util.List;

/**
 * A message in a conversation with an LLM.
 *
 * <p><b>恰好三个变体</b>，与 pi 的 {@code Message} 联合类型一致
 * （{@code packages/ai/src/types.ts:470}：{@code UserMessage | AssistantMessage |
 * ToolResultMessage}）。<b>没有 system 角色</b> —— 系统提示不属于消息列表，
 * 它是 {@code Context.systemPrompt}，由各 provider 适配层映射到自己的 system 字段。</p>
 */
public sealed interface Message {

    /** The role of the message author. */
    String role();

    /** The content blocks that make up this message. */
    List<ContentBlock> content();

    /** A message from the end user. */
    record UserMessage(List<ContentBlock> content) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public UserMessage {
            content = List.copyOf(content);
        }

        @Override
        public String role() {
            return "user";
        }
    }

    /**
     * A message from the assistant (LLM) —— pi {@code AssistantMessage} 的镜像
     * （{@code packages/ai/src/types.ts:427-449}）。
     *
     * <p>{@code stopReason} is the reason the turn ended ({@code stop} /
     * {@code tool_use} / {@code length} / {@code error} / {@code aborted} /
     * {@code deferred}), or {@code null} for messages that never came from a
     * completed stream. It is the single source of truth for the reason
     * (docs/22 D1) — readers must not keep a parallel copy.
     * {@code deferred} is the provider handle carried only when
     * {@code stopReason} is {@code "deferred"}.</p>
     *
     * <p><b>provider 身份三元组 + 计量</b>（对齐 package 3a，docs/31 §8.19）：pi 的
     * 每个 provider 在构造消息时就写死 {@code api}/{@code provider}/{@code model}
     * （协议判别字面量 + {@code ModelId}），并把响应的 {@code usage} 和
     * {@code timestamp} 一起落在消息上；harness 的自动压缩估算
     * （{@code estimateContextTokens}）与溢出守卫（{@code _checkCompaction} 的
     * sameModel/stale 判断）全部从这条消息读起 —— 缺了这些字段，压缩触发时机和
     * 溢出恢复行为就和 pi 不一样。生产路径由 {@code AbstractChatApi} 在事件出口挂载、
     * 经 {@link #fromPartial} 转入终局消息。</p>
     *
     * <p>{@code rawStopReason}（⑨，docs/31 §8.35.14 裁决 D5）是线格上的**原值**：
     * 五条车道都在观测到它的时候就地写下（Anthropic {@code :744}、Google {@code :217}、
     * Mistral {@code :614}、completions {@code :572}、Responses {@code shared:588}／
     * {@code :747}），Google 还拿它当收尾文案的**唯一来源**（{@code :272-273}）。
     * 它**不是** {@code stopReason} 的反推 —— 前者是线格词汇
     * （{@code "max_tokens"}／{@code "MAX_TOKENS"}／{@code "tool_calls"}），
     * 后者是 pi 的 {@code StopReason}。pi 的消息整体 stringify 落盘 ⇒ 它同样是转录里的键。</p>
     *
     * <p>pi 类型上的 {@code responseModel}/{@code responseId}/
     * {@code providerThinkingLevel}/{@code diagnostics}/
     * {@code endTurn} 在对齐面（packages/agent/src）没有任何消费者（grep 全数命中的
     * 只有 prompt-templates/skills 的同名局部量），故不移植；哪天 pi 的消费进
     * 对齐面，清点时重开。（⚠️ {@code rawStopReason} 原本也在这张清单上，§8.35.14 第五节
     * 的复核把它移出：pi 有**两个读点**，都在 Google 车道的**生产者层**——「对齐面没有消费者」
     * 的措辞是对的，但「故不移植」的结论错了，因为那一层 pi-java 也要实现。）</p>
     *
     * <p>可选字段全部「null ≙ pi 的 undefined」：序列化时键主动省略（Jackson 会把
     * null 写出来，JS 的 stringify 会丢 undefined —— 规则同 §8.18 的 A7）。
     * 兼容构造器服务于流式未成的消息和旧数据解码。</p>
     */
    record AssistantMessage(
        List<ContentBlock> content,
        String stopReason,
        DeferredHandle deferred,
        String api,
        String provider,
        String model,
        com.pijava.ai.Usage usage,
        java.time.Instant timestamp,
        String errorMessage,
        String rawStopReason
    ) implements Message {
        /** Compact constructor that defensively copies the content blocks. */
        public AssistantMessage {
            content = List.copyOf(content);
        }

        /**
         * Compatibility constructor: every pre-existing call site, and data
         * written before stop reasons were recorded, carries neither field.
         */
        public AssistantMessage(List<ContentBlock> content) {
            this(content, null, null, null, null, null, null, null, null, null);
        }

        /** Compatibility constructor for the pre-3a (content, stopReason, deferred) shape. */
        public AssistantMessage(List<ContentBlock> content, String stopReason, DeferredHandle deferred) {
            this(content, stopReason, deferred, null, null, null, null, null, null, null);
        }

        /**
         * 从流式 partial 快照投影出终局消息 —— pi 的 partial 与终局<b>同一个
         * 对象形状</b>（{@code assistant-message-frame.ts:77-92} 的
         * {@code cloneStartMessage} 逐字段携带），所以投影必须全字段，
         * 不能只搬 content/stopReason（那是 3a 之前的丢点）。
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
                usageOf(partial.usage()),
                partial.timestamp(),
                partial.errorMessage(),
                partial.rawStopReason());
        }

        /**
         * 把 partial 上的 {@link com.pijava.ai.stream.StreamEvent.UsageInfo} 归一为
         * 完整 {@link com.pijava.ai.Usage}：有全量分解用全量（含 cache/cost），只有
         * input/output 计数的合成（cache 0、cost 零）；无 UsageInfo ⇒ null（键省略）。
         */
        private static com.pijava.ai.Usage usageOf(
                com.pijava.ai.stream.StreamEvent.UsageInfo info) {
            if (info == null) {
                return null;
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
        public AssistantMessage withStopReason(String newStopReason) {
            return new AssistantMessage(content, newStopReason, deferred,
                api, provider, model, usage, timestamp, errorMessage, rawStopReason);
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
     * <p>{@code role()} 的 {@code "tool"} 是 pi-java 持久化方言（JSONL/SQLite 里
     * 写作 {@code "tool"}/{@code toolUseId}）；对外的 WS wire 由
     * {@code WebWireJson} 转回 pi 的 {@code "toolResult"}/{@code toolCallId} 拼写。
     * 别把 {@code role()} 改成 {@code "toolResult"} —— 那会连带改破既有会话文件。</p>
     */
    record ToolResultMessage(String toolUseId, String toolName,
                             List<ContentBlock> content,
                             Object details, Object usage, List<String> addedToolNames,
                             boolean isError) implements Message {
        /** Compact constructor: defensive copies; empty {@code addedToolNames} ≙ pi 的省略。 */
        public ToolResultMessage {
            content = List.copyOf(content);
            addedToolNames = addedToolNames == null ? List.of() : List.copyOf(addedToolNames);
        }

        /**
         * Compatibility constructor for messages without a structured payload
         * (pi 的对象字面量里这些字段本就可选)。生产路径一律走全参构造，从结果对象
         * 转发 —— 见 {@code PiToolRunner.toOutcome}。
         */
        public ToolResultMessage(String toolUseId, String toolName,
                                 List<ContentBlock> content, boolean isError) {
            this(toolUseId, toolName, content, null, null, List.of(), isError);
        }

        @Override
        public String role() {
            return "tool";
        }
    }
}
