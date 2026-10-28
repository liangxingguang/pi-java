package com.pijava.examples;

import java.util.List;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolCallEventResult;

/**
 * pi {@code examples/extensions/protected-paths.ts:10-30} 移植（docs/40 P3）——
 * write/edit 写受保护路径（{@code .env}/{@code .git/}/{@code node_modules/}）⇒ 阻断；
 * 有 UI 时先发 warning 通知；其他工具放行。
 */
public final class ProtectedPathsExtension implements PiExtension {

    /** pi 的 protectedPaths 逐字。 */
    static final List<String> PROTECTED_PATHS = List.of(".env", ".git/", "node_modules/");

    @Override
    public String name() {
        return "protected-paths";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.TOOL_CALL, (event, c) -> {
            if (!"write".equals(event.toolName()) && !"edit".equals(event.toolName())) {
                return null;
            }
            var path = String.valueOf(event.input().get("path"));
            var isProtected = isProtected(path);
            if (isProtected) {
                if (c.hasUi()) {
                    c.ui().notify("Blocked write to protected path: " + path,
                        NotifyType.WARNING);
                }
                return new ToolCallEventResult(true,
                    "Path \"" + path + "\" is protected", null);
            }
            return null;
        });
    }

    /** pi 的 {@code protectedPaths.some(p => path.includes(p))}。 */
    static boolean isProtected(String path) {
        for (var protectedPath : PROTECTED_PATHS) {
            if (path.contains(protectedPath)) {
                return true;
            }
        }
        return false;
    }
}
