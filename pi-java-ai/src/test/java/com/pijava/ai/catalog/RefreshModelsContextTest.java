package com.pijava.ai.catalog;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.auth.RecordedCredential;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #16：刷新上下文按 pi 形状带 credential / baseUrl / signal；
 * 离线相不解析凭据（避免 OAuth 刷新副作用）。
 */
class RefreshModelsContextTest {

    private static final CatalogPublisherPort PUBLISH = publication -> true;

    @Test
    void offlinePhaseHasNoCredentialOrBaseUrlButAUsableSignal() {
        var context = RefreshModelsContext.offline(Optional.empty(), PUBLISH);

        assertThat(context.allowNetwork()).isFalse();
        assertThat(context.credential()).isEmpty();
        assertThat(context.baseUrl()).isNull();
        assertThat(context.signal()).isNotNull();
        assertThat(context.signal().isAborted()).isFalse();
    }

    @Test
    void onlinePhaseCarriesCredentialBaseUrlAndSignal() {
        var credential = new RecordedCredential(AuthKind.OAUTH, "access", "stored:openrouter");
        var signal = AbortSignal.create();

        var context = RefreshModelsContext.online(
            Optional.of(ModelsStoreEntry.of(List.of())), PUBLISH, true,
            Optional.of(credential), "https://openrouter.ai/api/v1", signal);

        assertThat(context.allowNetwork()).isTrue();
        assertThat(context.force()).isTrue();
        assertThat(context.credential()).contains(credential);
        assertThat(context.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(context.signal()).isSameAs(signal);
    }

    @Test
    void nullsNormalizeToTheAbsentShape() {
        var context = new RefreshModelsContext(null, PUBLISH, false, false, null, null, null);

        assertThat(context.stored()).isEmpty();
        assertThat(context.credential()).isEmpty();
        assertThat(context.baseUrl()).isNull();
        assertThat(context.signal()).isNotNull();
    }

    @Test
    void theLegacyThreeArgumentOnlineFactoryStaysAvailable() {
        var context = RefreshModelsContext.online(Optional.empty(), PUBLISH, false);

        assertThat(context.credential()).isEmpty();
        assertThat(context.baseUrl()).isNull();
        assertThat(context.signal().isAborted()).isFalse();
    }
}
