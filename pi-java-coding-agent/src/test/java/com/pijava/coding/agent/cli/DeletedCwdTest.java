package com.pijava.coding.agent.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.pijava.coding.agent.Main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W10（pi main.ts:591-599）：当前工作目录已不存在 ⇒ 逐字错误＋exit 1。
 * 用 {@code user.dir} 属性模拟（Java 无 chdir）。
 */
class DeletedCwdTest {

    @TempDir
    java.nio.file.Path tmp;

    @Test
    void deletedWorkingDirectoryFailsWithVerbatimMessage() throws Exception {
        var gone = Files.createDirectory(tmp.resolve("gone"));
        var originalDir = System.getProperty("user.dir");
        var originalErr = System.err;
        var err = new ByteArrayOutputStream();
        System.setProperty("user.dir", gone.toString());
        Files.delete(gone);
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = Main.run(new String[] {"--help"});
            assertThat(code).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains(
                "Error: The current working directory no longer exists. "
                    + "Change to an existing directory and try again.");
        } finally {
            System.setErr(originalErr);
            System.setProperty("user.dir", originalDir);
        }
    }
}
