package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.stream.StreamEvent;

/**
 * L5 W1（pi {@code _startRun}/{@code _prepareRun}，agent-session.ts:1894-2013）：
 * 运行启动窗与 {@code before_agent_start} 的共用驱动。
 *
 * <ol>
 *   <li>置 {@code agentRunActive}、清 abort 旗（pi :1895-1896）；</li>
 *   <li>hook（user 臂带 prompt/images，custom 臂带 message）→ 处理器返回的 custom
 *       草稿 → systemPrompt/sections 写回；</li>
 *   <li>hook <b>之后</b> flush 延迟 bash/custom（pi :1908-1909）——启动窗内到达的
 *       消息排在起始消息之前，先于 {@code onStarted}；</li>
 *   <li>drain nextTurn（pi :2088-2092），起虚拟线程驱动；{@code onStarted}
 *       在 flush 后、起线程前调用（RPC {@code "started"} 回执时点）。</li>
 * </ol>
 *
 * <p>失败臂（pi :1899-1906）：复位 active、flush bash/custom、抛出。Java 侧
 * 无 idle-wait promise（{@code waitForIdle} 由调用方直接走引擎）。</p>
 */
final class RunStarter {

    private RunStarter() {}

    static SessionResult startUser(AgentSession owner, String prompt,
                                   List<com.pijava.agent.harness.PromptImage> images,
                                   Runnable onStarted,
                                   StreamObserver streamObserver,
                                   EntryObserver entryObserver) {
        return start(owner, new Trigger(prompt, images, null),
            onStarted, streamObserver, entryObserver);
    }

    /**
     * pi {@code sendCustomMessage} 的 {@code triggerTurn} 臂（:2398-2405）：空闲时
     * 用一条 custom 消息起一轮 run（同一启动窗与 hook）。
     */
    static SessionResult startCustom(AgentSession owner, QueuedCustomMessage message,
                                     StreamObserver streamObserver,
                                     EntryObserver entryObserver) {
        return start(owner, new Trigger(null, List.of(), message),
            null, streamObserver, entryObserver);
    }

    private static SessionResult start(AgentSession owner, Trigger trigger,
                                       Runnable onStarted,
                                       StreamObserver streamObserver,
                                       EntryObserver entryObserver) {
        owner.agentRunActive.set(true);
        owner.agentRunAbortRequested.set(false);
        var handlerDrafts = new ArrayList<com.pijava.coding.agent.extension.event
            .ExtensionResults.CustomMessageDraft>();
        try {
            if (owner.extensionFirer() != null) {
                // 批 16（T3c）：sections 基线＝harness 当前自定义段的拷贝，处理器改写
                // 的终态写回 harness——下一 run 的 promptLoadout diff 自动产补丁
                //（pi systemPromptOptions.sections 贯穿处理器、写回装配输入的链对位）。
                // 批 32（docs/44 T2，裁决③）：事件 {@code systemPrompt} 传**渲染后**整份
                // 提示（pi runner.ts:1420-1472 的 event.systemPrompt 对位）。
                var start = trigger.custom() != null
                    ? owner.extensionFirer().beforeAgentStartCustom(
                        trigger.custom().customType(), trigger.custom().content(),
                        trigger.custom().display(), trigger.custom().details(),
                        owner.harness()::currentSystemPrompt,
                        owner.harness().getPromptSections())
                    : owner.extensionFirer().beforeAgentStart(trigger.prompt(),
                        trigger.images(), owner.harness()::currentSystemPrompt,
                        owner.harness().getPromptSections());
                handlerDrafts.addAll(start.messages());
                if (start.systemPrompt() != null) {
                    owner.harness().setSystemPrompt(start.systemPrompt());
                }
                owner.harness().setPromptSections(start.sections());
            }
            // 批 24（docs/35 D4 臂4，pi agent-session.ts:1829 逐字）：run 记装配进转录
            // ——到此仍未注册的恢复工具放弃等待（永不注册的工具不留 pending）。
            owner.harness().pendingToolNames().clear();
        } catch (Throwable t) {
            // pi :1899-1906 失败臂：复位 active、flush、抛出（调用方处置）。
            owner.agentRunActive.set(false);
            owner.flushPendingCustomMessages();
            throw t;
        }
        // pi :1908-1909：启动窗内到达的 bash/custom 延迟消息在 hook 之后、起始消息
        // 之前落盘。
        for (var pendingBash : owner.drainPendingBashMessages()) {
            owner.harness().appendLaneEntry(owner.laneName(), pendingBash);
        }
        owner.flushPendingCustomMessages();
        // prompt 臂保留既有落盘序（处理器消息先于用户消息，窄差 D19）；custom 臂
        // 按 pi 序排在起始 custom 与 nextTurn 之后。
        if (trigger.custom() == null) {
            for (var draft : handlerDrafts) {
                owner.messaging().appendCustomDraft(draft);
            }
        }
        if (onStarted != null) {
            onStarted.run();
        }

        var queue = new LinkedBlockingQueue<Optional<StreamEvent>>();
        var entriesFuture = new CompletableFuture<List<Entry>>();
        var statusFuture = new CompletableFuture<RunStatus>();

        if (trigger.custom() != null) {
            // pi custom 臂（:1977-1980）：起始消息是 custom 本体（先落盘，续跑驱动）；
            // nextTurn 与处理器消息按 pi 的 messages 序排在其后。
            owner.messaging().appendCustomMessageNow(trigger.custom());
            for (var nextTurn : owner.drainPendingNextTurnMessages()) {
                owner.messaging().appendCustomMessageNow(nextTurn);
            }
            for (var draft : handlerDrafts) {
                owner.messaging().appendCustomDraft(draft);
            }
            Thread.startVirtualThread(() -> SessionRunner.driveContinuation(
                owner, queue, entriesFuture, statusFuture, streamObserver, entryObserver));
        } else {
            // 批 26（docs/37 T2，pi :2088-2092 对位）：nextTurn custom 消息随本次起跑
            // 注入（排在起始消息之后，经 initialMessages 进待发列表）。
            var nextTurnCustoms = owner.drainPendingNextTurnMessages().stream()
                .map(QueuedCustomMessage::toQueuedItem).toList();
            Thread.startVirtualThread(() -> SessionRunner.drive(
                owner, trigger.prompt(), trigger.images(), nextTurnCustoms, queue,
                entriesFuture, statusFuture, streamObserver, entryObserver));
        }

        Stream<StreamEvent> stream = Stream.generate(() -> {
            try {
                return queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.<StreamEvent>empty();
            }
        }).takeWhile(Optional::isPresent).map(Optional::get);
        return new SessionResult(stream, entriesFuture, statusFuture, "started");
    }

    /**
     * pi {@code BeforeAgentStartTrigger} 的判别记录：两臂只在 {@code prompt}/
     * {@code custom} 上有差异，用单一 record 带可空 custom（不用 sealed）。
     */
    private record Trigger(String prompt,
                           List<com.pijava.agent.harness.PromptImage> images,
                           QueuedCustomMessage custom) {
    }
}
