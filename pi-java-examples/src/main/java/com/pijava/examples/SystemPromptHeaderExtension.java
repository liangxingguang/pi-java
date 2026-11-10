package com.pijava.examples;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/system-prompt-header.ts:1-16} 移植（docs/51 §2.7）——
 * {@code agent_start} 把生效系统提示的字符数写进 footer 状态段（键
 * {@code system-prompt}），{@code session_shutdown} 删键。
 */
public final class SystemPromptHeaderExtension implements PiExtension {

    /** pi 的 {@code setStatus} 键。 */
    static final String STATUS_KEY = "system-prompt";

    @Override
    public String name() {
        return "system-prompt-header";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.AGENT_START, (event, c) -> {
            var prompt = c.getSystemPrompt();
            // pi ${prompt.length} —— JS 与 java String.length 同为 UTF-16 单元
            c.ui().setStatus(STATUS_KEY, "System: " + prompt.length() + " chars");
            return null;
        });

        ctx.on(ExtensionEventSpec.SESSION_SHUTDOWN, (event, c) -> {
            c.ui().setStatus(STATUS_KEY, null);
            return null;
        });
    }
}
