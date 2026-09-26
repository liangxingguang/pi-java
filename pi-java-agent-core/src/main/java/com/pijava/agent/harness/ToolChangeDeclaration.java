package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolStateChanges;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.Message;
import com.pijava.agent.tool.ToolRegistry;

/**
 * pi {@code packages/agent/src/agent-loop.ts:281-333} —— <b>工具增删的生产者</b>。
 *
 * <p>{@code context.tools} 是运行时**能执行**的工具；转录里的系统消息声明的是模型**可以调用**的
 * 工具。每次请求之前，两者的差变成一条系统消息上的 {@code toolsAdded}/{@code toolsRemoved}。
 * 当 pending 里已有一条系统消息时，它的工具字段被当作**意图**而非事实，替换为「已提交转录」与
 * 「可执行集合」之间的差 —— 于是重放总是恰好得到 {@code context.tools}。否则新建一条系统消息，
 * 插在第一条非 system 的 pending 消息之前。</p>
 *
 * <p>四条纪律（pi 的 {@code declareToolChanges}，逐条对齐）：</p>
 *
 * <ol>
 *   <li><b>锚点是 pending 里最后一条 system 消息</b>（从后往前找，{@code :293-299}）；</li>
 *   <li><b>对照基准是「已提交转录 ＋ 把该消息的工具字段剥掉」</b>（{@code :300-308}）——
 *       pending 消息自己声明的工具是意图，不是事实；</li>
 *   <li>有 pending 系统消息 ⇒ 把 {@code changes} 写回它（{@code :311-315}）；
 *       {@code changes} 为空**且**它自己也没声明过工具 ⇒ <b>原对象原样返回</b>（保留调用方的对象身份）；</li>
 *   <li>没有 pending ⇒ 只在有变化时新建一条（内容为空、时间戳 {@code now}），插在第一条非 system 之前
 *       （{@code :316-320}）。</li>
 * </ol>
 *
 * <p>{@link #withToolChanges} 先丢掉 {@code toolsAdded}/{@code toolsRemoved} 再按非空条件写回
 * ⇒ <b>空列表省略键</b>（{@code :325-333}）—— java 侧由「空列表」这个表示承担
 * （{@code SessionJson} 的落线门再把它翻成键缺席）。</p>
 *
 * <p>⚠️ <b>本类的存在依赖一条 Java 独有的前提</b>：转录里得有「模型现在知道哪些工具」这个声明，
 * 否则每一次比较的基准都是空表、每次都会宣告全部工具。pi 靠
 * {@code createMutableAgentState}（{@code agent.ts:84-85}）在会话起点把「系统提示 ＋ 工具」
 * 折成一条前导系统消息来解决；java 的同一件事在
 * {@link PiLaneEngine} 起手时做（{@code docs/51 §12} 的 F11）。</p>
 */
final class ToolChangeDeclaration {

    private ToolChangeDeclaration() {}

