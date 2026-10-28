package com.pijava.examples;

import java.util.regex.Pattern;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolCallEventResult;

/**
 * pi {@code examples/extensions/permission-gate.ts:10-34} 移植（docs/40 P2）——
 * tool_call 守卫：bash 命令命中危险模式（{@code rm -rf}/{@code sudo}/
 * {@code chmod 777}）时，无 UI 默认阻断；有 UI 弹选择，非 Yes ⇒ 阻断。
 */
public final class PermissionGateExtension implements PiExtension {

    /** pi 的三个危险模式逐字（{@code /i} ⇒ CASE_INSENSITIVE）。 */
    static final Pattern[] DANGEROUS_PATTERNS = {
        Pattern.compile("\\brm\\s+(-rf?|--recursive)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(chmod|chown)\\b.*777", Pattern.CASE_INSENSITIVE),
    };

    @Override
    public String name() {
        return "permission-gate";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.TOOL_CALL, (event, c) -> {
            if (!"bash".equals(event.toolName())) {
                return null;
            }
            var command = String.valueOf(event.input().get("command"));
            var isDangerous = isDangerous(command);
            if (isDangerous) {
                if (!c.hasUi()) {
                    // In non-interactive mode, block by default
                    return new ToolCallEventResult(true,
                        "Dangerous command blocked (no UI for confirmation)", null);
                }
                var choice = c.ui().select(
                    "⚠️ Dangerous command:\n\n  " + command + "\n\nAllow?",
                    java.util.List.of("Yes", "No"), null);
                if (!"Yes".equals(choice)) {
                    return new ToolCallEventResult(true, "Blocked by user", null);
                }
            }
            return null;
        });
    }

    /** pi 的 {@code dangerousPatterns.some(p => p.test(command))}。 */
    static boolean isDangerous(String command) {
        for (var pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}
