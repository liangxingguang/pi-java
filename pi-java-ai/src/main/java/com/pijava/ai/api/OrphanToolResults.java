package com.pijava.ai.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * pi {@code transform-messages.ts:158-232} 的第二遍：重建消息序列，
 * 为孤儿 toolCall 压入合成结果，error/aborted 助手整条跳过。
 *
 * <p>⚠️ pi 的 {@code heldSystemMessages}（P13）在 java 上不存在 ——
 * {@code Message} 的 sealed 穷举里没有 system 角色（D5/§8⑤：不实现、登记，
 * 随 {@code SystemMessage} 包一起补，docs/47 §7-R5）。</p>
 */
final class OrphanToolResults {

    /** pi {@code :174} 的 {@code "No result provided"} —— 逐字。 */
    private static final String NO_RESULT_TEXT = "No result provided";

    private OrphanToolResults() {}

    static List<Message> apply(List<Message> transformed) {
        var result = new ArrayList<Message>(transformed.size());
        var pendingToolCalls = new ArrayList<ContentBlock.ToolUseContent>();
        var existingToolResultIds = new HashSet<String>();
        for (var msg : transformed) {
            if (msg instanceof Message.AssistantMessage assistant) {
                // P8（:191-193）：先关上一轮的。
                closePendingToolCalls(pendingToolCalls, existingToolResultIds, result);
                // P9（:201-203）：error/aborted 整条不进 result、不更新 pending。
                // continue 发生在 close 之后 ⇒ error 消息前面的孤儿照样被合成，
                // error 消息自己的 toolCall 不进 pending。
                if ("error".equals(assistant.stopReason())
                        || "aborted".equals(assistant.stopReason())) {
                    continue;
                }
                var toolCalls = assistant.content().stream()
                    .filter(ContentBlock.ToolUseContent.class::isInstance)
                    .map(ContentBlock.ToolUseContent.class::cast)
                    .toList();
                if (!toolCalls.isEmpty()) {
                    // P10（:206-210）：非空才触碰 —— 重置（不是累积）；空则不触碰。
                    pendingToolCalls = new ArrayList<>(toolCalls);
                    existingToolResultIds = new HashSet<>();
                }
                result.add(assistant); // P11（:212）
            } else if (msg instanceof Message.ToolResultMessage toolResult) {
                // P12（:213-215）：真结果到场，先记账再压入。
                existingToolResultIds.add(toolResult.toolUseId());
                result.add(toolResult);
            } else if (msg instanceof Message.UserMessage user) {
                // P14（:222-225）：新 user 回合打断工具流 —— 先 close 再压入。
                closePendingToolCalls(pendingToolCalls, existingToolResultIds, result);
                result.add(user);
            } else {
                // sealed 穷举兜底：Message 只有 3 个变体，此分支不可达
                // （P13 的 system 角色在 java 上不存在，D5 —— 留空即投机骨架，故不写）。
                throw new IllegalStateException("unreachable message role");
            }
        }
        // P16（:232）：循环后再 close 一次 —— 转录以未答调用结尾时在这里合成。
        closePendingToolCalls(pendingToolCalls, existingToolResultIds, result);
        return List.copyOf(result);
    }

    /**
     * pi {@code :167-186}：对每个 pending 中未被 {@code existingToolResultIds}
     * 覆盖的调用，压入一条合成结果（{@code "No result provided"}，{@code isError: true}）；
     * 随后清空两者。合成条不设 {@code timestamp} —— java 的
     * {@code ToolResultMessage} 没有该字段（D3：不可观察偏差，pi 的
     * {@code timestamp: Date.now()} 是本地元数据，六条车道都不读）。
     */
    private static void closePendingToolCalls(List<ContentBlock.ToolUseContent> pendingToolCalls,
                                              Set<String> existingToolResultIds,
                                              List<Message> result) {
        if (!pendingToolCalls.isEmpty()) {
            for (var tc : pendingToolCalls) {
                if (!existingToolResultIds.contains(tc.id())) {
                    result.add(new Message.ToolResultMessage(tc.id(), tc.name(),
                        List.of(new ContentBlock.TextContent(NO_RESULT_TEXT)), true));
                }
            }
            pendingToolCalls.clear();
            existingToolResultIds.clear();
        }
    }
}
