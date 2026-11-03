package com.pijava.ai.identity;

/**
 * Three-state attribution field.
 *
 * <p>pi 的 {@code attribution?: ClientAttribution | null}（{@code types.ts} 同族）用
 * {@code undefined} 表示「不参与合并」，用 {@code null} 表示「显式关闭」。Java 的
 * nullable 引用区分不了两者，故用本 sealed 类型：</p>
 *
 * <ul>
 *   <li>{@link Unset}：缺席——合并时跳过；</li>
 *   <li>{@link Off}：显式 {@code null}——合并后关闭此前得到的归因；</li>
 *   <li>{@link Value}：有值——合并时覆盖。</li>
 * </ul>
 */
public sealed interface AttributionSetting permits AttributionSetting.Unset,
        AttributionSetting.Off, AttributionSetting.Value {

    /** 缺席（字段未设置）。 */
    AttributionSetting UNSET = new Unset();

    /** 显式关闭归因。 */
    AttributionSetting OFF = new Off();

    /** 有值状态。 */
    record Unset() implements AttributionSetting {}

    /** 显式关闭状态。 */
    record Off() implements AttributionSetting {}

    /** 有值状态。 */
    record Value(ClientAttribution attribution) implements AttributionSetting {
        public Value {
            java.util.Objects.requireNonNull(attribution, "attribution");
        }
    }

    /** 便捷工厂：把非空值包成 {@link Value}。 */
    static AttributionSetting of(ClientAttribution attribution) {
        return new Value(attribution);
    }
}
