package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.agent.session.UuidV7;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/summarize.ts:1-199} 移植（docs/52 §2.7）——
 * 命令 {@code /summarize} 用固定模型 openai/gpt-5.2 总结当前分支并在自定义 UI 显示。
 * 四个纯逻辑函数可单测；BorderedLoader 降级为同步方言（D239）。
 */
public final class SummarizeExtension implements PiExtension {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "summarize";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("summarize",
            "Summarize the current conversation in a custom UI",
            args -> run(ctx)));
    }

    /** pi L148-198：命令主流程。 */
    static void run(ExtensionContext ctx) {
        var branch = ctx.sessionManager().getBranch();
        var conversationText = buildConversationText(branch);
        if (conversationText.isBlank()) {
            if (ctx.hasUi()) {
                ctx.ui().notify("No conversation text found", NotifyType.WARNING);
            }
            return;
        }
        if (ctx.hasUi()) {
            ctx.ui().notify("Preparing summary...", NotifyType.INFO);
        }

        var model = ctx.modelRegistry().find("openai", "gpt-5.2");
        if (model.isEmpty()) {
            if (ctx.hasUi()) {
                ctx.ui().notify("Model openai/gpt-5.2 not found", NotifyType.WARNING);
            }
            return;
        }
        if (!ctx.modelRegistry().hasConfiguredAuth(model.get())) {
            if (ctx.hasUi()) {
                ctx.ui().notify("No authentication configured for openai/gpt-5.2",
                    NotifyType.WARNING);
            }
            return;
        }

        var userMessage = new Message.UserMessage(
            List.of(new ContentBlock.TextContent(buildSummaryPrompt(conversationText))),
            java.time.Instant.now());
        var options = new ExtensionCompleteOptions(OptionalInt.empty(),
            Optional.of(new ThinkingLevel.High()), Optional.of(CacheRetention.NONE),
            Optional.of(UuidV7.INSTANCE.next()), null);
        var response = ctx.modelRegistry().complete(model.get(),
            new Context(null, List.of(userMessage), List.of()), options);
        var summary = ExampleTexts.textOf(response.content());

        if ("tui".equals(ctx.mode())) {
            ctx.ui().custom((env, kb, done) -> {
                done.accept(summary);
                return new SummaryComponent();
            }, null);
        }
    }

    /** pi L21-43：提取 text 段；string 入参视作单段。 */
    static List<String> extractTextParts(List<ContentBlock> content) {
        var out = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent t) {
                out.add(t.text());
            }
        }
        return out;
    }

    /** pi L45-66：提取 assistant 的工具调用行。 */
    static List<String> extractToolCallLines(List<ContentBlock> content) {
        var out = new ArrayList<String>();
        for (var block : content) {
            if (block instanceof ContentBlock.ToolUseContent tu) {
                var args = tu.arguments() == null
                    ? "{}" : JsJson.stringify(MAPPER.valueToTree(tu.arguments()));
                out.add("Tool " + tu.name() + " was called with args " + args);
            }
        }
        return out;
    }

    /** pi L68-104：把分支消息投影为 User/Assistant 分段文本。 */
    static String buildConversationText(List<Entry> entries) {
        var sections = new ArrayList<String>();
        for (var entry : entries) {
            if (!(entry instanceof Entry.Message m)) {
                continue;
            }
            var msg = m.message();
            var role = msg.role();
            boolean isUser = "user".equals(role);
            boolean isAssistant = "assistant".equals(role);
            if (!isUser && !isAssistant) {
                continue;
            }
            var lines = new ArrayList<String>();
            var textParts = extractTextParts(msg.content());
            if (!textParts.isEmpty()) {
                var messageText = String.join("\n", textParts).trim();
                if (!messageText.isEmpty()) {
                    lines.add((isUser ? "User" : "Assistant") + ": " + messageText);
                }
            }
            if (isAssistant) {
                lines.addAll(extractToolCallLines(msg.content()));
            }
            if (!lines.isEmpty()) {
                sections.add(String.join("\n", lines));
            }
        }
        return String.join("\n\n", sections);
    }

    /** pi L106-115：逐字摘要提示词。 */
    static String buildSummaryPrompt(String conversationText) {
        return String.join("\n",
            "Summarize this conversation so I can resume it later.",
            "Include goals, key decisions, progress, open questions, and next steps.",
            "Keep it concise and structured with headings.",
            "",
            "<conversation>",
            conversationText,
            "</conversation>");
    }

    /** 占位组件（同步方言，工厂内已 done）。 */
    static final class SummaryComponent implements com.pijava.coding.agent.extension.ExtensionComponent {
        @Override
        public List<String> render(int width) {
            return List.of("Conversation Summary");
        }

        @Override
        public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) {
        }
    }
}
