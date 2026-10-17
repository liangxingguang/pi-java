package com.pijava.agent.tool;

/**
 * pi {@code bash.ts:199-208} 的会话身份来源（{@code ctx.sessionManager/model/thinkingLevel}
 * 三面晚绑定，仿 {@code imagePolicy} 先例）—— B242 的 PI_* 注入载荷。
 *
 * <p>全分量可空 ≙ pi 的条件不设（sessionFile/model/thinkingLevel 都是
 * {@code if (…)} 门控）。</p>
 */
public record SessionEnvironment(String sessionId, String sessionFile,
                                 String provider, String model, String thinkingLevel) {}
