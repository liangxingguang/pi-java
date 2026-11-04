package com.pijava.ai.provider.faux;

import java.util.Objects;

import com.pijava.ai.message.AssistantMessage;

/**
 * docs/20 §3.2：队列里的一项（pi {@code FauxResponseStep}，{@code providers/faux.ts:114}）。
 *
 * <p>它要么是一条完整的助手消息，要么是一个按请求现场求值的工厂。</p>
 */
public sealed interface FauxResponseStep {

    /** 直接预置的完整响应。 */
    record Message(AssistantMessage message) implements FauxResponseStep {
        /** Compact constructor: the message is required. */
        public Message {
            Objects.requireNonNull(message, "message");
        }
    }

    /** 按请求上下文现场求值的响应。 */
    record Factory(FauxResponseFactory factory) implements FauxResponseStep {
        /** Compact constructor: the factory is required. */
        public Factory {
            Objects.requireNonNull(factory, "factory");
        }
    }

    /** 用一条预置消息造一个响应步。 */
    static FauxResponseStep of(AssistantMessage message) {
        return new Message(message);
    }

    /** 用工厂造一个响应步。 */
    static FauxResponseStep of(FauxResponseFactory factory) {
        return new Factory(factory);
    }
}
