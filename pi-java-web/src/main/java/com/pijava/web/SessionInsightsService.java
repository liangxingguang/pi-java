package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.jsonl.DefaultJsonlFileSystem;
import com.pijava.agent.session.jsonl.JsonlSessionListOptions;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.coding.agent.cli.Args;

import com.pijava.web.SessionInsightsReport.CompositionSlice;
import com.pijava.web.SessionInsightsReport.DayPoint;
import com.pijava.web.SessionInsightsReport.Report;
import com.pijava.web.SessionInsightsReport.SessionCard;
import com.pijava.web.SessionInsightsReport.ToolCount;

/**
 * 跨会话洞察扫描器（Web 扩展）。
 *
 * <p>默认 JSONL 后端：用 {@link JsonlSessionRepository} 的 header-only 列表拿到
 * 全部会话元数据，再逐个 open / 读取 entries 聚合 token、cost、按日用量、工具调用
 * 与会话卡片。SQLite 后端不在本扩展的首版范围内（返回 {@code supported=false}）。</p>
 *
 * <p>扫描结果按 {@code path + modifiedAtMs} 缓存在进程内；重复请求只重扫变更过的
 * 会话。单次请求只处理时间范围内有写入的会话，卡片上限 200。</p>
 */
final class SessionInsightsService {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final int MAX_CARDS = 200;
    private static final int MAX_CACHE = 1024;
    private static final int MAX_ALL_DAYS = 90;
    private static final int PREVIEW_CHARS = 140;
    /** 历史会话能重建的消息类别（系统提示/工具快照不可得）。 */
    private static final List<String> COMPOSITION_KEYS = List.of(
        ContextInsights.USER, ContextInsights.INJECTED, ContextInsights.ANSWERS,
        ContextInsights.REASONING, ContextInsights.TOOL_CALLS, ContextInsights.TOOL_RESULTS);

    private final Path sessionsRoot;
    private final Path defaultRoot;
    private final boolean flat;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    SessionInsightsService(Args args) {
        this.defaultRoot = Path.of(System.getProperty("user.home"),
            ".pi-java", "agent", "sessions");
        String configured = args == null ? null : args.sessionDir();
        this.flat = configured != null && !configured.isBlank();
        this.sessionsRoot = flat ? Path.of(configured) : defaultRoot;
    }

    /** 测试缝：显式根目录。 */
    SessionInsightsService(Path sessionsRoot, Path defaultRoot, boolean flat) {
        this.sessionsRoot = sessionsRoot;
        this.defaultRoot = defaultRoot;
        this.flat = flat;
    }

    /**
     * 计算一个时间范围的跨会话报告。
     *
     * @param range today / 7d / 30d / all（其它值按 7d）
     * @return 报告；根目录不存在时返回空报告
     */
    Report report(String range) {
        return report(range, "jsonl");
    }

