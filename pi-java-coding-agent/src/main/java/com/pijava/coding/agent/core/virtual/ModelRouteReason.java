package com.pijava.coding.agent.core.virtual;

/**
 * Why a request is being routed (pi {@code ModelRouteReason},
 * {@code core/virtual-models.ts:50} 逐字四值）。
 *
 * <ul>
 *   <li>{@code USER}——用户消息后的首个请求</li>
 *   <li>{@code CONTINUATION}——工具结果/扩展消息之后</li>
 *   <li>{@code RETRY}——失败重试（含压缩后重试）</li>
 *   <li>{@code DIRECT}——agent loop 外的请求（如压缩摘要）；不写路由态</li>
 * </ul>
 */
public enum ModelRouteReason {

    /** First request after a user message. */
    USER("user"),

    /** After tool results or extension messages. */
    CONTINUATION("continuation"),

    /** Retry after a failure (including post-compaction). */
    RETRY("retry"),

    /** Outside the agent loop (e.g. compaction summaries). */
    DIRECT("direct");

    private final String wire;

    ModelRouteReason(String wire) {
        this.wire = wire;
    }

    /** The literal pi writes. */
    public String wire() {
        return wire;
    }
}
