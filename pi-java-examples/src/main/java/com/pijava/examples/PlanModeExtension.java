package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.CustomMessageDelivery;
import com.pijava.coding.agent.core.StreamingBehavior;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionFlagOptions;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeAgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.ContextEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft;
import com.pijava.coding.agent.extension.event.ExtensionResults.ToolCallEventResult;
import com.pijava.examples.PlanModeUtils.TodoItem;

/**
 * pi {@code examples/extensions/plan-mode/index.ts:1-394} 移植（docs/51 §2.2）——
 * 计划模式：{@code /plan} 与 {@code ctrl+alt+p} 切换、活动工具集收窄、bash 只读
 * 白名单拦截、{@code Plan:} 段提取待办、执行期 {@code [DONE:n]} 追进度、会话恢复
 * 重建完成标记。纯逻辑（工具集算法/持久化/恢复/模板）在 {@link PlanModeState}。
 *
 * <p>两处窄差（D235）：主题色/删除线降级为纯文本（{@code ui.theme()} 在 java 只是
 * 主题名窄投影）；{@code context} 事件载荷是 LLM 投影（无 {@code customType}），
 * 故只保留「含 {@code [PLAN MODE ACTIVE]} 的用户消息剔除」这条文本过滤。</p>
 *
 * <p>{@code pi.appendEntry}/{@code pi.sendMessage}/{@code pi.sendUserMessage}
 * 在 java 落在 register 期捕获的 per-extension {@link ExtensionContext} 上，
 * 与 pi 闭包捕获的 {@code pi} 同源。</p>
 */
public final class PlanModeExtension implements PiExtension {

    private boolean planModeEnabled;
    private boolean executionMode;
    private List<TodoItem> todoItems = new ArrayList<>();
    /** pi 的 {@code string[] | undefined}；{@code null} ≙ undefined。 */
    private List<String> toolsBeforePlanMode;
    /**
     * register 期捕获的 per-extension 上下文（pi 闭包的 {@code pi}）。
     *
     * <p>{@code getFlag} 的所有权门只在 per-extension 委托上有实现（宿主基础上下文
     * 对 flag 面一律抛 {@code assertActive}），因此命令行 flag 的读取必须经它。</p>
     */
    private ExtensionContext extensionContext;

