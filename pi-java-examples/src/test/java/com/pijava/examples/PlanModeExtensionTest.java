package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.SlashContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：plan-mode 扩展验收——{@code /plan} 切换后的活动工具集与状态段、
 * bash 拦截、{@code before_agent_start} 的 user/custom 两臂、{@code --plan} 启动臂、
 * {@code agent_end} 的 Execute 分支，以及 {@code restoreState}/{@code rebuildCompleted}
 * 的恢复语义。
 */
class PlanModeExtensionTest {

    @TempDir Path tmp;

    private static void runCommand(AgentSession session, String name, String args) {
        var command = session.services().slashCommands().get(name);
        assertThat(command).as("/%s 已注册", name).isNotNull();
        command.execute(args, SlashContext.of(session)).toCompletableFuture().join();
    }

    private static List<Entry> entries(AgentSession session) {
        return List.copyOf(session.accumulatedEntries());
    }

    private static List<String> customMessageTexts(AgentSession session, String customType) {
        return entries(session).stream()
            .filter(Entry.CustomMessage.class::isInstance)
            .map(Entry.CustomMessage.class::cast)
            .filter(entry -> customType.equals(entry.customType()))
            .map(entry -> entry.content().plainText())
            .toList();
    }

    // ── 切换、工具集、状态持久化 ─────────────────────────────────

    @Test
    void toggleNarrowsToolsPersistsStateAndNotifies() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PlanModeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pm", "ok"))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            runCommand(session, "plan", "");

            var active = session.getActiveToolNames();
            assertThat(active).contains("read", "bash", "grep", "find", "ls");
            assertThat(active).doesNotContain("edit", "write");
            assertThat(ui.statuses()).containsEntry("plan-mode", "⏸ plan");
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Plan mode enabled. Built-in write tools disabled."));

            var persisted = entries(session).stream()
                .filter(Entry.Custom.class::isInstance)
                .map(Entry.Custom.class::cast)
                .filter(entry -> "plan-mode".equals(entry.customType()))
                .toList();
            assertThat(persisted).isNotEmpty();
            assertThat(persisted.get(persisted.size() - 1).data())
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("executing", Boolean.FALSE);

