package com.pijava.coding.agent.extension;

import java.util.Optional;

import com.pijava.agent.harness.Context;
import com.pijava.ai.message.Message;

/**
 * docs/50 §2.2：pi {@code ModelRegistry} 的只读门面（扩展面）。
 *
 * <p>只提供示例真实消费的三项：{@code find}/{@code hasConfiguredAuth}/
 * {@code complete}。pi 的 {@code findOfType}/{@code classify} 不提供（D233：
 * Java AI 层无 classifier 模型类型）。</p>
 */
public interface ExtensionModelRegistry {

    /**
     * 查找模型：虚拟模型表优先，其次目录。未命中 {@link Optional#empty()}，
     * <b>不回退到当前模型</b>。
     */
    Optional<ExtensionModelInfo> find(String provider, String modelId);

    /** pi {@code hasConfiguredAuth}：凭证门（虚拟 provider 的 auth 豁免同源）。 */
    boolean hasConfiguredAuth(ExtensionModelInfo model);

    /**
     * 一次性补全：纯读调用，不追加会话记录、不发会话事件。
     * {@code options.signal} 已中止时返回 {@code stopReason=aborted} 的同形消息。
     */
    Message.AssistantMessage complete(ExtensionModelInfo model, Context context,
                                      ExtensionCompleteOptions options);
}
