package com.pijava.ai.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * models.json parsing: schema mapping, id-from-map-key, defaults, errors.
 */
class ModelsJsonConfigTest {

    @TempDir
    Path tmp;

    private static final String FULL_CONFIG = """
        {
          "providers": {
            "teamorouter": {
              "name": "TeamoRouter",
              "baseUrl": "https://api.teamorouter.cn/v1",
              "apiKey": "sk-inline-test",
              "api": "openai-completions",
              "unknownFutureField": true,
              "models": [
                {
                  "id": "deepseek-v4-flash",
                  "name": "DeepSeek V4 Flash",
                  "reasoning": true,
                  "contextWindow": 1048576,
                  "maxTokens": 393216,
                  "alsoUnknown": {"x": 1}
                }
              ]
            },
            "my-relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1", "input": ["text", "image"], "cost": {"input": 1.5, "output": 6}}]
            }
          }
        }
        """;

    @Test
    void missingFileYieldsEmptyConfig() {
        var config = ModelsJsonConfig.load(tmp.resolve("absent.json"));
        assertThat(config.isEmpty()).isTrue();
        assertThat(config.providerIds()).isEmpty();
    }

    @Test
    void parsesProvidersWithIdFromMapKey() {
        var config = write(FULL_CONFIG);
        // Jackson map deserialization does not preserve file order.
        assertThat(config.providerIds()).containsExactlyInAnyOrder("teamorouter", "my-relay");
        var def = config.provider("teamorouter");
        assertThat(def).isNotNull();
        assertThat(def.name()).isEqualTo("TeamoRouter");
        assertThat(def.baseUrl()).isEqualTo("https://api.teamorouter.cn/v1");
        assertThat(def.api()).isEqualTo("openai-completions");
        assertThat(def.apiKey()).isEqualTo("sk-inline-test");
    }

    @Test
    void ignoresUnknownFields() {
        var config = write(FULL_CONFIG);
        assertThat(config.provider("teamorouter")).isNotNull();
        assertThat(config.provider("teamorouter").models()).hasSize(1);
    }

    @Test
    void buildProvidersRoutesProtocolsAndDefaultsDisplayName() {
        var config = write(FULL_CONFIG);
        var providers = config.buildProviders();
        assertThat(providers).hasSize(2);

        var teamo = (ModelsJsonProvider) providers.stream()
            .filter(p -> p.name().equals("teamorouter")).findFirst().orElseThrow();
        assertThat(teamo.displayName()).isEqualTo("TeamoRouter");
        assertThat(teamo.supportedProtocols()).containsExactly(Protocol.OPENAI_COMPLETIONS);
        assertThat(teamo.providerConfig().defaultBaseUrl()).isEqualTo("https://api.teamorouter.cn/v1");

        var relay = (ModelsJsonProvider) providers.stream()
            .filter(p -> p.name().equals("my-relay")).findFirst().orElseThrow();
        assertThat(relay.displayName()).isEqualTo("my-relay");
        assertThat(relay.supportedProtocols()).containsExactly(Protocol.ANTHROPIC_MESSAGES);
    }

    @Test
    void catalogMapsModelDefaults() {
        var config = write(FULL_CONFIG);
        var models = config.catalog().listModels();
        assertThat(models).hasSize(2);

        var deepseek = models.stream()
            .filter(m -> m.id().modelName().equals("deepseek-v4-flash")).findFirst().orElseThrow();
        assertThat(deepseek.id().provider()).isEqualTo("teamorouter");
        assertThat(deepseek.displayName()).isEqualTo("DeepSeek V4 Flash");
        assertThat(deepseek.maxInputTokens()).isEqualTo(1_048_576);
        assertThat(deepseek.maxOutputTokens()).isEqualTo(393_216);
        assertThat(deepseek.capabilities()).contains(ModelCapability.TEXT, ModelCapability.THINKING);

        var m1 = models.stream()
            .filter(m -> m.id().modelName().equals("m1")).findFirst().orElseThrow();
        assertThat(m1.capabilities()).contains(ModelCapability.IMAGE_INPUT);
        assertThat(m1.maxInputTokens()).isEqualTo(128_000);
        assertThat(m1.maxOutputTokens()).isEqualTo(16_384);
        assertThat(m1.pricing().inputPrice()).isEqualTo(1.5);
        assertThat(m1.pricing().outputPrice()).isEqualTo(6.0);
    }

