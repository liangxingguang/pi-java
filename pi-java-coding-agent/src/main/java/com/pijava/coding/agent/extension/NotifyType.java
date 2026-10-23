package com.pijava.coding.agent.extension;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 批 17（T1，docs/28）：pi {@code notify} 的类型字面量（types.ts:165
 * {@code "info" | "warning" | "error"}）——纯常量闭集用 enum（CLAUDE.md 规范）。
 */
public enum NotifyType {
    /** 信息（pi {@code "info"}）。 */
    INFO,
    /** 警告（pi {@code "warning"}）。 */
    WARNING,
    /** 错误（pi {@code "error"}）。 */
    ERROR;

    /** 线格式小写名（pi 字面量逐字）。 */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
