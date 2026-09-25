package com.pijava.ai.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;

/**
 * pi {@code packages/ai/src/utils/transcript.ts} 的重放一半。
 *
 * <p>包 A2（{@code docs/49}）落的是 provider 消费侧需要的六个函数：车道不再各自读
 * {@code request.systemPrompt()} / {@code request.tools()}，而是从这里取「前导系统消息的
 * 文本」与「重放后的工具表」。归一那一半（{@code normalizeContext}）在
 * {@link ContextNormalizer}。</p>
 *
 * <p>⚠️ 本类**不含** {@code getToolStateChanges} / {@code declarationsEqual} /
 * {@code hasToolRedefinitions} / {@code hasNonAdditiveToolChanges} / {@code resolveTranscriptTools}
 * —— 那些是包 A3（工具增删状态线）的，本包不造投机骨架。</p>
 */
public final class Transcripts {

    private Transcripts() {}

    /**
     * pi {@code transcript.ts:36-39} —— 转录**以下标 0 的系统消息**开头时返回它，否则 {@code null}。
     *
     * <p>判据是下标而不是「第一条 role=system」：pi 的三条车道逐字用 {@code i === 0}
     * （{@code openai-completions.ts:1249}、{@code mistral-conversations.ts:788}、
     * {@code openai-responses-shared.ts:218}）。</p>
     */
    public static Message.SystemMessage getInitialSystemMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(0) instanceof Message.SystemMessage system ? system : null;
    }

    /**
     * pi {@code transcript.ts:42-44} —— 丢掉前导系统消息（供「提示走独立字段」的 API 用）。
     *
     * <p>⚠️ 与 pi 的一处不可观察差异：pi 在没有前导系统消息时返回**同一个数组**，
     * 这里两个分支都返回不可变副本（对象身份不是可观察行为）。</p>
     */
    public static List<Message> withoutInitialSystemMessage(List<Message> messages) {
        var supplied = messages == null ? List.<Message>of() : messages;
        if (getInitialSystemMessage(supplied) == null) {
            return List.copyOf(supplied);
        }
        return List.copyOf(supplied.subList(1, supplied.size()));
    }

    /**
     * pi {@code transcript.ts:47-53} —— 按序重放每条系统消息的工具增删。
     *
     * <p>每条消息内**先 removals 后 additions**（{@code :49-51}）；重声明的同名工具
     * **保留首次位置、换值**（pi 的 {@code Map.set} 对已存在键不改插入序，与 java 的
     * {@code LinkedHashMap.put} 同义）。</p>
     */
    public static List<ToolDefinition> getCurrentTools(List<Message> messages) {
        var tools = new LinkedHashMap<String, ToolDefinition>();
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            for (var removed : system.toolsRemoved()) {
                tools.remove(removed.name());
            }
            for (var added : system.toolsAdded()) {
                tools.put(added.name(), added);
            }
        }
        return List.copyOf(tools.values());
    }

    /**
     * pi {@code transcript.ts:57-74} —— 把每条系统消息重放成一条前导消息。
     *
     * <p>四条纪律逐条对齐 pi：content 逐条取文本、**非空才收**、以 {@code "\n\n"} 连；
     * sections 按名覆盖；tools 走 {@link #getCurrentTools}（故出参**没有** {@code toolsRemoved}）；
     * {@code timestamp} 取**第一条**系统消息的。</p>
     *
     * <p>「一条系统消息都没有且没有工具」⇒ {@code null}（pi {@code :71} 的
     * {@code timestamp === undefined && tools.length === 0}）。</p>
     *
     * <p>⚠️ pi 的 sections 值可为 {@code null} 表示**删除**具名段（{@code :81-83}），
     * java 的 {@code Map<String,String>} 没有这个状态 ⇒ 此处只落覆盖一支
     * （{@code docs/49 §9 R3①}，登记 L3）。</p>
     *
     * <p>⚠️ 出参的 {@code sections}/{@code toolsAdded} 为空时是空集合，而 pi **省略键**
     * —— 落线由 {@code SessionJson} 的空值省略门负责（A1 的 M4 探针已钉住那道门）。</p>
     */
    public static Message.SystemMessage getCurrentSystemMessage(List<Message> messages) {
        var content = new ArrayList<String>();
        var sections = new LinkedHashMap<String, String>();
        Instant timestamp = null;
        for (var message : messages) {
            if (!(message instanceof Message.SystemMessage system)) {
                continue;
            }
            if (timestamp == null) {
                timestamp = system.timestamp();
            }
            var text = MessageTexts.contentText(system.content());
            if (!text.isEmpty()) {
                content.add(text);
            }
            sections.putAll(system.sections());
        }
        var tools = getCurrentTools(messages);
        if (timestamp == null && tools.isEmpty()) {
            return null;
        }
        return new Message.SystemMessage(String.join("\n\n", content),
            timestamp == null ? Instant.EPOCH : timestamp, sections, tools, List.of());
    }

    /** pi {@code transcript.ts:77-80} —— 重放后的系统提示文本（无系统消息 ⇒ 空串）。 */
    public static String getCurrentSystemPrompt(List<Message> messages) {
        var message = getCurrentSystemMessage(messages);
        return message == null ? "" : MessageTexts.getSystemMessageText(message);
    }

    /**
     * pi {@code transcript.ts:104-110} —— 头 + 全部非系统消息（顺序保持）。
     *
     * <p>对已经折叠过的转录**幂等**（pi 夹具 {@code system-message-replay.test.ts:59}）。</p>
     */
    public static TranscriptContext collapseSystemMessages(TranscriptContext context) {
        var supplied = context.messages();
        var head = getCurrentSystemMessage(supplied);
        var messages = new ArrayList<Message>(supplied.size() + 1);
        if (head != null) {
            messages.add(head);
        }
        for (var message : supplied) {
            if (message instanceof Message.SystemMessage) {
                continue;
            }
            messages.add(message);
        }
        return new TranscriptContext(messages);
    }

    /**
     * pi {@code transcript.ts:113-120} —— 模型接受中途系统消息就原样保留，否则折叠。
     *
     * <p>判据字段是 {@link ModelCompat#supportsMidConvoSystemMessages()}（pi
     * {@code types.ts:731}）：{@code null}（用户没写）与 {@code false} 同义 —— pi 的
     * {@code resolveTranscript(context, undefined)} 走折叠支。</p>
     */
    public static TranscriptContext resolveTranscript(TranscriptContext context, ModelInfo model) {
        boolean supports = model != null
            && Boolean.TRUE.equals(model.compat().supportsMidConvoSystemMessages());
        return supports ? context : collapseSystemMessages(context);
    }
}
