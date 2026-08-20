package com.pijava.coding.agent.subcommand;

import java.util.Optional;

import com.pijava.ai.auth.EnvApiKeyResolver;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthConfig;
import com.pijava.ai.auth.OAuthCredential;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.auth.OAuthFlow;
import com.pijava.ai.auth.OAuthProviders;

/**
 * {@code pi-java auth} subcommand (Phase 3 design §9.4).
 *
 * <p>Phase 3 supports {@code print-api-key <provider>} and {@code check <provider>};
 * P6-17 adds {@code oauth-login <provider>}（OAuth 授权码 + PKCE 流程）与
 * OAuth 凭证解析（{@code check}/{@code print-api-key} 落到 OAuth access token）。</p>
 */
public final class AuthCommand {

    private static final ConsoleInteraction CONSOLE = new ConsoleInteraction();

    private AuthCommand() {}

    /**
     * Dispatch an auth subcommand.
     *
     * @param subArgs tokens after {@code auth}
     * @return process exit code
     */
    public static int run(String[] subArgs) {
        if (subArgs.length == 0) {
            usage();
            return 1;
        }
        return switch (subArgs[0]) {
            case "print-api-key" -> printApiKey(provider(subArgs));
            case "check" -> check(provider(subArgs));
            case "oauth-login" -> oauthLogin(provider(subArgs));
            case "print-bearer-token" -> printBearerToken(provider(subArgs));
            default -> {
                System.out.println("Unknown auth command: " + subArgs[0]);
                usage();
                yield 1;
            }
        };
    }

    private static int oauthLogin(String provider) {
        if (provider == null) {
            usage();
            return 1;
        }
        var config = OAuthProviders.get(provider);
        if (config.isEmpty()) {
            System.out.println("No OAuth flow registered for provider '" + provider
                + "'. Available: " + OAuthProviders.names());
            return 1;
        }
        try {
            var credential = new OAuthFlow(config.get()).login(CONSOLE);
            new OAuthCredentialStore().store(provider, credential);
            System.out.println("Signed in to " + provider
                + " (OAuth credential saved to ~/.pi-java/auth-oauth.json).");
            return 0;
        } catch (Exception e) {
            System.out.println("OAuth login failed: " + e.getMessage());
            return 1;
        }
    }

    private static int printApiKey(String provider) {
        if (provider == null) {
            usage();
            return 1;
        }
        var key = resolve(provider);
        if (key.isEmpty()) {
            System.out.println("No API key found for " + provider
                + ". Run 'pi-java auth " + provider + "' or set the env var.");
            return 1;
        }
        System.out.println(key.get());
        return 0;
    }

    private static int check(String provider) {
        if (provider == null) {
            usage();
            return 1;
        }
        var key = resolve(provider);
        System.out.println(key.isPresent()
            ? "OK — API key configured for " + provider
            : "FAILED — no API key for " + provider);
        return key.isPresent() ? 0 : 1;
    }

    /** 输出 HTTP {@code Authorization} 请求头值（P6-15）。 */
    private static int printBearerToken(String provider) {
        if (provider == null) {
            usage();
            return 1;
        }
        var key = resolve(provider);
        if (key.isEmpty()) {
            System.out.println("No API key found for " + provider);
            return 1;
        }
        System.out.println(bearer(key.get()));
        return 0;
    }

    /** 将 API key 格式化为 {@code Authorization} 头值。 */
    static String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }

    private static Optional<String> resolve(String provider) {
        var env = new EnvApiKeyResolver().resolveApiKey(provider);
        if (env.isPresent()) {
            return env;
        }
        var file = new FileCredentialStore().resolveApiKey(provider);
        if (file.isPresent()) {
            return file;
        }
        var oauth = new OAuthCredentialStore().resolve(provider);
        if (oauth.isEmpty()) {
            return Optional.empty();
        }
        var credential = oauth.get();
        if (credential.isExpired()) {
            credential = refresh(provider, credential);
            if (credential != null) {
                new OAuthCredentialStore().store(provider, credential);
            }
        }
        return Optional.ofNullable(credential == null ? null : credential.accessToken());
    }

    /** 过期凭证尝试刷新；无 refresh token 或未注册 OAuth 配置时返回原值。 */
    private static OAuthCredential refresh(String provider, OAuthCredential credential) {
        if (credential.refreshToken().isBlank()) {
            return credential;
        }
        var config = OAuthProviders.get(provider);
        if (config.isEmpty()) {
            return credential;
        }
        try {
            return new OAuthFlow(config.get()).refresh(credential);
        } catch (Exception e) {
            System.out.println("OAuth token refresh failed for " + provider
                + ": " + e.getMessage());
            return credential;
        }
    }

    private static String provider(String[] subArgs) {
        return subArgs.length > 1 ? subArgs[1] : null;
    }

    private static void usage() {
        System.out.println("""
            Usage:
              pi-java auth print-api-key <provider>
              pi-java auth check <provider>
              pi-java auth oauth-login <provider>   (OAuth PKCE flow; e.g. openrouter)
              pi-java auth print-bearer-token <provider>   (print "Bearer <key>" header value)
            Providers: anthropic | openai | google | deepseek | mistral
            OAuth: """ + OAuthProviders.names());
    }

    /** 基于控制台的 OAuth 交互实现（无控制台时 prompt 返回空串）。 */
    private static final class ConsoleInteraction implements OAuthFlow.Interaction {
        @Override
        public void notify(String message) {
            System.out.println(message);
        }

        @Override
        public String prompt(String message) {
            var console = System.console();
            return console == null ? "" : console.readLine(message + " ");
        }
    }
}
