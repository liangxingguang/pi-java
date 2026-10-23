package com.pijava.coding.agent.core.bugreport;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.Settings;

/**
 * bug report 核心（pi {@code core/bug-report.ts} 逐函数对位）：脱敏（redact）、
 * 上报包文件列表、摘要消息预算选择。【模型可见面】（摘要系统提示词与指令）逐字。
 *
 * <p>收集（collect）部分在 {@link BugReportCollect}（500 行规拆分）。</p>
 *
 * <p>⚠️ 本类用<b>自带</b> {@link ObjectMapper}（默认可见性）：{@code Settings} 的
 * {@code valueToTree} 需要 record 组件访问器可见，{@code core.Json} 的共享 mapper
 * 关了 getter 可见性会把 record 投影成 {@code {}}。</p>
 */
public final class BugReport {

    /** pi {@code BUG_REPORT_CUSTOM_ENTRY_TYPE}：会话尾记录的 customType。 */
    public static final String CUSTOM_ENTRY_TYPE = "pi.bug-report";
    /** pi {@code BUG_REPORT_SCHEMA_VERSION}。 */
    static final int SCHEMA_VERSION = 1;
    /** pi {@code REDACTED}。 */
    static final String REDACTED = "<redacted>";

    /** pi {@code SENSITIVE_KEY} 逐字（Java Pattern 方言）。 */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
        "(?:^|[-_])(api[-_]?key|secret|token|password|passwd|credential|authorization|cookie)"
            + "(?:$|[-_])",
        Pattern.CASE_INSENSITIVE);
    /** pi 的 {@code key.replace(/([a-z0-9])([A-Z])/g, "$1_$2")}（camelCase→snake 先置）。 */
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    /** pi {@code redactUrl} 的嵌套 scheme 臂逐字。 */
    private static final Pattern NESTED_SCHEME = Pattern.compile(
        "^([a-z][a-z0-9+.-]*:)([a-z][a-z0-9+.-]*://.*)$", Pattern.CASE_INSENSITIVE);
    /** 绝对 URL 的 scheme:// 前缀（裁决⑨手写解析的入场券）。 */
    private static final Pattern SCHEME_PREFIX = Pattern.compile(
        "^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);

    static final ObjectMapper MAPPER = new ObjectMapper();

    private BugReport() {}

    /** pi {@code isSensitiveKey}：键名先 camelCase→snake_case 再过敏感正则。 */
    public static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY.matcher(CAMEL_BOUNDARY.matcher(key).replaceAll("$1_$2")).find();
    }

    /**
     * pi {@code redactUrl}（裁决⑨手写串解析；WHATWG {@code URL} 无 Java 对位）：
     * 嵌套 scheme 臂逐字；剥 userinfo；敏感 query 键的值换 {@code <redacted>}；
     * 无改动／解析不合法 ⇒ 原样返回。
     */
    public static String redactUrl(String value) {
        var nested = NESTED_SCHEME.matcher(value);
        if (nested.matches()) {
            return nested.group(1) + redactUrl(nested.group(2));
        }
        try {
            var scheme = SCHEME_PREFIX.matcher(value);
            if (!scheme.find()) {
                return value;
            }
            int authorityStart = scheme.end();
            int authorityEnd = value.length();
            for (int i = authorityStart; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    authorityEnd = i;
                    break;
                }
            }
            var authority = value.substring(authorityStart, authorityEnd);
            boolean changed = false;
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                authority = authority.substring(at + 1);
                changed = true;
            }
            if (authority.isEmpty()) {
                return value; // pi new URL("scheme://") 抛 ⇒ 原样
            }
            var rest = value.substring(authorityEnd); // path[?query][#fragment]
            int fragIndex = rest.indexOf('#');
            var fragment = fragIndex >= 0 ? rest.substring(fragIndex) : "";
            var pathQuery = fragIndex >= 0 ? rest.substring(0, fragIndex) : rest;
            int queryIndex = pathQuery.indexOf('?');
            String path = queryIndex >= 0 ? pathQuery.substring(0, queryIndex) : pathQuery;
            String query = queryIndex >= 0 ? pathQuery.substring(queryIndex + 1) : null;
            String redactedQuery = null;
            if (query != null) {
                var pairs = query.split("&", -1);
                var out = new String[pairs.length];
                boolean queryChanged = false;
                for (int i = 0; i < pairs.length; i++) {
                    var pair = pairs[i];
                    int eq = pair.indexOf('=');
                    var rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
                    String decodedKey;
                    try {
                        decodedKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        decodedKey = rawKey;
                    }
                    if (eq >= 0 && isSensitiveKey(decodedKey)) {
                        out[i] = rawKey + "=" + REDACTED;
                        queryChanged = true;
                    } else {
                        out[i] = pair;
                    }
                }
                if (queryChanged) {
                    redactedQuery = String.join("&", out);
                    changed = true;
                }
            }
            if (!changed) {
                return value;
            }
            return value.substring(0, authorityStart) + authority + path
                + (redactedQuery != null ? "?" + redactedQuery : query != null ? "?" + query : "")
                + fragment;
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * pi {@code redactJsonValue}：敏感键（含对象子树）整体换 {@code <redacted>}；
     * 字符串值逐个过 {@link #redactUrl}；其余递归。
     */
    public static JsonNode redactJsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return value;
        }
        if (value.isObject()) {
            var copy = MAPPER.createObjectNode();
            value.properties().forEach(field -> {
                if (isSensitiveKey(field.getKey())) {
                    copy.set(field.getKey(), TextNode.valueOf(REDACTED));
                } else {
                    copy.set(field.getKey(), redactJsonValue(field.getValue()));
                }
            });
            return copy;
        }
        if (value.isArray()) {
            var copy = (ArrayNode) value;
            var result = MAPPER.createArrayNode();
            copy.forEach(element -> result.add(
                element.isTextual()
                    ? TextNode.valueOf(redactUrl(element.textValue()))
                    : redactJsonValue(element)));
            return result;
        }
        if (value.isTextual()) {
            return TextNode.valueOf(redactUrl(value.textValue()));
        }
        return value;
    }

    /**
     * pi {@code redactSettings}：先剥 {@code trackingId}/{@code deviceId} 两键，
     * 再走全树脱敏。（java {@link Settings} 两键都有——设计稿「只有 deviceId」
     * 滞后，按 pi 语义剥两键。）
     */
    public static JsonNode redactSettings(Settings settings) {
        var tree = MAPPER.valueToTree(settings);
        if (tree instanceof ObjectNode object) {
            object.remove("trackingId");
            object.remove("deviceId");
        }
        return redactJsonValue(tree);
    }

    /** pi {@code BugReportBundle}。 */
    public record Bundle(
        ObjectNode metadata,
        ObjectNode diagnostics,
        String sessionJsonl,
        String summary
    ) {}

    /** pi {@code BugReportFile}（{@code name/contentType/data}）。 */
    public record ReportFile(String name, String contentType, String data) {}

    /**
     * pi {@code bugReportFiles}：report/diagnostics 两空格缩进＋尾 {@code \n}；
     * session/summary 可选（summary 补尾 {@code \n}）。
     */
    public static List<ReportFile> files(Bundle bundle) {
        var files = new ArrayList<ReportFile>();
        files.add(new ReportFile("report.json", "application/json",
            pretty(bundle.metadata()) + "\n"));
        files.add(new ReportFile("diagnostics.json", "application/json",
            pretty(bundle.diagnostics()) + "\n"));
        if (bundle.sessionJsonl() != null) {
            files.add(new ReportFile("session.jsonl", "application/x-ndjson",
                bundle.sessionJsonl()));
        }
        if (bundle.summary() != null) {
            files.add(new ReportFile("summary.md", "text/markdown",
                bundle.summary().endsWith("\n") ? bundle.summary() : bundle.summary() + "\n"));
        }
        return List.copyOf(files);
    }

    /** pi {@code bugReportArchiveFileName}。 */
    public static String archiveFileName(String id) {
        return "pi-bug-report-" + id + ".zip";
    }

    /**
     * pi {@code selectMessages}：从尾往前装预算；<b>第一条必进</b>
     * （{@code selected.length > 0 &&} 的短路），超预算即停。
     */
    public static List<Message> selectMessages(List<Message> messages, long tokenBudget) {
        var selected = new ArrayList<Message>();
        long tokens = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            long next = ContextUsageEstimator.estimateTokens(message);
            if (!selected.isEmpty() && tokens + next > tokenBudget) {
                break;
            }
            selected.add(message);
            tokens += next;
        }
        return selected.reversed();
    }

    /** pi {@code BUG_SUMMARY_SYSTEM_PROMPT}【模型可见】逐字。 */
    public static final String BUG_SUMMARY_SYSTEM_PROMPT =
        "You are helping a user file a bug report about pi, the coding agent they are "
            + "talking to. You will be shown the conversation transcript. Write a report for "
            + "the pi developers describing what the user was doing and what went wrong.\n"
            + "\n"
            + "Do NOT continue the conversation. Do NOT respond to any questions in the "
            + "conversation. ONLY output the report.";

    /** pi {@code BUG_SUMMARY_INSTRUCTIONS}【模型可见】逐字。 */
    public static final String BUG_SUMMARY_INSTRUCTIONS = """
        Write the bug report in Markdown with these sections:

        ## What the user was doing
        One short paragraph.

        ## What went wrong
        Concrete description of the failure: wrong output, errors, hangs, tool failures, unexpected behavior. Quote error messages and tool output verbatim where they exist.

        ## Steps to reproduce
        Numbered list, as specific as the transcript allows.

        ## Relevant details
        Tool calls involved, files touched, model behavior, anything else that helps a developer reproduce or locate the problem.

        Do not include file contents, secrets, or credentials from the transcript; refer to files by path only. Keep the report factual and concise.""";

    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize bug report JSON", e);
        }
    }
}
