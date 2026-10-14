package com.pijava.ai.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pijava.ai.stream.StreamEvent;

/**
 * A streaming snapshot of an assistant message being built.
 *
 * <p>Unlike {@link Message.AssistantMessage} (which is the final static
 * LLM response), this record is updated continuously as each
 * {@link StreamEvent} arrives. Every {@code StreamEvent} carries its
 * current {@code partial} snapshot, enabling consumers (notably
 * {@code AgentHarness}) to simply replace the last message in context
 * rather than accumulating deltas manually.</p>
 *
 * <p><b>3a：partial 与终局同形状携带 provider 身份 + 计量</b>（pi 的 partial 本就
 * 是完整 {@code AssistantMessage}，见 {@code assistant-message-frame.ts:77-92}）。
 * {@code api}/{@code provider}/{@code model}/{@code timestamp} 由
 * {@code AbstractChatApi} 在事件出口统一挂载（adapter 的协议判别字面量 +
 * {@code StreamRequest.model()}），conformance 桩自己挂（对齐 run.test.ts 的
 * {@code createAssistantMessage}）。未挂载 ⇒ null，终局投影原样带过。</p>
 *
 * <p>This type lives in {@code pi-java-ai} (not {@code pi-java-agent-core})
 * so that protocol adapters can produce it without a reverse dependency.</p>
 *
 * @param id           unique message identifier (generated on first event)
 * @param content      current content blocks (text, thinking, tool calls)
 * @param usage        token usage so far (may be null early in the stream)
 * @param stopReason   final stop reason（pi {@code StopReason} 闭集枚举：STOP/TOOL_USE/
 *                     LENGTH/ERROR/ABORTED/DEFERRED），流未成形时为 {@code null}。
 *                     ⚠️ **没有 "end_turn"** —— 那是 Anthropic 线格上的取值，pi 在
 *                     {@code mapStopReason}（{@code anthropic-messages.ts:1467}）里就翻成了
 *                     {@code "stop"}，故它从不进消息（原 docs/31 §8.35.14 裁决 D2／提交 ③）。
 * @param api          provider protocol discriminator (pi {@code Model.api}),
 *                     or null when the producer does not know it
 * @param provider     provider name (pi {@code AssistantMessage.provider}), or null
 * @param model        model id (pi {@code AssistantMessage.model}), or null
 * @param providerThinkingLevel B110（pi {@code types.ts:565}）：本次响应实际使用的
 *                     provider-native effort 字面量（{@code low|medium|high|xhigh|max}）。
 *                     只由 anthropic-messages 车道在
 *                     {@code compat.supportsMidConvoEffort === true} 时盖
 *                     （值＝{@code options.effort ?? "high"}，请求发出前定死、start 帧即在）；
 *                     其余车道与历史消息为 null。唯一语义消费者是 B99 的历史 effort marker 重建。
 * @param timestamp    creation instant (pi {@code timestamp}: number of epoch ms), or null
 * @param errorMessage error-path message text (pi {@code errorMessage}), or null
 * @param rawStopReason the **unmapped** wire stop reason (pi {@code rawStopReason},
 *                     {@code types.ts:443}), or null when the lane observed none.
 *                     <p>⑨（D5）：它是线格的**原值**（Anthropic {@code "max_tokens"}、
 *                     Google {@code "MAX_TOKENS"}、completions {@code "tool_calls"}、
 *                     Mistral {@code "model_length"}、Responses 的复合量
 *                     {@code "incomplete.max_output_tokens"}），**不是** {@link #stopReason}
 *                     的反推 —— 五条车道都写作 {@code output.rawStopReason = <线格取值>}，
 *                     Google 还拿它当收尾文案的唯一来源（{@code google-generative-ai.ts:272}）。</p>
 */
