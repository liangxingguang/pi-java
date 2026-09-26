package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A7c 的端到端钉子：**内置目录**的 compat 标注经解析层真的落到了 Anthropic 的线上体。
 *
 * <p>与 {@code AnthropicThinkingWireTest}／{@code AnthropicMessagesApiBuildParamsTest} 的分工：
 * 那两份用手搓的 {@code ModelInfo}（显式 compat）钉**分支**，本份用
 * {@link BuiltinCatalog#anthropicModels()} 的**真**模型钉**可达性** —— 包 A7 之前
 * 内置目录全是 {@code ModelCompat.NONE}，故这几条在旧代码上必红（{@code docs/53 §3 F3}
 * 的 D1/D2/D4）。</p>
 */
class AnthropicCompatWireTest {

    private MessageCreateParams buildParams(StreamRequest request) throws Exception {
        var options = new ApiOptions(
            "https://api.teamorouter.cn", "sk-test",
            Duration.ofSeconds(10), 1, Map.of());
        var api = new AnthropicMessagesApi(options, "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    private static ModelInfo builtIn(String modelName) {
        return BuiltinCatalog.anthropicModels()
            .find(ModelId.of("anthropic", modelName)).orElseThrow();
    }

    private static StreamRequest request(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                          double temperature) {
        return new StreamRequest(
            model, null,
            List.of(new Message.UserMessage(List.of(new ContentBlock.TextContent("hi")))),
            List.of(), -1, temperature, Map.of(), reasoning);
    }

    // ── D1：思考形态（forceAdaptiveThinking）─────────────────────

    @Test
    void fable5AndSonnet46UseAdaptiveThinking() throws Exception {
        for (var id : new String[] {"claude-fable-5", "claude-sonnet-4-6", "claude-opus-4-8"}) {
            var params = buildParams(request(builtIn(id),
                Optional.of(new ThinkingLevel.High()), -1));

            assertThat(params.thinking().orElseThrow().isAdaptive()).as(id).isTrue();
        }
    }

    @Test
    void haiku45StaysOnTheBudgetShape() throws Exception {
        // 配对用例（docs/44 的「缺席断言必须与在场断言成对」）：同一构造下 haiku 不是 adaptive。
        var params = buildParams(request(builtIn("claude-haiku-4-5-20251001"),
            Optional.of(new ThinkingLevel.High()), -1));

        var thinking = params.thinking().orElseThrow();
        assertThat(thinking.isAdaptive()).isFalse();
        assertThat(thinking.isEnabled()).isTrue();
    }

    // ── D2：temperature 抑制（supportsTemperature）───────────────

    @Test
    void opus48DropsTheTemperatureField() throws Exception {
        var params = buildParams(request(builtIn("claude-opus-4-8"),
            Optional.empty(), 0.5));

        assertThat(params.temperature()).isEmpty();
    }

    @Test
    void sonnet46KeepsTheTemperatureField() throws Exception {
        // 配对：同一个 0.5 的温度在未标注抑制的模型上照发。
        var params = buildParams(request(builtIn("claude-sonnet-4-6"),
            Optional.empty(), 0.5));

        assertThat(params.temperature()).isPresent();
        assertThat(params.temperature().orElseThrow()).isEqualTo(0.5);
    }

    // ── D4：中途系统消息的保留（supportsMidConvoSystemMessages）──

    @Test
    void theAnnotatedModelKeepsAMidConversationSystemMessage() throws Exception {
        var params = buildParams(requestWithUpdate(builtIn("claude-opus-4-8")));

        // 保留 ⇒ 那条更新落成**会话里的一条 system 消息**（pi :1050 的原生渲染）。
        assertThat(params.messages())
            .anyMatch(m -> m.role() == MessageParam.Role.SYSTEM);
    }

    @Test
    void theUnannotatedModelFoldsItAway() throws Exception {
        var params = buildParams(requestWithUpdate(builtIn("claude-haiku-4-5-20251001")));

        // 折叠 ⇒ 只剩前导系统消息（它被 `subList(1, …)` 切走）＋ user，没有中途的 system。
        assertThat(params.messages())
            .noneMatch(m -> m.role() == MessageParam.Role.SYSTEM);
    }

    /**
     * 转录 = [前导系统消息, userA, **中途**系统段更新, userB]。
     *
     * <p>⚠️ 那条更新**不能放下标 0**：{@code ContextNormalizer} 见到首条已是系统消息就认为
     * 「调用方自己造好了转录」⇒ 会**跳过** {@code systemPrompt}，于是前导消息变成那条更新
     * 本身、<em>中途</em>的形状根本没被构造出来（第一版夹具就是这样空过的）。
     * 位置与 {@code docs/51 §12.4.2} 的 A5 教训同源：要钉「就地发」就得给出**它之前还有东西**
     * 的转录。</p>
     */
    private static StreamRequest requestWithUpdate(ModelInfo model) {
        // ⚠️ 不能用 `Map.of("tools", null)` —— 它在 null **值**上直接 NPE（Map.of 的已知限制），
        // 而 pi 的段删除语义正是「值 null」（docs/52 §12.3）。
        var sections = new LinkedHashMap<String, String>();
        sections.put("tools", null);
        var update = new Message.SystemMessage("", Instant.EPOCH, sections, List.of(), List.of());
        return new StreamRequest(
            model, "be brief",
            List.of(user("a"), update, user("b")),
            List.of(), -1, -1, Map.of(), Optional.empty());
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }
}
