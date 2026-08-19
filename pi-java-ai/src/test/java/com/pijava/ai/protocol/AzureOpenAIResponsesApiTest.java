package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-1f: AzureOpenAIResponsesApi — baseUrl 五级解析、部署名映射、部署名作 model。
 * 事件映射与 OpenAIResponsesApi 共享 {@link ResponsesStreamProcessor}，不重测。
 */
class AzureOpenAIResponsesApiTest {

    @Test
    void parseDeploymentNameMapSkipsMalformedEntries() {
        // "bad"（无 =）、"= "（空 modelId）跳过；"a=b=c" 按 split("=",2) 是合法项。
        var map = AzureOptions.parseDeploymentNameMap(
            "gpt-4o=deploy-a, , bad, a=b=c, = , x=y");
        assertThat(map).containsEntry("gpt-4o", "deploy-a")
            .containsEntry("a", "b=c")
            .containsEntry("x", "y")
            .hasSize(3);
    }

    @Test
    void emptyDeploymentMapReturnsEmpty() {
        assertThat(AzureOptions.parseDeploymentNameMap(null)).isEmpty();
        assertThat(AzureOptions.parseDeploymentNameMap("   ")).isEmpty();
    }

    @Test
    void azureBaseUrlOptionOverridesOptionsBaseUrl() {
        var api = new AzureOpenAIResponsesApi(new ApiOptions(
            "https://fallback.example.com/v1", "test-key", Duration.ofSeconds(1), 0,
            Map.of("azureBaseUrl", "https://api.example.com/v1")),
            "AZURE_OPENAI_API_KEY");
        assertThat(api).isNotNull();
    }

    @Test
    void resourceNameBuildsDefaultBaseUrl() {
        var api = new AzureOpenAIResponsesApi(new ApiOptions(
            "", "test-key", Duration.ofSeconds(1), 0,
            Map.of("azureResourceName", "my-resource")),
            "AZURE_OPENAI_API_KEY");
        assertThat(api).isNotNull();
    }

    @Test
    void allEmptyBaseUrlThrows() {
        assertThatThrownBy(() -> new AzureOpenAIResponsesApi(
            new ApiOptions("", "test-key", Duration.ofSeconds(1), 0, Map.of()),
            "AZURE_OPENAI_API_KEY"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AZURE_OPENAI_BASE_URL");
    }

    @Test
    void buildParamsUsesDeploymentNameAsModel() throws Exception {
        var request = new StreamRequest(
            ModelId.of("azure-openai-responses", "gpt-4o"),
            List.of(new Message.UserMessage(
                List.of(new ContentBlock.TextContent("hi")))),
            List.of(), 100, -1, Map.of());
        var method = ResponsesMessageConverter.class.getDeclaredMethod(
            "buildParams", StreamRequest.class, ResponsesOptions.class, String.class);
        method.setAccessible(true);
        var params = (com.openai.models.responses.ResponseCreateParams) method.invoke(
            null, request, ResponsesOptions.from(ApiOptions.defaults()), "my-deploy");
        assertThat(params.model().orElseThrow().asString()).isEqualTo("my-deploy");
    }

    @Test
    void defaultApiVersionIsV1() {
        assertThat(AzureOptions.DEFAULT_API_VERSION).isEqualTo("v1");
    }
}