    /**
     * pi {@code declareToolChanges} —— 返回**要注入 pending 流的消息列表**。
     *
     * <p>无变化时返回**入参本身**（同一列表对象），有变化时返回新列表 ——
     * pi 的三条返回路径（{@code pendingMessages} 原样 / {@code baseline.map(...)} /
     * 切片重拼）各自保留对象身份，这里同样如此，调用方可以靠身份判「什么都没发生」。</p>
     */
    static List<Message> declare(Context context, List<Message> pendingMessages) {
        // ① 锚点：pending 里**最后**一条系统消息
        int systemIndex = -1;
        for (int i = pendingMessages.size() - 1; i >= 0; i--) {
            if (pendingMessages.get(i) instanceof Message.SystemMessage) {
                systemIndex = i;
                break;
            }
        }
        Message.SystemMessage pending = systemIndex < 0
            ? null : (Message.SystemMessage) pendingMessages.get(systemIndex);

        // ② 基准：该消息的工具字段剥掉（那是意图，不是事实）
        var baseline = pending == null
            ? pendingMessages
            : stripToolFields(pendingMessages, systemIndex, pending);

        var executable = ToolRegistry.definitionsOf(context.tools());
        var changes = Transcripts.getToolStateChanges(
            Transcripts.getCurrentTools(concat(context.messages(), baseline)), executable);
        boolean unchanged = changes.toolsAdded().isEmpty() && changes.toolsRemoved().isEmpty();

        // ③ 有 pending：写回它；无变化且它自己也没声明过 ⇒ 原对象原样返回
        if (pending != null) {
            if (unchanged && pending.toolsAdded().isEmpty() && pending.toolsRemoved().isEmpty()) {
                return pendingMessages;
            }
            var merged = new ArrayList<Message>(baseline.size());
            for (int i = 0; i < baseline.size(); i++) {
                merged.add(i == systemIndex ? withToolChanges(pending, changes) : baseline.get(i));
            }
            return merged;
        }

        // ④ 无 pending：只在有变化时新建一条，插在第一条非 system 消息之前
        if (unchanged) {
            return pendingMessages;
        }
        var update = withToolChanges(blankSystemMessage(), changes);
        int insertIndex = 0;
        while (insertIndex < pendingMessages.size()
                && pendingMessages.get(insertIndex) instanceof Message.SystemMessage) {
            insertIndex++;
        }
        var inserted = new ArrayList<Message>(pendingMessages.size() + 1);
        inserted.addAll(pendingMessages.subList(0, insertIndex));
        inserted.add(update);
        inserted.addAll(pendingMessages.subList(insertIndex, pendingMessages.size()));
        return inserted;
    }

    /**
     * pi {@code createInitialSystemMessage}（{@code utils/transcript.ts:10-23}）＋
     * {@code createMutableAgentState} 的判据（{@code agent.ts:84-85}）—— 会话起点的前导声明。
     *
     * <p>提示与工具**都**空 ⇒ {@code null}（pi 的 {@code undefined}，「空转录保持为空」）。
     * {@code timestamp} 是 {@code 0}（{@code Instant.EPOCH}），不是 {@code now} ——
     * 与 {@code now} 的新建消息不同，pi 的起点声明是可以被认出来的。</p>
     */
    static Message.SystemMessage initialDeclaration(String systemPrompt, List<ToolDefinition> tools) {
        boolean hasPrompt = systemPrompt != null && !systemPrompt.isEmpty();
        boolean hasTools = tools != null && !tools.isEmpty();
        if (!hasPrompt && !hasTools) {
            return null;
        }
        return new Message.SystemMessage(systemPrompt == null ? "" : systemPrompt,
            Instant.EPOCH, Map.of(), hasTools ? List.copyOf(tools) : List.of(), List.of());
    }

    /**
     * pi {@code withToolChanges}（{@code :325-333}）—— 复制一条系统消息，工具字段换成
     * {@code changes}；**空列表省略键**（java 侧即空列表）。
     *
     * <p>⚠️ pi 的 {@code changes} 装的是声明形状（三键），java 的转录槽装的是
     * {@link ToolDefinition} 全形 ⇒ 这里过一次 {@link Transcripts#toToolDefinition}
     * （有损但往返稳定，理由见那个方法的 javadoc）。</p>
     */
    private static Message.SystemMessage withToolChanges(Message.SystemMessage message,
                                                         ToolStateChanges changes) {
        return new Message.SystemMessage(message.content(), message.timestamp(), message.sections(),
            changes.toolsAdded().stream().map(Transcripts::toToolDefinition).toList(),
            changes.toolsRemoved());
    }

    /** pi 的 {@code NO_CHANGES} 那一支：同一条消息，工具字段剥空。 */
    private static List<Message> stripToolFields(List<Message> messages, int systemIndex,
                                                 Message.SystemMessage pending) {
        var stripped = new ArrayList<Message>(messages);
        stripped.set(systemIndex, new Message.SystemMessage(pending.content(), pending.timestamp(),
            pending.sections(), List.of(), List.of()));
        return stripped;
    }

    /** pi {@code :316} 的 {@code {role:"system", content:"", timestamp: Date.now()}}。 */
    private static Message.SystemMessage blankSystemMessage() {
        return new Message.SystemMessage("", Instant.now(), Map.of(), List.of(), List.of());
    }

    private static List<Message> concat(List<Message> left, List<Message> right) {
        var both = new ArrayList<Message>(left.size() + right.size());
        both.addAll(left);
        both.addAll(right);
        return both;
    }
}
