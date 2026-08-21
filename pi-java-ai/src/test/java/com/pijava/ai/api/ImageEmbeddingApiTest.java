package com.pijava.ai.api;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-28: ImageApi/EmbeddingApi 载荷 JSON round-trip + ImageStopReason wire。
 */
class ImageEmbeddingApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void imageRequestRoundTrip() throws Exception {
        var req = new ImageRequest(ModelId.of("openrouter-images", "flux"),
            List.of(new ContentBlock.TextContent("a red panda")));
        var back = JSON.readValue(JSON.writeValueAsString(req), ImageRequest.class);
        assertThat(back).isEqualTo(req);
    }

    @Test
    void imageResultRoundTrip() throws Exception {
        var result = new ImageResult("openrouter-images", "flux",
            List.of(new ContentBlock.TextContent("caption"),
                new ContentBlock.ImageContent("image/png", "AA==")),
            ImageStopReason.STOP, null, 12345L);
        var back = JSON.readValue(JSON.writeValueAsString(result), ImageResult.class);
        assertThat(back).isEqualTo(result);
    }

    @Test
    void imageResultErrorPathRoundTrip() throws Exception {
        var result = new ImageResult("openrouter-images", "flux", List.of(),
            ImageStopReason.ERROR, "boom", 0L);
        var back = JSON.readValue(JSON.writeValueAsString(result), ImageResult.class);
        assertThat(back).isEqualTo(result);
    }

    @Test
    void imageStopReasonWire() throws Exception {
        assertThat(JSON.writeValueAsString(ImageStopReason.STOP)).isEqualTo("\"stop\"");
        assertThat(JSON.writeValueAsString(ImageStopReason.ERROR)).isEqualTo("\"error\"");
        assertThat(JSON.writeValueAsString(ImageStopReason.ABORTED)).isEqualTo("\"aborted\"");
    }

    @Test
    void embeddingRequestRoundTrip() throws Exception {
        var req = new EmbeddingRequest(ModelId.of("openai", "text-embedding-3-small"),
            List.of("hello", "world"));
        var back = JSON.readValue(JSON.writeValueAsString(req), EmbeddingRequest.class);
        assertThat(back).isEqualTo(req);
    }

    @Test
    void embeddingResultRoundTrip() throws Exception {
        var result = new EmbeddingResult("text-embedding-3-small",
            List.of(new float[] {0.1f, 0.2f}, new float[] {0.3f, 0.4f}), 5);
        var back = JSON.readValue(JSON.writeValueAsString(result), EmbeddingResult.class);
        assertThat(back.model()).isEqualTo("text-embedding-3-small");
        assertThat(back.inputTokens()).isEqualTo(5);
        assertThat(back.embeddings()).hasSize(2);
        assertThat(back.embeddings().get(0)).containsExactly(0.1f, 0.2f);
    }
}
