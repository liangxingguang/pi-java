package com.pijava.evals.harness;

import java.util.Map;

/**
 * 批 32（docs/44 §3.3）：一次工具调用的观测记录。
 *
 * @param id        call id
 * @param name      tool name
 * @param arguments arguments
 * @param status    {@code ok} | {@code error} | {@code pending}
 */
public record ToolCallRecord(String id, String name, Map<String, Object> arguments,
                             String status) {

    /** @return {@code true} when the matching tool result was successful */
    public boolean succeeded() {
        return "ok".equals(status);
    }
}
