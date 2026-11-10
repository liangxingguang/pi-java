package com.pijava.examples;

import java.util.List;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.core.StreamingBehavior;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/send-user-message.ts:1-97} 移植（docs/51 §2.6）——
 * 四条命令演示 {@code ctx.sendUserMessage}：{@code /ask}（空闲才发）、
 * {@code /steer}（流式中插队）、{@code /followup}（流式中排队）、
 * {@code /askwith}（两段文本块）。
 */
public final class SendUserMessageExtension implements PiExtension {

    @Override
    public String name() {
        return "send-user-message";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("ask",
            "Send a user message to the agent", args -> {
                if (args.strip().isEmpty()) {
                    ctx.ui().notify("Usage: /ask <message>", NotifyType.WARNING);
                    return;
                }
                // sendUserMessage always triggers a turn when not streaming
                if (!ctx.isIdle()) {
                    ctx.ui().notify("Agent is busy. Use /steer or /followup instead.",
                        NotifyType.WARNING);
                    return;
                }
                ctx.sendUserMessage(args);
            }));

        ctx.slashCommands().register(ExampleCommands.command("steer",
            "Send a steering message (interrupts current processing)", args -> {
                if (args.strip().isEmpty()) {
                    ctx.ui().notify("Usage: /steer <message>", NotifyType.WARNING);
                    return;
                }
                if (ctx.isIdle()) {
                    ctx.sendUserMessage(args);
                } else {
                    ctx.sendUserMessage(args, new ExtensionContext.SendUserMessageOptions(
                        StreamingBehavior.STEER, null));
                }
            }));

        ctx.slashCommands().register(ExampleCommands.command("followup",
            "Queue a follow-up message (waits for current processing)", args -> {
                if (args.strip().isEmpty()) {
                    ctx.ui().notify("Usage: /followup <message>", NotifyType.WARNING);
                    return;
                }
                if (ctx.isIdle()) {
                    ctx.sendUserMessage(args);
                } else {
                    ctx.sendUserMessage(args, new ExtensionContext.SendUserMessageOptions(
                        StreamingBehavior.FOLLOW_UP, null));
                    ctx.ui().notify("Follow-up queued", NotifyType.INFO);
                }
            }));

        ctx.slashCommands().register(ExampleCommands.command("askwith",
            "Send a user message with structured content", args -> {
                if (args.strip().isEmpty()) {
                    ctx.ui().notify("Usage: /askwith <message>", NotifyType.WARNING);
                    return;
                }
                if (!ctx.isIdle()) {
                    ctx.ui().notify("Agent is busy", NotifyType.WARNING);
                    return;
                }
                ctx.sendUserMessage(CustomMessageContent.of(List.of(
                    new ContentBlock.TextContent("User request: " + args),
                    new ContentBlock.TextContent("Please respond concisely."))), null);
            }));
    }
}
