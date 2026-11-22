package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.AbortSignal;

/**
 * #79/B55：渐进富列表（pi {@code listSessionsFromDir}/{@code listAll}，
 * session-manager.ts:892-982,1944-2034；docs/09 §11）—— 从
 * {@link JsonlSessionRepository} 拆出（≤500 行规约），行为不变。
 */
final class RichSessionLister {

    private final JsonlSessionRepoFileSystem fs;
    private final Path sessionsRoot;
    private final Function<String, List<Path>> directoryResolver;
    private final java.util.function.BiPredicate<String, String> cwdMatches;

    RichSessionLister(Path sessionsRoot, JsonlSessionRepoFileSystem fs,
                      Function<String, List<Path>> directoryResolver,
                      java.util.function.BiPredicate<String, String> cwdMatches) {
        this.sessionsRoot = sessionsRoot;
        this.fs = fs;
        this.directoryResolver = directoryResolver;
        this.cwdMatches = cwdMatches;
    }


    /** pi {@code MAX_CONCURRENT_SESSION_INFO_LOADS}（:899）。 */
    static final int MAX_CONCURRENT_INFO_LOADS = 10;
    /** pi {@code MAX_CONCURRENT_SESSION_DISCOVERY_LOADS}（:900）。 */
    static final int MAX_CONCURRENT_DISCOVERY_LOADS = 64;
    /** pi {@code CURRENT_SESSION_LIST_PUBLISH_INTERVAL}（:901）。 */
    static final int CURRENT_LIST_PUBLISH_INTERVAL = 10;
    /** pi {@code ALL_SESSION_LIST_PUBLISH_INTERVAL}（:902）。 */
    static final int ALL_LIST_PUBLISH_INTERVAL = 100;

    /**
     * pi {@code listSessionsFromDir}（:949-982）：项目目录内文件名 desc 排序
     * （localeCompare ≙ 码点序，窄差 D4 同族）→ 10 路并发
     * {@code buildSessionInfo} → {@code loaded==1 || %10 || ==total} 发布排序快照。
     * 返回序 = 候选序（pi 不在此排序）。目录/IO 失败 ⇒ 空列表（abort 除外，pi :978-981）。
     *
     * @param cwd 项目工作目录（{@code null} ⇒ 空；跨项目走 {@link #listAllRich}）
     */
    public List<RichSessionInfo> listRich(String cwd, SessionListProgress progress,
                                          AbortSignal signal) {
        throwIfAborted(signal);
        if (cwd == null) {
            return List.of();
        }
        try {
            var files = directoryResolver.apply(cwd).stream()
                .flatMap(dir -> fs.listDir(dir).stream())
                .filter(e -> !"directory".equals(e.kind()) && e.name().endsWith(".jsonl"))
                .sorted((a, b) -> b.name().compareTo(a.name()))
                .map(JsonlSessionRepoFileSystem.DirEntry::path)
                .toList();
            int total = files.size();
            var partial = new CopyOnWriteArrayList<RichSessionInfo>();
            var loaded = new AtomicInteger();
            var results = mapWithConcurrency(files, MAX_CONCURRENT_INFO_LOADS, (file, index) -> {
                var info = buildRichInfo(file, mtimeOf(file), signal);
                // B278（docs/24 W4c）：pi list 的 includeSession 过滤（进度快照同过滤）。
                if (info != null && cwdMatches != null && !cwdMatches.test(cwd, info.cwd())) {
                    info = null;
                }
                int n = loaded.incrementAndGet();
                if (info != null) {
                    partial.add(info);
                }
                // pi :971-973 发布点。
                boolean publish = n == 1 || n % CURRENT_LIST_PUBLISH_INTERVAL == 0 || n == total;
                publish(progress, n, total, publish ? sortedByActivity(partial) : null);
                return info;
            }, signal);
            return results.stream().filter(Objects::nonNull).toList();
        } catch (SessionListAbortedException e) {
            throw e;
        } catch (RuntimeException e) {
            throwIfAborted(signal); // pi :978-981：catch 后先转抛 abort，再返回空
            return List.of();
        }
    }

