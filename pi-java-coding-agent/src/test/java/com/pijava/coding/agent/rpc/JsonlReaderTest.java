package com.pijava.coding.agent.rpc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-5b: JsonlReader — 严格 LF-only 分帧。
 */
class JsonlReaderTest {

    @Test
    void splitsOnLfOnly() throws Exception {
        var lines = read("{\"id\":\"1\"}\n{\"id\":\"2\"}\n");
        assertThat(lines).containsExactly("{\"id\":\"1\"}", "{\"id\":\"2\"}");
    }

    @Test
    void stripsTrailingCrButSplitsOnlyOnLf() throws Exception {
        // \r 非行分隔符：只在 \n 上切分，剥除行尾 \r；行内 \r 保留。
        var lines = read("a\rb\nc\r\n");
        assertThat(lines).containsExactly("a\rb", "c");
    }

    @Test
    void doesNotSplitOnUnicodeSeparators() throws Exception {
        // U+2028/U+2029 在 JSON 字符串内合法，多字节 UTF-8 不含 0x0A → 不切分。
        String payload = "{\"text\":\"line1 line2 \"}";
        var lines = read(payload + "\n" + "{\"id\":\"2\"}\n");
        assertThat(lines).containsExactly(payload, "{\"id\":\"2\"}");
    }

    @Test
    void eofResidualBufferIsLastLine() throws Exception {
        var lines = read("{\"id\":\"1\"}\n{\"partial\"");
        assertThat(lines).containsExactly("{\"id\":\"1\"}", "{\"partial\"");
    }

    @Test
    void skipsEmptyLines() throws Exception {
        var lines = read("\n\n{\"id\":\"1\"}\n\n\n{\"id\":\"2\"}\n");
        assertThat(lines).containsExactly("{\"id\":\"1\"}", "{\"id\":\"2\"}");
    }

    @Test
    void emptyInputReturnsNothing() throws Exception {
        assertThat(read("")).isEmpty();
    }

    private static List<String> read(String input) throws IOException {
        var lines = new ArrayList<String>();
        try (var reader = new JsonlReader(new ByteArrayInputStream(
                input.getBytes(StandardCharsets.UTF_8)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