    // ------------------------------------------------- B8-3：models.json 的 compat 块

    /**
     * <b>B8-3</b>：{@code compat.allowEmptySignature} 从 models.json 读进
     * {@link com.pijava.ai.catalog.ModelInfo#compat()}。
     *
     * <p>与 B8-1/B8-2（{@code AnthropicThinkingReplayTest}）分工：那两条钉**投送与落线**
     * （compat 从 {@code StreamRequest} 到唯一行为点），这条钉**入口**（文件 → 目录元数据）。
     * 两者互不依赖 —— 这条不经过 {@code StreamRequest}，所以即使投送链断了也照样绿。</p>
     */
    @Test
    void readsAllowEmptySignatureFromCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1", "compat": {"allowEmptySignature": true}}]
            }}}
            """);

        var model = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();

        assertThat(model.compat().allowEmptySignature()).isTrue();
    }

    /**
     * <b>B8-3</b>：没有 {@code compat} 块 ⇒ 全 false（{@link ModelCompat#NONE}）。
     *
     * <p>pi `anthropic-messages.ts:193` 的 `?? false` 把「缺席」与「false」归一 ⇒ **二态**，
     * 不是三态（§8.34.4 决策 3）。这条与上一条合起来把二态钉成事实。</p>
     */
    @Test
    void absentCompatBlockMeansNoFlags() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1"}]
            }}}
            """);

        var model = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();

        assertThat(model.compat()).isEqualTo(ModelCompat.NONE);
    }

    /**
     * <b>B8-3</b>：{@code compat} 块里**未知**的键被忽略（pi-java 只做被消费的那些标志）。
     *
     * <p>⚠️ 这是与 pi 的一处**刻意不同**：pi 的 compat 接口有八个字段，pi-java 只携带被实际
     * 消费的一个（{@code ModelCompat} 的 javadoc 记着这条）。忽略未知键使「pi 新加的 compat
     * 标志」不会把文件打崩 —— 代价是新标志会**静默失效**，故此处显式钉住该行为，
     * 免得日后误以为是解析 bug。</p>
     */
    @Test
    void ignoresUnknownKeysInsideCompatBlock() {
        var config = write("""
            {"providers": {"relay": {
              "baseUrl": "https://relay.example.com",
              "api": "anthropic-messages",
              "models": [{"id": "m1", "compat": {
                "allowEmptySignature": true,
                "someFutureFlag": 1
              }}]
            }}}
            """);

        var model = config.catalog().find(ModelId.of("relay", "m1")).orElseThrow();

        assertThat(model.compat()).isEqualTo(ModelCompat.of(true));
    }

    @Test
    void missingApiThrowsWithProviderId() {
        var config = write("""
            {"providers": {"broken": {"baseUrl": "https://x.example.com",
              "models": [{"id": "m"}]}}}
            """);
        assertThatThrownBy(config::buildProviders)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("api");
    }

    @Test
    void missingBaseUrlThrowsWithProviderId() {
        var config = write("""
            {"providers": {"broken": {"api": "openai-completions",
              "models": [{"id": "m"}]}}}
            """);
        assertThatThrownBy(config::buildProviders)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("baseUrl");
    }

    @Test
    void missingModelIdThrowsWithProviderId() {
        var config = write("""
            {"providers": {"broken": {"api": "openai-completions",
              "baseUrl": "https://x.example.com", "models": [{"name": "m"}]}}}
            """);
        assertThatThrownBy(config::buildProviders)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("broken")
            .hasMessageContaining("id");
    }

    @Test
    void malformedJsonThrowsWithPath() throws IOException {
        var path = tmp.resolve("bad-" + System.nanoTime() + ".json");
        Files.writeString(path, "{not json");
        assertThatThrownBy(() -> ModelsJsonConfig.load(path))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(path.toString());
    }

    private ModelsJsonConfig write(String json) {
        var path = tmp.resolve("models-" + System.nanoTime() + ".json");
        try {
            Files.writeString(path, json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ModelsJsonConfig.load(path);
    }
}
