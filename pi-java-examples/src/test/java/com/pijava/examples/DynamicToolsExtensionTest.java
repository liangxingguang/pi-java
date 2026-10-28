package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P16：dynamic-tools——session_start 注册 echo_session；/add-echo-tool 运行时
 * 注册；非法名（空/非 {@code [a-z0-9_]}）⇒ Usage 警告；同名去重 ⇒「already registered」
 * 警告；执行返回 {@code [<prefix>]<message>}。
 */
class DynamicToolsExtensionTest {

    @TempDir Path tmp;

    @Test
    void normalizeToolNameRules() {
        assertThat(DynamicToolsExtension.normalizeToolName("  My_Echo  ")).isEqualTo("my_echo");
        assertThat(DynamicToolsExtension.normalizeToolName("")).isNull();
        assertThat(DynamicToolsExtension.normalizeToolName("   ")).isNull();
        assertThat(DynamicToolsExtension.normalizeToolName("bad-name")).isNull();
        assertThat(DynamicToolsExtension.normalizeToolName("bad.name")).isNull();
        assertThat(DynamicToolsExtension.normalizeToolName("ok_123")).isEqualTo("ok_123");
    }

    @Test
    void sessionStartRegistersEchoSession() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DynamicToolsExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-dyn", "done"))) {
                assertThat(session.getAllTools())
                    .anySatisfy(t -> assertThat(t.name()).isEqualTo("echo_session"));
            }
        }
    }

    @Test
    void addEchoToolRegistersAtRuntimeAndDedupes() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, DynamicToolsExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-dyn2", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");

                var command = session.services().slashCommands().get("add-echo-tool");
                assertThat(command).isNotNull();

                // 运行时注册
                command.execute("my_echo", SlashContext.of(session)).toCompletableFuture().join();
                var tool = session.services().tools().get("my_echo");
                assertThat(tool).isNotNull();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Registered dynamic tool: my_echo", NotifyType.INFO));

                // 执行返回 [prefix]message
                @SuppressWarnings("unchecked")
                var raw = (com.pijava.agent.tool.AgentTool<Map<String, Object>, Object>) tool;
                var executed = raw.execute("t1", Map.of("message", "hi"), null, null, null);
                assertThat(ExampleFixtures.textOf(executed.content())).isEqualTo("[my_echo] hi");

                // 同名去重
                command.execute("my_echo", SlashContext.of(session)).toCompletableFuture().join();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Tool already registered: my_echo", NotifyType.WARNING));

                // 非法名
                command.execute("Bad Name", SlashContext.of(session)).toCompletableFuture().join();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Usage: /add-echo-tool <tool_name> (lowercase, numbers, underscores)",
                        NotifyType.WARNING));
            }
        }
    }
}
