package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;

import com.pijava.ai.api.TranscriptToolSupport;
import com.pijava.ai.message.Message;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * Responses 车道的工具状态增量（pi {@code openai-responses-shared.ts:167-190,232-265}）。
 *
 * <p>{@code changes} 模式下每条系统消息都就地声明工具；第二次及以后的声明前面要加
 * 增量通知，删除则用固定文案通知。{@code additions} 模式仍走合成的
 * {@code tool_search_call}/{@code tool_search_output} 对。</p>
 */
final class ResponsesToolChanges {

    private static final String INCREMENTAL_NOTICE =
        "This is an incremental tools update. Previously declared tools remain available unless "
        + "explicitly marked unavailable. A tool declared again here is available again, and its "
        + "latest definition replaces the earlier one.";

    private final TranscriptToolSupport support;
    private final EasyInputMessage.Role instructionRole;
    private final boolean supportsStrictMode;
    private final Map<String, String> grammarProperties;
    private final Set<String> unavailableTools = new LinkedHashSet<>();

    private boolean declaredAdditionalTools;

    ResponsesToolChanges(TranscriptToolSupport support,
                         EasyInputMessage.Role instructionRole,
                         boolean supportsStrictMode,
                         Map<String, String> grammarProperties) {
        this.support = support;
        this.instructionRole = instructionRole;
        this.supportsStrictMode = supportsStrictMode;
        this.grammarProperties = grammarProperties;
    }

    /** pi {@code getResponsesToolSupport}（{@code openai-responses-shared.ts:167-174}）。 */
    static TranscriptToolSupport support(boolean midConversationSystemMessages,
                                         boolean additionalTools,
                                         boolean toolSearch) {
        if (midConversationSystemMessages && additionalTools) {
            return TranscriptToolSupport.CHANGES;
        }
        return toolSearch ? TranscriptToolSupport.ADDITIONS : TranscriptToolSupport.NONE;
    }

    /**
     * pi {@code appendSystemToolAdditions}（{@code :232-265}）：先处理重新声明的工具，
     * 再决定是否发增量通知与就地声明项。
     */
    void appendToolAdditions(List<ResponseInputItem> items, Message.SystemMessage system,
                             int msgIndex) {
        var tools = system.toolsAdded();
        for (var tool : tools) {
            unavailableTools.remove(tool.name());
        }
        if (tools.isEmpty()) {
            return;
        }
        if (support == TranscriptToolSupport.CHANGES) {
            if (declaredAdditionalTools) {
                items.add(ResponseInputWire.inputMessage(instructionRole,
                    SanitizeUnicode.surrogates(renderIncrementalToolsNotice(unavailableTools))));
            }
            declaredAdditionalTools = true;
        }
        ResponseToolWire.anchorSystemToolAdditions(items, system, msgIndex, support,
            supportsStrictMode, grammarProperties);
    }

    /**
     * pi {@code openai-responses-shared.ts:287-294}：删除通知。同名重声明不算删除；
     * 返回空串表示没有要通知的删除。
     */
    String removalNotice(Message.SystemMessage system) {
        var added = new LinkedHashSet<String>();
        for (var tool : system.toolsAdded()) {
            added.add(tool.name());
        }
        var removed = new ArrayList<String>();
        for (var tool : system.toolsRemoved()) {
            if (!added.contains(tool.name())) {
                removed.add(tool.name());
            }
        }
        if (removed.isEmpty()) {
            return "";
        }
        for (var name : removed) {
            unavailableTools.add(name);
        }
        return renderToolRemovalNotice(removed);
    }

    private static String renderIncrementalToolsNotice(Set<String> unavailable) {
        return unavailable.isEmpty()
            ? INCREMENTAL_NOTICE
            : INCREMENTAL_NOTICE + "\nStill unavailable: " + String.join(", ", unavailable) + ".";
    }

    private static String renderToolRemovalNotice(List<String> names) {
        var sb = new StringBuilder(
            "The following tools are no longer available. Do not call them:");
        for (var name : names) {
            sb.append("\n- ").append(name);
        }
        return sb.toString();
    }
}
