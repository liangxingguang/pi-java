package com.pijava.ai.message;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包①（docs/31 §8.33.7 证伪点 1）：{@code ThinkingContent} 从两个组件长到三个，
 * 新增的 {@code redacted} 是**原生 boolean**，靠 Jackson 的布尔缺省值容忍旧 JSON。
 *
 * <p><b>为什么值得单独立夹具</b>：生产链路里**没有**任何一处用 Jackson 读
 * {@code ContentBlock}（JSONL/SQLite 走 {@code MessageJsonCodec} 手写解码，web 走自己的
 * 投影）——所以这条不是端到端缺口。但 {@code ContentBlock} 带着
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes}（{@code :16-25}），是**公开 API 面**：
 * 用方照注解自建 mapper 时，缺 {@code redacted} 的旧 JSON 必须仍能读出，
 * 且「规范构造器 + 两个便捷构造器」三个构造器不许让 Jackson 挑花眼
 * （record + 多构造器是 Jackson 反序列化的已知歧义点，故实测而非推断）。</p>
 */
class ContentBlockJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void legacyThinkingJsonWithoutRedactedStillReads() throws Exception {
        assertThat(JSON.readValue("{\"type\":\"thinking\",\"text\":\"old\"}", ContentBlock.class))
            .as("缺 redacted 的旧 JSON ⇒ 布尔缺省 false，不许抛")
            .isEqualTo(new ContentBlock.ThinkingContent("old", "", false));
    }

    @Test
    void redactedThinkingJsonSurvivesRoundTrip() throws Exception {
        var block = new ContentBlock.ThinkingContent("r", "sig", true);
        assertThat(JSON.readValue(JSON.writeValueAsString(block), ContentBlock.class))
            .isEqualTo(block);
    }
}
