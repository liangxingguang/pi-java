package com.pijava.coding.agent.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * get_state 载荷（对齐 pi {@code RpcSessionState} 的线格式）。
 *
 * <p>wire 友好字段：model 为 {@code "provider/id"}，thinkingLevel 为 wire 值
 * （{@code "off"}|minimal|low|medium|high|xhigh|max），队列模式为
 * {@code "all"|"one-at-a-time"}。</p>
 *
 * <p><b>{@code NON_NULL} 是契约的一部分，不是省事</b>（{@code docs/31 §8.37}）：
 * pi 的 {@code sessionFile?: string} / {@code sessionName?: string}
 * （{@code rpc-types.ts:103/:105}）是可选的，{@code JSON.stringify} 对
 * {@code undefined} <b>省略键</b>。故 {@code sessionFile} 在非文件后端
 * （sqlite / in-memory / {@code --no-session}）必须**缺席**而不是 {@code null} ——
 * 这与 {@link RpcResponse} 的信封纪律同一条。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