    /**
     * 计算一个时间范围的报告；非 JSONL 后端（SQLite）明确返回
     * {@code supported=false}，由前端显示后端不支持，而不是展示空的 JSONL 数据。
     */
    Report report(String range, String backend) {
        if (backend != null && !"jsonl".equalsIgnoreCase(backend)) {
            return unsupported(range, backend);
        }
        var window = Window.of(range);
        if (!Files.exists(sessionsRoot)) {
            return empty(window);
        }
        var repo = new JsonlSessionRepository(sessionsRoot, defaultRoot, flat,
            new DefaultJsonlFileSystem());
        List<JsonlSessionMetadata> metas;
        try {
            metas = repo.list(JsonlSessionListOptions.all());
        } catch (RuntimeException e) {
            return empty(window);
        }
        long sinceMs = window.sinceDate() == null
            ? Long.MIN_VALUE
            : window.sinceDate().atStartOfDay(ZONE).toInstant().toEpochMilli();

        var globalDays = new TreeMap<LocalDate, Day>();
        var globalTools = new HashMap<String, Integer>();
        var cards = new ArrayList<SessionCard>();
        double totalPrompt = 0;
        double totalOutput = 0;
        double totalCost = 0;
        double totalCacheRead = 0;
        int totalTurns = 0;
        int totalTools = 0;

        for (var meta : metas) {
            if (window.sinceDate() != null && meta.modifiedAtMs() < sinceMs) {
                continue;
            }
            var cached = scanCached(meta, repo);
            if (cached == null) {
                continue;
            }
            var card = card(cached, meta, window);
            if (card == null) {
                continue;
            }
            cards.add(card);
            totalPrompt += card.promptTokens();
            totalOutput += card.outputTokens();
            totalCost += card.costUsd();
            totalTurns += card.turns();
            totalTools += card.toolCalls();
            totalCacheRead += cached.cacheReadIn(window);
            for (var day : cached.days().entrySet()) {
                if (!window.inRange(day.getKey())) {
                    continue;
                }
                globalDays.computeIfAbsent(day.getKey(), ignored -> new Day()).add(day.getValue());
            }
            for (var day : cached.toolDays().entrySet()) {
                if (!window.inRange(day.getKey())) {
                    continue;
                }
                day.getValue().forEach((name, count) ->
                    globalTools.merge(name, count, Integer::sum));
            }
        }

        cards.sort(Comparator.comparingLong(SessionCard::modifiedMs).reversed());
        int sessionCount = cards.size();
        var topCards = cards.size() > MAX_CARDS
            ? List.copyOf(cards.subList(0, MAX_CARDS))
            : List.copyOf(cards);
        double cacheHitRate = totalPrompt > 0 ? totalCacheRead / totalPrompt : 0;
        return new Report(
            window.label(),
            true,
            "jsonl",
            sessionCount,
            totalPrompt,
            totalOutput,
            totalPrompt + totalOutput,
            totalCost,
            cacheHitRate,
            totalTurns,
            totalTools,
            days(globalDays, window),
            tools(globalTools),
            topCards);
    }

    // ── 单会话扫描 ─────────────────────────────────────────────────────
    private Cached scanCached(JsonlSessionMetadata meta, JsonlSessionRepository repo) {
        String key = meta.path().toString();
        var existing = cache.get(key);
        if (existing != null && existing.modifiedMs() == meta.modifiedAtMs()) {
            return existing;
        }
        Cached scanned;
        try {
            scanned = scan(meta, repo);
        } catch (RuntimeException e) {
            return null;
        }
        if (scanned != null) {
            if (cache.size() >= MAX_CACHE) {
                cache.clear();
            }
            cache.put(key, scanned);
        }
        return scanned;
    }

    private Cached scan(JsonlSessionMetadata meta, JsonlSessionRepository repo) {
        Session<JsonlSessionMetadata> session = repo.open(meta);
        // 组成投影必须按提交序（oldest first），否则 pathToLeaf 会把最旧条目当叶子。
        var entries = session.findEntries(new EntryQuery(
            null, null, EntryOrder.OLDEST_FIRST, null, null));
        var days = new HashMap<LocalDate, Day>();
        var toolDays = new HashMap<LocalDate, Map<String, Integer>>();
        var composition = new HashMap<String, Double>();
        for (var key : COMPOSITION_KEYS) {
            composition.put(key, 0.0);
        }
        for (var projected : ContextEntries.projectEntries(entries)) {
            for (var visible : projected.messages()) {
                addComposition(composition, projected.source(), visible);
            }
        }
        LocalDate fallbackDate = meta.createdAt() == null
            ? LocalDate.now(ZONE)
            : LocalDate.ofInstant(meta.createdAt(), ZONE);
        int messages = 0;
        String firstMessage = "";
        String model = null;
        long modelTs = Long.MIN_VALUE;

        for (var entry : entries) {
            var date = entry.timestamp() == null
                ? fallbackDate
                : LocalDate.ofInstant(entry.timestamp(), ZONE);
            if (entry instanceof Entry.Message messageEntry) {
                messages++;
                var message = messageEntry.message();
                if (message instanceof Message.UserMessage user) {
                    day(days, date).turns++;
                    if (firstMessage.isEmpty()) {
                        firstMessage = preview(MessageTexts.contentText(user.content()));
                    }
                } else if (message instanceof Message.AssistantMessage assistant) {
                    addTools(days, toolDays, date, assistant.content());
                    addUsage(days, date, assistant.usage());
                    if (assistant.model() != null && entry.timestamp() != null
                            && entry.timestamp().toEpochMilli() > modelTs) {
                        model = assistant.provider() + "/" + assistant.model();
                        modelTs = entry.timestamp().toEpochMilli();
                    }
                } else if (message instanceof Message.ToolResultMessage tool
                        && tool.usage() instanceof Usage usage) {
                    addUsage(days, date, usage);
                }
            } else if (entry instanceof Entry.Usage usageEntry) {
                addUsage(days, date, usageEntry.usage());
                if (usageEntry.model() != null && entry.timestamp() != null
                        && entry.timestamp().toEpochMilli() > modelTs) {
                    model = usageEntry.provider() + "/" + usageEntry.model();
                    modelTs = entry.timestamp().toEpochMilli();
                }
            } else if (entry instanceof Entry.Compaction compaction) {
                addUsage(days, date, compaction.usage());
            } else if (entry instanceof Entry.BranchSummary summary) {
                addUsage(days, date, summary.usage());
            } else if (entry instanceof Entry.ModelChange change) {
                if (entry.timestamp() != null && entry.timestamp().toEpochMilli() > modelTs) {
                    model = change.provider() + "/" + change.modelId();
                    modelTs = entry.timestamp().toEpochMilli();
                }
            } else if (entry instanceof Entry.BashExecution) {
                messages++;
            }
        }
        return new Cached(meta.modifiedAtMs(), session.getName(), messages,
            firstMessage, model, days, toolDays, composition);
    }

