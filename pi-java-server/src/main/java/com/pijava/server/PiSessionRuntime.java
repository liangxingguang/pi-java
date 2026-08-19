package com.pijava.server;

import java.util.function.Consumer;

import com.pijava.protocol.ModelRef;
import com.pijava.protocol.ProtocolThinkingLevel;
import com.pijava.protocol.SessionPhase;
import com.pijava.protocol.SessionSnapshot;

/**
 * 一个已获取的会话租约（对齐 pi {@code PiSessionRuntime}）。
 *
 * <p>冲突操作必须直接拒绝（回 BUSY / SESSION_LOCKED），不排队。</p>
 */
public interface PiSessionRuntime extends AutoCloseable {

    /** 当前会话快照。 */
    SessionSnapshot snapshot();

    /** 当前会话阶段。 */
    SessionPhase getPhase();

    /** 提交 prompt。 */
    void prompt(PromptInput input);

    /** 注入 steer 消息。 */
    void steer(SteerInput input);

    /** 中止当前运行。 */
    void abort();

    /** 切换模型。 */
    void setModel(ModelRef model);

    /** 切换思考等级。 */
    void setThinking(ProtocolThinkingLevel level);

    /** 订阅运行时事件；返回的 Runnable 退订。 */
    Runnable subscribe(Consumer<RuntimeEvent> listener);

    @Override
    void close();
}
