package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.CompactionAbortToken;
import com.pijava.agent.tool.ShellResult;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.BeforeAgentStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.InputEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeForkEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeSwitchEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionBeforeTreeEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionShutdownEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionStartEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.SessionTreeEvent;
import com.pijava.coding.agent.extension.event.ExtensionEvent.UserBashEvent;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.CustomMessageDraft;
import com.pijava.coding.agent.extension.event.ExtensionResults.InputEventResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeForkResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeSwitchResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.SessionBeforeTreeResult;
import com.pijava.coding.agent.extension.event.ExtensionResults.TreePreparation;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.BEFORE_AGENT_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.INPUT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_FORK;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_SWITCH;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_BEFORE_TREE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_TREE;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.USER_BASH;

/**
 * 宿主发射器（docs/15 §3.3，裁决③）—— pi runner 的宿主侧发射法在 java 的合订本：
 * 生命周期（{@code session_start}/{@code session_shutdown}）、可否决的 before 族
 * （{@code session_before_switch}/{@code session_before_fork}）、结果臂
 * （{@code input}/{@code user_bash}/{@code before_agent_start}）。
 *
 * <p>全部<b>无订阅者快走</b>（pi 多处触发点先查 {@code hasHandlers} 同构）；处理器
 * 在调用线程同步执行（java 无 await —— 同步跑完即 pi 的 await 完成时点，裁决③）。
 * 错误纪律逐族照 pi：</p>
 * <ul>
 *   <li>生命周期/输入/before_agent_start：逐处理器 catch ⇒ emitError ⇒ 继续；</li>
 *   <li>before_switch/fork：{@code cancel} 短路（后续处理器不跑），否则最后发言者赢
 *       （pi 通用 emit，runner.ts:1089-1118）；</li>
 *   <li>user_bash：首个发言者赢；处理器异常 emitError 后<b>重抛</b>（pi
 *       runner.ts:1283-1290——调用方不得回落本地执行）。</li>
 * </ul>
 */
public final class SessionEventFirer {

    /** pi {@code emitInput} 的产出（runner.ts:1520-1558）。 */
    public record InputOutcome(boolean handled, String text) {}

    /** pi {@code emitBeforeAgentStart} 的产出（runner.ts:1420-1472）。批 16（T3c）：
     *  增 {@code sections}——处理器改写后的终态段表，调用方写回 harness。 */
    public record BeforeAgentStartOutcome(List<CustomMessageDraft> messages,
                                          String systemPrompt,
                                          java.util.Map<String, String> sections) {}

    private final ExtensionEventBus bus;
    private final ExtensionContext ctx;

    public SessionEventFirer(ExtensionEventBus bus, ExtensionContext ctx) {
        this.bus = bus;
        this.ctx = ctx;
    }

    /** 总线（boundary/压缩适配器与 firer 共享）。 */
    public ExtensionEventBus bus() {
        return bus;
    }

