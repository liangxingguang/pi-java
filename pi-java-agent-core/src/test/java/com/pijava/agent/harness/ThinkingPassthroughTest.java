package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步1：<b>引擎层不翻译思考级别</b> —— 它只把级别<b>原样</b>投给 {@link StreamFn}。
 *
 * <p>⚠️ <b>本类取代了 {@code ThinkingTranslationTest}</b>，而且把它的断言<b>反过来</b>了。
 * 旧类钉的是「harness 用 {@code thinkingLevelMap} 把级别翻译成 {@code ThinkingConfig}」
 * —— 那正是包H5 要<b>拆掉</b>的行为：翻译需要 {@code model.compat} 与
 * {@code model.thinkingLevelMap}，而引擎层只有 {@code ModelId}（{@code docs/31 §8.34.4 决策 5}
 * 实测的断链）。pi 的分层是：{@code SimpleStreamOptions.reasoning} 是<b>未翻译的级别</b>
 * （{@code types.ts:328}），翻译在<b>车道内</b>做（{@code anthropic-messages.ts:858-904}）。</p>
 *
 * <p>所以本类的核心断言是<b>一条否命题</b>：<b>表在不在场，都不影响这一层投出去的东西</b>。</p>
 */
class ThinkingPassthroughTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** 跑一次 prompt，抓下传给 {@link StreamFn} 的 options。 */
    private static StreamOptions captureOptions(ModelThinkingLevel level, ThinkingLevelMap map) {
        var captured = new AtomicReference<StreamOptions>();
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (model, context, options) -> {
            captured.set(options);
            return StreamIterator.from(List.of(
                    new StreamEvent.Start(AssistantMessage.empty()),
                    new StreamEvent.TextEnd(0, "ok", partial),
                    new StreamEvent.StreamDone("stop", null, partial)));
        };

        var harness = AgentHarness.create(new HarnessConfig(
                sf, MODEL, level, "",
                Set.of(), 200_000, null, null, null,
                null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, map,
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));

        harness.prompt("hello");
        return captured.get();
    }

    /** 非空表 ⇒ 级别<b>原样</b>投出（表里写了什么都不影响这一层）。 */
    @Test
    void levelReachesTheStreamFnUntranslated() {
        var map = ThinkingLevelMap.of(Map.of(
                ModelThinkingLevel.of(new ThinkingLevel.High()), Optional.of("high")));

        var options = captureOptions(new ModelThinkingLevel.Enabled(new ThinkingLevel.High()), map);

        assertThat(options.reasoning()).contains(new ThinkingLevel.High());
    }

    /**
     * ⚠️ <b>本类的要害</b>：空表与上面那条<b>结果相同</b> —— 引擎层对表<b>无感知</b>。
     *
     * <p>旧行为下这两条会给出<b>不同</b>结果（空表 ⇒ {@code ThinkingConfig.OFF}），
     * 那正是「翻译发生在引擎层」的指纹。</p>
     */
    @Test
    void emptyMapChangesNothingAtThisLayer() {
        var withEmptyMap = captureOptions(
                new ModelThinkingLevel.Enabled(new ThinkingLevel.High()), ThinkingLevelMap.empty());

        assertThat(withEmptyMap.reasoning()).contains(new ThinkingLevel.High());
    }

    /** {@code Off} ≙ pi 的 {@code "off"} —— 请求侧就是**不传**（{@code agent.ts:465}）。 */
    @Test
    void offBecomesAbsentReasoning() {
        var options = captureOptions(ModelThinkingLevel.off(), ThinkingLevelMap.empty());

        assertThat(options.reasoning()).isEmpty();
    }
}
