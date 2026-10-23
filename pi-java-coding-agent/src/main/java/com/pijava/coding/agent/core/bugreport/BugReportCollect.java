package com.pijava.coding.agent.core.bugreport;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.UuidV7;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.provider.Provider;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.crash.CrashRecord;
import com.pijava.coding.agent.extension.InstalledExtension;

/**
 * bug report 元数据与诊断收集（pi {@code core/bug-report.ts} 的
 * {@code collectBugReportMetadata}/{@code collectBugReportDiagnostics} 逐段对位）。
 *
 * <p>裁决⑧：java 无 ModelRuntime 聚合面／扩展运行时元数据，不可得键<b>省略</b>
 * （不造恒值）——provider 节缺 {@code authStatus/usingOAuth/registeredByExtension}、
 * model 节缺 {@code input}、{@code extensionErrors} 恒空（窄差 D82）。</p>
 */
public final class BugReportCollect {

    private BugReportCollect() {}

    /**
     * pi {@code CollectBugReportMetadataOptions} 对位。{@code id} 为 null ⇒
     * 生成 UUID v7；{@code authTypes} 由调用方按凭证存储预算（裁决⑧：
     * {@code "api_key"}／{@code "oauth"} 有序）。
     */
    public record CollectOptions(
        String id,
        String hint,
        String sessionId,
        String cwd,
        boolean includeSession,
        boolean includeSummary,
        int messageCount,
        ModelInfo model,
        Provider provider,
        List<String> authTypes,
        String thinkingLevel,
        List<InstalledExtension> extensions,
        Settings globalSettings,
        Settings projectSettings
    ) {}

    /** pi {@code collectBugReportMetadata}：键序保持 pi 声明序（ObjectNode 保序）。 */
    public static ObjectNode collectMetadata(CollectOptions options) {
        var root = BugReport.MAPPER.createObjectNode();
        root.put("schemaVersion", BugReport.SCHEMA_VERSION);
        root.put("id", options.id() != null ? options.id() : UuidV7.uuid().toString());
        root.put("createdAt", Instant.now().toString());
        var hint = options.hint() != null && !options.hint().trim().isEmpty()
            ? options.hint().trim() : null;
        if (hint != null) {
            root.put("hint", hint);
        } else {
            root.putNull("hint");
        }
        root.set("environment", collectEnvironment());
        var session = root.putObject("session");
        session.put("id", options.sessionId());
        session.put("included", options.includeSession());
        session.put("summaryIncluded", options.includeSummary());
        session.put("messageCount", options.messageCount());
        if (options.includeSession()) {
            session.put("cwd", options.cwd());
        }
        if (options.model() != null) {
            root.set("model", describeModel(options.model()));
        } else {
            root.putNull("model");
        }
        if (options.provider() != null) {
            root.set("provider", describeProvider(options.provider(), options.authTypes()));
        } else {
            root.putNull("provider");
        }
        if (options.thinkingLevel() != null) {
            root.put("thinkingLevel", options.thinkingLevel());
        } else {
            root.putNull("thinkingLevel");
        }
        var extensions = root.putArray("extensions");
        for (var extension : options.extensions()) {
            extensions.add(describeExtension(extension));
        }
        // java 无扩展加载错误聚合 ⇒ 恒空表（D82 注记）。
        root.putArray("extensionErrors");
        var settings = root.putObject("settings");
        settings.set("global", BugReport.redactSettings(options.globalSettings()));
        settings.set("project", BugReport.redactSettings(options.projectSettings()));
        return root;
    }

    /**
     * pi {@code collectBugReportDiagnostics}：只收失败/异常臂（diagnostics 非空｜
     * stopReason error/aborted｜errorMessage 非空），正常 assistant 消息只计入
     * {@code assistantMessageCount}；crashes 剥 {@code notified} 键。
     */
    public static ObjectNode collectDiagnostics(
            List<Entry> entries, String sessionId, List<CrashRecord> crashes) {
        var assistant = BugReport.MAPPER.createArrayNode();
        int assistantMessageCount = 0;
        for (var entry : entries) {
            if (!(entry instanceof Entry.Message messageEntry)
                    || !(messageEntry.message() instanceof Message.AssistantMessage message)) {
                continue;
            }
            assistantMessageCount++;
            var diagnostics = message.diagnostics() != null
                ? message.diagnostics() : List.<com.pijava.ai.message.AssistantMessageDiagnostic>of();
            boolean failed = !diagnostics.isEmpty()
                || message.stopReason() == StopReason.ERROR
                || message.stopReason() == StopReason.ABORTED
                || (message.errorMessage() != null && !message.errorMessage().isEmpty());
            if (!failed) {
                continue;
            }
            var item = assistant.addObject();
            item.put("entryId", entry.id());
            item.put("timestamp", entry.timestamp() != null ? entry.timestamp().toString() : null);
            putNullable(item, "provider", message.provider());
            putNullable(item, "model", message.model());
            putNullable(item, "api", message.api());
            if (message.stopReason() != null) {
                item.put("stopReason", message.stopReason().wireName());
            } else {
                item.putNull("stopReason");
            }
            // pi 条件展开（=== undefined 不产键）。
            if (message.rawStopReason() != null) {
                item.put("rawStopReason", message.rawStopReason());
            }
            if (message.errorMessage() != null) {
                item.put("errorMessage", message.errorMessage());
            }
            item.set("diagnostics", BugReport.MAPPER.valueToTree(diagnostics));
        }
        var root = BugReport.MAPPER.createObjectNode();
        root.put("schemaVersion", BugReport.SCHEMA_VERSION);
        root.put("sessionId", sessionId);
        root.put("entryCount", entries.size());
        root.put("assistantMessageCount", assistantMessageCount);
        root.set("assistant", assistant);
        var crashesNode = root.putArray("crashes");
        for (var crash : crashes) {
            ObjectNode node = BugReport.MAPPER.valueToTree(crash);
            node.remove("notified");
            crashesNode.add(node);
        }
        return root;
    }

