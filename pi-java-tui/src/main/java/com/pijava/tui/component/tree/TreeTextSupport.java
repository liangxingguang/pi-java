package com.pijava.tui.component.tree;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.SessionTreeNode;

/**
 * pi tree-selector.ts 的纯文本助手脚（模型过滤与渲染层共用）：
 * {@code extractContent/extractFullContent}（:887-902）、{@code hasTextContent}
 * （:933-944）、{@code getSearchableText}（:562-620）、{@code getEntryCopyText}
 * （:904-931）、{@code normalize}（:777）。
 *
 * <p>java 的 {@code bashExecution} 是独立 entry 变体（非 pi 的 message role），
 * label/session_info 不进 Entry ADT（docs/19 裁决①，窄差 D43）——分派按 java
 * 变体对位 pi 的 role/type 表。</p>
 */
final class TreeTextSupport {

    private TreeTextSupport() {}

    /** pi {@code normalize}（:777）：换行/制表 ⇒ 空格＋trim。 */
    static String normalize(String s) {
        return s.replace('\n', ' ').replace('\t', ' ').trim();
    }

    /** pi {@code extractContent}（:887-889）：全文 200 截。 */
    static String extractContent(List<ContentBlock> content) {
        return truncate(extractFullContent(content), 200);
    }

    /** pi {@code extractFullContent}（:891-902）：拼接全部文本块。 */
    static String extractFullContent(List<ContentBlock> content) {
        if (content == null) {
            return "";
        }
        var result = new StringBuilder();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                result.append(text.text());
            }
        }
        return result.toString();
    }

    /** pi {@code hasTextContent}（:933-944）：任一非空文本块。 */
    static boolean hasTextContent(List<ContentBlock> content) {
        if (content == null) {
            return false;
        }
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text
                    && !text.text().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** custom_message 的内容形：裸串或块数组（pi :818-823/:585-589）。 */
    static String extractCustomContent(CustomMessageContent content) {
        return switch (content) {
            case CustomMessageContent.Text t -> t.text();
            case CustomMessageContent.Blocks b -> extractFullContent(b.blocks());
        };
    }

    /**
     * pi {@code getSearchableText}（:562-620）对位：label＋按 entry 类型拼
     * 搜索语料，空格 join。role 名用 pi 线值（"toolResult"/"bashExecution"）。
     */
    static String searchableText(SessionTreeNode node) {
        var parts = new ArrayList<String>();
        if (node.label() != null) {
            parts.add(node.label());
        }
        switch (node.entry()) {
            case Entry.Message m -> {
                parts.add(piRole(m.message()));
                parts.add(extractContent(m.message().content()));
            }
            case Entry.BashExecution b -> {
                parts.add("bashExecution");
                if (b.command() != null) {
                    parts.add(b.command());
                }
            }
            case Entry.CustomMessage cm -> {
                parts.add(cm.customType());
                parts.add(extractCustomContent(cm.content()));
            }
            case Entry.Compaction c -> parts.add("compaction");
            case Entry.BranchSummary bs -> {
                parts.add("branch summary");
                parts.add(bs.summary());
            }
            case Entry.ModelChange mc -> {
                parts.add("model");
                parts.add(mc.modelId());
            }
            case Entry.ThinkingLevelChange tc -> {
                parts.add("thinking");
                parts.add(tc.thinkingLevel());
            }
            case Entry.Custom c -> {
                parts.add("custom");
                parts.add(c.customType());
            }
            case Entry.ContextEdit ce -> {
                parts.add("context edit");
                parts.add(ce.replacement() == null ? "omit" : "replace");
                parts.add(ce.targetId());
            }
            case Entry.Usage u -> {
                // pi switch 无 usage 臂（恒隐，不进搜索语料）
            }
        }
        return String.join(" ", parts);
    }

    /**
     * pi {@code getEntryCopyText}（:904-931）对位：复制全文；trim 后空 ⇒
     * {@code null}（pi 的 undefined）。
     */
    static String copyText(SessionTreeNode node) {
        String text = switch (node.entry()) {
            case Entry.Message m -> {
                String full = extractFullContent(m.message().content());
                if (full.isEmpty() && m.message() instanceof Message.AssistantMessage am) {
                    full = am.errorMessage();
                }
                yield full;
            }
            case Entry.BashExecution b -> b.command();
            case Entry.CustomMessage cm -> extractCustomContent(cm.content());
            case Entry.Compaction c -> c.summary();
            case Entry.BranchSummary bs -> bs.summary();
            default -> null;
        };
        return text != null && !text.trim().isEmpty() ? text : null;
    }

    /** java 消息变体 → pi 的 role 线值（搜索语料与显示分派共用词汇）。 */
    static String piRole(Message message) {
        return switch (message) {
            case Message.UserMessage u -> "user";
            case Message.AssistantMessage a -> "assistant";
            case Message.ToolResultMessage t -> "toolResult";
            case Message.SystemMessage s -> "system";
        };
    }

    /** 无省略号截断（pi slice/truncate 的 "" 省略号语义）。 */
    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
