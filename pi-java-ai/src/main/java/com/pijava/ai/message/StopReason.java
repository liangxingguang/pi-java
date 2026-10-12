package com.pijava.ai.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 助手消息终局原因的<b>闭集</b>（pi {@code packages/ai/src/types.ts:469}）：
 * {@code "pending" | "stop" | "length" | "toolUse" | "error" | "aborted"
 * | "deferred"}。
 *
 * <p>纯常量闭集 → enum（CLAUDE.md 规范）。命名注意：官方 SDK 另有
 * {@code com.anthropic.models.messages.StopReason}（wire 入站枚举），本类型在
 * {@code com.pijava.ai.message} 包，同文件需两者时用全限定名区分。</p>
 *
 * <h2>运行时语义（与 pi 对齐）</h2>
 * <ul>
 *   <li><b>生产端闭集</b>：各车道把 provider 原文映射成本枚举，未知值必须在车道处 throw
 *       （pi anthropic {@code `Unhandled stop reason: ${reason}`}，
 *       {@code api/anthropic-messages.ts:1637}）——本类不代抛，车道保留各自逐字文案。</li>
 *   <li><b>解码端容错</b>：pi 的 TS 联合类型运行时擦除，{@code JSON.parse} 后直接转型、
 *       零校验，未知值只是让所有字符串比较都不命中。故 Java 反序列化未知/空值一律得
 *       {@code null}，由既有 {@code null == 未落定} 逻辑吸收。</li>
 *   <li><b>遗留 {@code "tool_use"}</b>：旧 JSONL 存档的 snake_case 归一（B109）唯一入口是
 *       {@code JsonlCodec.readStopReason}；本类型的 {@link #fromWireOrNull} <b>不</b>映射它，
 *       只有 Jackson 往返入口 {@link #fromJson} 额外认这个遗产字面。</li>
 * </ul>
 */
public enum StopReason {
    PENDING("pending"),
    STOP("stop"),
    LENGTH("length"),
    TOOL_USE("toolUse"),
    ERROR("error"),
    ABORTED("aborted"),
    DEFERRED("deferred");

    private final String wire;

    StopReason(String wire) {
        this.wire = wire;
    }

    /** pi wire 字面（camelCase）。 */
    @JsonValue
    public String wireName() {
        return wire;
    }

    /**
     * 容错解码：规范七值正常映射；{@code null}／空串／<b>任何未知值</b>（含遗留
     * {@code "tool_use"}）均返 {@code null} —— 等价 pi 解码后比较全不命中。
     *
     * <p>车道生产端请在得到 {@code null} 时按 pi 文案自行 throw；存档解码侧直接用
     * {@code null} 语义（未落定）。{@code "tool_use"} 的归一走
     * {@code JsonlCodec.readStopReason}（B109）或 Jackson 入口 {@link #fromJson}。</p>
     */
    public static StopReason fromWireOrNull(String wire) {
        if (wire == null || wire.isEmpty()) {
            return null;
        }
        for (var reason : values()) {
            if (reason.wire.equals(wire)) {
                return reason;
            }
        }
        return null;
    }

    /**
     * Jackson 往返入口：规范七值之外，额外把旧存档字面 {@code "tool_use"} 归一到
     * {@link #TOOL_USE}；其余未知值（含 {@code null}）容错为 {@code null}。
     */
    @JsonCreator
    public static StopReason fromJson(String wire) {
        if ("tool_use".equals(wire)) {
            return TOOL_USE;
        }
        return fromWireOrNull(wire);
    }

    /**
     * 落定谓词：{@code null} 与 {@link #PENDING} 为未落定，其余五值皆已落定
     * （迁移目标，语义同现 {@code StreamEvent.isSettled(String)}）。
     */
    public static boolean isSettled(StopReason reason) {
        return reason != null && reason != PENDING;
    }
}
