package com.pijava.ai.provider;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.ModelCatalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-02（原 docs/59 §4.5，登记 B138）：{@code effectiveOptions} 的 baseUrl 回落
 * **必须保真 authKind**。
 *
 * <p>改前这里用五参 {@code ApiOptions} 构造重建 ⇒ compact 构造器把 kind 归一成
 * {@code API_KEY}。生产上 baseUrl 恒空（{@code DefaultProviders.apiOptions} 只在
 * CLI/settings 给值时才非空）⇒ {@code Credentials} 解析出的 {@code BEARER}/{@code OAUTH}
 * 形态**全部**在进车道前被拆台（A0 步7 的车道分派形同虚设）。</p>
 */
class ConfigurableProviderAuthKindTest {

    /** 同包内取 protected 的 effectiveOptions；协议面不参与本夹具。 */
    private static final class TestProvider extends ConfigurableProvider {
        @Override
        protected ProviderConfig config() {
            return ProviderConfig.single("test", "Test", "https://test.example.com/v1",
                "TEST_API_KEY", Protocol.OPENAI_COMPLETIONS, ModelCatalog.empty());
        }

        @Override
        protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void baseUrlFallbackPreservesTheCredentialKind() {
        var out = new TestProvider().effectiveOptions(new ApiOptions(
            "", "tok", Duration.ofSeconds(1), 0, Map.of(), AuthKind.BEARER));

        assertThat(out.baseUrl()).isEqualTo("https://test.example.com/v1");
        assertThat(out.authKind()).isEqualTo(AuthKind.BEARER);
    }

    @Test
    void explicitBaseUrlPassesThroughUntouched() {
        var options = new ApiOptions("https://relay.example.com", "tok",
            Duration.ofSeconds(1), 0, Map.of(), AuthKind.OAUTH);

        var out = new TestProvider().effectiveOptions(options);

        assertThat(out).isSameAs(options);
    }
}