    @Override
    public String name() {
        return "plan-mode";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.extensionContext = ctx;
        ctx.registerFlag("plan", ExtensionFlagOptions.ofBoolean(
            "Start in plan mode (read-only exploration)", Boolean.FALSE));

        ctx.slashCommands().register(ExampleCommands.command("plan",
            "Toggle plan mode (read-only exploration)", args -> togglePlanMode(ctx)));
        ctx.slashCommands().register(ExampleCommands.command("todos",
            "Show current plan todo list", args -> showTodos(ctx)));
        ctx.registerShortcut("ctrl+alt+p", "Toggle plan mode", c -> togglePlanMode(c));

        ctx.on(ExtensionEventSpec.TOOL_CALL, (event, c) -> {
            if (!planModeEnabled || !"bash".equals(event.toolName())) {
                return null;
            }
            var command = event.input().get("command");
            // pi `${command}` 对 undefined 亦写成 "undefined"（逐字）
            var commandText = command == null ? "undefined" : String.valueOf(command);
            if (PlanModeUtils.isSafeCommand(commandText)) {
                return null;
            }
            return new ToolCallEventResult(Boolean.TRUE,
                "Plan mode: command blocked (not allowlisted). "
                    + "Use /plan to disable plan mode first.\nCommand: " + commandText,
                null);
        });

        ctx.on(ExtensionEventSpec.CONTEXT, (event, c) -> {
            if (planModeEnabled) {
                return null;
            }
            return new ContextEventResult(
                event.messages().stream().filter(PlanModeState::keepMessage).toList());
        });

        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> planContext(event));
        ctx.on(ExtensionEventSpec.TURN_END, (event, c) -> {
            if (!executionMode || todoItems.isEmpty()) {
                return null;
            }
            // 宿主已把 turn_end 的 message 定型为 AssistantMessage（pi 的 isAssistantMessage 守卫
            // 在 java 是类型面事实）
            var assistant = event.message();
            if (PlanModeUtils.markCompletedSteps(PlanModeState.textContent(assistant),
                    todoItems) > 0) {
                updateStatus(c);
            }
            persistState(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.AGENT_END, (event, c) -> {
            onAgentEnd(event.messages(), c);
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            onSessionStart(c);
            return null;
        });
    }

    // ── 编排 ────────────────────────────────────────────────────────────

    private void togglePlanMode(ExtensionContext c) {
        planModeEnabled = !planModeEnabled;
        executionMode = false;
        todoItems = new ArrayList<>();

        if (planModeEnabled) {
            enablePlanModeTools(c);
            c.ui().notify("Plan mode enabled. Built-in write tools disabled.", null);
        } else {
            restoreNormalModeTools(c);
            c.ui().notify("Plan mode disabled. Full access restored.", null);
        }
        updateStatus(c);
        persistState(c);
    }

    private void showTodos(ExtensionContext c) {
        if (todoItems.isEmpty()) {
            c.ui().notify("No todos. Create a plan first with /plan", NotifyType.INFO);
            return;
        }
        var list = new StringBuilder();
        for (int i = 0; i < todoItems.size(); i++) {
            var item = todoItems.get(i);
            if (i > 0) {
                list.append('\n');
            }
            list.append(i + 1).append(". ").append(item.completed() ? "✓" : "○")
                .append(' ').append(item.text());
        }
        c.ui().notify("Plan Progress:\n" + list, NotifyType.INFO);
    }

    private void enablePlanModeTools(ExtensionContext c) {
        if (toolsBeforePlanMode == null) {
            toolsBeforePlanMode = List.copyOf(c.getActiveTools());
        }
        c.setActiveTools(PlanModeState.planModeTools(toolsBeforePlanMode));
    }

    private void restoreNormalModeTools(ExtensionContext c) {
        c.setActiveTools(toolsBeforePlanMode != null
            ? toolsBeforePlanMode : PlanModeState.normalModeTools(c.getActiveTools()));
        toolsBeforePlanMode = null;
    }

    private void updateStatus(ExtensionContext c) {
        if (executionMode && !todoItems.isEmpty()) {
            long completed = todoItems.stream().filter(TodoItem::completed).count();
            // D235：{@code theme.fg("accent", ...)} 降级为纯文本
            c.ui().setStatus("plan-mode", "📋 " + completed + "/" + todoItems.size());
        } else if (planModeEnabled) {
            c.ui().setStatus("plan-mode", "⏸ plan");
        } else {
            c.ui().setStatus("plan-mode", null);
        }

        if (executionMode && !todoItems.isEmpty()) {
            var lines = new ArrayList<String>();
            for (var item : todoItems) {
                // D235：success/muted/strikethrough 降级为 ☑/☐ 纯文本
                lines.add(item.completed() ? "☑ " + item.text() : "☐ " + item.text());
            }
            c.ui().setWidget("plan-todos", new ExtensionWidgetContent.Lines(lines),
                ExtensionWidgetOptions.DEFAULT);
        } else {
            c.ui().setWidget("plan-todos", null, ExtensionWidgetOptions.DEFAULT);
        }
    }

    private void persistState(ExtensionContext c) {
        c.appendEntry(PlanModeState.ENTRY_TYPE, PlanModeState.stateData(planModeEnabled,
            todoItems, executionMode, toolsBeforePlanMode));
    }

    private BeforeAgentStartEventResult planContext(BeforeAgentStartEvent event) {
        if (event.messageType() != BeforeAgentStartEvent.MessageType.USER) {
            return null;
        }
        if (planModeEnabled) {
            return new BeforeAgentStartEventResult(new CustomMessageDraft("plan-mode-context",
                CustomMessageContent.of(PlanModeState.PLAN_MODE_CONTEXT), false, null), null);
        }
        if (executionMode && !todoItems.isEmpty()) {
            var remaining = new StringBuilder();
            for (var item : todoItems) {
                if (item.completed()) {
                    continue;
                }
                if (!remaining.isEmpty()) {
                    remaining.append('\n');
                }
                remaining.append(item.step()).append(". ").append(item.text());
            }
            return new BeforeAgentStartEventResult(new CustomMessageDraft(
                "plan-execution-context",
                CustomMessageContent.of(PlanModeState.EXECUTION_CONTEXT + "\n" + remaining),
                false, null), null);
        }
        return null;
    }

    private void onAgentEnd(List<Message> messages, ExtensionContext c) {
        if (executionMode && !todoItems.isEmpty()) {
            if (todoItems.stream().allMatch(TodoItem::completed)) {
                var completedList = new StringBuilder();
                for (var item : todoItems) {
                    if (!completedList.isEmpty()) {
                        completedList.append('\n');
                    }
                    completedList.append("~~").append(item.text()).append("~~");
                }
                c.sendMessage("plan-complete",
                    CustomMessageContent.of("**Plan Complete!** ✓\n\n" + completedList),
                    true, null, new ExtensionContext.SendMessageOptions(Boolean.FALSE, null));
                executionMode = false;
                todoItems = new ArrayList<>();
                updateStatus(c);
                // Save cleared state so resume doesn't restore old execution mode
                persistState(c);
            }
            return;
        }

        if (!planModeEnabled || !c.hasUi()) {
            return;
        }

        Message.AssistantMessage lastAssistant = null;
        for (var message : messages) {
            if (message instanceof Message.AssistantMessage assistant) {
                lastAssistant = assistant;
            }
        }
        if (lastAssistant != null) {
            var extracted = PlanModeUtils.extractTodoItems(
                PlanModeState.textContent(lastAssistant));
            if (!extracted.isEmpty()) {
                todoItems = new ArrayList<>(extracted);
            }
        }

        if (todoItems.isEmpty()) {
            return;
        }
        persistState(c);

        var todoListText = new StringBuilder();
        for (int i = 0; i < todoItems.size(); i++) {
            if (i > 0) {
                todoListText.append('\n');
            }
            todoListText.append(i + 1).append(". ☐ ").append(todoItems.get(i).text());
        }
        var planTodoListContent = CustomMessageContent.of("**Plan Steps ("
            + todoItems.size() + "):**\n\n" + todoListText);

        var choice = c.ui().select("Plan mode - what next?", List.of(
            "Execute the plan (track progress)",
            "Stay in plan mode",
            "Refine the plan"), null);

        if (choice != null && choice.startsWith("Execute")) {
            var first = todoItems.get(0);
            planModeEnabled = false;
            executionMode = true;
            restoreNormalModeTools(c);
            updateStatus(c);
            persistState(c);

            var remainingList = new StringBuilder();
            for (var item : todoItems) {
                if (!remainingList.isEmpty()) {
                    remainingList.append('\n');
                }
                remainingList.append(item.step()).append(". ").append(item.text());
            }
            var execMessage = "Execute the plan.\n\nRemaining steps:\n" + remainingList
                + "\n\nStart with: " + first.text()
                + "\nAfter completing a step, include a [DONE:n] tag in your response.";
            c.sendMessage("plan-todo-list", planTodoListContent, true, null,
                new ExtensionContext.SendMessageOptions(null, CustomMessageDelivery.FOLLOW_UP));
            c.sendMessage(PlanModeState.EXECUTE_TYPE, CustomMessageContent.of(execMessage),
                true, null, new ExtensionContext.SendMessageOptions(Boolean.TRUE,
                    CustomMessageDelivery.FOLLOW_UP));
        } else if ("Refine the plan".equals(choice)) {
            var refinement = c.ui().editor("Refine the plan:", "");
            if (refinement != null && !refinement.strip().isEmpty()) {
                c.sendMessage("plan-todo-list", planTodoListContent, true, null,
                    new ExtensionContext.SendMessageOptions(null,
                        CustomMessageDelivery.FOLLOW_UP));
                c.sendUserMessage(refinement.strip(),
                    new ExtensionContext.SendUserMessageOptions(
                        StreamingBehavior.FOLLOW_UP, null));
            }
        }
    }

    private void onSessionStart(ExtensionContext c) {
        if (Boolean.TRUE.equals(extensionContext.getFlag("plan"))) {
            planModeEnabled = true;
        }

        var entries = c.sessionManager().getEntries();
        var restored = PlanModeState.restoreState(entries);
        if (restored.isPresent()) {
            var state = restored.get();
            planModeEnabled = state.enabled();
            todoItems = new ArrayList<>(state.todos());
            executionMode = state.executing();
            toolsBeforePlanMode = state.toolsBefore();
            if (executionMode && !todoItems.isEmpty()) {
                PlanModeState.rebuildCompleted(entries, todoItems);
            }
        }

        if (planModeEnabled) {
            enablePlanModeTools(c);
        }
        updateStatus(c);
    }

    /** 当前持久化快照（测试与调试用；pi 侧无此成员）。 */
    Entry.Custom snapshot(ExtensionContext c) {
        return new Entry.Custom("snapshot", 0, null, null, PlanModeState.ENTRY_TYPE,
            PlanModeState.stateData(planModeEnabled, todoItems, executionMode,
                toolsBeforePlanMode));
    }
}
