package com.pijava.agent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包⑧（docs/35）：bash 的**流式部分结果** —— shell 层的输出汇。
 *
 * <p>pi 侧只有 {@code bash} 一家真的流式（{@code bash.ts:265}/{@code :297}），
 * 其余六个内置工具声明为 {@code _onUpdate?}（**下划线＝故意未使用**）且全文再无
 * 引用 ⇒ 本包只做 bash。</p>
 *
 * <p>⚠️ 关键对齐点：载荷是**累积快照**（不是增量）—— 官方文档原文
 * {@code docs/rpc.md:1055}「contains the accumulated output so far (not just the
 * delta)」，消费者**整块替换**（{@code interactive-mode.ts:3348-3353}）。
 * 本夹具钉的是**汇**这一层（收增量），累积/节流在 {@code BashTool} 侧。</p>
 */
class ShellOutputStreamingTest {

    @TempDir
    Path tmp;

    // ── ① 汇收到的增量拼起来 = 完整输出 ──────────────────────────────

    @Test
    void sinkReceivesDeltasThatConcatenateToTheFullOutput() throws Exception {
        var executor = new DefaultShellExecutor(createFakeBash().toString());
        var seen = new ArrayList<String>();

        var result = executor.execute("echo sink-test", options(tmp, seen::add));

        assertThat(result.exitCode()).isZero();
        assertThat(seen).as("执行中应当至少报到一次").isNotEmpty();
        assertThat(String.join("", seen))
            .as("增量拼起来必须与终局输出逐字相同")
            .isEqualTo(result.output());
    }

    // ── ② sink 为 null 时行为与今天逐字相同（回归）──────────────────

    @Test
    void nullSinkKeepsTodaysBehaviour() throws Exception {
        var executor = new DefaultShellExecutor(createFakeBash().toString());

        var result = executor.execute("echo no-sink", options(tmp, null));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("echo no-sink");
    }

    // ── ③ 多字节字符被分块切断时不产生乱码 ──────────────────────────
    //
    // 这是本包**最容易写错的一处**：8 KiB 的读缓冲会在任意字节处切断 UTF-8 序列，
    // 直接 new String(buf, 0, n, UTF_8) 会把一个汉字劈成两个 U+FFFD。
    // 故把「按字符边界增量解码」隔离成一个小单元，用**逐字节**喂入做最狠的切分。

    @Test
    void multiByteCharactersSurviveArbitraryChunkBoundaries() {
        String original = "中文测试A✓𝄞é";
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
        var stream = new Utf8ChunkStream();

        var sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String piece = stream.accept(bytes, i, 1);
            assertThat(piece)
                .as("逐字节喂入的每一片都不得含替换符（半个字符必须留在流内）")
                .doesNotContain("�");
            sb.append(piece);
        }
        sb.append(stream.finish());

        assertThat(sb.toString()).isEqualTo(original);
    }

    @Test
    void multiByteCharactersSurviveOneBigChunk() {
        String original = "中文测试A✓𝄞é";
        byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
        var stream = new Utf8ChunkStream();

        var sb = new StringBuilder(stream.accept(bytes, 0, bytes.length));
        sb.append(stream.finish());

        assertThat(sb.toString()).isEqualTo(original);
    }

    private static ShellOptions options(Path cwd, ShellOutputSink sink) {
        return new ShellOptions(
            cwd.toString(), Map.of(), true, OptionalLong.empty(), null, sink);
    }

    /** 与 {@code DefaultShellExecutorTest} 同法：一个把 stdin 回显出来的假 bash。 */
    private Path createFakeBash() throws Exception {
        if (isWindows()) {
            Path script = tmp.resolve("bash.cmd");
            Files.writeString(script, "@echo off\r\nfindstr /r \".*\"\r\n");
            return script;
        }
        Path script = tmp.resolve("bash");
        Files.writeString(script, "#!/bin/sh\necho \"$@\"\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
