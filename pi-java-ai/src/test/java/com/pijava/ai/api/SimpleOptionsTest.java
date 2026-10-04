package com.pijava.ai.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A-10：请求侧选项解析 —— pi {@code simple-options.ts:15-52} 的可观察半边。
 *
 * <p>四条语义各一组：<b>回落</b>（缺席 ⇒ 模型上限）、<b>夹取</b>（上下文窗口 − 已占 − 4096）、
 * <b>未知窗口不夹</b>（{@code contextWindow <= 0} 是哨兵）、<b>目录未命中的兜底</b>
 * （{@code 原 docs/57 §6 R4}，**刻意偏差**）。</p>
 *
 * <p>⚠️ 本文件钉的是**值**；「这些值真的落到线格上了」由
 * {@code AnthropicMaxTokensWireTest} 与 {@code CompletionsMaxTokensWireTest} 钉。</p>
 */
class SimpleOptionsTest {

    // ── maxTokensOrDefault（pi 的 `options?.maxTokens ?? model.maxTokens`）──

    /** 调用方给了上限 ⇒ **就是它**（pi 的 {@code ??} 左侧命中）。 */
    @Test
    void requestedCapWinsWhenPresent() {
        assertThat(SimpleOptions.maxTokensOrDefault(model(200_000, 8192), 512)).isEqualTo(512);
    }

    /** ★ 缺席（java 的 {@code -1} 哨兵）⇒ 回落到**模型自己的输出上限**，不是某个字面量。 */
    @Test
    void absentCapFallsBackToTheModelCap() {
        assertThat(SimpleOptions.maxTokensOrDefault(model(200_000, 8192), -1)).isEqualTo(8192);
        assertThat(SimpleOptions.maxTokensOrDefault(model(200_000, 32_768), -1)).isEqualTo(32_768);
    }

    /**
     * ★ 目录未命中（{@code ModelInfo.minimal}，0/0）⇒ 兜底常量（{@code 原 docs/57 §6 R4}）。
     *
     * <p>⚠️ 这条钉的**不是** pi 的行为：pi 的公式在这一格上会算出 {@code 1}
     * （{@code contextWindow <= 0} ⇒ {@code max(1, maxTokens)} 而 {@code maxTokens == 0}），
     * 但 pi 的数据面里不存在 0（它的 {@code Model.maxTokens} 必填无默认）。java 这一格
     * 今天有生产路径（{@code DefaultProviders} 的 {@code orElseGet}）⇒ 取保守默认。</p>
     */
    @Test
    void catalogMissUsesTheDocumentedFallback() {
        var minimal = ModelInfo.minimal(ModelId.of("deepseek", "deepseek-v4-flash"));
        assertThat(SimpleOptions.maxTokensOrDefault(minimal, -1))
            .isEqualTo(SimpleOptions.NO_MODEL_CAP_FALLBACK);
        assertThat(SimpleOptions.NO_MODEL_CAP_FALLBACK).isEqualTo(4096);
    }

    /** 模型为 {@code null}（{@code modelId()} 有守卫 ⇒ 存在这种请求）⇒ 也只走兜底。 */
    @Test
    void nullModelUsesTheFallbackWithoutClamping() {
        var request = new StreamRequest((ModelInfo) null, "", List.of(user("hi")), List.of(),
            -1, -1, Map.of(), java.util.Optional.empty());
        assertThat(SimpleOptions.resolveMaxTokens(null, request.transcript(), -1))
            .isEqualTo(SimpleOptions.NO_MODEL_CAP_FALLBACK);
    }

    // ── clampMaxTokensToContext（pi simple-options.ts:15-19）────────────

    /** 余量充足 ⇒ 上限原样（夹取是**上限**，不是缩放）。 */
    @Test
    void roomyContextLeavesTheCapAlone() {
        var transcript = new TranscriptContext(List.of(user("hi")));
        assertThat(SimpleOptions.clampMaxTokensToContext(model(200_000, 8192), transcript, 8192))
            .isEqualTo(8192);
    }

    /**
     * ★ 余量不足 ⇒ 压到 {@code 窗口 − 已占 − 4096}。
     *
     * <p>取值刻意算好：窗口 20000、消息 40000 字符 ⇒ 估 10000 token ⇒
     * {@code 20000 − 10000 − 4096 = 5904}。</p>
     */
    @Test
    void clampsToTheAvailableRoom() {
        var transcript = new TranscriptContext(List.of(user("x".repeat(40_000))));
        assertThat(SimpleOptions.clampMaxTokensToContext(model(20_000, 8192), transcript, 8192))
            .isEqualTo(5904);
    }

