package com.pijava.ai.protocol;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * Anthropic 线程请求的线上形态（pi {@code anthropic-messages.ts:259-262}、{@code :357-359}、
 * {@code :376-382}）。
 *
 * <p>三态：不发 {@code thread}（端点不支持或 {@code disableContinuation}）／
 * {@link Type#CREATE} 完整请求 ＋ {@code thread:{type:"create"}}／
 * {@link Type#CONTINUE} 省略 {@code system} 与 {@code tools}、只发尾部消息 ＋
 * {@code thread:{type:"continue", previous_message_id}}。</p>
 */
final class AnthropicThreadWire {

    private AnthropicThreadWire() {
    }

    /** 线程请求三态（{@code null} ≙ 不发字段）。 */
    record ThreadRequest(Type type, int prefixCount, String replyId) {

        /** {@code thread} 的取值。 */
        enum Type { CREATE, CONTINUE }

        /** 首轮：完整请求 ＋ {@code create}。 */
        static ThreadRequest create() {
            return new ThreadRequest(Type.CREATE, 0, null);
        }

        /** 续轮：只发 {@code prefixCount} 之后的尾行 ＋ {@code continue}。 */
        static ThreadRequest continueFrom(int prefixCount, String replyId) {
            return new ThreadRequest(Type.CONTINUE, prefixCount, replyId);
        }

        boolean continuing() {
            return type == Type.CONTINUE;
        }
    }

    /**
     * 消息侧结果：是否含 preferred-strict 工具 ＋ **完整**逻辑请求的消息条数 ＋
     * **断点前**的逐行指纹（线程前缀哈希的原料，{@code cache_control} 尚未挂上，
     * 故标记搬移不会改变指纹）。
     */
    record MessagesResult(boolean hasPreferredStrictTools, int messageCount,
                          java.util.List<String> rowKeys) {
    }

    /**
     * 写入 {@code thread} 字段（pi 的 {@code {...rest, thread}} 落线；顺序上它是
     * body 的未知键，走 additionalBodyProperty）。
     *
     * @param builder 请求构造器
     * @param thread  三态；{@code null} 表示不发
     */
    static void apply(MessageCreateParams.Builder builder, ThreadRequest thread) {
        if (thread == null) {
            return;
        }
        var tree = ObjectMappers.jsonMapper().createObjectNode();
        tree.put("type", thread.continuing() ? "continue" : "create");
        if (thread.continuing()) {
            tree.put("previous_message_id", thread.replyId());
        }
        builder.putAdditionalBodyProperty("thread", JsonValue.from(tree));
    }
}