    /**
     * 历史会话的消息组成（降级口径）：只统计消息内容，系统提示/工具定义没有
     * 当时快照，不计入。压缩/分支摘要/custom/bash 按注入上下文归类。
     */
    private static void addComposition(Map<String, Double> composition, Entry source,
                                       Message message) {
        switch (message) {
            case Message.UserMessage user -> {
                String key = source instanceof Entry.Message
                    ? ContextInsights.USER : ContextInsights.INJECTED;
                composition.merge(key, ContextTokens.blocks(user.content()), Double::sum);
            }
            case Message.AssistantMessage assistant -> {
                for (var block : assistant.content()) {
                    switch (block) {
                        case ContentBlock.TextContent text -> composition.merge(
                            ContextInsights.ANSWERS, ContextTokens.text(text.text()), Double::sum);
                        case ContentBlock.ThinkingContent thinking -> composition.merge(
                            ContextInsights.REASONING, ContextTokens.text(thinking.text()), Double::sum);
                        case ContentBlock.ToolUseContent call -> composition.merge(
                            ContextInsights.TOOL_CALLS,
                            ContextTokens.text(call.name())
                                + ContextTokens.text(ContextTokens.json(call.arguments())),
                            Double::sum);
                        default -> {
                            // 图片/差量块不在消息组成里拆项。
                        }
                    }
                }
            }
            case Message.ToolResultMessage result -> composition.merge(
                ContextInsights.TOOL_RESULTS, ContextTokens.blocks(result.content()), Double::sum);
            case Message.SystemMessage ignored -> {
                // 历史系统提示不可得（且当时文本未按会话完整持久化）。
            }
        }
    }

    private static void addUsage(Map<LocalDate, Day> days, LocalDate date, Usage usage) {
        if (usage == null) {
            return;
        }
        var day = day(days, date);
        day.prompt += usage.input() + usage.cacheRead() + usage.cacheWrite();
        day.output += usage.output();
        day.cacheRead += usage.cacheRead();
        day.cost += usage.cost() == null ? 0 : usage.cost().total();
    }

    private static void addTools(Map<LocalDate, Day> days,
                                 Map<LocalDate, Map<String, Integer>> toolDays,
                                 LocalDate date, List<ContentBlock> content) {
        for (var block : content) {
            if (!(block instanceof ContentBlock.ToolUseContent call)) {
                continue;
            }
            day(days, date).toolCalls++;
            toolDays.computeIfAbsent(date, ignored -> new LinkedHashMap<>())
                .merge(call.name(), 1, Integer::sum);
        }
    }

    private static Day day(Map<LocalDate, Day> days, LocalDate date) {
        return days.computeIfAbsent(date, ignored -> new Day());
    }

