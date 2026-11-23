package com.pijava.tui.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.util.ProgramStatusProtocol.Kind;
import com.pijava.tui.util.ProgramStatusProtocol.State;
import com.pijava.tui.util.ProgramStatusProtocol.Status;

/**
 * 交互模式 → OSC 7501 状态机（docs/26 §4.3，K39）：pi
 * {@code program-status-reporter.ts} 的 java 对位。运行期 {@code working}、
 * 压缩期 {@code working(Compacting context)}、对话框等待 {@code blocked}、
 * 结束 {@code done/error/idle}；消息只取会话名、对话框标题与错误首行。
 *
 * <p>状态按字段键去重；{@link #clear()} 在退出时写 {@code clear} 并停用。
 * 只在渲染线程调用（与 pi 同线程），输出经 {@link Consumer} 注入。</p>
 */
public final class ProgramStatusReporter {

    /** 对话框阻塞状态（pi {@code BlockedStatus}）。 */
    public record BlockedStatus(Kind kind, String message) {

        /** 一般提问（标题即消息）。 */
        public static BlockedStatus question(String message) {
            return new BlockedStatus(Kind.QUESTION, message);
        }
    }

    private final Consumer<String> sink;
    private final Supplier<String> sessionName;
    private final Map<String, BlockedStatus> blocked = new LinkedHashMap<>();
    private boolean enabled;
    private boolean cleared;
    private boolean runActive;
    private boolean compacting;
    private Status runResult = Status.of(State.DONE);
    private Status restingStatus = Status.of(State.IDLE);
    private String lastReport;

    /**
     * @param sink        OSC 序列输出（测试注入）
     * @param sessionName 会话名（working/done 消息；可返回 null）
     * @param enabled     是否输出
     */
    public ProgramStatusReporter(Consumer<String> sink, Supplier<String> sessionName, boolean enabled) {
        this.sink = sink;
        this.sessionName = sessionName;
        this.enabled = enabled;
    }

    /** 生产构造：写 stdout 并刷出，启用位读 {@code PI_PROGRAM_STATUS}。 */
    public static ProgramStatusReporter stdout(Supplier<String> sessionName) {
        return new ProgramStatusReporter(sequence -> {
            System.out.print(sequence);
            System.out.flush();
        }, sessionName, ProgramStatusProtocol.enabled(System.getenv()));
    }

    /**
     * 会话事件驱动状态机（pi {@code handleEvent} 逐 case 对位）。
     *
     * @param event 会话事件
     */
    public void handleEvent(AgentSessionEvent event) {
        switch (event) {
            case AgentSessionEvent.AgentStart ignored -> {
                runActive = true;
                runResult = Status.of(State.DONE);
            }
            case AgentSessionEvent.MessageEnd end -> {
                if (!(end.message() instanceof Message.AssistantMessage assistant)) {
                    return;
                }
                // 最近一次响应决定结局：失败重试成功后应覆盖 error。
                runResult = assistant.stopReason() == com.pijava.ai.message.StopReason.ERROR
                    ? new Status(State.ERROR, null, null, firstLine(assistant.errorMessage()))
                    : Status.of(State.DONE);
            }
            case AgentSessionEvent.CompactionStart ignored -> compacting = true;
            case AgentSessionEvent.CompactionEnd end -> {
                compacting = false;
                if (runActive) {
                    if (end.aborted()) {
                        runResult = Status.of(State.IDLE);
                    } else if (end.errorMessage() != null) {
                        runResult = new Status(State.ERROR, null, null, firstLine(end.errorMessage()));
                    }
                } else if (end.aborted()) {
                    restingStatus = Status.of(State.IDLE);
                } else if (end.reason() == AgentSessionEvent.CompactionReason.MANUAL) {
                    restingStatus = end.errorMessage() != null
                        ? new Status(State.ERROR, null, null, firstLine(end.errorMessage()))
                        : Status.of(State.DONE);
                }
            }
            case AgentSessionEvent.AgentSettled settled -> {
                runActive = false;
                restingStatus = settled.aborted() ? Status.of(State.IDLE) : runResult;
            }
            case AgentSessionEvent.SessionInfoChanged ignored -> {
                // 会话名进入 working/done 报告，只触发重报。
            }
            default -> {
                return;
            }
        }
        report();
    }

    /**
     * 置/清一个阻塞来源（pi {@code setBlocked}）：按打开顺序，最近者优先。
     *
     * @param source 来源键（如 {@code extension-dialog}、{@code extension-overlay:0}）
     * @param status 阻塞状态，{@code null} 表示该来源关闭
     */
    public void setBlocked(String source, @Nullable BlockedStatus status) {
        blocked.remove(source);
        if (status != null) {
            blocked.put(source, status);
        }
        report();
    }

    /** 会话切换等场景复位（pi {@code reset}）。 */
    public void reset() {
        runActive = false;
        compacting = false;
        runResult = Status.of(State.DONE);
        restingStatus = Status.of(State.IDLE);
        cleared = false;
        report();
    }

    /** 退出/停止：写 {@code clear} 并停用（幂等）。 */
    public void clear() {
        if (cleared) {
            return;
        }
        cleared = true;
        if (enabled) {
            sink.accept(ProgramStatusProtocol.format(Status.of(State.CLEAR)));
        }
    }

    /** 开关输出（测试/设置）。 */
    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    /** 当前生效状态（含 app；测试面）。 */
    Status currentStatus() {
        BlockedStatus activeBlock = null;
        for (BlockedStatus value : blocked.values()) {
            activeBlock = value;
        }
        Status status;
        if (activeBlock != null) {
            status = new Status(State.BLOCKED, null, activeBlock.kind(), activeBlock.message());
        } else if (compacting) {
            status = new Status(State.WORKING, null, null, "Compacting context");
        } else {
            status = runActive ? new Status(State.WORKING, null, null, null) : restingStatus;
            if (status.state() == State.WORKING || status.state() == State.DONE) {
                status = new Status(status.state(), null, null, sessionName.get());
            }
        }
        return new Status(status.state(), ProgramStatusProtocol.APP_NAME, status.kind(), status.message());
    }

    private void report() {
        if (!enabled) {
            return;
        }
        Status status = currentStatus();
        String key = status.state() + "|" + status.app() + "|" + status.kind() + "|" + status.message();
        if (key.equals(lastReport)) {
            return;
        }
        lastReport = key;
        sink.accept(ProgramStatusProtocol.format(status));
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "Error";
        }
        String trimmed = text.split("\\r?\\n", 2)[0].trim();
        return trimmed.isEmpty() ? "Error" : trimmed;
    }
}
