package com.pijava.protocol;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 协议侧思考等级（对齐 pi {@code ThinkingLevelSchema}：
 * off/minimal/low/medium/high/xhigh/max）。
 *
 * <p>协议模块不依赖 pi-java-ai，故独立定义；server 适配层负责与
 * pi-java 的 {@code ThinkingLevel} 互转。</p>
 */
public enum ProtocolThinkingLevel {
    OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX;

    /** wire 值。 */
    @JsonValue
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
