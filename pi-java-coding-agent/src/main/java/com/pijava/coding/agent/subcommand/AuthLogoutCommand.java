package com.pijava.coding.agent.subcommand;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.auth.OAuthCredentialStore;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.WebUrl;
import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.ProviderMatching;

/**
 * {@code pi-java auth logout <provider>} (pi {@code cli/auth-logout.ts}, anchor
 * {@code a00a5048d}).
 *
 * <p>Removes the credential stored by {@code auth login} or {@code /login}; environment
 * variables and models.json config stay, as with {@code /logout}.</p>
 */
public final class AuthLogoutCommand {

    private static final String APP_NAME = "pi-java";
    static final String USAGE = APP_NAME + " auth logout <provider>";

    /** Dependencies of {@code auth logout}, injected so tests can capture output. */
    public record Options(AuthTerminal terminal, ProviderRegistry providers,
                          FileCredentialStore apiKeys, OAuthCredentialStore oauthCredentials) {}

    private AuthLogoutCommand() {}

    /** Production dependencies. */
    public static Options production() {
        return new Options(new ProcessAuthTerminal(),
            com.pijava.coding.agent.core.DefaultProviders.defaultProviders(),
            new FileCredentialStore(), new OAuthCredentialStore());
    }

    /** Run {@code auth logout <provider>} and return the process exit code. */
    public static int run(String[] args, Options options) {
        var positional = filterOffline(args);
        if (positional.length != 1 || positional[0].startsWith("-")) {
            options.terminal().error("Usage: " + USAGE);
            return 1;
        }
        var providerRef = positional[0];

        List<StoredCredential> stored;
        try {
            stored = listStored(options);
        } catch (RuntimeException e) {
            options.terminal().error("Could not read stored credentials: " + messageOf(e));
            return 1;
        }

        var provider = ProviderMatching.findMatching(
            options.providers().listAll(), providerRef).stream().findFirst().orElse(null);
        // Providers registered by extensions are not loaded here, so their stored
        // credentials match by id alone.
        var id = provider == null ? providerRef : provider.name();
        var credential = stored.stream().filter(c -> c.providerId().equals(id))
            .findFirst().orElse(null);
        if (provider == null && credential == null) {
            options.terminal().error("Unknown provider \""
                + WebUrl.replaceControlCharacters(providerRef) + "\".");
            var ids = stored.stream().map(StoredCredential::providerId).sorted().toList();
            options.terminal().error("Stored credentials: "
                + (ids.isEmpty() ? "none" : String.join(", ", ids)));
            return 1;
        }
        var providerName = provider == null ? providerRef : provider.displayName();
        if (credential == null) {
            options.terminal().write("No stored credentials for " + providerName
                + ". Environment variables and models.json config are unchanged.");
            return 0;
        }

        try {
            options.oauthCredentials().delete(id);
            options.apiKeys().deleteAllForProvider(id);
        } catch (RuntimeException e) {
            options.terminal().error("Logout failed: " + messageOf(e));
            return 1;
        }
        options.terminal().write(credential.type() == LoginAuthType.OAUTH
            ? "Logged out of " + providerName + "."
            : "Removed stored API key for " + providerName
                + ". Environment variables and models.json config are unchanged.");
        return 0;
    }

    /** One stored credential, as pi's {@code CredentialInfo}. */
    record StoredCredential(String providerId, LoginAuthType type) {}

    /** List {@code auth-oauth.json} and {@code auth.json}; an OAuth entry wins per provider. */
    static List<StoredCredential> listStored(Options options) {
        var entries = new ArrayList<StoredCredential>();
        var oauthIds = options.oauthCredentials().storedProviders();
        oauthIds.forEach(providerId -> entries.add(
            new StoredCredential(providerId, LoginAuthType.OAUTH)));
        options.apiKeys().storedProviders().stream()
            .filter(providerId -> !oauthIds.contains(providerId))
            .forEach(providerId -> entries.add(
                new StoredCredential(providerId, LoginAuthType.API_KEY)));
        return entries;
    }

    private static String[] filterOffline(String[] args) {
        return java.util.Arrays.stream(args).filter(arg -> !"--offline".equals(arg)).toArray(String[]::new);
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
