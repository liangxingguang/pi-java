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
 * <p><b>包 A2 的 R5</b>（{@code docs/49 §5.5}）：系统消息对工具调用记账**透明** ——
 * 一条落在 toolCall 与其结果之间的系统消息被**扣住**，等（含合成的）结果发完再发；
 * 没有未答调用时就地放行。pi 的原话见 {@code transform-messages.ts:163-166}。</p>
 *
 * <p>⚠️ 包 A2 之前这里是 {@code else → throw "unreachable message role"}（注释写于
 * {@code Message} 只有三个变体的年代）。A1 加了 {@code SystemMessage} 之后那个分支
 * <b>可达</b>：带系统消息的请求在 Anthropic / Completions / Responses 三条车道上
 * 直接收场（实测：桩服务器零请求 + {@code IllegalStateException}）。</p>
 */
final class OrphanToolResults {

    /** pi {@code :174} 的 {@code "No result provided"} —— 逐字。 */
    private static final String NO_RESULT_TEXT = "No result provided";

    private OrphanToolResults() {}

    static List<Message> apply(List<Message> transformed) {
        var result = new ArrayList<Message>(transformed.size());
        var pendingToolCalls = new ArrayList<ContentBlock.ToolUseContent>();
        var existingToolResultIds = new HashSet<String>();
        // pi :163-166 —— 被扣住的系统消息（等结果发完再 flush）。
        var heldSystemMessages = new ArrayList<Message>();
        for (var msg : transformed) {
            if (msg instanceof Message.AssistantMessage assistant) {
                // P8（:191-193）：先关上一轮的。
                closePendingToolCalls(pendingToolCalls, existingToolResultIds,
                    heldSystemMessages, result);
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
            } else if (msg instanceof Message.SystemMessage system) {
                // P13（:216-219）：有未答调用 ⇒ 扣住；否则就地放行（前导系统消息恒走这一支
                // —— 它前面不可能有 pending，故它**留在下标 0**，Anthropic 的切头依赖这点）。
                if (pendingToolCalls.isEmpty()) {
                    result.add(system);
                } else {
                    heldSystemMessages.add(system);
                }
            } else if (msg instanceof Message.UserMessage user) {
                // P14（:222-225）：新 user 回合打断工具流 —— 先 close 再压入。
                closePendingToolCalls(pendingToolCalls, existingToolResultIds,
                    heldSystemMessages, result);
                result.add(user);
            } else {
                // sealed 穷举兜底：四个变体都已显式处理，走到这里说明 Message 又加了变体
                // （不写 default 是为了让这件事响亮，而不是被静默吞掉）。
                throw new IllegalStateException("unreachable message role");
            }
        }
        // P16（:232）：循环后再 close 一次 —— 转录以未答调用结尾时在这里合成。
        closePendingToolCalls(pendingToolCalls, existingToolResultIds, heldSystemMessages, result);
        return List.copyOf(result);
    }

    /**
     * pi {@code :167-186}：对每个 pending 中未被 {@code existingToolResultIds}
     * 覆盖的调用，压入一条合成结果（{@code "No result provided"}，{@code isError: true}）；
     * 随后清空两者。合成条不设 {@code timestamp} —— java 的
     * {@code ToolResultMessage} 没有该字段（D3：不可观察偏差，pi 的
     * {@code timestamp: Date.now()} 是本地元数据，六条车道都不读）。
     *
     * <p>⚠️ 被扣住的系统消息在这个方法的**最外层** flush（pi {@code :184-185}）——
     * 放在 {@code if (pendingToolCalls.length > 0)} **之内**是典型走样点：那样
     * 「无 pending 但有 held」时 held 永远发不出去。</p>
     */
    private static void closePendingToolCalls(List<ContentBlock.ToolUseContent> pendingToolCalls,
                                              Set<String> existingToolResultIds,
                                              List<Message> heldSystemMessages,
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
        result.addAll(heldSystemMessages);
        heldSystemMessages.clear();
    }
}
