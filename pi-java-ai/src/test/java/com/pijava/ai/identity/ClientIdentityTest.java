package com.pijava.ai.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 B266 / pi {@code mergeClientIdentity}（{@code identity.test.ts:81-92}）。
 */
class ClientIdentityTest {

    @Test
    void mergesIdentitiesFieldByField() {
        assertThat(ClientIdentity.merge(null, null)).isNull();
        assertThat(ClientIdentity.merge(
            new ClientIdentity(UserAgentMode.SHORT, AttributionSetting.of(ClientAttribution.PI), null),
            new ClientIdentity(null, AttributionSetting.UNSET, "1.0.0")))
            .isEqualTo(new ClientIdentity(UserAgentMode.SHORT,
                AttributionSetting.of(ClientAttribution.PI), "1.0.0"));
    }

    @Test
    void explicitOffOverridesAttribution() {
        assertThat(ClientIdentity.merge(
            new ClientIdentity(null, AttributionSetting.of(ClientAttribution.PI), null),
            new ClientIdentity(null, AttributionSetting.OFF, null)))
            .isEqualTo(new ClientIdentity(null, AttributionSetting.OFF, null));
    }

    @Test
    void emptySourcesStillProduceIdentity() {
        assertThat(ClientIdentity.merge(ClientIdentity.empty()))
            .isEqualTo(ClientIdentity.empty());
        assertThat(ClientIdentity.merge()).isNull();
    }
}
