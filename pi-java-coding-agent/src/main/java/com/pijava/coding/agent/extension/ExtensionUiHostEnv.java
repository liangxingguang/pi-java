package com.pijava.coding.agent.extension;

import java.util.function.BiFunction;
import java.util.function.IntSupplier;

/**
 * 批 17（T1，docs/28 裁决③）：扩展组件工厂的宿主环境——pi 工厂签名里的
 * {@code (tui, theme)} 的 java 窄化投影（SPI 不能引用 TamboUI 类型）。
 *
 * @param terminalWidth  终端列数源（现读）
 * @param terminalHeight 终端行数源（现读）
 * @param requestRender  请求重绘（pi {@code tui.requestRender()} 对位）
 * @param colorize       主题色槽位投影——pi {@code theme.fg("accent", text)} 对位；
 *                       第一个参数是色槽名（accent/muted/dim/…），tui 侧按当前主题解析
 */
public record ExtensionUiHostEnv(
    IntSupplier terminalWidth,
    IntSupplier terminalHeight,
    Runnable requestRender,
    BiFunction<String, String, String> colorize) {
}
