package com.pijava.server;

import com.pijava.protocol.SessionSnapshot;
import com.pijava.protocol.TranscriptProgress;

/**
 * 会话运行时事件 —— 由 {@link PiSessionRuntime#subscribe} 推送，服务器转发为
 * {@code ServerEvent} 给持有租约的连接。
 */
public record RuntimeEvent(
    SessionSnapshot snapshot,
    TranscriptProgress progress
) {}
