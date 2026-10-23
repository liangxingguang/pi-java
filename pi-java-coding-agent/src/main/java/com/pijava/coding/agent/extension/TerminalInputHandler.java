package com.pijava.coding.agent.extension;

import org.jspecify.annotations.Nullable;

/**
 * 批 17（T1，docs/28）：pi {@code TerminalInputHandler}（types.ts:130：
 * {@code (data: string) => { consume?: boolean; data?: string } | undefined}）
 * 的 java 对位——原始终端输入监听（interactive 模式限定）。
 */
@FunctionalInterface
public interface TerminalInputHandler {

    /**
     * 处理一段原始输入。
     *
     * @param data 原始输入串
     * @return 处理结果；{@code null} ≙ pi 的 {@code undefined}（不消费、不改写）
     */
    @Nullable Result handle(String data);

    /** pi 结果对象面（{@code consume} 消费键／{@code data} 改写串；null ≙ 键省略）。 */
    record Result(@Nullable Boolean consume, @Nullable String data) {}
}
