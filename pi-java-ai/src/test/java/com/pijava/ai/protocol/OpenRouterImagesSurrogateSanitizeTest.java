package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageRequest;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步4（{@code docs/43 D4}）：openrouter-images 车道的孤对代理净化落点 ——
 * pi {@code openrouter-images.ts:141} 的 1 处。
 *
 * <p>pi 在这条车道只有一处：{@code text: sanitizeSurrogates(item.text)}（user content
 * parts 的文本项）。java 对应 {@code toContentPart} 的文本分支 —— 车道的图片项不进净化
 * （pi 侧同理，图片走 base64 字符串）。</p>
 *
 * <p>观测面＝{@code buildParams} 的出参（包私有，无网络）。</p>
 */
class OpenRouterImagesSurrogateSanitizeTest {

    private static final char HIGH = (char) 0xD83D;
    private static final String EMOJI = "🙈";
    private static final String DIRTY = "a red panda " + HIGH + " please";
    private static final String CLEAN = "a red panda  please";

    private static OpenRouterImagesApi api() {
        return new OpenRouterImagesApi(
            new ApiOptions("", "test-key", Duration.ofSeconds(10), 0, Map.of()),
            "OPENROUTER_API_KEY");
    }

    private static List<String> payloads(String text) {
        var params = api().buildParams(new ImageRequest(
            ModelId.of("openrouter-images", "black-forest-labs/flux.2-flex"),
            List.of(new ContentBlock.TextContent(text))));
        return params.messages().get(0).asUser().content()
            .asArrayOfContentParts().stream()
            .filter(p -> p.isText())
            .map(p -> p.asText().text())
            .toList();
    }

    @Test
    void promptTextIsSanitized() {
        assertThat(payloads(DIRTY)).as("prompt 文本已净化").containsExactly(CLEAN);
    }

    @Test
    void pairedEmojiSurvives() {
        var paired = "a red panda " + EMOJI + " please";
        assertThat(payloads(paired)).as("配对 emoji 逐字保留").containsExactly(paired);
    }
}