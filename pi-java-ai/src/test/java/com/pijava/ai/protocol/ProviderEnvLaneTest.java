package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/19 #50：各车道读取点改走 {@code ProviderEnv} 后，请求级 env 能影响
 * key 回落、缓存保留期与 Azure 配置解析。
 */
class ProviderEnvLaneTest {

    private static ApiOptions options(Map<String, String> env) {
        return new ApiOptions("", "", Duration.ofSeconds(1), 0, Map.of(),
            AuthKind.API_KEY, null, null, env);
    }

    @Test
    void lanesResolveApiKeysFromRequestScopedEnv() {
        var openAi = options(Map.of("DOCS19_OPENAI_KEY", "key-1"));
        assertThat(new OpenAICompletionsApi(openAi, "DOCS19_OPENAI_KEY")).isNotNull();

        var anthropic = options(Map.of("DOCS19_ANTHROPIC_KEY", "key-2"));
        assertThat(new AnthropicMessagesApi(anthropic, "DOCS19_ANTHROPIC_KEY")).isNotNull();

        var gemini = options(Map.of("GEMINI_API_KEY", "key-3"));
        assertThat(new GoogleGenerativeAiApi(gemini)).isNotNull();

        var mistral = options(Map.of("MISTRAL_API_KEY", "key-4"));
        assertThat(new MistralConversationsApi(mistral)).isNotNull();

        var embedding = options(Map.of("DOCS19_EMBEDDING_KEY", "key-5"));
        assertThat(new OpenAIEmbeddingApi(embedding, "DOCS19_EMBEDDING_KEY")).isNotNull();

        var images = options(Map.of("DOCS19_IMAGES_KEY", "key-6"));
        assertThat(new OpenRouterImagesApi(images, "DOCS19_IMAGES_KEY")).isNotNull();
    }

    @Test
    void cacheRetentionEnvCanComeFromRequestScope() {
        var env = Map.of(
            "PI_CACHE_RETENTION", "long",
            "DOCS19_ANTHROPIC_KEY", "key-2",
            "DOCS19_OPENAI_KEY", "key-1",
            "MISTRAL_API_KEY", "key-4");

        var anthropic = new AnthropicMessagesApi(options(env), "DOCS19_ANTHROPIC_KEY");
        var completions = new OpenAICompletionsApi(options(env), "DOCS19_OPENAI_KEY");
        var mistral = new MistralConversationsApi(options(env));

        assertThat(anthropic.cacheRetentionEnv()).isEqualTo("long");
        assertThat(completions.cacheRetentionEnv()).isEqualTo("long");
        assertThat(mistral.cacheRetentionEnv()).isEqualTo("long");
    }

    @Test
    void azureReadsRequestScopedEnv() {
        var env = Map.of(
            "AZURE_OPENAI_API_KEY", "azure-key",
            "AZURE_OPENAI_BASE_URL", "https://azure.example.com/openai/v1",
            "AZURE_OPENAI_API_VERSION", "2024-10-01-preview",
            "AZURE_OPENAI_DEPLOYMENT_NAME_MAP", "gpt-4o=deploy-a");
        var options = options(env);
        var azure = AzureOptions.from(options);

        assertThat(AzureOpenAIResponsesApi.resolveBaseUrl(azure, options, env))
            .isEqualTo("https://azure.example.com/openai/v1");
        assertThat(AzureOpenAIResponsesApi.resolveApiVersion(azure, env))
            .isEqualTo("2024-10-01-preview");
        var request = new com.pijava.ai.api.StreamRequest(ModelId.of("azure", "gpt-4o"),
            null, java.util.List.of(new Message.UserMessage(
                java.util.List.of(new ContentBlock.TextContent("hi")))), java.util.List.of(), 10, -1);
        assertThat(AzureOpenAIResponsesApi.resolveDeploymentName(azure, request, env))
            .isEqualTo("deploy-a");
    }
}
