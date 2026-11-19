package com.pijava.web;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 上下文洞察载荷（Web 扩展，对齐 dsh-context 的 Context 面板）——
 * {@code getContextInsights} 的响应体，由 {@link ContextInsights} 组装。
 *
 * <p>所有 token 值都是<b>估算</b>（字符/4 启发式，与
 * {@code ContextUsageEstimator} 同口径）；只有 {@link Step#reported()} 为 true 的
 * {@code promptTokens}/{@code outputTokens} 是 provider 实报值。</p>
 */
public final class ContextReport {

    private ContextReport() {}

    /**
     * 完整报告。
     *
     * @param sessionId      当前会话 id
     * @param model          当前模型（{@code provider/modelId}），未选模型时为 null
     * @param contextWindow  模型上下文窗口；未知为 0
     * @param maxOutputTokens 模型最大输出窗口；未知为 0
     * @param reserveTokens  自动压缩预留 token 数
     * @param contextTokens  当前上下文占用（有可信 usage 时为 provider 锚定值）
     * @param estimatedTokens 当前组成的估算总量（各类别之和）
     * @param percent        当前占用 / 上下文窗口
     * @param freeTokens     窗口剩余（不小于 0）
     * @param totals         会话级汇总
     * @param categories     当前（或最近一次请求）组成类别
     * @param steps          逐请求上下文趋势
     * @param events         上下文事件
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(
            String sessionId,
            String model,
            int contextWindow,
            int maxOutputTokens,
            int reserveTokens,
            double contextTokens,
            double estimatedTokens,
            double percent,
            double freeTokens,
            Totals totals,
            List<Category> categories,
            List<Step> steps,
            List<Event> events) {
    }

    /**
     * 会话级汇总（来自 branch 上全部 assistant usage）。
     *
     * @param turns        用户输入轮数
     * @param requests     模型请求数（assistant 消息数）
     * @param toolCalls    工具调用数
     * @param cacheHitRate 缓存命中率（cacheRead / prompt 总量）
     * @param costUsd      provider 计价合计（USD）
     * @param promptTokens prompt token 合计（input + cacheRead + cacheWrite）
     * @param outputTokens 输出 token 合计
     */
    public record Totals(
            int turns,
            int requests,
            int toolCalls,
            double cacheHitRate,
            double costUsd,
            double promptTokens,
            double outputTokens) {
    }

    /**
     * 一个组成类别。
     *
     * @param key    稳定判别键（system/tools/user/injected/answers/reasoning/toolCalls/toolResults）
     * @param label  展示名
     * @param tokens 该类别当前估算 token 量
     * @param items  可展开的明细项（仅 live 组成携带；趋势步只带总量）
     */
    public record Category(String key, String label, double tokens, List<Item> items) {
    }

    /**
     * 类别内一项可展开内容。
     *
     * @param label   项名（如工具名、消息序号）
     * @param tokens  该项估算 token 量
     * @param preview 内容预览（截断、压平空白）
     */
    public record Item(String label, double tokens, String preview) {
    }

    /**
     * 一次模型请求的上下文快照。
     *
     * @param index           从 0 开始的请求序号（跨压缩保持原序）
     * @param promptTokens    prompt token 数（provider 实报或估算）
     * @param outputTokens    output token 数
     * @param contextTokens   该请求进入窗口的 token 量（趋势柱高）
     * @param reported        是否来自 provider usage
     * @param categories      请求发出前的累计组成（key → tokens）
     */
    public record Step(
            int index,
            double promptTokens,
            double outputTokens,
            double contextTokens,
            boolean reported,
            Map<String, Double> categories) {
    }

    /**
     * 上下文事件（压缩 / 模型切换 / 思考级别 / 分支摘要 / 上下文编辑）。
     *
     * @param type         判别键
     * @param label        展示名
     * @param timestamp    ISO-8601 时间
     * @param tokensBefore 事件发生前的 token 量（压缩事件携带）
     * @param detail       细节文本
     */
    public record Event(
            String type,
            String label,
            String timestamp,
            Double tokensBefore,
            String detail) {
    }
}
