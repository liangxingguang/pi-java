package com.pijava.ai.protocol;

/**
 * 服务端消息重写的影响摘要（网关策略），对齐 pi {@code PiMessagesRewriteImpact}。
 */
public record RewriteImpact(
    String policyId,
    int policyVersion,
    boolean changed,
    int tokenCountChange,
    int messageCountChange,
    boolean systemPromptChanged
) {}
