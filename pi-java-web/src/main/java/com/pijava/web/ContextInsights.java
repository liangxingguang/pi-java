package com.pijava.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;

import com.pijava.web.ContextReport.Category;
import com.pijava.web.ContextReport.Event;
import com.pijava.web.ContextReport.Item;
import com.pijava.web.ContextReport.Report;
import com.pijava.web.ContextReport.Step;
import com.pijava.web.ContextReport.Totals;

/**
 * 从会话 branch 计算上下文洞察（Web 扩展，对齐 dsh-context 的 Context 面板）。
 *
 * <p><b>组成口径</b>：模型可见消息取 {@link ContextEntries#projectEntries} 的投影
 * （压缩摘要、分支摘要、custom_message、bash 输出都按投影后的 user 消息读），
 * 每条消息按块拆进八个类别；系统提示与工具定义是投影之外的固定开销，单独计。
 * 每块字符数 / 4 与 {@link ContextUsageEstimator#estimateTokens} 同一条 pi 启发式
 * （图片按 4800 字符常数）。</p>
 *
 * <p><b>占用口径</b>：有可信 assistant usage 时信 provider（
 * {@link ContextUsageEstimator#estimateProjectedContextTokens} 的 usage 锚定值），
 * 否则退回「消息估算 + 系统提示 + 工具定义」。估算总量可能小于 provider 实报的
 * prompt 量（缓存/协议开销），此时占用取两者较大值，图上的差额由「未归因」留白
 * 承担。</p>
 */
final class ContextInsights {

    static final String SYSTEM = "system";
    static final String TOOLS = "tools";
    static final String USER = "user";
    static final String INJECTED = "injected";
    static final String ANSWERS = "answers";
    static final String REASONING = "reasoning";
    static final String TOOL_CALLS = "toolCalls";
    static final String TOOL_RESULTS = "toolResults";

    private static final List<String> ORDER = List.of(
        SYSTEM, TOOLS, USER, INJECTED, ANSWERS, REASONING, TOOL_CALLS, TOOL_RESULTS);

    /** 每个类别最多保留的明细项（防止长会话把 WS 载荷撑大）。 */
    private static final int MAX_ITEMS = 60;
    /** 明细预览字符数。 */
    private static final int PREVIEW_CHARS = 220;
    /** 趋势最多保留的请求数（取最近一段）。 */
    private static final int MAX_STEPS = 120;

    private ContextInsights() {}

    /** 一个工具的线格式视图（schema 已序列化为 JSON 文本）。 */
    record ToolView(String name, String description, String schemaJson) {
    }

    /**
     * 计算完整报告。
     *
     * @param sessionId     会话 id
     * @param model         {@code provider/modelId}，可为 null
     * @param contextWindow 上下文窗口，未知为 0
     * @param maxOutputTokens 最大输出窗口，未知为 0
     * @param reserveTokens 自动压缩预留 token 数
     * @param systemPrompt  当前系统提示
     * @param tools         当前激活工具
     * @param branch        当前车道的 branch entries
     * @return 报告
     */
    static Report build(String sessionId, String model, int contextWindow, int maxOutputTokens,
                        int reserveTokens, String systemPrompt, List<ToolView> tools,
                        List<Entry> branch) {
        var projected = ContextEntries.projectEntries(branch);
        var acc = new LinkedHashMap<String, Acc>();
        for (var key : ORDER) {
            acc.put(key, new Acc());
        }

        double systemTokens = ContextTokens.text(systemPrompt);
        if (systemTokens > 0) {
            acc.get(SYSTEM).add(systemTokens, "System prompt", systemPrompt);
        }
        double toolsTokens = 0;
        for (var tool : tools) {
            double tokens = ContextTokens.tool(tool.name(), tool.description(), tool.schemaJson());
            toolsTokens += tokens;
            acc.get(TOOLS).add(tokens, tool.name(), tool.description());
        }

        List<Step> steps = new ArrayList<>();
        int request = 0;
        for (var entry : projected) {
            if (entry.source() instanceof Entry.Message message
                    && message.message() instanceof Message.AssistantMessage assistant) {
                steps.add(step(request++, assistant, acc));
            }
            for (var visible : entry.messages()) {
                addMessage(acc, entry.source(), visible);
            }
        }
        if (steps.size() > MAX_STEPS) {
            steps = new ArrayList<>(steps.subList(steps.size() - MAX_STEPS, steps.size()));
        }

        var estimate = ContextUsageEstimator.estimateProjectedContextTokens(projected, branch);
        double composition = total(acc);
        double contextTokens = estimate.usageTokens() > 0
            ? estimate.tokens()
            : estimate.tokens() + systemTokens + toolsTokens;
        contextTokens = Math.max(contextTokens, composition);
        double percent = contextWindow > 0 ? contextTokens / contextWindow * 100 : 0;
        double free = contextWindow > 0 ? Math.max(0, contextWindow - contextTokens) : 0;

        return new Report(
            sessionId,
            model,
            contextWindow,
            maxOutputTokens,
            reserveTokens,
            contextTokens,
            composition,
            percent,
            free,
            totals(branch),
            categories(acc),
            steps,
            events(branch));
    }

