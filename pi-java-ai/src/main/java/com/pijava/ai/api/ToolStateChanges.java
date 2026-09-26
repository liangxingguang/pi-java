package com.pijava.ai.api;

import java.util.List;

/**
 * pi {@code utils/transcript.ts:145-148} —— 两次**完整**工具状态之间的差分。
 *
 * <p>两侧的载荷形状不同，这是本记录最容易写错的地方：</p>
 *
 * <ul>
 *   <li>{@code toolsAdded} 装的是剥离后的**完整定义**（{@link ToolDeclaration}，三键
 *       {@code {name,description,parameters}}）—— 车道要拿它去建工具表；</li>
 *   <li>{@code toolsRemoved} 只装**名字**（{@link ToolReference}）—— pi 的
 *       {@code tool_removal} 块按名引用，不需要定义。</li>
 * </ul>
 *
 * <p>两条纪律（{@code docs/51 §2 P4}）：</p>
 *
 * <ol>
 *   <li><b>定义改变 ＝ 先删后加</b> —— 同名工具会**同时**出现在两侧
 *       （{@code current} 侧因为定义不同而算「加」、{@code previous} 侧因为定义不同而算「删」）；</li>
 *   <li>两侧的输出顺序**分别**跟入参：{@code toolsAdded} 跟 {@code current}、
 *       {@code toolsRemoved} 跟 {@code previous}（pi 的 {@code filter} 保序）。
 *       ⚠️ 生产者（{@code AnthropicRequestBuilder}）依赖这个顺序决定块序。</li>
 * </ol>
 *
 * @param toolsAdded   当前有而此前没有（或定义变了的）工具定义，按 {@code current} 序
 * @param toolsRemoved 此前有而当前没有（或定义变了的）工具名，按 {@code previous} 序
 */
public record ToolStateChanges(List<ToolDeclaration> toolsAdded, List<ToolReference> toolsRemoved) {

    /** Compact constructor that defensively copies both sides. */
    public ToolStateChanges {
        toolsAdded = List.copyOf(toolsAdded);
        toolsRemoved = List.copyOf(toolsRemoved);
    }
}
