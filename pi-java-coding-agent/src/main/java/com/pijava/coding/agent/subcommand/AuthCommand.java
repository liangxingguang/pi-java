package com.pijava.coding.agent.subcommand;

import java.util.Optional;

import com.pijava.ai.auth.AuthProfileManager;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.auth.DeviceCodeConfig;
import com.pijava.ai.auth.DeviceCodeFlow;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthConfig;
import com.pijava.ai.auth.OAuthCredential;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.auth.OAuthFlow;
import com.pijava.ai.auth.OAuthInteraction;
import com.pijava.ai.auth.OAuthProvider;
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
            case "profile" -> profileCommand(subArgs);
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
        var providerSpec = OAuthProviders.get(provider);
        if (providerSpec.isEmpty()) {
            System.out.println("No OAuth flow registered for provider '" + provider
                + "'. Available: " + OAuthProviders.names());
            return 1;
        }
        try {
            var credential = login(providerSpec.get());
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
        var credential = Credentials.resolveApiKey(provider);
        if (credential.isPresent()) {
            return credential;
        }
        var oauth = new OAuthCredentialStore().resolve(provider);
        if (oauth.isEmpty()) {
            return Optional.empty();
        }
        var oauthCredential = oauth.get();
        if (oauthCredential.isExpired()) {
            oauthCredential = refresh(provider, oauthCredential);
            if (oauthCredential != null) {
                new OAuthCredentialStore().store(provider, oauthCredential);
            }
        }
        return Optional.ofNullable(
            oauthCredential == null ? null : oauthCredential.accessToken());
    }

    /** 过期凭证尝试刷新；无 refresh token 或未注册 OAuth 配置时返回原值。 */
    private static OAuthCredential refresh(String provider, OAuthCredential credential) {
        if (credential.refreshToken().isBlank()) {
            return credential;
        }
        var providerSpec = OAuthProviders.get(provider);
        if (providerSpec.isEmpty()) {
            return credential;
        }
        try {
            return switch (providerSpec.get()) {
                case OAuthProvider.Pkce(OAuthConfig c) -> new OAuthFlow(c).refresh(credential);
                case OAuthProvider.Device(DeviceCodeConfig d) -> new DeviceCodeFlow(d).refresh(credential);
            };
        } catch (Exception e) {
            System.out.println("OAuth token refresh failed for " + provider
                + ": " + e.getMessage());
            return credential;
        }
    }

    /** 按 provider 流程判别执行 PKCE 或 device-code 登录。 */
    private static OAuthCredential login(OAuthProvider provider) throws Exception {
        return switch (provider) {
            case OAuthProvider.Pkce(OAuthConfig c) -> new OAuthFlow(c).login(CONSOLE);
            case OAuthProvider.Device(DeviceCodeConfig d) -> new DeviceCodeFlow(d).login(CONSOLE);
        };
    }

    /** 多 profile 认证（P6-18）：{@code auth profile set|unset|list|set-key ...}。 */
    private static int profileCommand(String[] subArgs) {
        if (subArgs.length < 2) {
            usage();
            return 1;
        }
        return switch (subArgs[1]) {
            case "set" -> setProfile(subArgs);
            case "unset" -> unsetProfile(subArgs);
            case "list" -> listProfiles(subArgs);
            case "set-key" -> setProfileKey(subArgs);
            default -> {
                System.out.println("Unknown profile command: " + subArgs[1]);
                usage();
                yield 1;
            }
        };
    }

    private static int setProfile(String[] subArgs) {
        if (subArgs.length < 4) {
            usage();
            return 1;
        }
        var provider = subArgs[2];
        new AuthProfileManager().setActiveProfile(provider, subArgs[3]);
        System.out.println("Active profile for " + provider + " set to " + subArgs[3]);
        return 0;
    }

    private static int unsetProfile(String[] subArgs) {
        if (subArgs.length < 3) {
            usage();
            return 1;
        }
        var provider = subArgs[2];
        new AuthProfileManager().clearActiveProfile(provider);
        System.out.println("Active profile for " + provider + " cleared (default credential)");
        return 0;
    }

    private static int listProfiles(String[] subArgs) {
        if (subArgs.length < 3) {
            usage();
            return 1;
        }
        var provider = subArgs[2];
        var manager = new AuthProfileManager();
        System.out.println("Active profile for " + provider + ": "
            + manager.activeProfile(provider).orElse("(default)"));
        return 0;
    }

    private static int setProfileKey(String[] subArgs) {
        if (subArgs.length < 5) {
            usage();
            return 1;
        }
        var provider = subArgs[2];
        var profile = subArgs[3];
        new FileCredentialStore().storeApiKey(provider, profile, subArgs[4]);
        System.out.println("Stored API key for " + provider + " profile " + profile);
        return 0;
    }

    private static String provider(String[] subArgs) {
        return subArgs.length > 1 ? subArgs[1] : null;
    }

    private static void usage() {
        System.out.println("""
            Usage:
              pi-java auth print-api-key <provider>
              pi-java auth check <provider>
              pi-java auth oauth-login <provider>   (OAuth PKCE/device-code flow)
              pi-java auth print-bearer-token <provider>   (print "Bearer <key>" header value)
              pi-java auth profile set <provider> <name>   (activate a credential profile)
              pi-java auth profile unset <provider>
              pi-java auth profile list <provider>
              pi-java auth profile set-key <provider> <name> <key>   (store a profile key)
            Providers: anthropic | openai | google | deepseek | mistral
            OAuth: """ + OAuthProviders.names());
    }

    /** 基于控制台的 OAuth 交互实现（无控制台时 prompt 返回空串）。 */
    private static final class ConsoleInteraction implements OAuthInteraction {
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
