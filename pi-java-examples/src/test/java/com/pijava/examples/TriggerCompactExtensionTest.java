package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：trigger-compact 验收——跨线判据（纯函数）与 {@code /trigger-compact}
 * 的通知序列（started ⇒ completed/failed）。
 */
class TriggerCompactExtensionTest {

    @TempDir Path tmp;

    @Test
    void crossedThresholdOnlyOnUpwardCrossing() {
        assertThat(TriggerCompactExtension.crossed(100_000, 100_001)).isTrue();
        assertThat(TriggerCompactExtension.crossed(99_999, 100_001)).isTrue();
        // 上一轮已经超线 ⇒ 不再重复触发（pi 的 previousTokens <= THRESHOLD 条件）
        assertThat(TriggerCompactExtension.crossed(100_001, 100_002)).isFalse();
        // 本轮未超线 ⇒ 不触发
        assertThat(TriggerCompactExtension.crossed(99_999, 100_000)).isFalse();
        // 无上一轮 ⇒ 不触发（pi 的 undefined/null 两臂同判）
        assertThat(TriggerCompactExtension.crossed(null, 200_000)).isFalse();
    }

    @Test
    void commandCompactsImmediatelyAndReportsOutcome() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, TriggerCompactExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    sequenceFaux("faux-tc", List.of("first", "SUMMARY", "tail")))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            session.processPrompt("hello", PromptConfig.defaults());

            var command = session.services().slashCommands().get("trigger-compact");
            assertThat(command).isNotNull();
            command.execute("", SlashContext.of(session)).toCompletableFuture().join();

            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Compaction started"));
            // 转录太小 ⇒ 失败回调臂（pi 逐字文案）
            assertThat(ui.notices()).anySatisfy(notice -> {
                assertThat(notice.message()).startsWith("Compaction failed: ");
                assertThat(notice.type()).isEqualTo(NotifyType.ERROR);
            });
        }
    }

    @Test
    void commandPassesCustomInstructions() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, TriggerCompactExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    sequenceFaux("faux-tc2", List.of("first", "SUMMARY", "tail")))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            session.processPrompt("hello", PromptConfig.defaults());

            var command = session.services().slashCommands().get("trigger-compact");
            command.execute("  focus on the api  ", SlashContext.of(session))
                .toCompletableFuture().join();

            assertThat(ui.notices()).anySatisfy(notice -> assertThat(notice.message())
                .isEqualTo("Compaction started"));
        }
    }

    /** 两轮以上文本响应的 faux（压缩摘要走同一 provider 的下一响应）。 */
    private static FauxProvider sequenceFaux(String name, List<String> texts) {
        var responses = new ArrayList<List<StreamEvent>>();
        for (var text : texts) {
            responses.add(ExampleFixtures.textEvents(text));
        }
        return FauxProvider.sequence(name, responses);
    }
}
