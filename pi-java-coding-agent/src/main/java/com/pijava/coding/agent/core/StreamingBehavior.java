package com.pijava.coding.agent.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 并发 prompt 的排队行为（A5，{@code docs/25}；pi
 * {@code streamingBehavior?: "steer" | "followUp"}，
 * {@code agent-session.ts:304}）。纯常量闭集 ⇒ enum。
 *
 * <ul>
 *   <li>{@link #STEER}：排入当前 run 的下一轮（当前助手回合工具执行完后、
 *       下一次 LLM 调用前注入）；</li>
 *   <li>{@link #FOLLOW_UP}：排入 followUp 队列，当前 run 完全结束后才处理。</li>
 * </ul>
 */
public enum StreamingBehavior {
    STEER("steer"),
    FOLLOW_UP("followUp");

    private final String wireName;

    StreamingBehavior(String wireName) {
        this.wireName = wireName;
    }

    /** pi 线值：{@code "steer"} / {@code "followUp"}。 */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** 从线值解析；非法值抛 {@link IllegalArgumentException}。 */
    @JsonCreator
    public static StreamingBehavior fromWire(String value) {
        for (var behavior : values()) {
            if (behavior.wireName.equals(value)) {
                return behavior;
            }
        }
        throw new IllegalArgumentException(
            "Unknown streamingBehavior: " + value + " (expected 'steer' or 'followUp')");
    }
}