    /**
     * pi {@code listAll}（:1944-2034）：64 路目录发现 → 64 路 stat、mtime desc 排序
     * （basename 决胜）→ 10 路加载，每 100 条发布（首候选必发，:2022-2026）。
     * 返回活动序。整批失败 ⇒ 空列表（abort 除外，pi :2031-2034）。
     */
    public List<RichSessionInfo> listAllRich(SessionListProgress progress, AbortSignal signal) {
        throwIfAborted(signal);
        if (!fs.exists(sessionsRoot)) {
            return List.of();
        }
        try {
            List<Path> dirs = fs.listDir(sessionsRoot).stream()
                .filter(e -> "directory".equals(e.kind()))
                .map(JsonlSessionRepoFileSystem.DirEntry::path)
                .toList();
            var dirFiles = mapWithConcurrency(dirs, MAX_CONCURRENT_DISCOVERY_LOADS, (dir, i) -> {
                try {
                    return fs.listDir(dir).stream()
                        .filter(e -> !"directory".equals(e.kind()) && e.name().endsWith(".jsonl"))
                        .map(JsonlSessionRepoFileSystem.DirEntry::path)
                        .toList();
                } catch (RuntimeException e) {
                    return List.<Path>of(); // pi :1987-1989：单目录失败不拖垮整批
                }
            }, signal);
            var allFiles = dirFiles.stream().flatMap(List::stream).toList();
            record Candidate(Path path, long mtimeMs) {}
            var candidates = new ArrayList<>(mapWithConcurrency(allFiles,
                MAX_CONCURRENT_DISCOVERY_LOADS, (p, i) -> new Candidate(p, mtimeOf(p)), signal));
            // pi :2006-2010：mtime desc，basename localeCompare 决胜（码点序，窄差 D4 同族）。
            candidates.sort(Comparator.comparingLong(Candidate::mtimeMs).reversed()
                .thenComparing((a, b) -> b.path().getFileName().toString()
                    .compareTo(a.path().getFileName().toString())));
            int total = candidates.size();
            var partial = new CopyOnWriteArrayList<RichSessionInfo>();
            var loaded = new AtomicInteger();
            var firstCandidateLoaded = new AtomicBoolean();
            var results = mapWithConcurrency(candidates, MAX_CONCURRENT_INFO_LOADS, (cand, index) -> {
                var info = buildRichInfo(cand.path(), cand.mtimeMs(), signal);
                int n = loaded.incrementAndGet();
                if (index == 0) {
                    firstCandidateLoaded.set(true);
                }
                if (info != null) {
                    partial.add(info);
                }
                // pi :2022-2026 发布点（首候选落地前不发布快照）。
                boolean publish = firstCandidateLoaded.get()
                    && (index == 0 || n % ALL_LIST_PUBLISH_INTERVAL == 0 || n == total);
                publish(progress, n, total, publish ? sortedByActivity(partial) : null);
                return info;
            }, signal);
            return sortedByActivity(results.stream().filter(Objects::nonNull).toList());
        } catch (SessionListAbortedException e) {
            throw e;
        } catch (RuntimeException e) {
            throwIfAborted(signal); // pi :2031-2034：catch 后先转抛 abort，再返回空
            return List.of();
        }
    }

