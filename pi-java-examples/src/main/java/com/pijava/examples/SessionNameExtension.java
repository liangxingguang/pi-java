package com.pijava.examples;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/session-name.ts:1-27} 移植（docs/52 §2.5）——
 * 命令 {@code /session-name [name]} 有值则设置会话名，无值则显示当前名。
 */
public final class SessionNameExtension implements PiExtension {

    @Override
    public String name() {
        return "session-name";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("session-name",
            "Set or show session name (usage: /session-name [new name])",
            args -> run(ctx, args)));
    }

    /** pi L15-25：设名/显示两臂。 */
    static void run(ExtensionContext ctx, String args) {
        var name = args.trim();
        if (!name.isEmpty()) {
            ctx.setSessionName(name);
            ctx.ui().notify("Session named: " + name, NotifyType.INFO);
        } else {
            var current = ctx.getSessionName();
            ctx.ui().notify(current != null && !current.isEmpty()
                ? "Session: " + current : "No session name set", NotifyType.INFO);
        }
    }
}
