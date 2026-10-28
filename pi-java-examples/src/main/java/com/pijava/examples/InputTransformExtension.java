package com.pijava.examples;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;

/**
 * pi {@code examples/extensions/input-transform.ts:14-43} 移植（docs/40 P7）——
 * input 事件三态：{@code source=extension} ⇒ continue（防递归）；
 * {@code ?quick <q>} ⇒ transform；空 query／{@code ping}／{@code time} ⇒ handled＋通知。
 */
public final class InputTransformExtension implements PiExtension {

    @Override
    public String name() {
        return "input-transform";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.INPUT, (event, c) -> {
            // Source-based logic: skip processing for extension-injected messages
            if ("extension".equals(event.source())) {
                return new InputEventResult.Continue();
            }

            // Transform: ?quick prefix for brief responses
            if (event.text().startsWith("?quick ")) {
                var query = event.text().substring(7).strip();
                if (query.isEmpty()) {
                    c.ui().notify("Usage: ?quick <question>", NotifyType.WARNING);
                    return new InputEventResult.Handled();
                }
                return new InputEventResult.Transform(
                    "Respond briefly in 1-2 sentences: " + query, null);
            }

            // Handle: instant responses without LLM
            var lower = event.text().toLowerCase(Locale.ROOT);
            if ("ping".equals(lower)) {
                c.ui().notify("pong", NotifyType.INFO);
                return new InputEventResult.Handled();
            }
            if ("time".equals(lower)) {
                // JS new Date().toLocaleString() 对位（本地化中格式）
                c.ui().notify(LocalDateTime.now().format(DateTimeFormatter
                    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)),
                    NotifyType.INFO);
                return new InputEventResult.Handled();
            }

            return new InputEventResult.Continue();
        });
    }
}