    /**
     * pi {@code buildSessionInfo}（:800-890）：全文件逐行读 —— header 必为首条
     * （否则 {@code null}，:826）、最新 {@code session_info} 名（空名 ⇒ null）、
     * message 计数、lastActivityTime（user/assistant 的 message.timestamp 或
     * entry 时刻）、首条 user 文本（缺席 ⇒ {@code "(no messages)"}）、
     * {@code allMessagesText}（text 块空格拼接）。读失败 ⇒ {@code null}
     * （abort 除外，直接抛）。
     */
    private RichSessionInfo buildRichInfo(Path path, long fileMtimeMs, AbortSignal signal) {
        try {
            long mtimeMs = fileMtimeMs;
            if (mtimeMs < 0) {
                mtimeMs = fs.fileInfoMtimeMs(path); // pi :806：fileStats ?? stat
            }
            String id = null;
            String cwd = "";
            long headerTimeMs = -1;
            String parentSessionPath = null;
            String name = null;
            int messageCount = 0;
            String firstMessage = "";
            var allMessages = new StringBuilder();
            long lastActivity = 0;
            boolean headerSeen = false;
            for (String line : fs.readTextFile(path).split("\n")) {
                throwIfAborted(signal);
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = SessionJson.mapper().readTree(line);
                } catch (Exception e) {
                    continue; // pi parseSessionEntryLine：不可解析行跳过
                }
                String type = node.path("type").isTextual() ? node.path("type").asText() : null;
                if (type == null) {
                    continue;
                }
                if (!headerSeen) {
                    if (!"session".equals(type)) {
                        return null; // pi :826：首条非 header ⇒ 不是会话文件
                    }
                    headerSeen = true;
                    id = node.path("id").isTextual() ? node.path("id").asText() : null;
                    cwd = node.path("cwd").isTextual() ? node.path("cwd").asText() : "";
                    headerTimeMs = parseTimeMs(node.path("timestamp"));
                    // pi header.parentSession（session-manager.ts:865）：threaded 树认父。
                    parentSessionPath = node.path("parentSession").isTextual()
                        ? node.path("parentSession").asText() : null;
                    continue;
                }
                if ("session_info".equals(type)) {
                    // pi :832-834：最新 wins，空名 ⇒ undefined。
                    String raw = node.path("name").isTextual() ? node.path("name").asText() : null;
                    name = raw == null || raw.trim().isEmpty() ? null : raw.trim();
                    continue;
                }
                if (!"message".equals(type)) {
                    continue;
                }
                messageCount++;
                JsonNode message = node.path("message");
                String role = message.path("role").isTextual() ? message.path("role").asText() : null;
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                // pi getMessageActivityTime（:786-796）：message.timestamp 优先。
                long activity = message.path("timestamp").isNumber()
                    ? message.path("timestamp").longValue() : parseTimeMs(node.path("timestamp"));
                if (activity >= 0) {
                    lastActivity = Math.max(lastActivity, activity);
                }
                String textContent = extractText(message);
                if (textContent.isEmpty()) {
                    continue;
                }
                if (allMessages.length() > 0) {
                    allMessages.append(' ');
                }
                allMessages.append(textContent);
                if (firstMessage.isEmpty() && "user".equals(role)) {
                    firstMessage = textContent;
                }
            }
            throwIfAborted(signal); // pi :860：readline 等到 EOF 之后的中止
            if (!headerSeen || id == null) {
                return null;
            }
            long created = headerTimeMs >= 0 ? headerTimeMs : mtimeMs;
            // pi :867-872：lastActivity > headerTime > 文件 mtime。
            long modified = lastActivity > 0 ? lastActivity : created;
            return new RichSessionInfo(id, path.toString(), name, cwd, created, modified,
                messageCount, firstMessage.isEmpty() ? "(no messages)" : firstMessage,
                allMessages.toString(), parentSessionPath);
        } catch (SessionListAbortedException e) {
            throw e;
        } catch (Exception e) {
            throwIfAborted(signal); // pi :886-888：catch 后先转抛 abort
            return null;
        }
    }

    /** pi {@code extractTextContent}（:775-783）：裸串或 text 块空格拼接。 */
    private static String extractText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText(null)) && block.path("text").isTextual()) {
                parts.add(block.path("text").asText());
            }
        }
        return String.join(" ", parts);
    }

    /** ISO 时刻 ⇒ epoch ms；缺席/不可解析 ⇒ {@code -1}。 */
    private static long parseTimeMs(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return -1;
        }
        try {
            return Instant.parse(node.asText()).toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 文件 mtime；读不到 ⇒ {@code 0}（pi stat 失败 ⇒ 无 stats 候选，末位排序）。 */
    private long mtimeOf(Path path) {
        try {
            return fs.fileInfoMtimeMs(path);
        } catch (RuntimeException e) {
            return -1; // pi :1999-2000：候选可无 stats，buildSessionInfo 再试一次
        }
    }

    /** pi {@code sortSessionInfos}（:928-930）：活动时刻 desc。 */
    private static List<RichSessionInfo> sortedByActivity(List<RichSessionInfo> infos) {
        return infos.stream()
            .sorted(Comparator.comparingLong(RichSessionInfo::modifiedMs).reversed())
            .toList();
    }

    private static void publish(SessionListProgress progress, int loaded, int total,
                                List<RichSessionInfo> partialSessions) {
        if (progress != null) {
            progress.onProgress(loaded, total, partialSessions);
        }
    }

    private static void throwIfAborted(AbortSignal signal) {
        if (signal != null && signal.isAborted()) {
            throw new SessionListAbortedException();
        }
    }

    /** 带索引的映射（pi {@code map} 回调的 {@code (item, index)} 形）。 */
    private interface IndexedMapper<T, R> {
        R apply(T item, int index);
    }

    /**
     * pi {@code mapWithConcurrency}（:909-926）：共享 {@code nextIndex} 的 worker 池
     * （虚拟线程）；worker 循环头 {@code throwIfAborted}；任一 worker 失败整批抛。
     */
    private static <T, R> List<R> mapWithConcurrency(List<T> items, int limit,
                                                     IndexedMapper<T, R> map, AbortSignal signal) {
        var results = new ArrayList<R>(java.util.Collections.nCopies(items.size(), null));
        var nextIndex = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int w = 0; w < Math.min(limit, items.size()); w++) {
                futures.add(executor.submit(() -> {
                    int index;
                    while ((index = nextIndex.getAndIncrement()) < items.size()) {
                        throwIfAborted(signal);
                        results.set(index, map.apply(items.get(index), index));
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Session list interrupted", e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("Session list failed", e.getCause());
                }
            }
        }
        return results;
    }
}
