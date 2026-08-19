package com.pijava.server;

import java.util.List;

import com.pijava.protocol.ModelMetadata;
import com.pijava.protocol.SessionMetadata;

/**
 * 服务边界 —— 由 coding-agent 侧实现，server 模块只依赖此接口（对齐 pi
 * {@code PiServerService}）。
 *
 * <p>会话控制面 + 独占租约 + 快照订阅，没有 entry 级存储方法。</p>
 */
public interface PiServerService {

    /** 列出全部会话元数据。 */
    List<SessionMetadata> listSessions();

    /** 列出全部可用模型。 */
    List<ModelMetadata> listModels();

    /** 创建会话；id 由 PiServer 生成并要求服务端持久化该确切 ID。 */
    PiSessionRuntime createSession(CreateSessionOptions options);

    /** 打开既有会话；已被其他连接持有抛 {@link SessionLockedException}。 */
    PiSessionRuntime openSession(String sessionId);
}
