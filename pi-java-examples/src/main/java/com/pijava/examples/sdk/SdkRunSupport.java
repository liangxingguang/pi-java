package com.pijava.examples.sdk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.SessionResult;

/**
 * docs/56 §2.2：SDK 运行型示例共享辅助——用真实 {@code AgentSession} 跑一条 prompt，
 * 订阅 message_update 的 text_delta 汇总（对齐 pi 的 subscribe→prompt→text_delta 闭环）。
 */
public final class SdkRunSupport {

    private SdkRunSupport() {}

    /**
     * 跑一条 prompt 并返回汇总的文本增量与会话结果。
     *
     * @param factory 会话工厂（调用方用 ExampleFixtures.newSession 注入 faux provider）
     */
    public static RunOutcome run(java.util.function.Supplier<com.pijava.coding.agent.core.AgentSession> factory,
                                 String prompt) {
        var subscribed = new ArrayList<String>();
        try (var session = factory.get()) {
            var sub = session.subscribe(event -> {
                if (event instanceof AgentSessionEvent.MessageUpdate mu
                        && mu.streamEvent() instanceof StreamEvent.TextDelta td) {
                    subscribed.add(td.delta());
                }
            });
            try {
                SessionResult result = session.processPrompt(prompt, PromptConfig.defaults());
                // stream() 单次消费：先聚 text_delta，再读 status/accumulatedMessages
                var deltas = new ArrayList<String>();
                result.stream().forEach(event -> {
                    if (event instanceof StreamEvent.TextDelta td) {
                        deltas.add(td.delta());
                    }
                });
                var finalText = String.join("", deltas);
                if (finalText.isEmpty()) {
                    finalText = lastAssistantText(session.accumulatedMessages());
                }
                int messageCount = session.accumulatedMessages().size();
                return new RunOutcome(result, List.copyOf(deltas), messageCount, finalText,
                    List.copyOf(subscribed));
            } finally {
                try {
                    sub.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 拼接所有 text_delta 为完整文本。 */
    public static String streamText(RunOutcome outcome) {
        return String.join("", outcome.deltas());
    }

    /** 取最后一条 assistant 消息的全文本（TextContent 拼接）。 */
    private static String lastAssistantText(java.util.List<com.pijava.ai.message.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg instanceof com.pijava.ai.message.Message.AssistantMessage a) {
                var sb = new StringBuilder();
                for (var block : a.content()) {
                    if (block instanceof com.pijava.ai.message.ContentBlock.TextContent t) {
                        sb.append(t.text());
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }

    public record RunOutcome(SessionResult result, List<String> deltas, int messageCount,
                             String finalText, List<String> subscribedDeltas) {}
}
