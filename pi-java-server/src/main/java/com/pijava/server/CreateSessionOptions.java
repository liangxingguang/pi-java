package com.pijava.server;

import com.pijava.protocol.ModelRef;
import com.pijava.protocol.ProtocolThinkingLevel;

/**
 * 创建会话选项（对齐 pi {@code CreateSessionOptions}）。
 */
public record CreateSessionOptions(
    String cwd,
    String name,
    ModelRef model,
    ProtocolThinkingLevel thinkingLevel
) {}
