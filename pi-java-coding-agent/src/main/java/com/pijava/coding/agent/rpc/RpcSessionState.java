package com.pijava.coding.agent.rpc;

/**
 * get_state 载荷（对齐 pi {@code RpcSessionState} 的线格式）。
 *
 * <p>wire 友好字段：model 为 {@code "provider/id"}，thinkingLevel 为 wire 值
 * （{@code "off"}|minimal|low|medium|high|xhigh|max），队列模式为
 * {@code "all"|"one-at-a-time"}。</p>
 */
public record RpcSessionState(
    String model,
    String thinkingLevel,
    boolean isStreaming,
    boolean isCompacting,
    String steeringMode,
    String followUpMode,
    String sessionFile,
    String sessionId,
    String sessionName,
    boolean autoCompactionEnabled,
    int messageCount,
    int pendingMessageCount
) {}