    /** ★ 未知窗口（{@code contextWindow <= 0}）⇒ **不夹**，只做下限（pi 的第一支）。 */
    @Test
    void unknownContextWindowSkipsTheClamp() {
        var transcript = new TranscriptContext(List.of(user("x".repeat(40_000))));
        assertThat(SimpleOptions.clampMaxTokensToContext(model(0, 8192), transcript, 8192))
            .isEqualTo(8192);
        // 与上面的 5904 配对：唯一的差别就是窗口那一格 ⇒ 证明窗口真的参与了运算。
    }

    /** 余量算负（窗口被占满）⇒ 落到 {@code MIN_MAX_TOKENS}，不出现 0 或负数。 */
    @Test
    void exhaustedContextFloorsAtOneToken() {
        var transcript = new TranscriptContext(List.of(user("x".repeat(400_000))));
        assertThat(SimpleOptions.clampMaxTokensToContext(model(20_000, 8192), transcript, 8192))
            .isEqualTo(1);
    }

    /** 组合：回落之后再夹 —— 模型上限大于余量时，结果是余量。 */
    @Test
    void resolveMaxTokensClampsTheModelCapToo() {
        var transcript = new TranscriptContext(List.of(user("x".repeat(40_000))));
        assertThat(SimpleOptions.resolveMaxTokens(model(20_000, 8192), transcript, -1))
            .isEqualTo(5904);
    }

    // ── resolveRequest（漏斗的那一跳）──────────────────────────────────

    /** 解析是**幂等**的：解析后的请求再解析一次值不变。 */
    @Test
    void resolveRequestIsIdempotent() {
        var once = SimpleOptions.resolveRequest(request(model(200_000, 8192), -1));
        assertThat(once.maxTokens()).isEqualTo(8192);
        assertThat(SimpleOptions.resolveRequest(once).maxTokens()).isEqualTo(8192);
    }

    /** 值没变 ⇒ 返回**原对象**（解析层在多数请求上不产生额外分配）。 */
    @Test
    void resolveRequestReturnsTheSameInstanceWhenNothingChanges() {
        var request = request(model(200_000, 8192), 512);
        assertThat(SimpleOptions.resolveRequest(request)).isSameAs(request);
    }

    /** 解析只动 {@code maxTokens}，其余组件逐一同值（含转录与思考级别）。 */
    @Test
    void resolveRequestOnlyChangesTheOutputCap() {
        var request = request(model(200_000, 8192), -1);
        var resolved = SimpleOptions.resolveRequest(request);

        assertThat(resolved.maxTokens()).isEqualTo(8192);
        assertThat(resolved.model()).isSameAs(request.model());
        assertThat(resolved.transcript()).isSameAs(request.transcript());
        assertThat(resolved.temperature()).isEqualTo(request.temperature());
        assertThat(resolved.extra()).isEqualTo(request.extra());
        assertThat(resolved.reasoning()).isEqualTo(request.reasoning());
    }

    // ── samplingParamsOf（pi buildBaseOptions:27-30 的结果）─────────────

    /** 缺席或空 ⇒ 空（车道那边就是「不写」）。 */
    @Test
    void absentOrEmptySamplingParamsYieldEmpty() {
        assertThat(SimpleOptions.samplingParamsOf(model(200_000, 8192))).isEmpty();
        assertThat(SimpleOptions.samplingParamsOf(model(200_000, 8192, Map.of()))).isEmpty();
    }

    /** 非空 ⇒ 原样交出（per-request 那一半在 java 没有生产者，见 B125）。 */
    @Test
    void nonEmptySamplingParamsArePassedThrough() {
        var params = Map.<String, Object>of("top_p", 0.9);
        assertThat(SimpleOptions.samplingParamsOf(model(200_000, 8192, params))).contains(params);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ModelInfo model(int contextWindow, int maxOutput) {
        return model(contextWindow, maxOutput, Map.of());
    }

    private static ModelInfo model(int contextWindow, int maxOutput, Map<String, Object> sampling) {
        return new ModelInfo(ModelId.of("test", "m"), "m",
            Set.of(), contextWindow, maxOutput, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), sampling, ModelCompat.NONE);
    }

    private static StreamRequest request(ModelInfo model, int maxTokens) {
        return new StreamRequest(model, "", List.of(user("hi")), List.of(),
            maxTokens, -1, Map.of(), java.util.Optional.empty());
    }
}
