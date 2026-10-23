package com.pijava.coding.agent.core.bugreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.crash.CrashRecord;
import org.junit.jupiter.api.Test;

/** pi {@code bug-report.test.ts} 对拍：redact/diagnostics/files/selectMessages。 */
class BugReportTest {

    // ── isSensitiveKey 边界表 ─────────────────────────────

    @Test
    void sensitiveKeyBoundaryTable() {
        assertThat(BugReport.isSensitiveKey("apiKey")).isTrue();
        assertThat(BugReport.isSensitiveKey("api_key")).isTrue();
        assertThat(BugReport.isSensitiveKey("API-KEY")).isTrue();
        assertThat(BugReport.isSensitiveKey("x_token")).isTrue();
        assertThat(BugReport.isSensitiveKey("password")).isTrue();
        assertThat(BugReport.isSensitiveKey("authorization")).isTrue();
        assertThat(BugReport.isSensitiveKey("cookie")).isTrue();
        assertThat(BugReport.isSensitiveKey("clientSecret")).isTrue();
        // 不误伤：count/monkey 不含边界分隔的敏感词。
        assertThat(BugReport.isSensitiveKey("count")).isFalse();
        assertThat(BugReport.isSensitiveKey("monkey")).isFalse();
        assertThat(BugReport.isSensitiveKey("theme")).isFalse();
        assertThat(BugReport.isSensitiveKey("tokens")).isFalse(); // token 后无边界…s 不是 [-_]/$
    }

    // ── redactUrl 六案 ────────────────────────────────────

    @Test
    void redactUrlStripsUserinfo() {
        assertThat(BugReport.redactUrl("https://user:pass@example.com/path"))
            .isEqualTo("https://example.com/path");
    }

    @Test
    void redactUrlRedactsSensitiveQueryValues() {
        assertThat(BugReport.redactUrl("https://example.com/?api_key=secret&ok=1"))
            .isEqualTo("https://example.com/?api_key=<redacted>&ok=1");
    }

    @Test
    void redactUrlNestedSchemeRecurses() {
        assertThat(BugReport.redactUrl("https://proxy.example/http://inner?token=x"))
            .isEqualTo("https://proxy.example/http://inner?token=<redacted>");
    }

    @Test
    void redactUrlInvalidOrRelativeReturnsAsIs() {
        assertThat(BugReport.redactUrl("not a url")).isEqualTo("not a url");
        assertThat(BugReport.redactUrl("/relative/path?token=x")).isEqualTo("/relative/path?token=x");
    }

    @Test
    void redactUrlUnchangedReturnsAsIs() {
        assertThat(BugReport.redactUrl("https://example.com/path?q=1"))
            .isEqualTo("https://example.com/path?q=1");
    }

    // ── redactJsonValue 嵌套 ──────────────────────────────

    @Test
    void redactJsonValueRedactsKeysAndUrlsRecursively() throws Exception {
        var tree = BugReport.MAPPER.readTree("""
            {
              "defaultApiKey": "sk-live",
              "nested": {"client_secret": "abc", "note": "see https://u:p@h.example/x"},
              "list": ["https://h.example/?token=t", 42]
            }
            """);
        var redacted = BugReport.redactJsonValue(tree);
        assertThat(redacted.at("/defaultApiKey").textValue()).isEqualTo("<redacted>");
        assertThat(redacted.at("/nested/client_secret").textValue()).isEqualTo("<redacted>");
        // pi redactUrl 只认「整串即 URL」（new URL 失败 ⇒ 原样）——散文中的 URL 不动。
        assertThat(redacted.at("/nested/note").textValue())
            .isEqualTo("see https://u:p@h.example/x");
        assertThat(redacted.at("/list/0").textValue())
            .isEqualTo("https://h.example/?token=<redacted>");
        assertThat(redacted.at("/list/1").intValue()).isEqualTo(42);
    }

    // ── redactSettings：剥 trackingId/deviceId ───────────

    @Test
    void redactSettingsStripsTrackingAndDeviceIds() {
        var settings = new Settings();
        settings.deviceId = "dev-1";
        settings.trackingId = "track-1";
        settings.defaultApiKey = "sk-live";
        settings.theme = "dark";
        var redacted = BugReport.redactSettings(settings);
        assertThat(redacted.has("deviceId")).isFalse();
        assertThat(redacted.has("trackingId")).isFalse();
        assertThat(redacted.at("/defaultApiKey").textValue()).isEqualTo("<redacted>");
        assertThat(redacted.at("/theme").textValue()).isEqualTo("dark");
    }

    // ── metadata 两臂（includeSession 的 cwd 键有无）──────

    @Test
    void metadataOmitsCwdWhenSessionNotIncluded() {
        var options = options(false);
        var metadata = BugReportCollect.collectMetadata(options);
        assertThat(metadata.at("/session/included").booleanValue()).isFalse();
        assertThat(metadata.at("/session").has("cwd")).isFalse();
        assertThat(metadata.at("/schemaVersion").intValue()).isEqualTo(1);
        assertThat(metadata.at("/session/messageCount").intValue()).isEqualTo(7);
        assertThat(metadata.at("/hint").isNull()).isTrue();
        assertThat(metadata.at("/extensionErrors").size()).isZero();
    }

