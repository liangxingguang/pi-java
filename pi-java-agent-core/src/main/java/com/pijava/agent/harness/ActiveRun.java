package com.pijava.agent.harness;

import java.util.concurrent.CompletableFuture;

import com.pijava.ai.AbortSignal;

/**
 * pi {@code AgentState.activeRun} 的 Java 版 —— **存在即为正在运行**（{@code docs/31 §3.2}）。
 *
 * <p>它取代了原先的三态枚举 {@code RunPhase.Idle/Assistant/Checkpoint}：pi 没有相位字段，
 * 「是否在跑」由 {@code this.activeRun !== undefined} 表达（{@code agent.ts}），本类型照抄这个形状。
 * 原先由相位承担的另外两件事各有归属 —— outcome 判定看消息的 {@code stopReason}，
 * 收口由 {@link AgentHarness} 的 run 生命周期负责。</p>
 *
 * @param signal 本次运行的取消信号；{@code abort()} 触发它，工具执行与流读取都读它
 * @param done   本次运行的完成信号。{@link AgentHarness#prompt} 在 {@code finally} 里完成它，
 *               {@link AgentHarness#waitForIdle} 等待它 —— 与 pi 的
 *               {@code waitForIdle() { return this.activeRun?.promise ?? Promise.resolve(); }}
 *               对应：空闲时立即返回，运行时等到该次运行收口。
 */
record ActiveRun(AbortSignal signal, CompletableFuture<Void> done) {

    /** 新开一次运行：装上未触发的取消信号与未完成的完成信号。 */
    static ActiveRun start() {
        return new ActiveRun(AbortSignal.create(), new CompletableFuture<>());
    }
}
