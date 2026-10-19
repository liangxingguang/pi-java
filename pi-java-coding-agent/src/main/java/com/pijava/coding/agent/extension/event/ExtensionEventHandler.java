package com.pijava.coding.agent.extension.event;

import com.pijava.coding.agent.extension.ExtensionContext;

/**
 * pi {@code ExtensionHandler}（{@code (event, ctx) => result | undefined}）——
 * 返回 {@code null ≙ undefined}（不发言）。
 *
 * @param <E> 事件载荷类型
 * @param <R> 结果类型（观察型为 {@link Void}，恒返回 null）
 */
@FunctionalInterface
public interface ExtensionEventHandler<E extends ExtensionEvent, R> {

    /**
     * 处理事件。抛出的异常按事件族分道（裁决③）：{@code tool_call} 向上传播
     * （转工具错误结果），其余族逐处理器捕获后经 {@code extension_error} 上报。
     */
    R handle(E event, ExtensionContext ctx) throws Exception;
}
