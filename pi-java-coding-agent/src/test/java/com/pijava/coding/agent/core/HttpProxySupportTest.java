package com.pijava.coding.agent.core;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 11 T3（docs/22 §3.5，pi {@code applyHttpProxySettings}，
 * http-dispatcher.ts:45-49）：{@code settings.httpProxy} → JVM 系统属性
 * （裁决③：pi 写进程 env 的 {@code ??=} 语义对位为「env 或属性已有值则不覆盖」）。
 */
class HttpProxySupportTest {

    /** 记录式 setter：避免在单测里写 JVM 全局系统属性。 */
    private static Map<String, String> recording() {
        return new HashMap<>();
    }

    @Test
    void blankOrNullIsNoOp() {
        var props = recording();

        HttpProxySupport.apply(null, k -> null, props::put);
        HttpProxySupport.apply("   ", k -> null, props::put);

        assertThat(props).isEmpty();
    }

    /** pi {@code ??=}：env 已有 HTTP_PROXY/HTTPS_PROXY ⇒ 设置不覆盖。 */
    @Test
    void envPresentSkipsApplication() {
        var props = recording();

        HttpProxySupport.apply("http://proxy.local:3128", k -> "http://other", props::put);

        assertThat(props).isEmpty();
    }

    @Test
    void appliesHostAndExplicitPortToBothSchemes() {
        var props = recording();

        HttpProxySupport.apply("http://proxy.local:3128", k -> null, props::put);

        assertThat(props).containsExactlyInAnyOrderEntriesOf(Map.of(
            "http.proxyHost", "proxy.local", "http.proxyPort", "3128",
            "https.proxyHost", "proxy.local", "https.proxyPort", "3128"));
    }

    /** 缺端口 ⇒ 80（jdk 默认 ProxySelector 的端口语义）。 */
    @Test
    void missingPortDefaultsTo80() {
        var props = recording();

        HttpProxySupport.apply("http://proxy.local", k -> null, props::put);

        assertThat(props).containsEntry("http.proxyPort", "80")
            .containsEntry("https.proxyPort", "80");
    }

    /** 非法 URL（无 host）⇒ 静默跳过（pi 对非法值也不报错）。 */
    @Test
    void invalidUrlIsSkipped() {
        var props = recording();

        HttpProxySupport.apply("not a url", k -> null, props::put);
        HttpProxySupport.apply("http://", k -> null, props::put);

        assertThat(props).isEmpty();
    }

    /** {@code ??=} 第二臂：系统属性已有值 ⇒ 不覆盖（public 入口真实系统属性面）。 */
    @Test
    void existingSystemPropertyIsNotOverwritten() {
        String savedHost = System.getProperty("http.proxyHost");
        String savedPort = System.getProperty("http.proxyPort");
        String savedHttpsHost = System.getProperty("https.proxyHost");
        String savedHttpsPort = System.getProperty("https.proxyPort");
        System.setProperty("http.proxyHost", "preset.example");
        try {
            // 注入空 env（真实 env 可能带 HTTP_PROXY ⇒ 走 env 臂）＋真实 setter。
            HttpProxySupport.apply("http://proxy.local:3128", k -> null, System::setProperty);

            assertThat(System.getProperty("http.proxyHost")).isEqualTo("preset.example");
            // 未预设的键仍被应用。
            assertThat(System.getProperty("http.proxyPort")).isEqualTo("3128");
            assertThat(System.getProperty("https.proxyHost")).isEqualTo("proxy.local");
        } finally {
            restore("http.proxyHost", savedHost);
            restore("http.proxyPort", savedPort);
            restore("https.proxyHost", savedHttpsHost);
            restore("https.proxyPort", savedHttpsPort);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
