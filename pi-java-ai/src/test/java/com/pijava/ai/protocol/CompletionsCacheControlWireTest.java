package com.pijava.ai.protocol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CacheControlFormat;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.builtin.OpenRouterModels;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-02（B105，原 docs/59 §4.9）端到端的钉子：anthropic 形状的 {@code cache_control}
 * 真的落到了 completions 车道的**出站体**上（pi {@code openai-completions.ts:858-859}
 * ＋ {@code :1069-1180}）。
 *
 * <p>观测面与 {@code AnthropicCacheControlWireTest}（A-01）同一手法：真字节
 * （{@link RecordingHttpServer}），断言按键取值、不逐字节（SDK 键序与 pi 不同，
 * {@code 原 docs/54 §4.7}）。模型用**内置目录**的 {@code anthropic/claude-fable-5:batch}
 * —— {@code cacheControlFormat} 走请求期探测（{@code detectCompat:1632}），不手搓 compat。</p>
 *
 * <p>三断点（pi {@code applyAnthropicCacheControl:1081-1089} 的次序）：第一条
 * system|developer 消息、工具表末项、倒序第一条「挂得上」的会话消息。</p>
 */
class CompletionsCacheControlWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT = "be brief";

    // ── 夹具 ────────────────────────────────────────────────────

    /** 内置 openrouter batch 模型（探测给 cacheControlFormat=anthropic、developer 角色、长缓存支持）。 */
    private static ModelInfo batchModel() {
        return OpenRouterModels.catalog()
            .find(ModelId.of("openrouter", "anthropic/claude-fable-5:batch")).orElseThrow();
    }

    /** 对照：内置 openrouter 的 openai/* 模型（探测给 cacheControlFormat=null）。 */
    private static ModelInfo openAiModel() {
        return OpenRouterModels.catalog()
            .find(ModelId.of("openrouter", "openai/gpt-5.1")).orElseThrow();
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of("type", "object"));
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message assistantText(String text) {
        return new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
            "stop", null, null, null, null, null, null, null, null);
    }

    private static Message assistantToolCallOnly() {
        return new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("t1", "lookup", Map.of())),
            "toolUse", null, null, null, null, null, null, null, null);
    }

    private static Message toolResult(String text) {
        return new Message.ToolResultMessage("t1", "lookup",
            List.of(new ContentBlock.TextContent(text)), false);
    }

    private static JsonNode body(ModelInfo model, List<Message> messages,
                                 List<ToolDefinition> tools, CacheRetention retention)
            throws Exception {
        var extra = new LinkedHashMap<String, Object>();
        if (retention != null) {
            extra.put("cacheRetention", retention.wireName());
        }
        return bodyWith(model, messages, tools, extra);
    }

    private static JsonNode bodyWith(ModelInfo model, List<Message> messages,
                                     List<ToolDefinition> tools, Map<String, Object> extra)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new OpenAICompletionsApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, extra),
                "OPENROUTER_API_KEY");
            var request = new StreamRequest(model, SYSTEM_PROMPT, messages, tools,
                -1, -1, Map.of());
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩回 400：请求体已录到
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    /** 递归收集全树里每一个 {@code cache_control} 字段所在的路径。 */
    private static List<String> cacheControlPaths(JsonNode node) {
        var out = new ArrayList<String>();
        collect(node, "$", out);
        return out;
    }

    private static void collect(JsonNode node, String path, List<String> out) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                if ("cache_control".equals(name)) {
                    out.add(path + ".cache_control");
                }
                collect(node.get(name), path + "." + name, out);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(node.get(i), path + "[" + i + "]", out);
            }
        }
    }

    private static JsonNode lastMessage(JsonNode body) {
        var messages = body.path("messages");
        return messages.get(messages.size() - 1);
    }

    // ── 三断点（pi :1081-1089）────────────────────────────────────

    @Test
    void batchModelGetsExactlyTheThreeBreakpoints() throws Exception {
        var wire = body(batchModel(),
            List.of(user("hi"), assistantText("hello"), user("next")),
            List.of(tool("alpha"), tool("omega")),
            CacheRetention.SHORT);

        var paths = cacheControlPaths(wire);
        assertThat(paths).hasSize(3);

        // ① 第一条指令消息：batch 模型是 reasoning + anthropic/ 前缀 ⇒ developer 角色，
        //    串 content 被改写成 [{type:text,text,cache_control}]（pi :1160-1171）。
        var instruction = wire.path("messages").get(0);
        assertThat(instruction.path("role").asText()).isEqualTo("developer");
        assertThat(instruction.path("content").get(0).path("cache_control").path("type").asText())
            .isEqualTo("ephemeral");
        assertThat(instruction.path("content").get(0).path("cache_control").has("ttl")).isFalse();

        // ② 工具表**末项**（omega，不是 alpha；⚠️ 无 supportsCacheControlOnTools 门）。
        var tools = wire.path("tools");
        assertThat(tools.get(1).path("function").path("name").asText()).isEqualTo("omega");
        assertThat(tools.get(1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(tools.get(0).has("cache_control")).isFalse();

        // ③ 倒序第一条会话消息 = 末条 user；中间两条不挂。
        assertThat(lastMessage(wire).path("role").asText()).isEqualTo("user");
        assertThat(lastMessage(wire).path("content").get(0).path("cache_control").path("type")
            .asText()).isEqualTo("ephemeral");
        assertThat(cacheControlPaths(wire.path("messages").get(1))).isEmpty();
        assertThat(cacheControlPaths(wire.path("messages").get(2))).isEmpty();
    }

    @Test
    void stringContentIsRewrittenIntoAPartArray() throws Exception {
        // 末条是 tool 消息（串 content，恒非空——占位串兜底）⇒ 改写成数组分片（pi :1160-1171）。
        var wire = body(batchModel(),
            List.of(user("hi"), assistantToolCallOnly(), toolResult("42")),
            List.of(),
            CacheRetention.SHORT);

        var last = lastMessage(wire);
        assertThat(last.path("role").asText()).isEqualTo("tool");
        assertThat(last.path("content").isArray()).isTrue();
        assertThat(last.path("content").get(0).path("text").asText()).isEqualTo("42");
        assertThat(last.path("content").get(0).path("cache_control").path("type").asText())
            .isEqualTo("ephemeral");

        // 倒序 walk：assistant 只有 tool_calls、content 缺席 ⇒ 挂不上、继续往前
        // （pi addCacheControlToTextContent 的 undefined 分支 :1173-1175）。
        var assistant = wire.path("messages").get(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(cacheControlPaths(assistant)).isEmpty();
        // 断点总数不因 walk 多出来。
        assertThat(cacheControlPaths(wire)).hasSize(2);   // system + tool 消息（无工具表）
    }

    // ── 门与对照组 ─────────────────────────────────────────────

    @Test
    void openAiPrefixedModelGetsNoBreakpoints() throws Exception {
        // 对照：cacheControlFormat 探测只认 anthropic/ 前缀（:1632）。
        var wire = body(openAiModel(),
            List.of(user("hi")), List.of(tool("alpha")), CacheRetention.SHORT);
        assertThat(cacheControlPaths(wire)).isEmpty();
    }

    @Test
    void noneRetentionSendsNoBreakpoints() throws Exception {
        // pi :1073 —— cacheRetention === "none" ⇒ 整族撤掉（压缩摘要路径的生产者，A-01）。
        var wire = body(batchModel(),
            List.of(user("hi")), List.of(tool("alpha")), CacheRetention.NONE);
        assertThat(cacheControlPaths(wire)).isEmpty();
        assertThat(wire.has("prompt_cache_retention")).isFalse();
    }

    @Test
    void longRetentionAddsTheTtlAndPromptCacheRetention() throws Exception {
        // pi :1077（ttl "1h"）＋ :825（prompt_cache_retention "24h"）——两者共用同一个
        // supportsLongCacheRetention 门（openrouter 探测为真，:1671-1677）。
        var wire = body(batchModel(),
            List.of(user("hi")), List.of(), CacheRetention.LONG);

        var cc = wire.path("messages").get(0).path("content").get(0).path("cache_control");
        assertThat(cc.path("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.path("ttl").asText()).isEqualTo("1h");
        assertThat(wire.path("prompt_cache_retention").asText()).isEqualTo("24h");
    }

    @Test
    void explicitLongCacheRetentionFalseSuppressesBothTtlAndPromptKey() throws Exception {
        // 显式 supportsLongCacheRetention:false 压过探测（getCompat:1720）⇒ 断点照发、
        // 无 ttl、无 prompt_cache_retention。
        var explicit = new ModelCompat(false, null, true, false, null, null, null, null, null,
            true, null, null, null, null, Boolean.FALSE, null, null, null, null, null,
            Map.of(), Map.of(), null, CacheControlFormat.ANTHROPIC, null);
        var model = new ModelInfo(ModelId.of("relay", "anthropic/claude-x"), "X",
            Set.of(ModelCapability.TEXT, ModelCapability.THINKING), 200_000, 64_000, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), explicit);

        var wire = body(model, List.of(user("hi")), List.of(), CacheRetention.LONG);

        var cc = wire.path("messages").get(0).path("content").get(0).path("cache_control");
        assertThat(cc.path("type").asText()).isEqualTo("ephemeral");
        assertThat(cc.has("ttl")).isFalse();
        assertThat(wire.has("prompt_cache_retention")).isFalse();
    }

    @Test
    void promptCacheRetentionNeedsTheLongRetentionAlone() throws Exception {
        // :825 的门**不含** cacheControlFormat ⇒ 非 anthropic/ 前缀的 openrouter 模型
        // （探测 supportsLongCacheRetention 为真）在 long 下也发 prompt_cache_retention。
        var wire = body(openAiModel(), List.of(user("hi")), List.of(), CacheRetention.LONG);

        assertThat(wire.path("prompt_cache_retention").asText()).isEqualTo("24h");
        assertThat(cacheControlPaths(wire)).isEmpty();
        // prompt_cache_key 恒缺席：java 无 sessionId 通道，pi 在同样条件下也发不出
        // （clampOpenAIPromptCacheKey(undefined) → undefined；B134）。
        assertThat(wire.has("prompt_cache_key")).isFalse();
    }
}