    // ── 卡片与序列 ─────────────────────────────────────────────────────
    private static SessionCard card(Cached cached, JsonlSessionMetadata meta, Window window) {
        double prompt = 0;
        double output = 0;
        double cost = 0;
        double cacheRead = 0;
        int turns = 0;
        int toolCalls = 0;
        for (var day : cached.days().entrySet()) {
            if (!window.inRange(day.getKey())) {
                continue;
            }
            prompt += day.getValue().prompt;
            output += day.getValue().output;
            cost += day.getValue().cost;
            cacheRead += day.getValue().cacheRead;
            turns += day.getValue().turns;
            toolCalls += day.getValue().toolCalls;
        }
        if (window.sinceDate() != null
                && prompt == 0 && output == 0 && turns == 0 && toolCalls == 0) {
            return null;
        }
        var composition = new ArrayList<CompositionSlice>();
        for (var key : COMPOSITION_KEYS) {
            composition.add(new CompositionSlice(key, ContextInsights.labelOf(key),
                cached.composition().getOrDefault(key, 0.0)));
        }
        return new SessionCard(
            meta.id(),
            meta.path().toString(),
            cached.name() == null ? "" : cached.name(),
            meta.cwd() == null ? "" : meta.cwd(),
            meta.createdAt() == null ? null : meta.createdAt().toString(),
            meta.modifiedAtMs(),
            cached.messages(),
            turns,
            toolCalls,
            prompt,
            output,
            cost,
            prompt > 0 ? Math.min(1, cacheRead / prompt) : 0,
            cached.model(),
            meta.parentSessionId(),
            cached.firstMessage(),
            List.copyOf(composition));
    }

    private static List<DayPoint> days(TreeMap<LocalDate, Day> observed, Window window) {
        var out = new ArrayList<DayPoint>();
        if (window.days() > 0) {
            var today = LocalDate.now(ZONE);
            var start = today.minusDays(window.days() - 1L);
            for (var date = start; !date.isAfter(today); date = date.plusDays(1)) {
                var day = observed.get(date);
                out.add(new DayPoint(date.toString(),
                    day == null ? 0 : day.prompt,
                    day == null ? 0 : day.output,
                    day == null ? 0 : day.cost));
            }
            return out;
        }
        var dates = new ArrayList<>(observed.keySet());
        dates.sort(Comparator.naturalOrder());
        int from = Math.max(0, dates.size() - MAX_ALL_DAYS);
        for (var date : dates.subList(from, dates.size())) {
            var day = observed.get(date);
            out.add(new DayPoint(date.toString(), day.prompt, day.output, day.cost));
        }
        return out;
    }

    private static List<ToolCount> tools(Map<String, Integer> counts) {
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(12)
            .map(entry -> new ToolCount(entry.getKey(), entry.getValue()))
            .toList();
    }

    private static Report empty(Window window) {
        return new Report(window.label(), true, "jsonl", 0, 0, 0, 0, 0, 0, 0, 0,
            List.of(), List.of(), List.of());
    }

    private static Report unsupported(String range, String backend) {
        var window = Window.of(range);
        return new Report(window.label(), false, backend, 0, 0, 0, 0, 0, 0, 0, 0,
            List.of(), List.of(), List.of());
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS
            ? flat
            : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    // ── 内部模型 ───────────────────────────────────────────────────────

    private record Cached(
            long modifiedMs,
            String name,
            int messages,
            String firstMessage,
            String model,
            Map<LocalDate, Day> days,
            Map<LocalDate, Map<String, Integer>> toolDays,
            Map<String, Double> composition) {

        double cacheReadIn(Window window) {
            double sum = 0;
            for (var day : days.entrySet()) {
                if (window.inRange(day.getKey())) {
                    sum += day.getValue().cacheRead;
                }
            }
            return sum;
        }
    }

    private static final class Day {
        private double prompt;
        private double output;
        private double cacheRead;
        private double cost;
        private int turns;
        private int toolCalls;

        private void add(Day other) {
            prompt += other.prompt;
            output += other.output;
            cacheRead += other.cacheRead;
            cost += other.cost;
            turns += other.turns;
            toolCalls += other.toolCalls;
        }
    }

    private record Window(String label, int days, LocalDate sinceDate) {

        static Window of(String range) {
            String value = range == null ? "7d" : range;
            var today = LocalDate.now(ZONE);
            return switch (value) {
                case "today" -> new Window("today", 1, today);
                case "30d" -> new Window("30d", 30, today.minusDays(29));
                case "all" -> new Window("all", 0, null);
                default -> new Window("7d", 7, today.minusDays(6));
            };
        }

        boolean inRange(LocalDate date) {
            return sinceDate == null || !date.isBefore(sinceDate);
        }
    }
}
