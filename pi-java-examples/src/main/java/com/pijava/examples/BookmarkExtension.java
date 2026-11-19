package com.pijava.examples;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionSessionView;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/bookmark.ts:1-50} 移植（docs/52 §2.4）——
 * 命令 {@code /bookmark [label]} 给最后一条 assistant 消息打标签，
 * {@code /unbookmark} 清除最近一条有标签的条目。标签经宿主 {@code setLabel}
 * 落在条目上（不经 appendEntry）。
 */
public final class BookmarkExtension implements PiExtension {

    @Override
    public String name() {
        return "bookmark";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("bookmark",
            "Bookmark last message (usage: /bookmark [label])",
            args -> bookmark(ctx, args)));

        ctx.slashCommands().register(ExampleCommands.command("unbookmark",
            "Remove bookmark from last labeled entry",
            args -> unbookmark(ctx)));
    }

    /** pi L15-30：倒序找 assistant message，打标签。 */
    static void bookmark(ExtensionContext ctx, String args) {
        var label = !args.trim().isEmpty() ? args.trim() : "bookmark-" + System.currentTimeMillis();
        var entries = ctx.sessionManager().getEntries();
        var target = lastAssistantEntry(entries);
        if (target == null) {
            ctx.ui().notify("No assistant message to bookmark", NotifyType.WARNING);
            return;
        }
        ctx.setLabel(target.id(), label);
        ctx.ui().notify("Bookmarked as: " + label, NotifyType.INFO);
    }

    /** pi L36-48：倒序找有标签的条目，清除。 */
    static void unbookmark(ExtensionContext ctx) {
        ExtensionSessionView view = ctx.sessionManager();
        var entries = view.getEntries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            var label = view.getLabel(entry.id());
            if (label != null && !label.isEmpty()) {
                ctx.setLabel(entry.id(), null);
                ctx.ui().notify("Removed bookmark: " + label, NotifyType.INFO);
                return;
            }
        }
        ctx.ui().notify("No bookmarked entry found", NotifyType.WARNING);
    }

    /** 倒序第一条 assistant message 条目；无则 null。 */
    static Entry lastAssistantEntry(List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if (entry instanceof Entry.Message m && "assistant".equals(m.message().role())) {
                return entry;
            }
        }
        return null;
    }
}
