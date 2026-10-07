package com.pijava.coding.agent.modes;

import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.coding.agent.core.CompactionInputBuffer;
import com.pijava.coding.agent.core.EntryObserver;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.PromptRouter;
import com.pijava.coding.agent.core.SessionResult;
import com.pijava.coding.agent.core.StreamObserver;
import com.pijava.coding.agent.core.StreamingBehavior;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Interactive mode: submits prompts and forwards incremental stream events and
 * complete entries to observers (Phase 3 design §11.1).
 *
 * <p>The coding-agent module defines this class without any TUI types; the
 * TUI implements {@link EntryObserver}/{@link StreamObserver} and drives the
 * render loop.</p>
 *
 * <p>B175（docs/26）：压缩窗口内提交不抛错 —— 文本进
 * {@link CompactionInputBuffer}，{@code CompactionEnd} 时自动 flush 重放
 * （pi {@code interactive-mode.ts:4688-4783}）。</p>
 */
public final class InteractiveMode {

    private AgentSession session;
    private EntryObserver entryObserver = entry -> { };
    private StreamObserver streamObserver = event -> { };
    private final CompactionInputBuffer buffer = new CompactionInputBuffer();
    private AutoCloseable sessionSubscription;

    /**
     * Create interactive mode driving the given session.
     *
     * @param session the underlying agent session
     */
    public InteractiveMode(AgentSession session) {
        this.session = session;
        this.sessionSubscription = session.subscribe(this::onSessionEvent);
    }

    /** CompactionEnd ⇒ 缓冲自动重放（pi flushCompactionQueue，compaction_end 处无条件）。 */
    private void onSessionEvent(AgentSessionEvent event) {
        if (event instanceof AgentSessionEvent.CompactionEnd end) {
            buffer.flush(end.willRetry(), replayer);
            // 缓冲此时已空：发最终聚合（引擎队列里是刚重放入的内容）。
            session.emitQueueUpdate();
        }
    }

    /** 缓冲重放口：生产投递走当前会话与观察者（pi 无扩展命令分支）。 */
    private final CompactionInputBuffer.Replayer replayer =
        new CompactionInputBuffer.Replayer() {
            @Override
            public void prompt(String text, StreamingBehavior mode) {
                session.processPrompt(text, PromptConfig.withStreamingBehavior(mode),
                    streamObserver, entryObserver);
            }

            @Override
            public void steer(String text) {
                session.steer(text);
            }

            @Override
            public void followUp(String text) {
                session.followUp(text);
            }

            @Override
            public void cancelQueued(String queueType) {
                session.harness().cancelQueued(session.laneName(), queueType);
            }
        };

    /** Register observers (non-blocking; no thread starts here). */
    public void setObservers(EntryObserver entries, StreamObserver stream) {
        this.entryObserver = entries;
        this.streamObserver = stream;
    }

    /**
     * Submit a prompt.
     *
     * <p>B175（{@code docs/26}；pi {@code interactive-mode.ts:3318-3338}）：
     * 压缩中提交 ⇒ 客户端缓冲＋状态提示（不产生帧）；运行中提交 ⇒
     * steer 排队，返回 queued，不起驱动线程；空闲 ⇒ 驱动 harness（每条 run
     * 恰一条虚拟线程）。</p>
     */
    public SessionResult submit(String prompt) {
        if (session.isCompacting()) {
            buffer.add(prompt, StreamingBehavior.STEER);
            session.emitQueueUpdate(buffer.steeringTexts(), buffer.followUpTexts());
            session.flashStatus("Queued message for after compaction");
            return SessionResult.queued();
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

    /**
     * Queue a follow-up message (Alt+Enter).
     *
     * <p>压缩中 ⇒ 缓冲为 followUp（B175）；运行中 ⇒ 引擎 followUp 队列；
     * 空闲 ⇒ 等同普通 Enter（pi：空闲 Alt+Enter 普通提交）。</p>
     */
    public void followUp(String prompt) {
        if (session.isCompacting()) {
            buffer.add(prompt, StreamingBehavior.FOLLOW_UP);
            session.emitQueueUpdate(buffer.steeringTexts(), buffer.followUpTexts());
            session.flashStatus("Queued message for after compaction");
            return;
        }
        if (session.isRunning()) {
            session.followUp(prompt);
            return;
        }
        submit(prompt);
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

    /**
     * Swap the active session ({@code /new /resume /fork /clone})：摘旧订阅、
     * 清空缓冲、接新会话的 CompactionEnd。
     */
    public void switchSession(AgentSession newSession) {
        closeSubscription();
        buffer.clear();
        this.session = newSession;
        this.sessionSubscription = newSession.subscribe(this::onSessionEvent);
    }

    private void closeSubscription() {
        if (sessionSubscription == null) {
            return;
        }
        try {
            sessionSubscription.close();
        } catch (Exception ignored) {
            // 忽略退订失败
        }
        sessionSubscription = null;
    }
}
