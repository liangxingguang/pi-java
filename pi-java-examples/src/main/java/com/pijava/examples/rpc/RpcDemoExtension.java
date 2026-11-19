package com.pijava.examples.rpc;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/rpc-demo.ts} 移植（docs/56 §2.3，入册）——
 * 3 command + 5 event，经 ctx.ui.* 演示 RPC 九方法；危险命令拦截为纯函数。
 */
public final class RpcDemoExtension implements PiExtension {

    /** pi L48-49：危险命令正则（rm -rf/-r/-f/--recursive / sudo）。 */
    static final Pattern DANGEROUS = Pattern.compile(
        ".*\\brm\\s+(-rf?|--recursive)\\b.*|.*\\bsudo\\b.*", Pattern.CASE_INSENSITIVE);

    /** pi L50-66 拦截结果投影。 */
    public record ToolCallDecision(boolean block, String reason) {}

    @Override
    public String name() {
        return "rpc-demo";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            // 宿主已把 session_start 的事件定型为 SessionStartEvent（pi 的 event.reason）
            var reason = event.reason();
            c.ui().setTitle("new".equals(reason) ? "pi RPC Demo (new session)" : "pi RPC Demo");
            c.ui().setWidget("rpc-demo",
                new ExtensionWidgetContent.Lines(List.of("--- RPC Extension UI Demo ---", "Loaded and ready.")),
                null);
            c.ui().setStatus("rpc-demo", "Turns: 0");
            return null;
        });
        ctx.on(ExtensionEventSpec.AGENT_END, (event, c) -> null);
        ctx.slashCommands().register(command("rpc-input",
            "Prompt for text input (demonstrates ctx.ui.input in RPC)", args -> {
                var value = ctx.ui().input("Enter a value", "type something...", null);
                ctx.ui().notify(value == null ? "Input cancelled" : "You entered: " + value,
                    NotifyType.INFO);
            }));
        ctx.slashCommands().register(command("rpc-editor",
            "Open multi-line editor (demonstrates ctx.ui.editor in RPC)", args -> {
                var text = ctx.ui().editor("Edit some text", "Line 1\nLine 2\nLine 3");
                if (text == null) {
                    ctx.ui().notify("Editor cancelled", NotifyType.INFO);
                } else {
                    ctx.ui().notify("Editor submitted (" + text.split("\n", -1).length + " lines)",
                        NotifyType.INFO);
                }
            }));
        ctx.slashCommands().register(command("rpc-prefill",
            "Prefill the input editor (demonstrates ctx.ui.setEditorText in RPC)", args -> {
                ctx.ui().setEditorText("This text was set by the rpc-demo extension.");
                ctx.ui().notify("Editor prefilled", NotifyType.INFO);
            }));
    }

    /** rpc 包内最小命令工厂（ExampleCommands 为父包包级，此处不可见）。 */
    private static SlashCommand command(String name, String description,
                                        java.util.function.Consumer<String> handler) {
        return new SlashCommand() {
            public String name() { return name; }
            public String description() { return description; }
            public String argumentHint() { return ""; }
            public CompletableFuture<String> execute(String args, SlashContext context) {
                handler.accept(args);
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    /** pi L48：危险判定纯函数。 */
    public static boolean isDangerous(String command) {
        return command != null && DANGEROUS.matcher(command).matches();
    }

    /** pi L50-66：危险命令的拦截决策。 */
    public static ToolCallDecision decide(String command, boolean hasUi, String selection) {
        if (!isDangerous(command)) {
            return new ToolCallDecision(false, null);
        }
        if (!hasUi) {
            return new ToolCallDecision(true, "Dangerous command blocked (no UI)");
        }
        if (!"Allow".equals(selection)) {
            return new ToolCallDecision(true, "Blocked by user");
        }
        return new ToolCallDecision(false, null);
    }
}
