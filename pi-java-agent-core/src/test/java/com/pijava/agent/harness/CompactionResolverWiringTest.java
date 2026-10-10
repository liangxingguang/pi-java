package com.pijava.agent.harness;

import java.util.Map;
import java.util.function.Function;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.ai.model.ModelId;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B223（{@code docs/47}）：压缩设置槽从<b>静态值</b>改成「按当前模型解析」的函数。
 *
 * <p>pi 的四个压缩决策点全部现读 {@code getCompactionSettings(this.model)}
 * （settings-manager.ts 语义，锚点 {@code 200387122}），不缓存 —— 所以：</p>
 * <ul>
 *   <li>{@code setModel} 之后，下一次决策必须读到新模型的预算（T-A2）；</li>
 *   <li>{@link AgentHarness#fork()} 的新 harness 有自己的 {@code lane.model}，
 *       fork 切模型不得串回父 harness（T-A3）；</li>
 *   <li>宿主不接 resolver 时维持引擎旧契约「不接＝不压」（T-A1，护栏）。</li>
 * </ul>
 */
class CompactionResolverWiringTest {

    private static final ModelId<?> MODEL_A = ModelId.of("faux", "model-a");
    private static final ModelId<?> MODEL_B = ModelId.of("faux", "model-b");
    private static final CompactionSettings FOR_A =
        new CompactionSettings(true, 111, 222);
    private static final CompactionSettings FOR_B =
        new CompactionSettings(true, 333, 444);

    /** 按模型名分发的解析器 —— 每次调用都重读，内部不缓存。 */
    private static Function<ModelId<?>, CompactionSettings> perModel() {
        Map<String, CompactionSettings> byName = Map.of(
            MODEL_A.modelName(), FOR_A,
            MODEL_B.modelName(), FOR_B);
        return m -> byName.get(m.modelName());
    }

    private static AgentHarness harness(
            ModelId<?> initial, Function<ModelId<?>, CompactionSettings> resolver) {
        StreamFn neverCalled = (model, context, options) -> {
            throw new AssertionError("streamFn 不该被调用：本夹具只观察设置解析");
        };
        var builder = HarnessConfig.builder()
            .streamFn(neverCalled)
            .model(initial)
            .maxInputTokens(200_000)
            .contextWindow(ignored -> 200_000);
        if (resolver != null) {
            builder.compactionResolver(resolver);
        }
        return AgentHarness.create(builder.build());
    }

    /** T-A1（护栏）：不接 resolver ⇒ 决策读 null（G0 关），切模型也不变。 */
    @Test
    void unwiredResolverStaysNullAcrossModelChanges() {
        try (var h = harness(MODEL_A, null)) {
            assertThat(h.currentCompactionSettings()).isNull();
            h.setModel(MODEL_B);
            assertThat(h.currentCompactionSettings()).isNull();
        }
    }

    /** T-A2（设计红）：setModel 后下一次解析读<b>当前</b>模型，不是建时快照。 */
    @Test
    void resolverIsCalledWithTheCurrentModelAfterSetModel() {
        try (var h = harness(MODEL_A, perModel())) {
            assertThat(h.currentCompactionSettings())
                .as("建时模型 A ⇒ A 的预算")
                .isEqualTo(FOR_A);

            h.setModel(MODEL_B);

            assertThat(h.currentCompactionSettings())
                .as("setModel(B) 后下一次决策必须按 B 解析")
                .isEqualTo(FOR_B);
        }
    }

    /** T-A3（设计红）：fork 用自己的 lane.model 解析，fork 切模型不串父 harness。 */
    @Test
    void forkResolvesAgainstItsOwnCurrentModel() {
        try (var parent = harness(MODEL_A, perModel());
                var forked = parent.fork()) {
            assertThat(parent.currentCompactionSettings()).isEqualTo(FOR_A);
            assertThat(forked.currentCompactionSettings()).isEqualTo(FOR_A);

            forked.setModel(MODEL_B);

            assertThat(forked.currentCompactionSettings())
                .as("fork 切到 B ⇒ fork 按 B 解析")
                .isEqualTo(FOR_B);
            assertThat(parent.currentCompactionSettings())
                .as("父 harness 的模型仍是 A，不得被 fork 串走")
                .isEqualTo(FOR_A);
        }
    }
}
