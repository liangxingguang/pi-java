package com.pijava.agent.hook;

import com.pijava.agent.harness.PiLoop;

/**
 * 扩展面的 {@code prepareRequest} 钩子（pi 的唯一名就是 {@code prepareRequest}，
 * {@code types.ts:186-190}；接口名留住 java 侧的 Hook 后缀惯例，
 * 仿 {@link ShouldStopAfterTurnHook} 留住 {@code shouldStopAfterTurn} 名的先例）。
 *
 * <p>在<b>每一次</b>对话式 provider 请求之前调用（含第一次），此时 pending 消息已
 * append 进 context。返回 {@link PiLoop.RequestUpdate} 可整体替换 context 或改写
 * model/thinking（对本 run 后续请求生效）；返回 {@code null} 弃权。</p>
 */
@FunctionalInterface
public interface PrepareRequestHook {

    /**
     * Prepare the runtime state for the provider request being prepared.
     *
     * @param request 请求前快照（多个钩子时含前钩的替换结果）
     * @return the update to apply, or null for no change
     */
    PiLoop.RequestUpdate prepareRequest(PiLoop.PrepareRequestContext request);
}
