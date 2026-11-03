package com.pijava.agent.harness;

import com.pijava.ai.identity.AttributionSetting;
import com.pijava.ai.identity.ClientAttribution;
import com.pijava.ai.identity.ClientIdentity;

/**
 * docs/14 B266：pi {@code DEFAULT_AGENT_IDENTITY}
 * （{@code packages/agent/src/agent-loop.ts:37-42}）的 Java 落点。
 *
 * <p>agent loop 的默认身份：pi-ai 的默认 {@code User-Agent} 加指向 pi 的归因头。
 * {@code HarnessConfig.identity} 逐字段覆盖它。</p>
 */
public final class AgentIdentity {

    /** pi {@code DEFAULT_AGENT_IDENTITY = { attribution: PI_ATTRIBUTION }} 逐字。 */
    public static final ClientIdentity DEFAULT = new ClientIdentity(
        null, AttributionSetting.of(ClientAttribution.PI), null);

    private AgentIdentity() {}
}
