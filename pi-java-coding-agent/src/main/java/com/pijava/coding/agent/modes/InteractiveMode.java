package com.pijava.coding.agent.modes;

import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.EntryObserver;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.PromptRouter;
import com.pijava.coding.agent.core.SessionResult;
import com.pijava.coding.agent.core.StreamObserver;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Interactive mode: submits prompts and forwards incremental stream events and
 * complete entries to observers (Phase 3 design §11.1).
 *
 * <p>The coding-agent module defines this class without any TUI types; the
 * TUI implements {@link EntryObserver}/{@link StreamObserver} and drives the
 * render loop.</p>
 */
public final class InteractiveMode {

    private AgentSession session;
    private EntryObserver entryObserver = entry -> { };
    private StreamObserver streamObserver = event -> { };

    /**
     * Create interactive mode driving the given session.
     *
     * @param session the underlying agent session
     */
    public InteractiveMode(AgentSession session) {
        this.session = session;
    }

    /** Register observers (non-blocking; no thread starts here). */
    public void setObservers(EntryObserver entries, StreamObserver stream) {
        this.entryObserver = entries;
        this.streamObserver = stream;
    }

    /**
     * Submit a prompt.
     *
     * <p>A5（{@code docs/25}；pi {@code interactive-mode.ts:3318-3338}）：
     * 压缩中提交 ⇒ 调用线程同步抛错（TUI 提示，不产生帧）；运行中提交 ⇒
     * steer 排队，返回 queued，不起驱动线程；空闲 ⇒ 驱动 harness（每条 run
     * 恰一条虚拟线程）。</p>
     */
    public SessionResult submit(String prompt) {
        if (session.isCompacting()) {
            throw new IllegalStateException(PromptRouter.COMPACTING_MESSAGE);
        }
        if (session.isRunning()) {
            session.steer(prompt);
            return SessionResult.queued();
        }
        return session.processPrompt(
            prompt, PromptConfig.defaults(), streamObserver, entryObserver);
    }

    /** Abort the current run (cross-thread safe). */
    public void abort() {
        session.abort();
    }

    /** Queue a follow-up message (Alt+Enter while running). */
    public void followUp(String prompt) {
        session.followUp(prompt);
    }

    /** Queue a steering message (injected into the current run). */
    public void steer(String prompt) {
        session.steer(prompt);
    }

    /**
     * Dispatch a slash command.
     *
     * @param input   full input line
     * @param context slash context (quit/switch callbacks)
     * @return result future when the input is a command, else {@code null}
     */
    public CompletionStage<String> dispatch(String input, SlashContext context) {
        return session.services().slashCommands().dispatch(input, context);
    }

    /** The wrapped session. */
    public AgentSession session() {
        return session;
    }

    /** Swap the active session ({@code /new /resume /fork /clone}). */
    public void switchSession(AgentSession newSession) {
        this.session = newSession;
    }
}
