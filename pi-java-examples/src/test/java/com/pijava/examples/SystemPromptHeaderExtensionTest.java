package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：system-prompt-header 验收——{@code agent_start} 把生效系统提示的字符
 * 数写进 {@code system-prompt} 状态段（JS 与 Java 的 {@code length} 同为 UTF-16
 * 单元），{@code session_shutdown} 删键。
 */
class SystemPromptHeaderExtensionTest {

    private static final Pattern FORMAT = Pattern.compile("System: (\\d+) chars");

    @TempDir Path tmp;

    @Test
    void agentStartPublishesPromptLength() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                SystemPromptHeaderExtension.class, PromptLengthProbe.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-sph", "ok"))) {
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");

            session.processPrompt("hi", PromptConfig.defaults()).status();

            var status = ui.statuses().get(SystemPromptHeaderExtension.STATUS_KEY);
            assertThat(status).isNotNull();
            var matcher = FORMAT.matcher(status);
            assertThat(matcher.matches()).isTrue();
            // 与同一时点独立读到的生效系统提示长度一致（agent_start 窗内取值）
            assertThat(Integer.parseInt(matcher.group(1)))
                .isEqualTo(PromptLengthProbe.LENGTHS.get(0));
        }
    }

    @Test
    void sessionShutdownClearsTheStatus() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp,
                SystemPromptHeaderExtension.class)) {
            var session = ExampleFixtures.newSession(tmp,
                ExampleFixtures.textProvider("faux-sph2", "ok"));
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            session.processPrompt("hi", PromptConfig.defaults()).status();
            assertThat(ui.statuses()).containsKey(SystemPromptHeaderExtension.STATUS_KEY);

            session.close();
            // pi 的 setStatus(key, undefined) ⇒ 删键（ScriptedUi 记为 null 值）
            assertThat(ui.statuses())
                .containsEntry(SystemPromptHeaderExtension.STATUS_KEY, null);
        }
    }

    /** 伴生探针：在 {@code agent_start} 时点独立记录 {@code getSystemPrompt()} 长度。 */
    public static final class PromptLengthProbe implements PiExtension {

        /** 各次 agent_start 时点读到的长度。 */
        static final List<Integer> LENGTHS = new ArrayList<>();

        @Override
        public String name() {
            return "system-prompt-length-probe";
        }

        @Override
        public void register(ExtensionContext ctx) {
            ctx.on(ExtensionEventSpec.AGENT_START, (event, c) -> {
                LENGTHS.add(c.getSystemPrompt().length());
                return null;
            });
        }
    }
}