    @Test
    void metadataIncludesCwdAndTrimsHint() {
        var options = new BugReportCollect.CollectOptions(
            "fixed-id", "  it broke  ", "sess-1", "/work", true, false, 7,
            null, null, List.of(), null, List.of(), new Settings(), new Settings());
        var metadata = BugReportCollect.collectMetadata(options);
        assertThat(metadata.at("/id").textValue()).isEqualTo("fixed-id");
        assertThat(metadata.at("/hint").textValue()).isEqualTo("it broke");
        assertThat(metadata.at("/session/cwd").textValue()).isEqualTo("/work");
    }

    // ── diagnostics 过滤臂 ────────────────────────────────

    @Test
    void diagnosticsCollectsOnlyFailureArmsAndStripsNotified() {
        var ok = assistantEntry("e1", StopReason.STOP, null, null, null);
        var errored = assistantEntry("e2", StopReason.ERROR, "boom", null, null);
        var aborted = assistantEntry("e3", StopReason.ABORTED, null, null, null);
        var withDiagnostics = assistantEntry("e4", StopReason.STOP, null, null,
            List.of(new AssistantMessageDiagnostic("pi_messages_response_failure",
                123L, null, null)));
        var crash = new CrashRecord("2026-10-23T00:00:00Z", "1.0",
            CrashRecord.Kind.FATAL_ERROR, "crash!", null, null, "/w", Boolean.TRUE);

        var diagnostics = BugReportCollect.collectDiagnostics(
            List.of(ok, errored, aborted, withDiagnostics), "sess-1", List.of(crash));

        assertThat(diagnostics.at("/entryCount").intValue()).isEqualTo(4);
        assertThat(diagnostics.at("/assistantMessageCount").intValue()).isEqualTo(4);
        assertThat(diagnostics.at("/assistant")).hasSize(3);
        assertThat(diagnostics.at("/assistant/0/entryId").textValue()).isEqualTo("e2");
        assertThat(diagnostics.at("/assistant/0/stopReason").textValue()).isEqualTo("error");
        assertThat(diagnostics.at("/assistant/0/errorMessage").textValue()).isEqualTo("boom");
        // rawStopReason 缺席 ⇒ 不产键（pi 条件展开）。
        assertThat(diagnostics.at("/assistant/0").has("rawStopReason")).isFalse();
        assertThat(diagnostics.at("/assistant/2/entryId").textValue()).isEqualTo("e4");
        assertThat(diagnostics.at("/assistant/2/diagnostics/0/type").textValue())
            .isEqualTo("pi_messages_response_failure");
        // crashes 剥 notified。
        assertThat(diagnostics.at("/crashes/0").has("notified")).isFalse();
        assertThat(diagnostics.at("/crashes/0/message").textValue()).isEqualTo("crash!");
    }

    // ── files 尾换行 ──────────────────────────────────────

    @Test
    void filesIncludesOptionalsAndTrailingNewlines() {
        var bundle = new BugReport.Bundle(
            BugReport.MAPPER.createObjectNode().put("a", 1),
            BugReport.MAPPER.createObjectNode().put("b", 2),
            "{\"type\":\"session\"}\n",
            "# Report");
        var files = BugReport.files(bundle);
        assertThat(files).extracting(BugReport.ReportFile::name)
            .containsExactly("report.json", "diagnostics.json", "session.jsonl", "summary.md");
        assertThat(files.get(0).data()).endsWith("\n");
        assertThat(files.get(1).data()).endsWith("\n");
        assertThat(files.get(3).data()).isEqualTo("# Report\n");

        var minimal = BugReport.files(new BugReport.Bundle(
            BugReport.MAPPER.createObjectNode(), BugReport.MAPPER.createObjectNode(),
            null, null));
        assertThat(minimal).extracting(BugReport.ReportFile::name)
            .containsExactly("report.json", "diagnostics.json");
    }

    @Test
    void archiveFileNameShape() {
        assertThat(BugReport.archiveFileName("abc")).isEqualTo("pi-bug-report-abc.zip");
    }

    // ── selectMessages 预算（首条必进/超预算停）───────────

    @Test
    void selectMessagesAlwaysIncludesLastMessageAndStopsOverBudget() {
        var big = user("x".repeat(4_000));  // ~1000 tokens
        var small1 = user("a");
        var small2 = user("b");
        // 预算装不下 big+small1+small2，但末条必进；从尾装：small2, small1, big 超预算停。
        var selected = BugReport.selectMessages(List.of(big, small1, small2), 10);
        assertThat(selected).containsExactly(small1, small2);

        // 单条超大 ⇒ 首条（末条）必进。
        var forced = BugReport.selectMessages(List.of(big), 1);
        assertThat(forced).containsExactly(big);
    }

    // ── helpers ───────────────────────────────────────────

    private static BugReportCollect.CollectOptions options(boolean includeSession) {
        return new BugReportCollect.CollectOptions(
            null, null, "sess-1", "/work", includeSession, false, 7,
            null, null, List.of(), null, List.of(), new Settings(), new Settings());
    }

    private static Entry assistantEntry(String id, StopReason stopReason, String errorMessage,
            String rawStopReason, List<AssistantMessageDiagnostic> diagnostics) {
        var message = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("t")), stopReason, null,
            "anthropic-messages", "anthropic", "claude-fable-5", null, null, null,
            diagnostics, null, Instant.parse("2026-10-23T00:00:00Z"),
            errorMessage, rawStopReason, null);
        return new Entry.Message(id, 1L, null, Instant.parse("2026-10-23T00:00:00Z"),
            message, null);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
