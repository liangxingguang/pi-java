package com.pijava.examples;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.harness.Context;
import com.pijava.agent.session.UuidV7;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionNewSessionOptions;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/handoff.ts:1-190} 移植（docs/51 §2.8）——
 * {@code /handoff <goal>}：把当前分支（含压缩摘要＋{@code firstKeptEntryId} 之后的条目）
 * 序列化，用辅助模型生成新线程提示词，回填编辑器供改，再 {@code ctx.newSession}
 * 切到新会话并把提示词写进新会话编辑器。
 *
 * <p>窄差 D236：pi 的 {@code BorderedLoader} 是异步加载组件（转圈＋Esc 中止＋
 * {@code loader.signal}）；java 的 {@code ui.custom} 是阻塞方言、{@code complete} 同步，
 * 故在工厂内同步生成后立即 {@code done(text)}，不再驱动转圈动画。可观察结果
 * （生成文本／取消／{@code null}）与中止语义一致。</p>
 */
public final class HandoffExtension implements PiExtension {

    /** pi {@code SYSTEM_PROMPT}（逐字）。 */
    static final String SYSTEM_PROMPT = """
        You are a context transfer assistant. Given a conversation history and the user's goal\
         for a new thread, generate a focused prompt that:

        1. Summarizes relevant context from the conversation (decisions made, approaches taken,\
         key findings)
        2. Lists any relevant files that were discussed or modified
        3. Clearly states the next task based on the user's goal
        4. Is self-contained - the new thread should be able to proceed without the old conversation

        Format your response as a prompt the user can send to start the new thread.\
         Be concise but include all necessary context. Do not include any preamble like\
         "Here's the prompt" - just output the prompt itself.

        Example output format:
        ## Context
        We've been working on X. Key decisions:
        - Decision 1
        - Decision 2

        Files involved:
        - path/to/file1.ts
        - path/to/file2.ts

        ## Task
        [Clear description of what to do next based on the user's goal]""";

    /** pi loader 组件显示的文案。 */
    static final String LOADER_TEXT = "Generating handoff prompt...";

    @Override
    public String name() {
        return "handoff";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("handoff",
            "Transfer context to a new focused session", args -> run(ctx, args)));
    }

    private void run(ExtensionContext c, String args) {
        if (!"tui".equals(c.mode())) {
            c.ui().notify("handoff requires interactive mode", NotifyType.ERROR);
            return;
        }

        var model = c.getModel();
        if (model.isEmpty()) {
            c.ui().notify("No model selected", NotifyType.ERROR);
            return;
        }

        var goal = args.strip();
        if (goal.isEmpty()) {
            c.ui().notify("Usage: /handoff <goal for new thread>", NotifyType.ERROR);
            return;
        }

        var messages = handoffMessages(c.sessionManager().getBranch());
        if (messages.isEmpty()) {
            c.ui().notify("No conversation to hand off", NotifyType.ERROR);
            return;
        }

        var conversationText = com.pijava.agent.compaction.ConversationSerializer
            .serialize(messages);
        var currentSessionFile = c.sessionManager().getSessionFile();

        // D236：阻塞方言——工厂内同步生成，立即 done(text)；组件只作占位渲染。
        var result = c.ui().custom((ExtensionUiHostEnv env, KeybindingsManager keybindings,
                                    java.util.function.Consumer<String> done) -> {
            String generated = null;
            try {
                var userMessage = new Message.UserMessage(List.of(new ContentBlock.TextContent(
                    "## Conversation History\n\n" + conversationText
                        + "\n\n## User's Goal for New Thread\n\n" + goal)), Instant.now());
                var response = c.modelRegistry().complete(model.get(),
                    new Context(SYSTEM_PROMPT, List.of(userMessage), List.of()),
                    new ExtensionCompleteOptions(OptionalInt.empty(), Optional.empty(),
                        Optional.of(CacheRetention.NONE),
                        Optional.of(UuidV7.INSTANCE.next()), null));
                if (response.stopReason() != StopReason.ABORTED) {
                    generated = textOf(response);
                }
            } catch (RuntimeException e) {
                generated = null;
            }
            done.accept(generated);
            return new LoaderComponent();
        }, null);

        if (result == null) {
            c.ui().notify("Cancelled", NotifyType.INFO);
            return;
        }

        var editedPrompt = c.ui().editor("Edit handoff prompt", result);
        if (editedPrompt == null) {
            c.ui().notify("Cancelled", NotifyType.INFO);
            return;
        }

        var newSessionResult = c.newSession(new ExtensionNewSessionOptions(currentSessionFile,
            replacementCtx -> {
                replacementCtx.ui().setEditorText(editedPrompt);
                replacementCtx.ui().notify("Handoff ready. Submit when ready.",
                    NotifyType.INFO);
            }));

        if (newSessionResult.cancelled()) {
            c.ui().notify("New session cancelled", NotifyType.INFO);
        }
    }

    /**
     * pi {@code getHandoffMessages}：有压缩时取「压缩条目＋[firstKeptEntryId, 压缩)＋
     * 压缩之后」；否则全分支。
     */
    static List<Message> handoffMessages(List<Entry> branch) {
        int compactionIndex = -1;
        for (int i = branch.size() - 1; i >= 0; i--) {
            if (branch.get(i) instanceof Entry.Compaction) {
                compactionIndex = i;
                break;
            }
        }
        if (compactionIndex < 0) {
            return project(branch);
        }

        var compaction = (Entry.Compaction) branch.get(compactionIndex);
        int firstKeptIndex = -1;
        for (int i = 0; i < branch.size(); i++) {
            if (Objects.equals(branch.get(i).id(), compaction.firstKeptEntryId())) {
                firstKeptIndex = i;
                break;
            }
        }
        var compacted = new ArrayList<Entry>();
        compacted.add(compaction);
        if (firstKeptIndex >= 0) {
            compacted.addAll(branch.subList(firstKeptIndex, compactionIndex));
        }
        compacted.addAll(branch.subList(compactionIndex + 1, branch.size()));
        return project(compacted);
    }

    private static List<Message> project(List<Entry> entries) {
        var messages = new ArrayList<Message>();
        for (var entry : entries) {
            var message = entryToMessage(entry);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /** pi {@code entryToMessage}＋bash 条目的 user 文本投影（{@link ContextEntries} 既有规则）。 */
    static Message entryToMessage(Entry entry) {
        return switch (entry) {
            case Entry.Message message -> message.message();
            case Entry.Compaction compaction -> new Message.UserMessage(List.of(
                new ContentBlock.TextContent(ContextEntries.compactionText(
                    compaction.summary()))), compaction.timestamp());
            case Entry.BashExecution bash -> bash.excludeFromContext()
                ? null
                : new Message.UserMessage(List.of(new ContentBlock.TextContent(
                    ContextEntries.bashExecutionText(bash))), bash.timestamp());
            case null, default -> null;
        };
    }

    /** pi 的 {@code response.content.filter(text).map(text).join("\n")}。 */
    static String textOf(Message.AssistantMessage response) {
        var texts = new ArrayList<String>();
        for (var block : response.content()) {
            if (block instanceof ContentBlock.TextContent text) {
                texts.add(text.text());
            }
        }
        return String.join("\n", texts);
    }

    /** pi {@code BorderedLoader} 的文本级占位（D236：无动画/无 Esc 中止）。 */
    static final class LoaderComponent implements ExtensionComponent {

        @Override
        public List<String> render(int width) {
            return List.of(LOADER_TEXT);
        }

        @Override
        public void handleInput(KeybindingsManager.KeyStroke key) {
            // D236：阻塞方言下无取消键面
        }
    }
}