            runCommand(session, "plan", "");
            assertThat(session.getActiveToolNames()).contains("edit", "write");
            // pi 的 setStatus(key, undefined) ⇒ 删键（ScriptedUi 记为 null 值）
            assertThat(ui.statuses()).containsEntry("plan-mode", null);
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Plan mode disabled. Full access restored."));
        }
    }

    @Test
    void planFlagStartupEnablesPlanMode() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PlanModeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pm2", "ok"), "hello", "--plan")) {
            assertThat(session.getActiveToolNames()).contains("read", "bash", "grep", "find", "ls");
            assertThat(session.getActiveToolNames()).doesNotContain("edit", "write");
        }
    }

    // ── bash 拦截 ───────────────────────────────────────────────

    @Test
    void destructiveBashIsBlockedWhilePlanModeIsActive() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PlanModeExtension.class);
                var session = ExampleFixtures.newSession(tmp, ExampleFixtures.toolThenDone(
                    "faux-pm3", "call-1", "bash", Map.of("command", "rm -rf build")))) {
            var ui = new ExampleFixtures.ScriptedUi().answer("Stay in plan mode");
            session.extensionUI(ui, "tui");
            runCommand(session, "plan", "");

            var result = session.processPrompt("clean the build", PromptConfig.defaults());
            assertThat(result.status().exitCode()).isZero();

            var toolTexts = entries(session).stream()
                .filter(Entry.Message.class::isInstance)
                .map(Entry.Message.class::cast)
                .map(Entry.Message::message)
                .filter(Message.ToolResultMessage.class::isInstance)
                .map(Message.ToolResultMessage.class::cast)
                .map(Message.ToolResultMessage::content)
                .toList();
            assertThat(toolTexts).isNotEmpty();
            var toolText = ExampleFixtures.textOf(toolTexts.get(toolTexts.size() - 1));
            assertThat(toolText).contains("Plan mode: command blocked (not allowlisted).");
            assertThat(toolText).contains("Command: rm -rf build");
        }
    }

    // ── before_agent_start 两臂 ─────────────────────────────────

    @Test
    void planContextIsInjectedOnlyForUserMessages() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PlanModeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pm4", "ok"))) {
            var ui = new ExampleFixtures.ScriptedUi().answer("Stay in plan mode");
            session.extensionUI(ui, "tui");
            runCommand(session, "plan", "");

            session.processPrompt("look around", PromptConfig.defaults());
            assertThat(customMessageTexts(session, "plan-mode-context"))
                .anySatisfy(text -> assertThat(text).contains("[PLAN MODE ACTIVE]"));

            var beforeCustom = customMessageTexts(session, "plan-mode-context").size();
            // custom 跑（后台通知形）不再重复注入计划上下文
            session.sendCustomMessage("note", CustomMessageContent.of("background note"),
                false, null, Boolean.TRUE, null);
            assertThat(customMessageTexts(session, "plan-mode-context")).hasSize(beforeCustom);
        }
    }

    // ── agent_end 的 Execute 分支 ───────────────────────────────

    @Test
    void executeChoiceQueuesTodoListAndExecutionMessages() throws Exception {
        var planText = """
            Plan:
            1. Read the loader
            2. Add the validation step""";
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PlanModeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    sequenceFaux("faux-pm5", planText, 3))) {
            var ui = new ExampleFixtures.ScriptedUi()
                .answer("Execute the plan (track progress)");
            session.extensionUI(ui, "tui");
            runCommand(session, "plan", "");

            session.processPrompt("make a plan", PromptConfig.defaults());

            // FOLLOW_UP 投递在 run 收尾窗内落盘：短轮询等待（≤2s）
            var customTypes = List.<String>of();
            for (int i = 0; i < 40; i++) {
                customTypes = entries(session).stream()
                    .filter(Entry.CustomMessage.class::isInstance)
                    .map(entry -> ((Entry.CustomMessage) entry).customType())
                    .toList();
                if (customTypes.contains("plan-mode-execute")) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(customTypes).contains("plan-todo-list", "plan-mode-execute");
            var executeText = customMessageTexts(session, "plan-mode-execute").get(0);
            assertThat(executeText).startsWith("Execute the plan.");
            assertThat(executeText).contains("1. Loader");
            assertThat(executeText).contains("After completing a step, include a [DONE:n] tag");
            assertThat(ui.statuses()).containsEntry("plan-mode", "📋 0/2");
        }
    }

    private static FauxProvider sequenceFaux(String name, String text, int count) {
        var responses = new ArrayList<List<StreamEvent>>();
        for (int i = 0; i < count; i++) {
            responses.add(ExampleFixtures.textEvents(text));
        }
        return FauxProvider.sequence(name, responses);
    }

    // ── 恢复语义 ───────────────────────────────────────────────

    @Test
    void restoreStateReadsLastPlanModeEntry() {
        var stale = new Entry.Custom("e1", 0, null, Instant.EPOCH, "plan-mode",
            Map.of("enabled", Boolean.FALSE, "todos", List.of(), "executing", Boolean.FALSE));
        var latest = new Entry.Custom("e2", 1, null, Instant.EPOCH, "plan-mode",
            Map.of("enabled", Boolean.TRUE, "executing", Boolean.TRUE,
                "todos", List.of(Map.of("step", 1, "text", "Read config",
                    "completed", Boolean.FALSE)),
                "toolsBeforePlanMode", List.of("read", "bash", "edit", "write")));

        var state = PlanModeState.restoreState(List.of(stale, latest)).orElseThrow();
        assertThat(state.enabled()).isTrue();
        assertThat(state.executing()).isTrue();
        assertThat(state.todos()).hasSize(1);
        assertThat(state.todos().get(0).text()).isEqualTo("Read config");
        assertThat(state.toolsBefore()).containsExactly("read", "bash", "edit", "write");

        // 无持久条目 ⇒ empty（pi 的 isResume=false 臂）
        assertThat(PlanModeState.restoreState(List.of())).isEmpty();
    }

    @Test
    void rebuildCompletedOnlyScansAfterLastExecuteMarker() {
        var items = PlanModeUtils.extractTodoItems("Plan:\n1. Read config\n2. Write parser");
        assertThat(items).hasSize(2);

        var beforeExecute = messageEntry("m0", "[DONE:2]");
        var executeMarker = new Entry.CustomMessage("e1", 1, null, Instant.EPOCH,
            "plan-mode-execute", CustomMessageContent.of("Execute the plan."), true, null);
        var afterExecute = messageEntry("m1", "[DONE:1]");

        PlanModeState.rebuildCompleted(List.of(beforeExecute, executeMarker, afterExecute),
            items);
        assertThat(items.get(0).completed()).isTrue();
        // 执行标记之前的 [DONE:2] 不计入本轮
        assertThat(items.get(1).completed()).isFalse();
    }

    private static Entry.Message messageEntry(String id, String text) {
        return new Entry.Message(id, 0, null, Instant.EPOCH,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text))), null);
    }

    /** 工具集算法的纯函数断言（pi 的 getPlanModeTools/getNormalModeTools）。 */
    @Test
    void toolsetAlgorithmsPreserveOrderAndDropManagedNames() {
        var active = List.of("read", "bash", "edit", "write", "custom_x");
        var plan = PlanModeState.planModeTools(active);
        assertThat(plan).containsExactly("read", "bash", "custom_x",
            "grep", "find", "ls", "questionnaire");

        var normal = PlanModeState.normalModeTools(List.of("read", "grep", "custom_x"));
        assertThat(normal).containsExactly("read", "bash", "edit", "write", "custom_x");
    }

    @Test
    void todosCommandReportsEmptyState() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PlanModeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pm6", "ok"))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            runCommand(session, "todos", "");
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("No todos. Create a plan first with /plan"));
        }
    }
}
