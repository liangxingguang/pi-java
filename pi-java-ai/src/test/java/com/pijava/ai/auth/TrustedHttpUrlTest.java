package com.pijava.ai.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/19 §2.4：#43 服务端 URL 白名单（pi {@code auth/oauth/trusted-url.ts}）。
 *
 * <p>只断言登录流实际会遇到的归一形态：大小写、默认端口、空路径、空格与其他
 * ISO 控制字符、非 ASCII 百分号编码；WHATWG 的边缘面（反斜杠、IDN）不在此。</p>
 */
class TrustedHttpUrlTest {

    @Test
    void addsTrailingSlashWhenPathIsEmpty() {
        assertThat(TrustedHttpUrl.normalize("https://verify.example.com"))
            .isEqualTo("https://verify.example.com/");
    }

    @Test
    void lowercasesSchemeAndHostButKeepsPathCase() {
        assertThat(TrustedHttpUrl.normalize("HTTPS://EXAMPLE.COM/Verify"))
            .isEqualTo("https://example.com/Verify");
    }

    @Test
    void removesDefaultPorts() {
        assertThat(TrustedHttpUrl.normalize("http://ex.com:80/x")).isEqualTo("http://ex.com/x");
        assertThat(TrustedHttpUrl.normalize("https://ex.com:443/x")).isEqualTo("https://ex.com/x");
    }

    @Test
    void keepsNonDefaultPort() {
        assertThat(TrustedHttpUrl.normalize("https://ex.com:8443/x"))
            .isEqualTo("https://ex.com:8443/x");
    }

    @Test
    void percentEncodesSpace() {
        assertThat(TrustedHttpUrl.normalize("https://ex.com/a b"))
            .isEqualTo("https://ex.com/a%20b");
    }

    @Test
    void encodesNonAsciiAsPercentEscapes() {
        assertThat(TrustedHttpUrl.normalize("https://ex.com/中"))
            .isEqualTo("https://ex.com/%E4%B8%AD");
    }

    @Test
    void stripsTabNewlineAndCarriageReturn() {
        assertThat(TrustedHttpUrl.normalize("ht\ttps://ex.\ncom/a\rb"))
            .isEqualTo("https://ex.com/ab");
    }

    @Test
    void preservesQueryAndFragment() {
        assertThat(TrustedHttpUrl.normalize("HTTPS://EX.COM/x?q=1#frag"))
            .isEqualTo("https://ex.com/x?q=1#frag");
    }

    @Test
    void collapsesDotSegments() {
        assertThat(TrustedHttpUrl.normalize("https://ex.com/a/../b")).isEqualTo("https://ex.com/b");
    }

    @Test
    void rejectsNonHttpProtocols() {
        assertThat(TrustedHttpUrl.normalize("file:///etc/passwd")).isNull();
        assertThat(TrustedHttpUrl.normalize("javascript:alert(1)")).isNull();
        assertThat(TrustedHttpUrl.normalize("ftp://ex.com/x")).isNull();
    }

    @Test
    void rejectsRelativeAndHostlessUrls() {
        assertThat(TrustedHttpUrl.normalize("/x")).isNull();
        assertThat(TrustedHttpUrl.normalize("http://")).isNull();
        assertThat(TrustedHttpUrl.normalize("https:///x")).isNull();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThat(TrustedHttpUrl.normalize(null)).isNull();
        assertThat(TrustedHttpUrl.normalize("")).isNull();
        assertThat(TrustedHttpUrl.normalize("   ")).isNull();
    }
}
