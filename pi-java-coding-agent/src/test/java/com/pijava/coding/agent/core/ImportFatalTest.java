package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.crash.CrashLog;
import com.pijava.coding.agent.core.crash.CrashRecord;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 15（裁决⑤/B256）：{@code /import} 失败修真为 pi fatal 形——记
 * {@code fatal_error} crash＋{@code @ui:fatal-exit:} marker（pi
 * interactive-mode.ts :6607 → handleFatalRuntimeError）。
 *
 * <p>崩溃落盘走 {@code user.home}（{@link CrashLog#crashLogPath} 每调现解析，
 * 批 14 教训）⇒ 夹具重定向到临时目录，<b>tearDown 前不得提前复原</b>
 * （settings 懒解析夹具坑）。</p>
 */
class ImportFatalTest {

    @TempDir
    Path fakeHome;

    private String realHome;

    @BeforeEach
    void redirectHome() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void restoreHome() {
        if (realHome != null) {
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void failedImportRecordsFatalCrashAndReturnsFatalMarker() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--no-session"}),
                InMemorySessionRepository.create())) {
            var ctx = SlashContext.of(session);
            var result = session.services().slashCommands()
                .dispatch("/import " + fakeHome.resolve("no-such.jsonl"), ctx)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertThat(result)
                .startsWith(CommandRegistry.UI_FATAL_EXIT_PREFIX
                    + "Failed to import session: ");

            var crashes = CrashLog.read(CrashLog.crashLogPath());
            assertThat(crashes).hasSize(1);
            assertThat(crashes.getFirst().kind()).isEqualTo(CrashRecord.Kind.FATAL_ERROR);
            assertThat(crashes.getFirst().cwd()).isEqualTo(session.cwd());
        }
    }

    @Test
    void crashLogLandsUnderRedirectedAgentDir() {
        // 路径每调现解析：agentDir 随 user.home 走（批 14 静态冻结修真对拍）。
        assertThat(CrashLog.crashLogPath())
            .isEqualTo(fakeHome.resolve(".pi-java").resolve("agent").resolve("crashes.json"));
    }
}
