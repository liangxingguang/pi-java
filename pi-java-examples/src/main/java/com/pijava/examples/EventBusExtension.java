package com.pijava.examples;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/event-bus.ts:12-43} 移植（docs/40 P13）——
 * 扩展间事件总线：{@code /emit} 广播 {@code my:notification}，监听者收通知
 * （{@code Event from <from>: <message>}）；session_start 自动 emit 一次。
 *
 * <p>裁决⑤：命令处理器经 register 期捕获的 per-extension 上下文调
 * {@code events()}（pi 闭包捕获 {@code pi} 对位）。订阅经跟踪视图注册——
 * 会话失效（替换/reload）时统一退订。</p>
 */
public final class EventBusExtension implements PiExtension {

    /** pi 的 {@code currentCtx}（session_start 捕获，事件处理器内消费）。 */
    private volatile ExtensionContext currentCtx;
    /** 裁决⑤：register 期捕获的 per-extension 上下文。 */
    private volatile ExtensionContext extensionContext;

    @Override
    public String name() {
        return "event-bus";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.extensionContext = ctx;

        // Store ctx for use in event handler
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            currentCtx = c;
            return null;
        });

        // Listen for events from other extensions
        ctx.events().on("my:notification", data -> {
            var now = currentCtx;
            if (now != null && data instanceof Map<?, ?> map) {
                now.ui().notify("Event from " + map.get("from") + ": " + map.get("message"),
                    NotifyType.INFO);
            }
        });

        // Command to emit events
        ctx.slashCommands().register(new EmitCommand());

        // Example: emit on session start
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            ctx.events().emit("my:notification",
                Map.of("message", "Session started", "from", "event-bus-example"));
            return null;
        });
    }

    /** pi {@code /emit} 命令。 */
    private final class EmitCommand implements SlashCommand {
        @Override
        public String name() {
            return "emit";
        }

        @Override
        public String description() {
            return "Emit my:notification event (usage: /emit message)";
        }

        @Override
        public String argumentHint() {
            return "";
        }

        @Override
        public CompletionStage<String> execute(String args, SlashContext context) {
            var message = args.trim().isEmpty() ? "hello" : args.trim();
            extensionContext.events().emit("my:notification",
                Map.of("message", message, "from", "/emit command"));
            return CompletableFuture.completedFuture("");
        }
    }
}
