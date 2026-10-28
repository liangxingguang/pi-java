package com.pijava.examples;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;

/**
 * pi {@code examples/extensions/confirm-destructive.ts:10-59} 移植（docs/40 P4）——
 * 会话切换/fork 前确认：{@code reason=new} 必问；{@code resume} 且有 user 消息条目才问；
 * 取消 ⇒ {@code {cancel:true}}＋对应通知。无 UI 直接放行。
 */
public final class ConfirmDestructiveExtension implements PiExtension {

    @Override
    public String name() {
        return "confirm-destructive";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_BEFORE_SWITCH, (event, c) -> {
            if (!c.hasUi()) {
                return null;
            }
            if ("new".equals(event.reason())) {
                var confirmed = c.ui().confirm("Clear session?",
                    "This will delete all messages in the current session.", null);
                if (!confirmed) {
                    c.ui().notify("Clear cancelled", NotifyType.INFO);
                    return new SessionBeforeSwitchResult(true);
                }
                return null;
            }

            // reason === "resume" - check if there are unsaved changes
            // (messages since last assistant response)
            var hasUnsavedWork = c.sessionManager().getEntries().stream()
                .anyMatch(e -> e instanceof Entry.Message m
                    && "user".equals(m.message().role()));
            if (hasUnsavedWork) {
                var confirmed = c.ui().confirm("Switch session?",
                    "You have messages in the current session. Switch anyway?", null);
                if (!confirmed) {
                    c.ui().notify("Switch cancelled", NotifyType.INFO);
                    return new SessionBeforeSwitchResult(true);
                }
            }
            return null;
        });

        ctx.on(ExtensionEventSpec.SESSION_BEFORE_FORK, (event, c) -> {
            if (!c.hasUi()) {
                return null;
            }
            var entryId = event.entryId() == null ? "" : event.entryId();
            // JS slice(0,8) 截断不抛 ⇒ java substring 防御（语义同）
            var shortId = entryId.substring(0, Math.min(8, entryId.length()));
            var choice = c.ui().select("Fork from entry " + shortId + "?",
                List.of("Yes, create fork", "No, stay in current session"), null);
            if (!"Yes, create fork".equals(choice)) {
                c.ui().notify("Fork cancelled", NotifyType.INFO);
                return new SessionBeforeForkResult(true, null);
            }
            return null;
        });
    }
}
