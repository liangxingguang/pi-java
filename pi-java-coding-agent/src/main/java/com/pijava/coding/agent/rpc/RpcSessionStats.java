package com.pijava.coding.agent.rpc;

/**
 * get_session_stats 载荷 —— 会话统计摘要。
 */
public record RpcSessionStats(
    String model,
    long totalTokens,
    int turnCount,
    int messageCount,
    String phase
) {}