    // ── 组成 ────────────────────────────────────────────────────────────

    private static List<Category> categories(Map<String, Acc> acc) {
        var out = new ArrayList<Category>(ORDER.size());
        for (var key : ORDER) {
            var category = acc.get(key);
            out.add(new Category(key, labelOf(key), category.tokens, List.copyOf(category.items)));
        }
        return out;
    }

    static String labelOf(String key) {
        return switch (key) {
            case SYSTEM -> "System prompt";
            case TOOLS -> "Tool definitions";
            case USER -> "User messages";
            case INJECTED -> "Injected context";
            case ANSWERS -> "Assistant answers";
            case REASONING -> "Reasoning";
            case TOOL_CALLS -> "Tool calls";
            case TOOL_RESULTS -> "Tool results";
            default -> key;
        };
    }

    private static void addMessage(Map<String, Acc> acc, Entry source, Message message) {
        switch (message) {
            case Message.UserMessage user -> {
                boolean injected = !(source instanceof Entry.Message);
                acc.get(injected ? INJECTED : USER).add(
                    ContextTokens.blocks(user.content()),
                    injected ? injectedLabel(source) : "User message",
                    ContextTokens.textOf(user.content()));
            }
            case Message.AssistantMessage assistant -> {
                for (var block : assistant.content()) {
                    switch (block) {
                        case ContentBlock.TextContent text ->
                            acc.get(ANSWERS).add(ContextTokens.text(text.text()), "Answer", text.text());
                        case ContentBlock.ThinkingContent thinking ->
                            acc.get(REASONING).add(ContextTokens.text(thinking.text()),
                                "Thinking", thinking.text());
                        case ContentBlock.ToolUseContent call -> {
                            String args = ContextTokens.json(call.arguments());
                            acc.get(TOOL_CALLS).add(
                                ContextTokens.text(call.name()) + ContextTokens.text(args),
                                call.name(),
                                call.name() + " " + args);
                        }
                        default -> {
                            // 图片/差量块不在请求回放里拆项；总量已由 messageTokens 兜住。
                        }
                    }
                }
            }
            case Message.ToolResultMessage result ->
                acc.get(TOOL_RESULTS).add(
                    ContextTokens.blocks(result.content()),
                    result.toolName() + " result",
                    ContextTokens.textOf(result.content()));
            case Message.SystemMessage ignored -> {
                // 系统提示由 harness.currentSystemPrompt() 整份计入；转录里的 system
                // 消息是**段差补丁**（或压缩时的旧提示快照），再加一遍就是重复计数。
            }
        }
    }

    private static Step step(int index, Message.AssistantMessage assistant,
                             Map<String, Acc> acc) {
        var snapshot = new LinkedHashMap<String, Double>();
        double estimatedPrompt = 0;
        for (var key : ORDER) {
            double tokens = acc.get(key).tokens;
            snapshot.put(key, tokens);
            estimatedPrompt += tokens;
        }
        Usage usage = credibleUsage(assistant);
        boolean reported = usage != null;
        double prompt = reported ? ContextTokens.prompt(usage) : estimatedPrompt;
        double output = reported ? usage.output() : ContextTokens.assistantOutput(assistant);
        return new Step(index, prompt, output, prompt, reported, snapshot);
    }

