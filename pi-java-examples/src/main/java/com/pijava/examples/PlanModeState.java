package com.pijava.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.examples.PlanModeUtils.TodoItem;

/**
 * pi {@code examples/extensions/plan-mode/index.ts} 的纯逻辑部分（docs/51 §2.2）——
 * 工具集算法、状态持久化形状、会话恢复与完成标记重建、注入模板常量。
 *
 * <p>从 {@link PlanModeExtension} 拆出仅为可测性与单文件 ≤500 行纪律；行为与
 * pi 的模块内函数一一对应，扩展本体只做编排。</p>
 */
final class PlanModeState {

    /** pi {@code PLAN_MODE_TOOLS}。 */
    static final List<String> PLAN_MODE_TOOLS =
        List.of("read", "bash", "grep", "find", "ls", "questionnaire");

    /** pi {@code NORMAL_MODE_TOOLS}。 */
    static final List<String> NORMAL_MODE_TOOLS = List.of("read", "bash", "edit", "write");

    /** pi {@code PLAN_MODE_DISABLED_TOOLS}。 */
    static final Set<String> PLAN_MODE_DISABLED_TOOLS = Set.of("edit", "write");

    /** pi {@code PLAN_MANAGED_TOOLS}（计划态与常态受管集的并）。 */
    static final Set<String> PLAN_MANAGED_TOOLS = Set.of(
        "read", "bash", "grep", "find", "ls", "questionnaire", "edit", "write");

    /** 计划模式注入的上下文标记（pi 模板首行；{@code context} 剔除据此判定）。 */
    static final String PLAN_MODE_MARKER = "[PLAN MODE ACTIVE]";

    /** 状态持久化的条目类型（pi {@code appendEntry("plan-mode", ...)}）。 */
    static final String ENTRY_TYPE = "plan-mode";

    /** 执行起点的标记类型（pi {@code sendMessage({customType: "plan-mode-execute"})}）。 */
    static final String EXECUTE_TYPE = "plan-mode-execute";

    /** pi {@code before_agent_start} 注入的计划上下文（逐字）。 */
    static final String PLAN_MODE_CONTEXT = """
        [PLAN MODE ACTIVE]
        You are in plan mode - a read-only exploration mode for safe code analysis.

        Restrictions:
        - Built-in edit and write tools are disabled
        - Other currently active tools remain available
        - Bash is restricted to an allowlist of read-only commands

        Ask clarifying questions using the questionnaire tool.
        Use brave-search skill via bash for web research.

        Create a detailed numbered plan under a "Plan:" header:

        Plan:
        1. First step description
        2. Second step description
        ...

        Do NOT attempt to make changes - just describe what you would do.""";

    /** 执行期注入的前缀（pi 逐字；调用方后接 {@code "\n"} ＋ remaining 列表）。 */
    static final String EXECUTION_CONTEXT = """
        [EXECUTING PLAN - Full tool access enabled]

        Remaining steps:""";

    private PlanModeState() {}

    /** pi {@code getPlanModeTools}：当前活动 − {edit,write} ＋ 计划工具，保序去重。 */
    static List<String> planModeTools(List<String> activeToolNames) {
        var names = new LinkedHashSet<String>(activeToolNames.size() + PLAN_MODE_TOOLS.size());
        activeToolNames.stream().filter(name -> !PLAN_MODE_DISABLED_TOOLS.contains(name))
            .forEach(names::add);
        names.addAll(PLAN_MODE_TOOLS);
        return List.copyOf(names);
    }

    /** pi {@code getNormalModeTools}：常态四件 ＋ 当前活动 − 受管集，保序去重。 */
    static List<String> normalModeTools(List<String> activeToolNames) {
        var names = new LinkedHashSet<String>();
        names.addAll(NORMAL_MODE_TOOLS);
        activeToolNames.stream().filter(name -> !PLAN_MANAGED_TOOLS.contains(name))
            .forEach(names::add);
        return List.copyOf(names);
    }

    /**
     * 持久化数据（pi {@code PlanModeState} 的 JSON 形状）——{@code toolsBeforePlanMode}
     * 为 {@code null} 时<b>省略键（pi 的 {@code undefined} 被 JSON 丢弃）。
     */
    static Map<String, Object> stateData(boolean enabled, List<TodoItem> todos,
                                         boolean executing, List<String> toolsBefore) {
        var data = new LinkedHashMap<String, Object>();
        data.put("enabled", enabled);
        var todoData = new ArrayList<Map<String, Object>>();
        for (var item : todos) {
            var one = new LinkedHashMap<String, Object>();
            one.put("step", item.step());
            one.put("text", item.text());
            one.put("completed", item.completed());
            todoData.add(one);
        }
        data.put("todos", todoData);
        data.put("executing", executing);
        if (toolsBefore != null) {
            data.put("toolsBeforePlanMode", toolsBefore);
        }
        return data;
    }

