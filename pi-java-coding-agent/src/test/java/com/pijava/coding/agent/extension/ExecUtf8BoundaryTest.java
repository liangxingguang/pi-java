package com.pijava.coding.agent.extension;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W3（B277a）：跨块 UTF-8 解码（pi core/exec.ts:63-68, 140-143）。子进程逐字节
 * 写出＋flush，读取端必然跨块；截断路径不得追加替换字符。
 */
class ExecUtf8BoundaryTest {

    @Test
    void multiByteCharactersSurviveChunkSplits() {
        var result = ExtensionExec.execCommand(ExecTestSupport.javaCommand(),
            ExecTestSupport.probeArgs("utf8-slow", "40"), null,
            new ExtensionExec.ExecOptions(null, null, null, null));
        assertThat(result.stdout()).isEqualTo("汉字🙂".repeat(40));
        assertThat(result.stdout()).doesNotContain("�");
    }

    @Test
    void truncationDropsIncompleteUtf8Suffix() {
        // cap 落在“汉”（3 字节）的第一个字节之后：只接受 1 字节。
        var result = ExtensionExec.execCommand(ExecTestSupport.javaCommand(),
            ExecTestSupport.probeArgs("utf8-slow", "2"), null,
            new ExtensionExec.ExecOptions(null, null, null, 1L));
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdout()).as("不完整的 3 字节序列被剥离，而非解码为替换字符")
            .isEmpty();
        assertThat(result.stdout()).doesNotContain("�");
        assertThat(result.code()).isEqualTo(1);
    }

    @Test
    void completeUtf8PrefixStripsOnlyIncompleteSuffix() {
        String text = "汉字🙂";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int cut = 0; cut <= bytes.length; cut++) {
            byte[] prefix = Arrays.copyOf(bytes, cut);
            String decoded = ExtensionExec.decode(prefix, true);
            assertThat(decoded).doesNotContain("�");
            assertThat(text).startsWith(decoded);
        }
        assertThat(ExtensionExec.decode(bytes, true)).isEqualTo(text);
    }
}