    // ═══════════════════════════════════════════════════════════
    // 生命周期（观察型）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code bindExtensions} 的发射（agent-session.ts:3279）。 */
    public void sessionStart(String reason, String previousSessionFile) {
        if (!bus.hasHandlers(SESSION_START)) {
            return;
        }
        var event = new SessionStartEvent(reason, previousSessionFile);
        for (var h : bus.snapshot(SESSION_START)) {
            try {
                h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }

    /** pi {@code emitSessionShutdownEvent}（runner.ts:258-267，hasHandlers 门）。 */
    public void sessionShutdown(String reason, String targetSessionFile) {
        if (!bus.hasHandlers(SESSION_SHUTDOWN)) {
            return;
        }
        var event = new SessionShutdownEvent(reason, targetSessionFile);
        for (var h : bus.snapshot(SESSION_SHUTDOWN)) {
            try {
                h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // before 族（cancel 短路，pi 通用 emit：runner.ts:1089-1118）
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code session_before_switch}（reason: {@code "new"|"resume"}）。
     *
     * @return true ⇒ 被扩展取消（调用方中止整个切换流，旧会话不动）
     */
    public boolean sessionBeforeSwitch(String reason, String targetSessionFile) {
        if (!bus.hasHandlers(SESSION_BEFORE_SWITCH)) {
            return false;
        }
        var event = new SessionBeforeSwitchEvent(reason, targetSessionFile);
        boolean cancelled = false;
        for (var h : bus.snapshot(SESSION_BEFORE_SWITCH)) {
            try {
                var r = h.handle(event, ctx);
                if (r != null && Boolean.TRUE.equals(r.cancel())) {
                    return true; // cancel 短路：后续处理器不跑（pi :1100-1104）
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
        return cancelled;
    }

    /**
     * pi {@code session_before_fork}。
     *
     * @return true ⇒ 被扩展取消
     */
    public boolean sessionBeforeFork(String entryId, String position) {
        if (!bus.hasHandlers(SESSION_BEFORE_FORK)) {
            return false;
        }
        var event = new SessionBeforeForkEvent(entryId, position);
        for (var h : bus.snapshot(SESSION_BEFORE_FORK)) {
            try {
                var r = h.handle(event, ctx);
                if (r != null && Boolean.TRUE.equals(r.cancel())) {
                    return true;
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
        return false;
    }

    /**
     * pi {@code session_before_tree}（通用 emit，runner.ts:1089-1118）：
     * cancel 短路；否则<b>最后发言者赢</b>；逐处理器 catch ⇒ emitError ⇒ 继续。
     *
     * @return 最后一个非 null 结果；无订阅者/无人发言 ⇒ null
     */
    public SessionBeforeTreeResult sessionBeforeTree(TreePreparation preparation,
                                                     CompactionAbortToken signal) {
        if (!bus.hasHandlers(SESSION_BEFORE_TREE)) {
            return null;
        }
        var event = new SessionBeforeTreeEvent(preparation, signal);
        SessionBeforeTreeResult result = null;
        for (var h : bus.snapshot(SESSION_BEFORE_TREE)) {
            try {
                var r = h.handle(event, ctx);
                if (r != null) {
                    result = r; // 最后发言者赢（pi :1099）
                    if (Boolean.TRUE.equals(r.cancel())) {
                        return result; // cancel 短路（pi :1100-1104）
                    }
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
        return result;
    }

    /** pi {@code session_tree}（观察型；通用 emit 返回值忽略）。 */
    public void sessionTree(String newLeafId, String oldLeafId,
                            Entry.BranchSummary summaryEntry, Boolean fromExtension) {
        if (!bus.hasHandlers(SESSION_TREE)) {
            return;
        }
        var event = new SessionTreeEvent(newLeafId, oldLeafId, summaryEntry, fromExtension);
        for (var h : bus.snapshot(SESSION_TREE)) {
            try {
                h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 结果臂
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code _runInputHandlers}＋{@code emitInput}（agent-session.ts:1921-1942、
     * runner.ts:1520-1558 逐字）：{@code handled} 短路；{@code transform} 链（后处理器
     * 看到前处理器的终值）；全程无变换 ⇒ 原文。
     */
    public InputOutcome input(String text, String source, String streamingBehavior) {
        if (!bus.hasHandlers(INPUT)) {
            return new InputOutcome(false, text);
        }
        String current = text;
        for (var h : bus.snapshot(INPUT)) {
            try {
                var r = h.handle(new InputEvent(current, null, source, streamingBehavior), ctx);
                if (r instanceof InputEventResult.Handled) {
                    return new InputOutcome(true, current); // pi :1539 立即返回
                }
                if (r instanceof InputEventResult.Transform t) {
                    current = t.text(); // images 恒 null（窄差 D12）：text 链照 pi
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
        return new InputOutcome(false, current);
    }

    /**
     * pi {@code emitUserBash}（runner.ts:1262-1296 逐字）：首个发言者赢；处理器异常
     * ⇒ emitError 后<b>重抛</b>（调用方不得回落本地执行——pi interactive-mode.ts:6994-6999
     * 注释逐字）。
     */
    public Optional<ShellResult> userBash(String command, boolean excludeFromContext,
                                          String cwd) {
        if (!bus.hasHandlers(USER_BASH)) {
            return Optional.empty();
        }
        var event = new UserBashEvent(command, excludeFromContext, cwd);
        for (var h : bus.snapshot(USER_BASH)) {
            UserBashEventResult r;
            try {
                r = h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e.getMessage(), e); // pi 的 throw err 同位
            }
            if (r != null && r.result() != null) {
                return Optional.of(r.result()); // 首个发言者赢（pi :1273-1278）
            }
        }
        return Optional.empty();
    }

    /**
     * pi {@code emitBeforeAgentStart}（runner.ts:1420-1472 逐字）：{@code message}
     * 逐处理器累积；{@code systemPrompt} 链式覆盖（后处理器看到前处理器的改写值）。
     * 逐处理器 catch ⇒ emitError ⇒ 继续。
     *
     * <p>批 16（T3c，裁决⑪）：{@code sectionsBaseline}（harness 当前自定义段的
     * <b>拷贝</b>）作为事件的可变 {@code sections} 传入，同一对象贯穿所有处理器；
     * 终态经 {@link BeforeAgentStartOutcome#sections()} 返回（调用方写回 harness，
     * 下一 run 的 promptLoadout diff 自动产补丁）。</p>
     */
    public BeforeAgentStartOutcome beforeAgentStart(String prompt,
                                                    Supplier<String> currentSystemPrompt,
                                                    java.util.Map<String, String> sectionsBaseline) {
        if (!bus.hasHandlers(BEFORE_AGENT_START)) {
            return new BeforeAgentStartOutcome(List.of(), null, sectionsBaseline);
        }
        var messages = new ArrayList<CustomMessageDraft>();
        var sections = new java.util.LinkedHashMap<String, String>(sectionsBaseline);
        String systemPrompt = null;
        for (var h : bus.snapshot(BEFORE_AGENT_START)) {
            try {
                var effective = systemPrompt != null ? systemPrompt : currentSystemPrompt.get();
                var r = h.handle(new BeforeAgentStartEvent(prompt, effective, sections), ctx);
                if (r != null) {
                    if (r.message() != null) {
                        messages.add(r.message());
                    }
                    if (r.systemPrompt() != null) {
                        systemPrompt = r.systemPrompt();
                    }
                }
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
        return new BeforeAgentStartOutcome(List.copyOf(messages), systemPrompt,
            java.util.Map.copyOf(sections));
    }

    /**
     * 批 16（T6b）：pi {@code _emitModelSelect}（agent-session.ts:2457-2469）——
     * 观察型；同模型的 equals 门在<b>调用方</b>（pi :2461-2463 同位）。
     */
    public void modelSelect(com.pijava.ai.model.ModelId<?> model,
                            com.pijava.ai.model.ModelId<?> previousModel, String source) {
        if (!bus.hasHandlers(ExtensionEventSpec.MODEL_SELECT)) {
            return;
        }
        var event = new ExtensionEvent.ModelSelectEvent(model, previousModel, source);
        for (var h : bus.snapshot(ExtensionEventSpec.MODEL_SELECT)) {
            try {
                h.handle(event, ctx);
            } catch (Throwable e) {
                bus.emitError(h.error(e));
            }
        }
    }
}
