package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-session JSONL aggregation. */
class SessionInsightsServiceTest {

    @Test
    void aggregatesUsageToolsAndDays(@TempDir Path root) throws Exception {
        var project = root.resolve("--D--work--");
        Files.createDirectories(project);
        try (var in = getClass().getResourceAsStream("/session-insights/sample.jsonl")) {
            assertThat(in).isNotNull();
            Files.copy(in, project.resolve("2026-10-05T00-43-13-702Z_sample.jsonl"));
        }

        var service = new SessionInsightsService(root, root, false);
        var report = service.report("all");

        assertThat(report.supported()).isTrue();
        assertThat(report.backend()).isEqualTo("jsonl");
        assertThat(report.sessionCount()).isEqualTo(1);
        assertThat(report.sessions()).hasSize(1);

        var card = report.sessions().getFirst();
        assertThat(card.id()).isNotBlank();
        assertThat(card.path()).endsWith(".jsonl");
        assertThat(card.messages()).isGreaterThan(0);
        assertThat(card.promptTokens()).isGreaterThan(0);
        assertThat(card.outputTokens()).isGreaterThan(0);
        assertThat(card.toolCalls()).isGreaterThan(0);
        assertThat(card.cacheHitRate()).isBetween(0.0, 1.0);
        assertThat(report.days()).isNotEmpty();
        assertThat(report.tools()).isNotEmpty();
        assertThat(report.totalTokens())
            .isEqualTo(card.promptTokens() + card.outputTokens());

        // 历史 composition 降级口径：只有消息类别，没有 system/tools。
        assertThat(card.composition()).isNotEmpty();
        assertThat(card.composition())
            .extracting(SessionInsightsReport.CompositionSlice::key)
            .doesNotContain("system", "tools");
        assertThat(card.composition().stream()
            .mapToDouble(SessionInsightsReport.CompositionSlice::tokens).sum())
            .isGreaterThan(0);

        // 非 JSONL 后端明确标记 unsupported，而不是展示空 JSONL 数据。
        var sqlite = service.report("7d", "sqlite");
        assertThat(sqlite.supported()).isFalse();
        assertThat(sqlite.backend()).isEqualTo("sqlite");
    }
}