    /** pi {@code collectEnvironment}；runtime/platform 族取 JVM 系统属性（裁决⑧子集）。 */
    private static ObjectNode collectEnvironment() {
        var env = BugReport.MAPPER.createObjectNode();
        env.put("version", Version.VERSION);
        // pi getPiUserAgent 形（品牌差随 D77 族）。
        env.put("userAgent", "pi-java/" + Version.VERSION + " ("
            + System.getProperty("os.name") + "; java/"
            + System.getProperty("java.version") + "; "
            + System.getProperty("os.arch") + ")");
        env.put("runtime", "java/" + System.getProperty("java.version"));
        env.put("platform", System.getProperty("os.name"));
        env.put("arch", System.getProperty("os.arch"));
        env.put("osRelease", System.getProperty("os.version"));
        env.putNull("osVersion"); // JVM 无第二来源（D82 注记）
        env.put("shell", shellName());
        var terminal = env.putObject("terminal");
        putNullable(terminal, "term", getenv("TERM"));
        putNullable(terminal, "program", getenv("TERM_PROGRAM"));
        putNullable(terminal, "programVersion", getenv("TERM_PROGRAM_VERSION"));
        putNullable(terminal, "colorterm", getenv("COLORTERM"));
        terminal.put("tmux", getenv("TMUX") != null);
        terminal.put("ssh", getenv("SSH_CONNECTION") != null
            || getenv("SSH_CLIENT") != null || getenv("SSH_TTY") != null);
        terminal.put("ci", getenv("CI") != null);
        // 键名帮助诊断配置；值永不离机（pi 注释原文）。
        var piVars = new TreeSet<String>();
        System.getenv().keySet().stream()
            .filter(name -> name.startsWith("PI_"))
            .forEach(piVars::add);
        var vars = env.putArray("piEnvironmentVariables");
        piVars.forEach(vars::add);
        return env;
    }

    /** pi {@code describeModel}（裁决⑧：{@code input} 无对位 ⇒ 省键）。 */
    private static ObjectNode describeModel(ModelInfo model) {
        var node = BugReport.MAPPER.createObjectNode();
        node.put("provider", model.id().provider());
        node.put("id", model.id().modelName());
        node.put("name", model.displayName());
        putNullable(node, "api", model.api());
        if (model.baseUrl() != null) {
            node.put("baseUrl", BugReport.redactUrl(model.baseUrl()));
        } else {
            node.putNull("baseUrl");
        }
        node.put("reasoning", model.capabilities().contains(ModelCapability.THINKING));
        node.put("contextWindow", model.maxInputTokens());
        node.put("maxTokens", model.maxOutputTokens());
        if (!model.samplingParams().isEmpty()) {
            node.set("samplingParams",
                BugReport.redactJsonValue(BugReport.MAPPER.valueToTree(model.samplingParams())));
        } else {
            node.putNull("samplingParams");
        }
        if (model.compat() != null) {
            node.set("compat",
                BugReport.redactJsonValue(BugReport.MAPPER.valueToTree(model.compat())));
        } else {
            node.putNull("compat");
        }
        node.set("thinkingLevelMap", thinkingLevelMapNode(model));
        var headerNames = node.putArray("headerNames");
        new TreeSet<>(model.headers().keySet()).forEach(headerNames::add);
        return node;
    }

    /** pi {@code describeProvider}（裁决⑧：authStatus/usingOAuth/registeredByExtension 省键）。 */
    private static ObjectNode describeProvider(Provider provider, List<String> authTypes) {
        var node = BugReport.MAPPER.createObjectNode();
        node.put("id", provider.name());
        node.put("name", provider.displayName());
        node.putNull("baseUrl"); // java Provider 无 baseUrl 面（D82）
        node.putArray("headerNames"); // java Provider 无 headers 面（D82）
        var types = node.putArray("authTypes");
        authTypes.forEach(types::add);
        return node;
    }

    /** pi {@code describeExtension}（裁决⑧：sourceInfo 恒 temporary/top-level、hidden false）。 */
    private static ObjectNode describeExtension(InstalledExtension extension) {
        var node = BugReport.MAPPER.createObjectNode();
        node.put("path", extension.path().toString());
        node.put("source", BugReport.redactUrl(extension.path().toString()));
        node.put("scope", "temporary");
        node.put("origin", "top-level");
        node.put("hidden", false);
        return node;
    }

    /** thinkingLevelMap：空表 ⇒ null（pi {@code ?? null}）；否则 label→effort（null 显式不支持）。 */
    private static JsonNode thinkingLevelMapNode(ModelInfo model) {
        var map = model.thinkingLevelMap();
        if (map == null || map.entries().isEmpty()) {
            return BugReport.MAPPER.nullNode();
        }
        var node = BugReport.MAPPER.createObjectNode();
        map.entries().forEach((level, effort) -> {
            if (effort.isPresent()) {
                node.put(level.label(), effort.get());
            } else {
                node.putNull(level.label());
            }
        });
        return node;
    }

    /** pi {@code process.env.SHELL?.split(/[\\/]/).pop() || null}（Windows 回落 ComSpec）。 */
    private static String shellName() {
        var shell = getenv("SHELL");
        if (shell == null) {
            shell = getenv("ComSpec");
        }
        if (shell == null || shell.isEmpty()) {
            return null;
        }
        var parts = shell.split("[/\\\\]");
        return parts[parts.length - 1];
    }

    private static String getenv(String name) {
        var value = System.getenv(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static void putNullable(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        } else {
            node.putNull(key);
        }
    }
}
