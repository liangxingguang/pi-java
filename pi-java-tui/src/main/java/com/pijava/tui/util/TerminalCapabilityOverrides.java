package com.pijava.tui.util;

/**
 * 设置侧终端能力覆盖（E2-32）：pi {@code settings-manager.ts:1198-1208}
 * {@code getTerminalCapabilityOverrides} 的 java 对位。
 *
 * <p>{@code imagesSet} 区分「不覆盖」与「显式覆盖为无图片」——pi 用
 * {@code { images: null }} 表达后者。覆盖在环境探测（含 {@code PI_*}）之后
 * 叠加，见 {@link TerminalCapabilities}。</p>
 *
 * @param imagesSet  是否覆盖图片协议
 * @param images     图片协议；{@code imagesSet=true} 且为 {@code null} ⇒ 无图片
 * @param trueColor  truecolor 覆盖（可空）
 * @param hyperlinks OSC 8 超链接覆盖（可空）
 */
public record TerminalCapabilityOverrides(
    boolean imagesSet,
    TerminalCapabilities.ImageProtocol images,
    Boolean trueColor,
    Boolean hyperlinks) {

    /** 无覆盖（pi {@code setCapabilityOverrides({})}）。 */
    public static final TerminalCapabilityOverrides NONE =
        new TerminalCapabilityOverrides(false, null, null, null);

    /** 把覆盖叠到探测结果上（设置优先）。 */
    public TerminalCapabilities applyTo(TerminalCapabilities detected) {
        return new TerminalCapabilities(
            imagesSet ? images : detected.images(),
            trueColor != null ? trueColor : detected.trueColor(),
            hyperlinks != null ? hyperlinks : detected.hyperlinks());
    }
}
