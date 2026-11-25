package com.pijava.coding.agent.extension;

/**
 * D29：pi {@code ExtensionRunner.invalidate}/{@code assertActive}（runner.ts:398,733-746；
 * runtime loader.ts:164-218）。会话替换/关闭后置 stale 文案（**首条胜出**），旧 ctx 再调
 * 宿主面即抛 {@link IllegalStateException}；{@code switchTo} 的原地 attach 只退订通道、
 * 不置 stale。
 */
final class ExtensionContextStaleness {

    /** pi 默认失效文案（runner.ts:733-735／loader.ts:204 同文，逐字）。 */
    static final String STALE_MESSAGE =
        "This extension ctx is stale after session replacement or reload. Do not use a captured "
        + "pi or command ctx after ctx.newSession(), ctx.fork(), ctx.switchSession(), or "
        + "ctx.reload(). For newSession, fork, and switchSession, move post-replacement work into "
        + "withSession and use the ctx passed to withSession. For reload, do not use the old ctx "
        + "after await ctx.reload().";

    private volatile String staleMessage;

    /** pi {@code runtime.invalidate}（loader.ts:200-207）：首条 wins＋退订通道。 */
    void invalidate(String message, Runnable unsubscribeChannels) {
        if (staleMessage != null) {
            return;
        }
        staleMessage = message != null ? message : STALE_MESSAGE;
        unsubscribeChannels.run();
    }

    void assertActive() {
        var stale = staleMessage;
        if (stale != null) {
            throw new IllegalStateException(stale);
        }
    }
}
