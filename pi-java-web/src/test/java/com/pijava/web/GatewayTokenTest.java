package com.pijava.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link GatewayToken} 解析 / 生成 / 比较（Stage D 鉴权）。 */
class GatewayTokenTest {

    @TempDir
    Path tmp;

    @Test
    void fromFileReadsFirstNonBlankLine() throws Exception {
        Path file = tmp.resolve("gateway-token");
        Files.writeString(file, "  my-secret  \n");
        assertThat(GatewayToken.fromFile(file)).contains("my-secret");
    }

    @Test
    void fromFileEmptyWhenMissingOrBlank() throws Exception {
        assertThat(GatewayToken.fromFile(tmp.resolve("missing"))).isEmpty();

        Path blank = tmp.resolve("blank");
        Files.writeString(blank, "   \n");
        assertThat(GatewayToken.fromFile(blank)).isEmpty();
    }

    @Test
    void generateProduces128BitHex() {
        String token = GatewayToken.generate();
        assertThat(token).matches("[0-9a-f]{32}");
        assertThat(GatewayToken.generate()).isNotEqualTo(token);
    }

    @Test
    void matchesIsExactAndRejectsBlanks() {
        assertThat(GatewayToken.matches("abc", "abc")).isTrue();
        assertThat(GatewayToken.matches("abc", "ABC")).isFalse();
        assertThat(GatewayToken.matches("abc", null)).isFalse();
        assertThat(GatewayToken.matches(null, "abc")).isFalse();
        assertThat(GatewayToken.matches("", "abc")).isFalse();
    }
}