public record AssistantMessage(
    String id,
    List<ContentBlock> content,
    StreamEvent.UsageInfo usage,
    StopReason stopReason,
    String api,
    String provider,
    String model,
    String providerThinkingLevel,
    Instant timestamp,
    String errorMessage,
    String rawStopReason
) {
    /** Compact constructor that defensively copies the content blocks. */
    public AssistantMessage {
        content = List.copyOf(content);
    }

    /** Constructor for pre-3a snapshots without identity fields. */
    public AssistantMessage(
            String id, List<ContentBlock> content, StreamEvent.UsageInfo usage,
            StopReason stopReason) {
        this(id, content, usage, stopReason, null, null, null, null, null, null, null);
    }

    /**
     * B110 之前的十参形状：{@code providerThinkingLevel} 缺席（null）。
     * 供批 4 之前的存量夹具零改签；生产路径的盖章经
     * {@link #withProviderThinkingLevel(String)}。
     */
    public AssistantMessage(
            String id, List<ContentBlock> content, StreamEvent.UsageInfo usage,
            StopReason stopReason,
            String api, String provider, String model,
            Instant timestamp, String errorMessage, String rawStopReason) {
        this(id, content, usage, stopReason,
            api, provider, model, null, timestamp, errorMessage, rawStopReason);
    }

    /** Create an empty initial snapshot. */
    public static AssistantMessage empty() {
        return new AssistantMessage(
            UUID.randomUUID().toString(),
            List.of(),
            null,
            null
        );
    }

    /** Create a copy with a new id. */
    public AssistantMessage withId(String newId) {
        return new AssistantMessage(newId, content, usage, stopReason,
            api, provider, model, providerThinkingLevel,
            timestamp, errorMessage, rawStopReason);
    }

    /** Create a copy with updated content blocks. */
    public AssistantMessage withContent(List<ContentBlock> newContent) {
        return new AssistantMessage(id, newContent, usage, stopReason,
            api, provider, model, providerThinkingLevel,
            timestamp, errorMessage, rawStopReason);
    }

    /** Create a copy with updated usage. */
    public AssistantMessage withUsage(StreamEvent.UsageInfo newUsage) {
        return new AssistantMessage(id, content, newUsage, stopReason,
            api, provider, model, providerThinkingLevel,
            timestamp, errorMessage, rawStopReason);
    }

    /** Create a copy with an updated stop reason. */
    public AssistantMessage withStopReason(StopReason newStopReason) {
        return new AssistantMessage(id, content, usage, newStopReason,
            api, provider, model, providerThinkingLevel,
            timestamp, errorMessage, rawStopReason);
    }

    /** Create a copy with the provider identity + creation time attached. */
    public AssistantMessage withIdentity(String newApi, String newProvider, String newModel,
                                         Instant newTimestamp) {
        return new AssistantMessage(id, content, usage, stopReason,
            newApi, newProvider, newModel, providerThinkingLevel,
            newTimestamp, errorMessage, rawStopReason);
    }

    /**
     * Create a copy with the provider-native effort stamped (B110,
     * pi {@code anthropic-messages.ts:581-588})。null 等价于不盖 ——
     * 身份缝对非 managed 车道调它传 null，保持键缺席（与 pi 的展开省略同形）。
     */
    public AssistantMessage withProviderThinkingLevel(String newProviderThinkingLevel) {
        return new AssistantMessage(id, content, usage, stopReason,
            api, provider, model, newProviderThinkingLevel,
            timestamp, errorMessage, rawStopReason);
    }

    /** Create a copy with an error-path message text attached (pi {@code errorMessage}). */
    public AssistantMessage withErrorMessage(String newErrorMessage) {
        return new AssistantMessage(id, content, usage, stopReason,
            api, provider, model, providerThinkingLevel,
            timestamp, newErrorMessage, rawStopReason);
    }

    /**
     * Create a copy carrying the line's **unmapped** stop reason (pi
     * {@code rawStopReason}, {@code types.ts:443}).
     *
     * <p>⑨（D5）：pi 的五条车道都在**观测到线格取值的那一刻**就地写这个字段
     * （{@code anthropic:744}／{@code google:217}／{@code mistral:614}／
     * {@code completions:572}／{@code responses-shared:588}），故它从那一刻起的
     * **每一帧** partial 上都在 —— 车道在同一个位置调本方法，形状即同。</p>
     */
    public AssistantMessage withRawStopReason(String newRawStopReason) {
        return new AssistantMessage(id, content, usage, stopReason,
            api, provider, model, providerThinkingLevel,
            timestamp, errorMessage, newRawStopReason);
    }
}
