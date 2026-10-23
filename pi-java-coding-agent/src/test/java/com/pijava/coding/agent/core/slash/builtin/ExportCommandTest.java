package com.pijava.coding.agent.core.slash.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.SlashContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 15（T10）：pi {@code handleExportCommand}（interactive-mode.ts:6520-6533）
 * ＋ {@code getPathCommandArgument}（:6537-6562）对拍——引号两臂、空白截断、
 * 缺省默认名、{@code .jsonl} 臂、文案逐字。
 */
class ExportCommandTest {

    @TempDir
    Path tmp;

    private AgentSession session() {
        return AgentSession.create(ArgsParser.parse(
            new String[] {"--session-dir", tmp.resolve("sessions").toString()}));
    }

    private static String dispatch(AgentSession session, String input) throws Exception {
        return session.services().slashCommands()
            .dispatch(input, SlashContext.of(session))
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void pathArgumentParsing() {
        assertThat(MiscCommands.pathCommandArgument("")).isNull();
        assertThat(MiscCommands.pathCommandArgument("   ")).isNull();
        assertThat(MiscCommands.pathCommandArgument("\"a b.html\"")).isEqualTo("a b.html");
        assertThat(MiscCommands.pathCommandArgument("'q f.html'")).isEqualTo("q f.html");
        assertThat(MiscCommands.pathCommandArgument("\"unclosed")).isNull();
        assertThat(MiscCommands.pathCommandArgument("x.jsonl extra")).isEqualTo("x.jsonl");
        assertThat(MiscCommands.pathCommandArgument("plain.html")).isEqualTo("plain.html");
    }

    @Test
    void quotedPathWithSpacesExportsHtml() throws Exception {
        try (var session = session()) {
            var target = tmp.resolve("my report.html");
            var result = dispatch(session, "/export \"" + target + "\"");
            assertThat(result).isEqualTo("Session exported to: " + target);
            assertThat(target).exists();
            assertThat(Files.readString(target)).contains("<");
        }
    }

    @Test
    void jsonlSuffixExportsJsonlAndTruncatesAtWhitespace() throws Exception {
        try (var session = session()) {
            var target = tmp.resolve("s.jsonl");
            var result = dispatch(session, "/export " + target + " trailing-ignored");
            assertThat(result).isEqualTo("Session exported to: " + target);
            var lines = Files.readAllLines(target);
            assertThat(lines).isNotEmpty();
            assertThat(JsonlCodec.parseHeader(lines.getFirst()).ok()).isTrue();
        }
    }

    @Test
    void noArgsWritesDefaultHtmlNameInCwd() throws Exception {
        try (var session = session()) {
            var result = dispatch(session, "/export");
            assertThat(result).matches("Session exported to: session-.+\\.html");
            // 落 cwd（模块目录）——断言后清理，不留测试残件。
            var name = result.substring("Session exported to: ".length());
            var written = Path.of(name);
            assertThat(written).exists();
            Files.deleteIfExists(written);
        }
    }

    @Test
    void unclosedQuoteFallsBackToDefault() throws Exception {
        try (var session = session()) {
            var result = dispatch(session, "/export \"unclosed");
            assertThat(result).matches("Session exported to: session-.+\\.html");
            Files.deleteIfExists(Path.of(
                result.substring("Session exported to: ".length())));
        }
    }

    @Test
    void registrationShapeMatchesPi() {
        var registry = com.pijava.coding.agent.core.slash.CommandRegistry.withBuiltins();
        var command = registry.get("export");
        assertThat(command.description())
            .isEqualTo("Export session (HTML default, or specify path: .html/.jsonl)");
    }
}
