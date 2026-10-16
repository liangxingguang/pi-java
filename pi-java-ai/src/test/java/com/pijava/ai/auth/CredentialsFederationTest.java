package com.pijava.ai.auth;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.ai.api.AuthKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #98：workload identity federation 的解析序（pi {@code providers/anthropic.ts:49-69}
 * ＋ {@code test/anthropic-federation.test.ts} 的 java 镜像）。
 *
 * <p>钉三件事：</p>
 * <ol>
 *   <li><b>命中形状</b>：三必需齐 ⇒ {@code FEDERATION} ＋ 空值 ＋ source 文案 ＋ env 透传
 *       （含两可选）；缺一必需 ⇒ empty；</li>
 *   <li><b>末位次序</b>：stored OAuth ／ {@code ANTHROPIC_AUTH_TOKEN} ／
 *       {@code ANTHROPIC_OAUTH_TOKEN} ／ {@code ANTHROPIC_API_KEY} 各自<b>赢过</b>
 *       federation（pi 注释 "Last in line so keys and ANTHROPIC_AUTH_TOKEN keep winning"）；</li>
 *   <li><b>边界</b>：仅 Anthropic；{@code resolveApiKey} 对空值凭证滤 blank。</li>
 * </ol>
 *
 * <p>环境读取走注入的 {@code envLookup}（map 缺席 ⇒ null），真实进程环境不参与断言。</p>
 */
class CredentialsFederationTest {

    @TempDir
    Path tmp;

    private static final Map<String, String> REQUIRED = Map.of(
        "ANTHROPIC_FEDERATION_RULE_ID", "rule-1",
        "ANTHROPIC_ORGANIZATION_ID", "org-1",
        "ANTHROPIC_IDENTITY_TOKEN_FILE", "/var/run/secrets/token");

    private record Stores(AuthProfileManager profiles, EnvApiKeyResolver env,
                          FileCredentialStore file) {}

    private Stores stores(Map<String, String> envVars) {
        return new Stores(
            new AuthProfileManager(tmp.resolve("profiles.json")),
            new EnvApiKeyResolver(envVars::get),
            new FileCredentialStore(tmp.resolve("auth.json")));
    }

    private Optional<RecordedCredential> resolve(String provider, Map<String, String> envVars) {
        var s = stores(envVars);
        return Credentials.resolveCredential(provider, s.profiles(), s.env(), s.file());
    }

    // ── 命中形状 ─────────────────────────────────────────────────────────

    @Test
    void allRequiredPresentYieldsFederationCredential() {
        var resolved = resolve("anthropic", REQUIRED);

        assertThat(resolved).isPresent();
        var credential = resolved.get();
        assertThat(credential.kind()).isEqualTo(AuthKind.FEDERATION);
        assertThat(credential.value()).as("federation 无 key（pi 的 auth 空对象）").isEmpty();
        assertThat(credential.source()).isEqualTo("workload identity federation");
        assertThat(credential.env())
            .as("三必需原样透传")
            .containsEntry("ANTHROPIC_FEDERATION_RULE_ID", "rule-1")
            .containsEntry("ANTHROPIC_ORGANIZATION_ID", "org-1")
            .containsEntry("ANTHROPIC_IDENTITY_TOKEN_FILE", "/var/run/secrets/token")
            .as("可选缺席 ⇒ 键不在（pi 的 if (value) 才收）")
            .doesNotContainKey("ANTHROPIC_SERVICE_ACCOUNT_ID")
            .doesNotContainKey("ANTHROPIC_WORKSPACE_ID");
    }

