package com.pijava.ai.auth;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-18: Credentials — profile 感知的解析顺序。
 *
 * <p>用 provider "faux"（无 API key 环境变量），避免真实环境变量干扰断言。</p>
 */
class CredentialsTest {

    @TempDir
    Path tmp;

    @Test
    void profileKeyWinsWhenProfileActive() {
        var profiles = new AuthProfileManager(tmp.resolve("profiles.json"));
        var file = new FileCredentialStore(tmp.resolve("auth.json"));
        profiles.setActiveProfile("faux", "work");
        file.storeApiKey("faux", "work", "profile-key");
        file.storeApiKey("faux", "default-key");

        var resolved = Credentials.resolveApiKey("faux", profiles,
            new EnvApiKeyResolver(), file);

        assertThat(resolved).hasValue("profile-key");
    }

    @Test
    void fallsBackToDefaultWhenProfileHasNoKey() {
        var profiles = new AuthProfileManager(tmp.resolve("profiles.json"));
        var file = new FileCredentialStore(tmp.resolve("auth.json"));
        profiles.setActiveProfile("faux", "work");
        file.storeApiKey("faux", "default-key");

        var resolved = Credentials.resolveApiKey("faux", profiles,
            new EnvApiKeyResolver(), file);

        assertThat(resolved).hasValue("default-key");
    }

    @Test
    void noProfileUsesDefault() {
        var profiles = new AuthProfileManager(tmp.resolve("profiles.json"));
        var file = new FileCredentialStore(tmp.resolve("auth.json"));
        file.storeApiKey("faux", "default-key");

        var resolved = Credentials.resolveApiKey("faux", profiles,
            new EnvApiKeyResolver(), file);

        assertThat(resolved).hasValue("default-key");
    }
}
