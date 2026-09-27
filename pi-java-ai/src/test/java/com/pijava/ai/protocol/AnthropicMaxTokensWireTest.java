package com.pijava.ai.protocol;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anthropic.models.messages.MessageCreateParams;

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
 * 包 A-10：Anthropic 车道的 {@code max_tokens} 到底发什么。
 *
 * <p>⚠️ 修复前这一格是**本仓自造的字面量 4096**：{@code maxTokens} 在生产上没有生产者
 * （三个 {@code StreamOptions} 构造点全部传 {@code OptionalInt.empty()}），
 * 而 builder 的兜底写成了 {@code 4096L}。pi 在同一路径上发的是
 * {@code clamp(model.maxTokens)}（{@code simple-options.ts:34} ＋
 * {@code anthropic-messages.ts:866}）。⇒ 每一条用例在本包实现前都红（红集见
 * {@code docs/57 §12}）。</p>
 *
 * <p>观测面是**真出站体**（{@link RecordingHttpServer}）：不能用
 * 「反射调 {@code buildParams} ＋ 序列化 SDK 对象」那条路（SDK 把请求包在 {@code body}
 * 里，得 {@code {}}，A-01 实测）。反射那条路本文件只用于 **RED-8**（目录未命中格），
 * 因为它刻意要观察**绕过漏斗**的那个入口。</p>
 */
class AnthropicMaxTokensWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 缺席 ⇒ 模型自己的输出上限（pi 的 `options?.maxTokens ?? model.maxTokens`）──

    /**
     * ★ RED-1：不传思考、不传上限 ⇒ {@code max_tokens} 是**模型的上限**
     * （{@code claude-sonnet-4-6} 内置目录 8192）。
     *
     * <p>这一条走的是**默认分支**（用户不开 {@code --thinking} 时就是它）。修复前得
     * {@code 4096} —— 输出上限被拦腰砍半，长回答截断后会报 {@code stopReason: "length"}。</p>
     */
    @Test
    void absentCapUsesTheBuiltInModelCap() throws Exception {
        assertThat(outboundMaxTokens(builtIn("claude-sonnet-4-6"), Optional.empty(), -1))
            .isEqualTo(8192);
    }

    /**
     * ★ RED-2：adaptive 分支（{@code forceAdaptiveThinking}）同样落模型上限。
     *
     * <p>pi 的 adaptive 分支是 {@code {...base}} —— 即 {@code base.maxTokens}，
     * 与无思考那条**同源**（{@code anthropic-messages.ts:880-884}）。
     * {@code claude-fable-5} 的内置上限是 16384。</p>
     */
    @Test
    void adaptiveBranchUsesTheModelCapToo() throws Exception {
        assertThat(outboundMaxTokens(builtIn("claude-fable-5"),
            Optional.of(new ThinkingLevel.High()), -1)).isEqualTo(16384);
    }

    /** 调用方显式给了上限 ⇒ 它就是上限（夹取只在余量不足时才动手）。 */
    @Test
    void explicitCapIsHonored() throws Exception {
        assertThat(outboundMaxTokens(builtIn("claude-sonnet-4-6"), Optional.empty(), 512))
            .isEqualTo(512);
    }

    /**
     * ★ 夹取真的到场：窗口 20000、消息 40000 字符 ⇒ 估 10000 token ⇒
     * {@code 20000 − 10000 − 4096 = 5904}。
     *
     * <p>⚠️ 实际发出去的是 <b>5903</b>：本条经**漏斗**（{@code StreamRequest} 的 legacy
     * 构造器 ⇒ {@code ContextNormalizer}）造转录，于是多一条前导系统消息
     * （{@code "sys"} 3 字符 ⇒ {@code ceil(3/4) = 1}）⇒ 已占 10001。
     * 与 {@code SimpleOptionsTest.clampsToTheAvailableRoom} 的 5904 差值正是这 1
     * —— 两处**刻意都留**，让「系统消息也计入估算」这件事有一个可对照的证据。</p>
     *
     * <p>与上面几条的差别只有「窗口」与「消息长度」两格 ⇒ 证明这条线上的值是**算出来的**，
     * 不是某个常量漏下来的。</p>
     */
    @Test
    void theClampReachesTheWire() throws Exception {
        var wire = outbound(withWindow(builtIn("claude-sonnet-4-6"), 20_000, 8192),
            List.of(longUser()), Optional.empty(), -1);

        assertThat(wire.path("max_tokens").asInt())
            .as("20000 − (10000 + 1) − 4096")
            .isEqualTo(5903);
    }

    // ── ★ RED-8：绕过漏斗的那个入口（目录未命中）──────────────────────

    /**
     * ★ RED-8：**直接调 {@code buildParams}** 且模型是 {@code ModelInfo.minimal}（0/0）
     * ⇒ {@code max_tokens} 是兜底常量，**不是 0**。
     *
     * <p>为什么这条必须有：{@code AnthropicMessagesApiBuildParamsTest} 与
     * {@code AnthropicSurrogateSanitizeTest} 走的就是「{@code ModelId} 版构造器（⇒
     * {@code minimal}）＋ 反射直调 {@code buildParams}」，而它们**都不断言
     * {@code max_tokens}** ⇒ 兜底写对写错它们都绿（{@code docs/57 §4.3} 的「夹具没牙」）。
     * 本条把那一格钉住。</p>
     *
     * <p>⚠️ 它的**牙不来自先红**：修复前的字面量恰好也是 4096 ⇒ 本用例在旧代码上
     * <b>也是绿的</b>。判别力由变异探针 M7 提供（把常量改成 4097 ⇒ 本用例红），
     * 如实记在 {@code docs/57 §12}。</p>
     *
     * <p>⚠️ 这里断言的是**字面量**而不是 {@code SimpleOptions.NO_MODEL_CAP_FALLBACK}：
     * 与常量比较会让本用例对「常量被改成多少」完全不敏感（M7 实测：改成 4097 时只有
     * 单测那条红）。兜底值是**登记在案的刻意偏差**（{@code docs/57 §6 R4}），
     * 它若漂移，两侧都该叫 —— 重写一遍 4096 是**故意的**。</p>
     */
    @Test
    void directBuildParamsOnACatalogMissUsesTheFallbackNotZero() throws Exception {
        var minimal = ModelInfo.minimal(ModelId.of("anthropic", "claude-sonnet-5"));
        var request = new StreamRequest(minimal, "", List.of(user("hi")), List.of(),
            -1, -1, Map.of());

        assertThat(directBuildParams(request).maxTokens())
            .as("不许是 0（Anthropic 会 400）、也不许是 pi 公式在这一格算出的 1")
            .isEqualTo(4096L);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 经漏斗（{@code AbstractChatApi.stream} ⇒ {@code SimpleOptions}）取真出站体里的 {@code max_tokens}。 */
    private static int outboundMaxTokens(ModelInfo model, Optional<ThinkingLevel> reasoning,
                                         int maxTokens) throws Exception {
        return outbound(model, List.of(user("hi")), reasoning, maxTokens)
            .path("max_tokens").asInt();
    }

    private static JsonNode outbound(ModelInfo model, List<Message> messages,
                                     Optional<ThinkingLevel> reasoning, int maxTokens)
            throws Exception {
        try (var server = new RecordingHttpServer()) {
            var api = new AnthropicMessagesApi(
                new ApiOptions(server.baseUrl(), "test-key", Duration.ofSeconds(5), 0, Map.of()),
                "ANTHROPIC_API_KEY");
            var request = new StreamRequest(model, "sys", messages, List.of(),
                maxTokens, -1, Map.of(), reasoning);
            try (var iter = api.streamBlocking(request, ApiOptions.defaults())) {
                while (iter.hasNext()) {
                    iter.next();
                }
            } catch (Exception ignored) {
                // 桩恒回 400：请求体已录到，流怎么结束与断言无关
            }
            assertThat(server.body()).as("请求必须真的发出去").isNotEmpty();
            return MAPPER.readTree(server.body());
        }
    }

    /** {@code ModelInfo.minimal} 那一格要的是**绕过漏斗**的入口。 */
    private static MessageCreateParams directBuildParams(StreamRequest request) throws Exception {
        var api = new AnthropicMessagesApi(
            new ApiOptions("https://example.invalid", "sk-test", Duration.ofSeconds(5), 0, Map.of()),
            "ANTHROPIC_API_KEY");
        Method method = AnthropicMessagesApi.class.getDeclaredMethod(
            "buildParams", StreamRequest.class);
        method.setAccessible(true);
        return (MessageCreateParams) method.invoke(api, request);
    }

    private static ModelInfo builtIn(String modelName) {
        return BuiltinCatalog.anthropicModels()
            .find(ModelId.of("anthropic", modelName)).orElseThrow();
    }

    /** 同上但换掉窗口与上限 —— 夹取那一条要一个**够小**的窗口才动得起来。 */
    private static ModelInfo withWindow(ModelInfo base, int contextWindow, int maxOutput) {
        return new ModelInfo(base.id(), base.displayName(), base.capabilities(),
            contextWindow, maxOutput, base.deprecated(), base.pricing(),
            base.thinkingLevelMap(), base.headers(), base.samplingParams(), base.compat());
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static Message longUser() {
        return user("x".repeat(40_000));
    }
}
