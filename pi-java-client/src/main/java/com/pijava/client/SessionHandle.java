package com.pijava.client;

import java.util.concurrent.atomic.AtomicReference;

import com.pijava.protocol.ServerEvent;
import com.pijava.protocol.SessionSnapshot;

/**
 * 会话租约 + 快照订阅（对齐 pi {@code SessionHandle}）。
 *
 * <p>持有最新快照，订阅客户端事件以跟踪快照变化；{@link #close()} 释放租约
 * （detach）。</p>
 */
public final class SessionHandle implements AutoCloseable {

    private final PiClient client;
    private final String sessionId;
    private final AtomicReference<SessionSnapshot> snapshot;
    private final AutoCloseable subscription;

    SessionHandle(PiClient client, SessionSnapshot initial) {
        this.client = client;
        this.sessionId = initial.id();
        this.snapshot = new AtomicReference<>(initial);
        this.subscription = client.subscribe(event -> {
            if (event instanceof ServerEvent.SessionSnapshotEvent ss
                    && ss.snapshot().id().equals(sessionId)) {
                snapshot.set(ss.snapshot());
            }
        });
    }

    /** 最新会话快照。 */
    public SessionSnapshot snapshot() {
        return snapshot.get();
    }

    /** 发送 prompt，返回更新后的快照。 */
    public SessionSnapshot prompt(String text) {
        var updated = client.prompt(sessionId, text);
        snapshot.set(updated);
        return updated;
    }

    /** 中止当前运行。 */
    public void abort() {
        client.abort(sessionId);
    }

    /** 释放租约。 */
    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // 退订失败可忽略
        }
        client.detach(sessionId);
    }
}