    private static Usage credibleUsage(Message.AssistantMessage assistant) {
        Usage usage = assistant.usage();
        if (usage == null) {
            return null;
        }
        StopReason reason = assistant.stopReason();
        if (reason == StopReason.ABORTED || reason == StopReason.ERROR) {
            return null;
        }
        return ContextUsageEstimator.calculateContextTokens(usage) > 0 ? usage : null;
    }

    private static String injectedLabel(Entry source) {
        return switch (source) {
            case Entry.Compaction ignored -> "Compaction summary";
            case Entry.BranchSummary ignored -> "Branch summary";
            case Entry.CustomMessage custom -> "Custom context: " + custom.customType();
            case Entry.BashExecution ignored -> "Bash output";
            default -> "Injected context";
        };
    }

    // ── 汇总 ────────────────────────────────────────────────────────────

    private static Totals totals(List<Entry> branch) {
        int turns = 0;
        int requests = 0;
        int toolCalls = 0;
        double prompt = 0;
        double output = 0;
        double cacheRead = 0;
        double cost = 0;
        for (var entry : branch) {
            if (!(entry instanceof Entry.Message message
                    && message.message() instanceof Message.AssistantMessage assistant)) {
                if (entry instanceof Entry.Message user
                        && user.message() instanceof Message.UserMessage) {
                    turns++;
                }
                continue;
            }
            requests++;
            toolCalls += countToolCalls(assistant.content());
            Usage usage = assistant.usage();
            if (usage != null) {
                prompt += ContextTokens.prompt(usage);
                output += usage.output();
                cacheRead += usage.cacheRead();
                cost += usage.cost() == null ? 0 : usage.cost().total();
            }
        }
        double cacheHitRate = prompt > 0 ? cacheRead / prompt : 0;
        return new Totals(turns, requests, toolCalls, cacheHitRate, cost, prompt, output);
    }

    private static int countToolCalls(List<ContentBlock> blocks) {
        int count = 0;
        for (var block : blocks) {
            if (block instanceof ContentBlock.ToolUseContent) {
                count++;
            }
        }
        return count;
    }

    // ── 事件 ────────────────────────────────────────────────────────────

    private static List<Event> events(List<Entry> branch) {
        var out = new ArrayList<Event>();
        for (var entry : branch) {
            switch (entry) {
                case Entry.Compaction compaction -> out.add(new Event(
                    "compaction",
                    "Context compacted",
                    timestamp(compaction.timestamp()),
                    (double) compaction.tokensBefore(),
                    preview(compaction.summary(), 120)));
                case Entry.ModelChange change -> out.add(new Event(
                    "model",
                    "Model switched",
                    timestamp(change.timestamp()),
                    null,
                    change.provider() + "/" + change.modelId()));
                case Entry.ThinkingLevelChange change -> out.add(new Event(
                    "thinking",
                    "Thinking level changed",
                    timestamp(change.timestamp()),
                    null,
                    change.thinkingLevel()));
                case Entry.BranchSummary summary -> out.add(new Event(
                    "branch_summary",
                    "Branch summarized",
                    timestamp(summary.timestamp()),
                    null,
                    preview(summary.summary(), 120)));
                case Entry.ContextEdit edit -> out.add(new Event(
                    "context_edit",
                    "Context edited",
                    timestamp(edit.timestamp()),
                    null,
                    preview(ContextTokens.json(edit.replacement()), 120)));
                default -> {
                    // 其余 entry 不改变上下文窗口。
                }
            }
        }
        return out;
    }

    private static String timestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static double total(Map<String, Acc> acc) {
        double total = 0;
        for (var category : acc.values()) {
            total += category.tokens;
        }
        return total;
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 单个类别的累计器（token 总量 + 有上限的明细）。 */
    private static final class Acc {
        private double tokens;
        private final List<Item> items = new ArrayList<>();

        private void add(double amount, String label, String text) {
            double safe = Math.max(0, amount);
            tokens += safe;
            if (items.size() < MAX_ITEMS) {
                items.add(new Item(label, safe, preview(text, PREVIEW_CHARS)));
            }
        }
    }
}
