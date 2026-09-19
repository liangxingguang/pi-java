package com.pijava.agent.tool;

/**
 * 执行中收到输出增量的汇 —— pi 的 {@code AgentToolUpdateCallback} 在 shell 层的
 * 对应物（包⑧，{@code docs/35}）。
 *
 * <p>pi 的 {@code bash} 靠它把「跑到**目前**为止的输出」推给宿主
 * （{@code bash.ts:265}/{@code :297}）。⚠️ 注意分层：本接口传的是**增量**
 * （自上次以来的新字节），累积与节流是 {@code BashTool} 侧的事 —— pi 的分工也是
 * 这样（它的 {@code OutputAccumulator} 攒、{@code bash.ts} 节流）。</p>
 *
 * <p>调用线程 = 读取子进程输出的那条线程。落在哪种线程上由 executor 决定，
 * 实现方若要把结果推给别处，需自行处理线程语义。</p>
 */
@FunctionalInterface
public interface ShellOutputSink {

    /**
     * 收到一块新输出。
     *
     * @param delta 自上次调用以来新到的输出（**增量**，不是累积快照）；
     *              已按字符边界解码，不会以半个多字节字符结尾
     */
    void onOutput(String delta);
}
