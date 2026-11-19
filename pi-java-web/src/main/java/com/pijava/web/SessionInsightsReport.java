package com.pijava.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 跨会话洞察载荷（Web 扩展，对齐 dsh-context 的 Context Insights 页面）。
 *
 * <p>数据来自 JSONL 会话文件的逐条扫描：只读 header、message、usage、
 * compaction / branch_summary 与 model_change 条目；token 为 provider 实报
 * usage 的合计（无 usage 的旧条目不计），不是字符估算。</p>
 */
public final class SessionInsightsReport {

    private SessionInsightsReport() {}

    /**
     * 一次洞察查询的结果。
     *
     * @param range         时间范围标签（today / 7d / 30d / all）
     * @param supported     当前会话后端是否支持扫描（目前为 JSONL）
     * @param backend       后端名
     * @param sessionCount  范围内有活动的会话数
     * @param promptTokens  prompt token 合计（input + cacheRead + cacheWrite）
     * @param outputTokens  output token 合计
     * @param totalTokens   prompt + output
     * @param costUsd       provider 计价合计
     * @param cacheHitRate  cacheRead / prompt
     * @param turns         用户消息数
     * @param toolCalls     工具调用数
     * @param days          按日序列（today/7d/30d 补齐空日；all 取最近 90 天）
     * @param tools         工具调用账本（降序）
     * @param sessions      会话卡片（按最后活动降序，最多 200 个）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(
            String range,
            boolean supported,
            String backend,
            int sessionCount,
            double promptTokens,
            double outputTokens,
            double totalTokens,
            double costUsd,
            double cacheHitRate,
            int turns,
            int toolCalls,
            List<DayPoint> days,
            List<ToolCount> tools,
            List<SessionCard> sessions) {
    }

    /**
     * 一天的用量。
     *
     * @param date         本地日期（ISO-8601，如 2026-11-11）
     * @param promptTokens prompt token 量
     * @param outputTokens output token 量
     * @param costUsd      当天计价
     */
    public record DayPoint(
            String date,
            double promptTokens,
            double outputTokens,
            double costUsd) {
    }

    /**
     * 工具调用计数。
     *
     * @param name  工具名
     * @param count 调用次数
     */
    public record ToolCount(String name, int count) {
    }

    /**
     * 会话消息组成的一个切片（历史会话只有消息，没有当时的系统提示/工具快照）。
     *
     * @param key    类别键（user/injected/answers/reasoning/toolCalls/toolResults）
     * @param label  展示名
     * @param tokens 该类别的字符估算 token 量
     */
    public record CompositionSlice(String key, String label, double tokens) {
    }

    /**
     * 单个会话的卡片数据（统计窗口内）。
     *
     * @param id               会话 id
     * @param path             会话文件路径（JSONL）
     * @param name             会话名，未命名为空串
     * @param cwd              工作目录
     * @param created          创建时间（ISO-8601）
     * @param modifiedMs       最后写入时间（epoch ms）
     * @param messages         消息数（全生命周期）
     * @param turns            窗口内用户消息数
     * @param toolCalls        窗口内工具调用数
     * @param promptTokens     窗口内 prompt token
     * @param outputTokens     窗口内 output token
     * @param costUsd          窗口内计价
     * @param cacheHitRate     窗口内缓存命中率
     * @param model            最近使用的模型（provider/model）
     * @param parentSessionId  父会话 id（fork / 新建会话），可为 null
     * @param firstMessage     首条用户消息预览
     * @param composition      降级口径的消息组成（无系统提示/工具快照）
     */
    public record SessionCard(
            String id,
            String path,
            String name,
            String cwd,
            String created,
            long modifiedMs,
            int messages,
            int turns,
            int toolCalls,
            double promptTokens,
            double outputTokens,
            double costUsd,
            double cacheHitRate,
            String model,
            String parentSessionId,
            String firstMessage,
            List<CompositionSlice> composition) {
    }
}
