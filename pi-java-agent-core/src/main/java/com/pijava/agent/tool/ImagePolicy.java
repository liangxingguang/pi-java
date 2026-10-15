package com.pijava.agent.tool;

import com.pijava.ai.catalog.InputLimits;

/**
 * 图片入模策略（批 9/B85），执行期从 {@link ToolContext#imagePolicy()} 现读。
 *
 * <p>合并了 pi 的两个通道：read 工具构造选项 {@code ReadToolOptions}
 * （{@code tools/read.ts:63-70}，autoResize/fallbackResizeOptions）与执行期
 * {@code ctx.model}（当前模型的 {@code inputLimits.images.resize} 与视觉位）。
 * Java 的 context 是会话级对象而模型可轮转，故由 coding-agent 经
 * {@link ToolContext#withImagePolicy} 注入晚绑定 supplier。</p>
 *
 * @param autoResize     是否把超限图片缩放到内联上限（pi {@code images.autoResize}，默认 true）
 * @param resize         当前模型的 resize 档位；{@code null} 用 {@code ImageResizer} 保守默认档
 *                       （2000/2000/4.5MiB/80，与 pi 内置默认同源）
 * @param supportsImages 当前模型是否吃图；{@code false} 时 read 加注记
 *                       （真正的省略由 ai 层 transform 降级闸完成）
 */
public record ImagePolicy(boolean autoResize, InputLimits.ResizeOptions resize,
                          boolean supportsImages) {

    /** pi 无模型元数据时的等效默认：开缩放、内置档、不注记。 */
    public static ImagePolicy defaults() {
        return DEFAULT;
    }

    private static final ImagePolicy DEFAULT = new ImagePolicy(true, null, true);
}
