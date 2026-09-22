package com.pijava.ai.auth;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.pijava.ai.api.AuthKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 A0 步7（{@code docs/43 D5/D6}）：Anthropic 凭证链的**种类**与次序 ——
 * pi {@code providers/anthropic.ts:18-39} 的三种形态 ＋ java 既有的 profile 扩展层。
 *
 * <p>最终次序（从高到低，D6）：</p>
 * <ol>
 *   <li>profile 环境变量 {@code <PROVIDER>_API_KEY_<PROFILE>}（java 扩展）⇒ API_KEY</li>
 *   <li>profile 文件凭证 {@code provider::profile}（java 扩展）⇒ API_KEY</li>
 *   <li>{@code ANTHROPIC_AUTH_TOKEN} ⇒ <b>BEARER</b>（pi ②）</li>
 *   <li>{@code ANTHROPIC_OAUTH_TOKEN} ⇒ <b>OAUTH</b>（pi ③ 前半）</li>
 *   <li>默认环境变量 {@code <PROVIDER>_API_KEY} ⇒ API_KEY（pi ③ 后半）</li>
 *   <li>默认文件凭证 ⇒ API_KEY</li>
 * </ol>
 *
 * <p><b>边界</b>（照 pi）：两个 token 变量**仅 Anthropic** —— pi 只定义了这两个符号，
 * 其余 provider 一律 {@code <PROVIDER>_API_KEY}（{@code env-api-keys.ts} 的 {@code envMap}）。
 * java 的 profile 层**不**扩展出 {@code ..._AUTH_TOKEN_<PROFILE>}（pi 无此物，凭空发明
 * 会成为无法对账的行为）。</p>
 *
 * <p>环境变量经注入的 lookup 读（{@code System::getenv} 在这里不可控）。</p>
 */
class CredentialChainTest {

    @TempDir
    Path tmp;

    private Map<String, String> env = new HashMap<>();

    private AuthProfileManager profiles() {
        return new AuthProfileManager(tmp.resolve("profiles.json"));
    }

    private FileCredentialStore file() {
        return new FileCredentialStore(tmp.resolve("auth.json"));
    }

    private java.util.Optional<RecordedCredential> resolve(String provider) {
        return Credentials.resolveCredential(provider, profiles(),
            new EnvApiKeyResolver(env::get), file());
    }

    // ── 四组合矩阵（D5/D6） ───────────────────────────────────────────────

    @Test
    void onlyAuthTokenYieldsBearer() {
        env.put("ANTHROPIC_AUTH_TOKEN", "bearer-tok");

        var credential = resolve("anthropic");

        assertThat(credential).isPresent();
        assertThat(credential.get().kind()).isEqualTo(AuthKind.BEARER);
        assertThat(credential.get().value()).isEqualTo("bearer-tok");
        assertThat(credential.get().source()).isEqualTo("env:ANTHROPIC_AUTH_TOKEN");
    }

    @Test
    void onlyOauthTokenYieldsOauth() {
        env.put("ANTHROPIC_OAUTH_TOKEN", "oat-tok");

        var credential = resolve("anthropic");

        assertThat(credential).isPresent();
        assertThat(credential.get().kind()).isEqualTo(AuthKind.OAUTH);
        assertThat(credential.get().value()).isEqualTo("oat-tok");
    }

    @Test
    void onlyApiKeyEnvYieldsPlainApiKey() {
        env.put("ANTHROPIC_API_KEY", "plain-key");

        var credential = resolve("anthropic");

        assertThat(credential).isPresent();
        assertThat(credential.get().kind()).isEqualTo(AuthKind.API_KEY);
        assertThat(credential.get().value()).isEqualTo("plain-key");
    }

    @Test
    void authTokenWinsOverOauthTokenAndApiKey() {
        env.put("ANTHROPIC_API_KEY", "plain-key");
        env.put("ANTHROPIC_OAUTH_TOKEN", "oat-tok");
        env.put("ANTHROPIC_AUTH_TOKEN", "bearer-tok");

        var credential = resolve("anthropic");

        assertThat(credential.get().kind()).isEqualTo(AuthKind.BEARER);
        assertThat(credential.get().value()).isEqualTo("bearer-tok");
    }

    @Test
    void oauthTokenWinsOverApiKey() {
        env.put("ANTHROPIC_API_KEY", "plain-key");
        env.put("ANTHROPIC_OAUTH_TOKEN", "oat-tok");

        assertThat(resolve("anthropic").get().kind()).isEqualTo(AuthKind.OAUTH);
    }

    // ── profile 层优先（java 扩展，D6） ───────────────────────────────────

    @Test
    void profileFileCredentialWinsOverTokens() {
        profiles().setActiveProfile("anthropic", "work");
        file().storeApiKey("anthropic", "work", "profile-key");
        env.put("ANTHROPIC_AUTH_TOKEN", "bearer-tok");

        var credential = resolve("anthropic");

        assertThat(credential.get().kind()).isEqualTo(AuthKind.API_KEY);
        assertThat(credential.get().value()).isEqualTo("profile-key");
    }

    // ── 边界：两个 token 变量仅 Anthropic ─────────────────────────────────

    @Test
    void tokensAreAnthropicOnly() {
        env.put("ANTHROPIC_AUTH_TOKEN", "bearer-tok");
        env.put("ANTHROPIC_OAUTH_TOKEN", "oat-tok");

        // 别的 provider 看都不看这两个变量（pi 的 envMap 里没有它们）。
        assertThat(resolve("mistral")).isEmpty();
        assertThat(resolve("openai")).isEmpty();
    }

    @Test
    void profileLayerHasNoTokenVariant() {
        profiles().setActiveProfile("anthropic", "work");
        // profile 层的 token 变体**不存在**（有意不加，D6）：带上它也不生效。
        env.put("ANTHROPIC_AUTH_TOKEN_WORK", "profile-bearer");

        assertThat(resolve("anthropic")).isEmpty();
    }
}