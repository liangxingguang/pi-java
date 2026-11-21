package com.pijava.ai.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code hashThreadPrefix}（{@code anthropic-messages.ts:321-350}）：缓存标记搬移与
 * content 形状差异都不改变哈希；设置或消息内容变化则改变哈希。
 */
class AnthropicThreadHashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode body(String systemText, String userText, boolean withMarker) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "claude-opus-4-1");
        body.putObject("system").put("type", "text").put("text", systemText);
        ArrayNode tools = body.putArray("tools");
        tools.addObject().put("name", "read").put("description", "Read a file");
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        if (withMarker) {
            // 挂标记时最新消息按文本块发送。
            ArrayNode blocks = user.putArray("content");
            ObjectNode block = blocks.addObject();
            block.put("type", "text");
            block.put("text", userText);
            block.putObject("cache_control").put("type", "ephemeral");
        } else {
            user.put("content", userText);
        }
        return body;
    }

    @Test
    void cacheMarkerAndContentShapeDoNotChangeTheHash() {
        String withMarker = AnthropicThreadHash.of("anthropic", "https://api.anthropic.com", body("sys", "hi", true));
        String plain = AnthropicThreadHash.of("anthropic", "https://api.anthropic.com", body("sys", "hi", false));

        assertThat(withMarker).isEqualTo(plain);
    }

    @Test
    void changedSystemOrToolsOrEndpointChangesTheHash() {
        String base = AnthropicThreadHash.of("anthropic", "https://api.anthropic.com", body("sys", "hi", false));

        assertThat(AnthropicThreadHash.of("anthropic", "https://api.anthropic.com", body("sys2", "hi", false)))
            .isNotEqualTo(base);
        assertThat(AnthropicThreadHash.of("anthropic", "https://proxy.test", body("sys", "hi", false)))
            .isNotEqualTo(base);
        assertThat(AnthropicThreadHash.of("other", "https://api.anthropic.com", body("sys", "hi", false)))
            .isNotEqualTo(base);
    }

    @Test
    void changedMessageTextChangesTheHash() {
        String base = AnthropicThreadHash.of("anthropic", "https://api.anthropic.com", body("sys", "hi", false));

        assertThat(AnthropicThreadHash.of("anthropic", "https://api.anthropic.com", body("sys", "hello", false)))
            .isNotEqualTo(base);
    }
}
