package com.pijava.coding.agent.spi;

import java.util.List;

import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.agent.session.jsonl.SessionListProgress;
import com.pijava.ai.AbortSignal;

/**
 * Session-list loader passed to {@link SessionSelectorProvider} (batch 14, docs/25 T11).
 * pi {@code SessionsLoader}（session-picker.ts:6-8）对位——选择器的双 scope 数据源
 * （current＝项目内 / all＝跨项目），由调用方（会话解析层）绑定到持久仓。
 */
@FunctionalInterface
public interface SessionListLoader {

    /** Load the scope's sessions, publishing progress; honour the abort signal. */
    List<RichSessionInfo> load(SessionListProgress progress, AbortSignal signal);
}