    @Test
    void optionalVarsAreCollectedWhenPresent() {
        var envVars = new HashMap<>(REQUIRED);
        envVars.put("ANTHROPIC_SERVICE_ACCOUNT_ID", "sa-1");
        envVars.put("ANTHROPIC_WORKSPACE_ID", "ws-1");

        var resolved = resolve("anthropic", envVars);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().env())
            .containsEntry("ANTHROPIC_SERVICE_ACCOUNT_ID", "sa-1")
            .containsEntry("ANTHROPIC_WORKSPACE_ID", "ws-1")
            .hasSize(5);
    }

    @Test
    void missingAnyRequiredVarYieldsEmpty() {
        for (var missing : List.of("ANTHROPIC_FEDERATION_RULE_ID",
                "ANTHROPIC_ORGANIZATION_ID", "ANTHROPIC_IDENTITY_TOKEN_FILE")) {
            var envVars = new HashMap<>(REQUIRED);
            envVars.remove(missing);

            assertThat(resolve("anthropic", envVars))
                .as("缺 %s ⇒ undefined（pi :62-64）", missing)
                .isEmpty();
        }
    }

    // ── 末位次序：每一条既有链路都赢过 federation ─────────────────────────

    @Test
    void storedOauthWinsOverFederation() {
        var s = stores(REQUIRED);
        var oauth = new OAuthCredential("oat-token", "refresh", 0L, null);

        var resolved = Credentials.resolveCredential("anthropic", s.profiles(), s.env(),
            s.file(), provider -> Optional.of(oauth));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().kind()).as("stored OAuth 在 federation 之前")
            .isEqualTo(AuthKind.API_KEY);
        assertThat(resolved.get().value()).isEqualTo("oat-token");
    }

    @Test
    void authTokenWinsOverFederation() {
        var envVars = new HashMap<>(REQUIRED);
        envVars.put("ANTHROPIC_AUTH_TOKEN", "bearer-tok");

        var resolved = resolve("anthropic", envVars);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().kind()).isEqualTo(AuthKind.BEARER);
        assertThat(resolved.get().source()).isEqualTo("env:ANTHROPIC_AUTH_TOKEN");
    }

    @Test
    void oauthTokenWinsOverFederation() {
        var envVars = new HashMap<>(REQUIRED);
        envVars.put("ANTHROPIC_OAUTH_TOKEN", "sk-ant-oat-x");

        var resolved = resolve("anthropic", envVars);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().kind()).isEqualTo(AuthKind.OAUTH);
    }

    @Test
    void apiKeyEnvWinsOverFederation() {
        var envVars = new HashMap<>(REQUIRED);
        envVars.put("ANTHROPIC_API_KEY", "sk-ant-key");

        var resolved = resolve("anthropic", envVars);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().kind()).isEqualTo(AuthKind.API_KEY);
        assertThat(resolved.get().value()).isEqualTo("sk-ant-key");
    }

    @Test
    void storedFileKeyWinsOverFederation() {
        var s = stores(REQUIRED);
        s.file().storeApiKey("anthropic", "file-key");

        var resolved = Credentials.resolveCredential("anthropic", s.profiles(), s.env(), s.file());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().kind()).isEqualTo(AuthKind.API_KEY);
        assertThat(resolved.get().source()).isEqualTo("stored:anthropic");
    }

    // ── 边界 ─────────────────────────────────────────────────────────────

    @Test
    void nonAnthropicProviderNeverFederates() {
        assertThat(resolve("openai", REQUIRED))
            .as("federation 仅 Anthropic（pi getAnthropicFederation 的 provider 门）")
            .isEmpty();
    }

    @Test
    void resolveApiKeyFiltersTheEmptyFederationValue() {
        var s = stores(REQUIRED);

        assertThat(Credentials.resolveApiKey("anthropic", s.profiles(), s.env(), s.file()))
            .as("federation 命中但无 key 值 ⇒ 展示侧照旧「未配置」（pi 的 auth 空对象）")
            .isEmpty();
    }

    @Test
    void threeArgConstructorKeepsEnvEmpty() {
        var credential = new RecordedCredential(AuthKind.API_KEY, "k", "env:X");

        assertThat(credential.env()).isEmpty();
    }
}
