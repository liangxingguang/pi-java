package com.pijava.coding.agent.subcommand;

import java.util.Optional;

import com.pijava.ai.auth.EnvApiKeyResolver;
import com.pijava.ai.auth.FileCredentialStore;

/**
 * {@code pi-java auth} subcommand (Phase 3 design §9.4).
 *
 * <p>Phase 3 supports {@code print-api-key <provider>} and
 * {@code check <provider>}; {@code print-bearer-token} arrives Phase 6.</p>
 */
public final class AuthCommand {

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
            case "print-bearer-token" -> {
                System.out.println(
                    "error: print-bearer-token is not implemented yet (Phase 6)");
                yield 2;
            }
            default -> {
                System.out.println("Unknown auth command: " + subArgs[0]);
                usage();
                yield 1;
            }
        };
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

    private static Optional<String> resolve(String provider) {
        var env = new EnvApiKeyResolver().resolveApiKey(provider);
        return env.isPresent() ? env : new FileCredentialStore().resolveApiKey(provider);
    }

    private static String provider(String[] subArgs) {
        return subArgs.length > 1 ? subArgs[1] : null;
    }

    private static void usage() {
        System.out.println("""
            Usage:
              pi-java auth print-api-key <provider>
              pi-java auth check <provider>
            Providers: anthropic | openai | google | deepseek | mistral""");
    }
}
