package com.pijava.coding.agent.extension;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 批 17（T1，docs/28）：pi {@code WorkingIndicatorOptions}（types.ts:143-147 逐字）
 * ——streaming 加载指示器配置。
 *
 * @param frames     动画帧——空表＝整体隐藏指示器；自定义帧逐字渲染
 *                   （颜色须扩展自带，pi 注释逐字）；null ≙ 键省略
 * @param intervalMs 帧间隔毫秒；null ≙ 键省略
 */
public record WorkingIndicatorOptions(
    @Nullable List<String> frames, @Nullable Long intervalMs) {
}
