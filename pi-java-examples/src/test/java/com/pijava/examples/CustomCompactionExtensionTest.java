package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：custom-compaction 验收——google faux 被消费一次、摘要接管默认压缩
 * （{@code Entry.Compaction.fromHook=true}、{@code tokensBefore}、{@code firstKeptEntryId}）、
 * 空摘要 ⇒ 警告＋回落默认压缩。
 */
class CustomCompactionExtensionTest {

    @TempDir Path tmp;

    /** 让转录超过默认 {@code keepRecentTokens=20000} 的正文（估算 ≈ 4 字符/token）。 */
    private static final String BULK = "x".repeat(40_000);

    @Test
    void customSummaryReplacesDefaultCompaction() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, CustomCompactionExtension.class);
                var session = ExampleFixtures.newSession(tmp, sequenceFaux("faux-cc",
                    List.of("first", "second", "third", "MAIN SUMMARY")))) {
            // google 车道的 faux 供给自定义压缩模型（docs/51 §3 夹具）
            session.services().providers().register(
                ExampleFixtures.textProvider("google", "GEMINI SUMMARY"));

            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            for (int i = 0; i < 3; i++) {
                session.processPrompt(BULK, PromptConfig.defaults()).status();
            }

            session.compact((String) null);

            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Custom compaction extension triggered"));
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .startsWith("Custom compaction: summarizing "));
            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .endsWith("with gemini-2.5-flash..."));

            var compaction = lastCompaction(session);
            assertThat(compaction).isNotNull();
            assertThat(compaction.summary()).isEqualTo("GEMINI SUMMARY");
            assertThat(compaction.fromHook()).isTrue();
            assertThat(compaction.tokensBefore()).isPositive();
            assertThat(compaction.firstKeptEntryId()).isNotNull();
        }
    }

    @Test
    void emptySummaryWarnsAndFallsBackToDefault() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, CustomCompactionExtension.class);
                var session = ExampleFixtures.newSession(tmp, sequenceFaux("faux-cc2",
                    List.of("first", "second", "third", "MAIN SUMMARY")))) {
            session.services().providers().register(
                ExampleFixtures.textProvider("google", ""));

            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            for (int i = 0; i < 3; i++) {
                session.processPrompt(BULK, PromptConfig.defaults()).status();
            }

            session.compact((String) null);

            assertThat(ui.notices()).anySatisfy(notice -> {
                assertThat(notice.message())
                    .isEqualTo("Compaction summary was empty, using default compaction");
                assertThat(notice.type()).isEqualTo(NotifyType.WARNING);
            });
            var compaction = lastCompaction(session);
            assertThat(compaction).isNotNull();
            // 空摘要不接管：回落默认压缩（不是我们的 GEMINI 文本，fromHook 不置真）
            assertThat(compaction.summary()).isNotEqualTo("GEMINI SUMMARY");
            assertThat(compaction.fromHook()).isNotEqualTo(Boolean.TRUE);
        }
    }

    /** 提示词模板：{@code previousSummary} 插在 {@code captures:} 之后，会话正文包在标签里。 */
    @Test
    void promptTemplateEmbedsPreviousSummaryAndConversation() {
        var plain = CustomCompactionExtension.SUMMARY_PROMPT_TEMPLATE.formatted("", "conv");
        assertThat(plain).contains(
            "that captures:\n\n1. The main goals and objectives discussed");
        assertThat(plain).endsWith("<conversation>\nconv\n</conversation>");

        var withPrevious = CustomCompactionExtension.SUMMARY_PROMPT_TEMPLATE.formatted(
            "\n\nPrevious session summary for context:\nOLD", "conv");
        assertThat(withPrevious).contains(
            "that captures:\n\nPrevious session summary for context:\nOLD\n\n1. The main goals");
    }

    private static Entry.Compaction lastCompaction(
            com.pijava.coding.agent.core.AgentSession session) {
        Entry.Compaction last = null;
        for (var entry : session.accumulatedEntries()) {
            if (entry instanceof Entry.Compaction compaction) {
                last = compaction;
            }
        }
        return last;
    }

    private static FauxProvider sequenceFaux(String name, List<String> texts) {
        var responses = new ArrayList<List<StreamEvent>>();
        for (var text : texts) {
            responses.add(ExampleFixtures.textEvents(text));
        }
        return FauxProvider.sequence(name, responses);
    }
}
