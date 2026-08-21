package com.pijava.ai.protocol;

import java.util.List;

import com.pijava.ai.api.EmbeddingRequest;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-28: OpenAIEmbeddingApi — 请求构建（model/input）与向量映射。
 */
class OpenAIEmbeddingApiTest {

    @Test
    void buildParamsSetsModelAndArrayInput() {
        var params = OpenAIEmbeddingApi.buildParams(new EmbeddingRequest(
            ModelId.of("openai", "text-embedding-3-small"),
            List.of("hello", "world")));
        assertThat(params.model().toString()).isEqualTo("text-embedding-3-small");
        assertThat(params.input().asArrayOfStrings()).containsExactly("hello", "world");
    }

    @Test
    void buildParamsWithSingleStringInput() {
        var params = OpenAIEmbeddingApi.buildParams(new EmbeddingRequest(
            ModelId.of("openai", "text-embedding-3-large"),
            List.of("solo")));
        assertThat(params.input().asArrayOfStrings()).containsExactly("solo");
    }

    @Test
    void toFloatArrayConvertsList() {
        assertThat(OpenAIEmbeddingApi.toFloatArray(List.of(0.1f, 2.5f, -3f)))
            .containsExactly(0.1f, 2.5f, -3f);
        assertThat(OpenAIEmbeddingApi.toFloatArray(List.of())).isEmpty();
    }
}
