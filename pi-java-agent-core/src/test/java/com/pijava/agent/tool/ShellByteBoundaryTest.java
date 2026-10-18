package com.pijava.agent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #95（A14，docs/25 §9）：**字节级**假 shell —— 泵边界上的多字节字符切分。
 *
 * <p>{@code ShellOutputStreamingTest} 的假 shell 喂的是**字符串**（字符级写入永远
 * 不会切在半字符）；本夹具直接让假 shell 把预制的 {@code byte[]} 原样喷到
 * stdout/stderr —— 构造「一个 3 字节 UTF-8 字符（U+4E2D 中 = E4 B8 AD）的
 * 第 2 字节恰好落在 8192 泵边界」：前缀 8191 字节 ASCII ＋ 3 字节字符 ＋ 尾巴，
 * 第一次 {@code read(buf, 8192)} 恰好停在 {@code 0xE4} 之后。</p>
 *
 * <p>泵审计结论（docs/25 §9.3）：{@code ShellProcessEngine} 的终局输出是
 * {@code ByteArrayOutputStream} 攒齐后**一次**解码（字节精确），增量路径走
 * {@link Utf8ChunkStream} 的字符边界增量解码 —— 两侧都不劈字符；本夹具把这条
 * 端到端钉死（真进程、真 8 KiB 读缓冲）。</p>
 */
class ShellByteBoundaryTest {

    @TempDir
    Path tmp;

    /** 8191 字节 ASCII ＋ U+4E2D（3 字节）＋ 尾巴 —— 第 8192 字节是字符的第 2 字节。 */
    private static byte[] boundaryPayload() {
        var head = new byte[8191];
        Arrays.fill(head, (byte) 'a');
        var middle = "中".getBytes(StandardCharsets.UTF_8);
        var tail = "tail\n".getBytes(StandardCharsets.UTF_8);
        var payload = new byte[head.length + middle.length + tail.length];
        System.arraycopy(head, 0, payload, 0, head.length);
        System.arraycopy(middle, 0, payload, head.length, middle.length);
        System.arraycopy(tail, 0, payload, head.length + middle.length, tail.length);
        assert payload[8191] == (byte) 0xE4 && payload[8192] == (byte) 0xB8;
        return payload;
    }

    private static String expected() {
        return "a".repeat(8191) + "中" + "tail\n";
    }

    /** 把载荷原样喷到 stdout（cmd/sh 双形），返回假 shell 路径。 */
    private Path fakeShellEmitting(byte[] payload, boolean toStderr) throws Exception {
        Path payloadFile = tmp.resolve(toStderr ? "payload-err.bin" : "payload.bin");
        Files.write(payloadFile, payload);
        if (isWindows()) {
            Path script = tmp.resolve(toStderr ? "emit-err.cmd" : "emit.cmd");
            var type = "type \"" + payloadFile.toAbsolutePath() + "\"";
            Files.writeString(script, "@echo off\r\nfindstr /r \".*\" >nul\r\n"
                + (toStderr ? type + " 1>&2\r\n" : type + "\r\n"));
            return script;
        }
        Path script = tmp.resolve(toStderr ? "emit-err.sh" : "emit.sh");
        var cat = "cat \"" + payloadFile.toAbsolutePath() + "\"";
        Files.writeString(script, "#!/bin/sh\n" + (toStderr ? cat + " >&2\n" : cat + "\n"));
        script.toFile().setExecutable(true);
        return script;
    }

    @Test
    void multiByteCharSplitAtThe8192PumpBoundarySurvivesVerbatim() throws Exception {
        var executor = new DefaultShellExecutor(fakeShellEmitting(boundaryPayload(), false).toString());
        var seen = new ArrayList<String>();

        var result = executor.execute("byte-exact", new ShellOptions(
            tmp.toString(), Map.of(), true, OptionalLong.empty(), null, seen::add));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
            .as("跨 8192 边界的 3 字节字符必须完整、无 U+FFFD、无丢字")
            .isEqualTo(expected())
            .doesNotContain("�");
        assertThat(String.join("", seen))
            .as("增量路径（Utf8ChunkStream）同样不劈字符")
            .isEqualTo(result.output())
            .doesNotContain("�");
    }

    @Test
    void stderrByteBoundaryCharSurvivesTheMerge() throws Exception {
        var executor = new DefaultShellExecutor(fakeShellEmitting(boundaryPayload(), true).toString());

        var result = executor.execute("byte-exact-err", new ShellOptions(
            tmp.toString(), Map.of(), true, OptionalLong.empty(), null, null));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
            .as("stderr 并入后同法：完整字符、无 U+FFFD")
            .isEqualTo(expected())
            .doesNotContain("�");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
