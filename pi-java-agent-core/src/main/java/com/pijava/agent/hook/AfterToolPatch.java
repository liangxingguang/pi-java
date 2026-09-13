package com.pijava.agent.hook;

import java.util.List;

import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;

/**
 * {@code after_tool} 钩子的返回值：对工具结果的**逐字段补丁**。
 *
 * <p>对齐 pi {@code finalizeExecutedToolCall}（{@code agent-loop.ts:744-751}）：
 * {@code afterResult} 的每个字段经 {@code ??} 合并 —— 未设的字段**保留原值**。
 * JS 里 {@code undefined} 与 {@code null} 都会落到 {@code ??} 的右操作数，所以
 * pi 同样**无法**把字段清成 null；Java 侧用 {@code null = 保留} 与之一一对应。</p>
 *
 * <p>此前 Java 侧让钩子返回整个 {@link ToolResult} 做**整体替换**：钩子想改
 * {@code content} 就必须自己记得把 {@code terminate=true} 抄过去，忘抄就静默丢失 ——
 * 与 pi 的形状不符，且丢失无声无息。</p>
 *
 * <p>钩子抛异常不是「无操作」：pi 在 {@code :754-757} 把异常转成错误结果
 * （内容换成异常文本、{@code isError=true}）。该 catch 长在
 * 收尾函数里而不是钩子系统里，pi-java 同构：异常由 {@code HookSystem.fireAfterTool}
 * **向上传播**，{@code PiToolRunner.execute} 的 catch 负责转错误结果。</p>
 *
 * @param content   替换内容块（{@code null}=保留）
 * @param details   替换结构化细节（{@code null}=保留）
 * @param usage     替换用量（{@code null}=保留）
 * @param terminate 替换批次终止提示（{@code null}=保留）
 * @param isError   替换错误标记（{@code null}=保留；作用于结果，而非工具本身）
 */
public record AfterToolPatch(
    List<ContentBlock> content,
    Object details,
    ToolResult.UsageInfo usage,
    Boolean terminate,
    Boolean isError) {

    /** 什么都不改的补丁（等价于返回 {@code null}）。 */
    public static AfterToolPatch none() {
        return new AfterToolPatch(null, null, null, null, null);
    }

    /** 只改内容的补丁（最常用：脱敏 / 截断 / 注脚）。 */
    public static AfterToolPatch content(List<ContentBlock> content) {
        return new AfterToolPatch(content, null, null, null, null);
    }
}
