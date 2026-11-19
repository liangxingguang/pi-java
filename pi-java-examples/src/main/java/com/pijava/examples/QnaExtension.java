package com.pijava.examples;

import java.util.List;
import java.util.Optional;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/qna.ts:1-118} 移植（docs/52 §2.6）——
 * 命令 {@code /qna} 从最后一条 assistant 消息抽取问题，回填编辑器。
 * BorderedLoader 异步动画降级为同步 ui.custom 方言（D239）：工厂内同步 complete
 * 后立即 done。
 */
public final class QnaExtension implements PiExtension {

    /** pi L14-28：逐字（{@code A: } 后含尾随空格）。 */
    static final String SYSTEM_PROMPT =
        "You are a question extractor. Given text from a conversation, extract any questions that need answering and format them for the user to fill in.\n"
        + "\n"
        + "Output format:\n"
        + "- List each question on its own line, prefixed with \"Q: \"\n"
        + "- After each question, add a blank line for the answer prefixed with \"A: \"\n"
        + "- If no questions are found, output \"No questions found in the last message.\"\n"
        + "\n"
        + "Example output:\n"
        + "Q: What is your preferred database?\n"
        + "A: \n"
        + "\n"
        + "Q: Should we use TypeScript or JavaScript?\n"
        + "A: \n"
        + "\n"
        + "Keep questions in the order they appeared. Be concise.";

    @Override
    public String name() {
        return "qna";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("qna",
            "Extract questions from last assistant message into editor",
            args -> run(ctx)));
    }

    /** pi L33-117：命令主流程（可测编排）。 */
    static void run(ExtensionContext ctx) {
        if (!"tui".equals(ctx.mode())) {
            ctx.ui().notify("qna requires interactive mode", NotifyType.ERROR);
            return;
        }
        Optional<ExtensionModelInfo> model = ctx.getModel();
        if (model.isEmpty()) {
            ctx.ui().notify("No model selected", NotifyType.ERROR);
            return;
        }

        var branch = ctx.sessionManager().getBranch();
        Scan scan = scanBranch(branch);
        if (scan.error() != null) {
            ctx.ui().notify(scan.error(), NotifyType.ERROR);
            return;
        }
        var lastText = scan.text();
        if (lastText == null) {
            ctx.ui().notify("No assistant messages found", NotifyType.ERROR);
            return;
        }

        var extracted = ctx.ui().custom((ExtensionUiHostEnv env,
                com.pijava.coding.agent.core.KeybindingsManager kb,
                java.util.function.Consumer<String> done) -> {
            var userMessage = new Message.UserMessage(
                List.of(new ContentBlock.TextContent(lastText)), java.time.Instant.now());
            var response = ctx.modelRegistry().complete(model.get(),
                new Context(SYSTEM_PROMPT, List.of(userMessage), List.of()),
                ExtensionCompleteOptions.defaults());
            String result = null;
            if (response != null && response.stopReason() != StopReason.ABORTED) {
                result = ExampleTexts.textOf(response.content());
            }
            done.accept(result);
            return new QnaComponent();
        }, null);

        if (extracted == null) {
            ctx.ui().notify("Cancelled", NotifyType.INFO);
            return;
        }
        ctx.ui().setEditorText(extracted);
        ctx.ui().notify("Questions loaded. Edit and submit when ready.", NotifyType.INFO);
    }

    /** 分支扫描结果：命中文本或错误文案（二者其一非 null）。 */
    record Scan(String text, String error) {}

    /** pi L48-71：倒序取最后一条 stop assistant 的文本段。 */
    static Scan scanBranch(List<Entry> branch) {
        for (int i = branch.size() - 1; i >= 0; i--) {
            var entry = branch.get(i);
            if (!(entry instanceof Entry.Message m)) {
                continue;
            }
            var msg = m.message();
            if (!"assistant".equals(msg.role())) {
                continue;
            }
            var stopReason = stopReasonOf(msg);
            if (stopReason != StopReason.STOP) {
                return new Scan(null, "Last assistant message incomplete ("
                    + (stopReason == null ? "null" : stopReason) + ")");
            }
            var text = ExampleTexts.textOf(msg.content());
            if (!text.isEmpty()) {
                return new Scan(text, null);
            }
        }
        return new Scan(null, "No assistant messages found");
    }

    /** 提取 assistant 消息的 stopReason（非 assistant 形态返回 null）。 */
    private static StopReason stopReasonOf(Message msg) {
        if (msg instanceof Message.AssistantMessage rec) {
            return rec.stopReason();
        }
        return null;
    }

    /** 占位组件（同步方言，工厂内已 done）。 */
    static final class QnaComponent implements ExtensionComponent {
        @Override
        public List<String> render(int width) {
            return List.of("Extracting questions...");
        }

        @Override
        public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) {
        }
    }
}