    /** 恢复出的持久态（pi {@code PlanModeState}）。 */
    record State(boolean enabled, List<TodoItem> todos, boolean executing,
                 List<String> toolsBefore) {}

    /**
     * pi {@code session_start} 的恢复段：取最后一条 {@code plan-mode} custom 条目。
     * 无条目 ⇒ {@link Optional#empty()}（pi 的 {@code isResume === false}）。
     */
    static Optional<State> restoreState(List<Entry> entries) {
        Entry.Custom planModeEntry = null;
        for (var entry : entries) {
            if (entry instanceof Entry.Custom custom && ENTRY_TYPE.equals(custom.customType())) {
                planModeEntry = custom;
            }
        }
        if (planModeEntry == null || planModeEntry.data() == null) {
            return Optional.empty();
        }
        var data = planModeEntry.data();
        var enabled = Boolean.TRUE.equals(data.get("enabled"));
        var executing = Boolean.TRUE.equals(data.get("executing"));
        var todos = parseTodos(data.get("todos"));
        List<String> toolsBefore = null;
        if (data.get("toolsBeforePlanMode") instanceof List<?> list) {
            var names = new ArrayList<String>();
            for (var one : list) {
                names.add(String.valueOf(one));
            }
            toolsBefore = List.copyOf(names);
        }
        return Optional.of(new State(enabled, todos, executing, toolsBefore));
    }

    /**
     * pi 恢复期的完成标记重建：只扫最后一条 {@code plan-mode-execute} <b>之后</b>的
     * assistant 消息，避免把上一轮计划的 {@code [DONE:n]} 算进本轮。
     */
    static void rebuildCompleted(List<Entry> entries, List<TodoItem> items) {
        int executeIndex = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (EXECUTE_TYPE.equals(customTypeOf(entries.get(i)))) {
                executeIndex = i;
                break;
            }
        }
        var allText = new StringBuilder();
        for (int i = executeIndex + 1; i < entries.size(); i++) {
            if (entries.get(i) instanceof Entry.Message message
                    && message.message() instanceof Message.AssistantMessage assistant) {
                if (!allText.isEmpty()) {
                    allText.append('\n');
                }
                allText.append(textContent(assistant));
            }
        }
        PlanModeUtils.markCompletedSteps(allText.toString(), items);
    }

    /**
     * pi {@code context} 的剔除判据（无 {@code customType} 面时的文本等价，D235）：
     * 只剔除含 {@link #PLAN_MODE_MARKER} 的用户消息。
     */
    static boolean keepMessage(Message message) {
        return !(message instanceof Message.UserMessage user)
            || !textContent(user).contains(PLAN_MODE_MARKER);
    }

    /** 消息的全部 text 块以 {@code "\n"} 拼接（pi {@code getTextContent}）。 */
    static String textContent(Message message) {
        var texts = new ArrayList<String>();
        var content = switch (message) {
            case Message.UserMessage user -> user.content();
            case Message.AssistantMessage assistant -> assistant.content();
            case Message.ToolResultMessage result -> result.content();
            case null, default -> List.<ContentBlock>of();
        };
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent text) {
                texts.add(text.text());
            }
        }
        return String.join("\n", texts);
    }

    private static String customTypeOf(Entry entry) {
        return switch (entry) {
            case Entry.Custom custom -> custom.customType();
            case Entry.CustomMessage message -> message.customType();
            case null, default -> null;
        };
    }

    private static List<TodoItem> parseTodos(Object raw) {
        var items = new ArrayList<TodoItem>();
        if (!(raw instanceof List<?> list)) {
            return items;
        }
        for (var one : list) {
            if (!(one instanceof Map<?, ?> map)) {
                continue;
            }
            var step = map.get("step") instanceof Number number ? number.intValue() : 0;
            var itemText = map.get("text") == null ? "" : String.valueOf(map.get("text"));
            var completed = Boolean.TRUE.equals(map.get("completed"));
            items.add(new TodoItem(step, itemText, completed));
        }
        return items;
    }
}
