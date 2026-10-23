package com.pijava.coding.agent.core.crash;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.Json;
import com.pijava.coding.agent.extension.InstalledExtension;

/**
 * 崩溃记录本体（pi {@code core/crash-log.ts} 逐函数对位）。
 *
 * <p>进程将死时把崩溃写入 {@code agentDir/crashes.json}（最多 {@value #MAX_CRASH_RECORDS}
 * 条、{@value #MAX_AGE_MILLIS} 毫秒有效），下次启动提示用户「Run /bug」。全部入口
 * best-effort：崩了不能再崩，任何失败静默（读空表／写丢记录／返回 {@code null}）。</p>
 *
 * <p>路径与环境<b>每调现解析</b>（批 14 静态冻结 {@code user.home} 教训），
 * 测试经 {@code PI_JAVA_CODING_AGENT_DIR} 注入临时 home。</p>
 */
public final class CrashLog {

    /** pi {@code MAX_CRASH_RECORDS}：只留末 5 条。 */
    public static final int MAX_CRASH_RECORDS = 5;
    /** pi {@code MAX_AGE}：7 天有效窗。 */
    public static final long MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private CrashLog() {}

    /** pi {@code crashLogPath(agentDir = getAgentDir())}。 */
    public static Path crashLogPath() {
        return FileSettingsStorage.defaultAgentDir().resolve("crashes.json");
    }

    /**
     * pi {@code readCrashLog}：读失败／非数组／缺 {@code timestamp} 或
     * {@code message} 字段 ⇒ 静默过滤或空表。
     */
    public static List<CrashRecord> read(Path path) {
        try {
            JsonNode root = Json.mapper().readTree(Files.readString(path));
            if (!root.isArray()) {
                return List.of();
            }
            var records = new ArrayList<CrashRecord>();
            for (JsonNode node : root) {
                if (!node.isObject()
                        || !node.path("timestamp").isTextual()
                        || !node.path("message").isTextual()) {
                    continue;
                }
                try {
                    records.add(Json.mapper().treeToValue(node, CrashRecord.class));
                } catch (Exception ignored) {
                    // 单条畸形不拖累整表（崩了不能再崩）。
                }
            }
            return List.copyOf(records);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * pi {@code recordCrash}：message 取 {@code error.message || error.name}
     * （空 message 回落类名）；追加后只留末 {@value #MAX_CRASH_RECORDS} 条；
     * 整体 try/catch 返回 {@code null}。
     */
    public static CrashRecord record(
            CrashRecord.Kind kind,
            Throwable error,
            String sessionFile,
            String cwd,
            Path path) {
        try {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            var record = new CrashRecord(
                Instant.now().toString(),
                Version.VERSION,
                kind,
                message,
                stackTraceOf(error),
                sessionFile,
                cwd,
                null);
            var records = new ArrayList<>(read(path));
            records.add(record);
            if (records.size() > MAX_CRASH_RECORDS) {
                records = new ArrayList<>(
                    records.subList(records.size() - MAX_CRASH_RECORDS, records.size()));
            }
            write(records, path);
            return record;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * pi {@code takeUnnotifiedCrash}：取最新一条未通知且 ≤{@value #MAX_AGE_MILLIS}
     * 毫秒的；命中后把<b>所有</b>未通知记录标记 {@code notified: true} 回写；
     * 回写失败静默（下次再提示无害）。
     */
    public static CrashRecord takeUnnotified(Path path, long nowMillis) {
        List<CrashRecord> records = read(path);
        CrashRecord found = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            var record = records.get(i);
            if (Boolean.TRUE.equals(record.notified())) {
                continue;
            }
            long age = ageMillis(record.timestamp(), nowMillis);
            if (age <= MAX_AGE_MILLIS) {
                found = record;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        try {
            var marked = records.stream()
                .map(record -> Boolean.TRUE.equals(record.notified())
                    ? record
                    : new CrashRecord(record.timestamp(), record.version(), record.kind(),
                        record.message(), record.stack(), record.sessionFile(),
                        record.cwd(), Boolean.TRUE))
                .toList();
            write(marked, path);
        } catch (Exception ignored) {
            // Showing the notice again is harmless.
        }
        return found;
    }

    /** pi {@code clearCrashLog}（{@code rmSync force} 对位）：删除失败静默。 */
    public static void clear(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The records can be attached again if cleanup fails.
        }
    }

    /**
     * pi {@code findExtensionStackMatches} 的降级形（批 15 裁决②）：java 栈帧
     * 是类名非文件路径，针＝扩展 JAR 文件名（去 {@code .jar}，与
     * {@link InstalledExtension#of} 命名同规则），栈文本小写 contains；
     * label＝清单 {@code name}。命中低、零误报（窄差 D80）。
     */
    public static List<String> findExtensionStackMatches(
            String stack, List<InstalledExtension> extensions) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        var haystack = stack.toLowerCase(Locale.ROOT);
        var matches = new LinkedHashSet<String>();
        for (var extension : extensions) {
            var needle = stripJarSuffix(extension.fileName());
            if (!needle.isEmpty()
                    && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                matches.add(extension.name());
            }
        }
        return List.copyOf(matches);
    }

    private static String stripJarSuffix(String fileName) {
        return fileName != null && fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4)
            : fileName == null ? "" : fileName;
    }

    private static long ageMillis(String timestamp, long nowMillis) {
        try {
            return nowMillis - Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            // pi Date.parse 失败 ⇒ NaN ⇒ NaN <= MAX_AGE 为 false（不匹配）。
            return Long.MAX_VALUE;
        }
    }

    private static String stackTraceOf(Throwable error) {
        var writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void write(List<CrashRecord> records, Path path) throws Exception {
        var parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var json = Json.mapper().writerWithDefaultPrettyPrinter()
            .writeValueAsString(records);
        Files.writeString(path, json + "\n");
    }
}
