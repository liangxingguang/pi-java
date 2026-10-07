package com.pijava.coding.agent.subcommand;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;
import com.pijava.mcp.config.LoadedMcpConfig;
import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpServerEntry;
import com.pijava.mcp.runtime.McpServerConnection;
import com.pijava.mcp.runtime.McpOAuthCredentialStore;
import com.pijava.mcp.runtime.McpSignIn;
import com.pijava.mcp.runtime.McpSignInCancelledError;
import com.pijava.mcp.runtime.McpSignInPrompt;

/**
 * {@code pi-java mcp}: add, remove, and check MCP servers and sign in to them outside a session
 * (pi {@code cli.ts}).
 *
 * <p>Agents run it through bash to configure servers, verify an {@code mcp.json} they wrote, and
 * start an OAuth sign-in; the user only approves access in the browser. Running sessions pick up
 * new credentials on their next turn.</p>
 */
public final class McpCommand {

    /** How long {@code login} waits for the browser ({@code cli.ts:77}). */
    static final int DEFAULT_LOGIN_TIMEOUT_SECONDS = 300;

    /** The subcommand name {@link SubcommandHandler} dispatches. */
    public static final String NAME = "mcp";

    /**
     * The command's environment (pi {@code McpCommandOptions}, {@code cli.ts:79-89}).
     *
     * @param cwd the directory the command runs in
     * @param agentDir the agent directory holding {@code mcp.json}
     * @param credentials where the OAuth state lives; a store in {@code agentDir} when {@code null}
     * @param openUrl how to open the browser; the platform browser when {@code null}
     * @param log where normal output goes
     * @param error where failures go
     * @param terminal whether a terminal is attached, so a redirect URL can be pasted
     * @param input where a pasted redirect URL is read from
     * @param clientName the MCP client name
     * @param clientVersion the MCP client version
     */
    public record Options(
            Path cwd,
            Path agentDir,
            McpOAuthCredentialStore credentials,
            @Nullable Consumer<String> openUrl,
            Consumer<String> log,
            Consumer<String> error,
            boolean terminal,
            InputStream input,
            String clientName,
            String clientVersion) {

        /** The defaults a real invocation runs with, writing to stdout and stderr. */
        public static Options of(Path cwd, Path agentDir, String clientName, String clientVersion) {
            return new Options(cwd, agentDir, new McpOAuthCredentialStore(agentDir), null,
                    System.out::println, System.err::println,
                    System.console() != null, System.in, clientName, clientVersion);
        }
    }

    private McpCommand() {
    }

    /**
     * Run {@code pi-java mcp <args>} (pi {@code runMcpCommand}, {@code cli.ts:185-260}).
     *
     * @param args the arguments after {@code mcp}
     * @param options the command's environment
     * @return the exit code
     */
    public static int run(String[] args, Options options) {
        var log = options.log();
        var error = options.error();
        var command = args.length == 0 ? null : args[0];
        var rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (command == null || "help".equals(command)
                || List.of(args).contains("--help") || List.of(args).contains("-h")) {
            log.accept(McpCliHelp.HELP);
            return 0;
        }
        var projectConfig = options.cwd().resolve(McpCliHelp.CONFIG_DIR_NAME).resolve("mcp.json");
        if ("add".equals(command) || "remove".equals(command)) {
            return "add".equals(command)
                    ? McpAddRemoveCommand.add(rest, projectConfig, options)
                    : McpAddRemoveCommand.remove(rest, projectConfig, options);
        }

        var projectTrusted = McpCommandSupport.projectTrusted(options);
        var loaded = McpConfig.load(options.agentDir(), options.cwd(), projectTrusted);
        var untrustedNote = !projectTrusted && Files.exists(projectConfig)
                ? projectConfig + " is ignored because the project is not trusted. Start "
                        + McpCliHelp.APP_NAME + " in the project to trust it."
                : null;
        var credentials = options.credentials();
        return switch (command) {
            case "list" -> list(rest, loaded, untrustedNote, options, credentials);
            case "login", "logout" -> loginOrLogout(command, rest, loaded, untrustedNote, options,
                    credentials);
            default -> {
                error.accept("Unknown mcp command \"" + command + "\".\n" + McpCliHelp.HINT);
                yield 1;
            }
        };
    }

    // -------------------------------------------------------------- list / login

    private static int list(String[] args, LoadedMcpConfig loaded, @Nullable String untrustedNote,
                            Options options, McpOAuthCredentialStore credentials) {
        var error = options.error();
        var parsed = McpCliOptions.parse(args, Map.of("json", McpCliOptions.Kind.FLAG), error,
                Integer.MAX_VALUE);
        if (parsed == null) {
            return 1;
        }
        if (!parsed.positional().isEmpty()) {
            error.accept("Usage: " + McpCliHelp.APP_NAME + " mcp list [--json]\n" + McpCliHelp.HINT);
            return 1;
        }
        return McpListCommand.run(loaded, parsed.has("json"), untrustedNote, options, credentials,
                options.log());
    }

    /** {@code cli.ts:218-255}. */
    private static int loginOrLogout(String command, String[] args, LoadedMcpConfig loaded,
                                     @Nullable String untrustedNote, Options options,
                                     McpOAuthCredentialStore credentials) {
        var log = options.log();
        var error = options.error();
        var known = "login".equals(command)
                ? Map.of("timeout", McpCliOptions.Kind.VALUE)
                : Map.<String, McpCliOptions.Kind>of();
        var parsed = McpCliOptions.parse(args, known, error, Integer.MAX_VALUE);
        if (parsed == null) {
            return 1;
        }
        var positional = parsed.positional();
        String name = positional.isEmpty() ? null : positional.get(0);
        if (name == null || positional.size() > 1) {
            error.accept("Usage: " + McpCliHelp.APP_NAME + " mcp " + command + " <server>\n"
                    + McpCliHelp.HINT);
            return 1;
        }
        var entry = loaded.servers().stream()
                .filter(server -> server.name().equals(name)).findFirst().orElse(null);
        if (entry == null) {
            var configured = loaded.servers().stream().map(McpServerEntry::name).toList();
            error.accept("No MCP server named \"" + name + "\"."
                    + (untrustedNote == null ? "" : " " + untrustedNote) + " Configured: "
                    + (configured.isEmpty() ? "none" : String.join(", ", configured)) + ".");
            return 1;
        }
        var connection = McpCommandSupport.connection(entry, options, credentials);
        var url = connection.oauthUrl();
        if (url == null) {
            error.accept("MCP server \"" + name + "\" does not use OAuth. Only HTTP servers"
                    + " without an Authorization header do.");
            return 1;
        }
        if ("logout".equals(command)) {
            var removed = credentials.remove(name, url);
            log.accept(removed ? "Signed out of MCP server \"" + name + "\"."
                    : "No stored credentials for MCP server \"" + name + "\".");
            return 0;
        }
        var timeoutMs = DEFAULT_LOGIN_TIMEOUT_SECONDS * 1000L;
        if (parsed.value("timeout") != null) {
            var given = McpCommandSupport.numberOrNull(parsed.value("timeout"));
            if (given == null || given <= 0) {
                error.accept("--timeout must be a positive number of seconds.");
                return 1;
            }
            timeoutMs = (long) (given * 1000);
        }
        try {
            return login(entry, connection, url, timeoutMs, options, credentials);
        } finally {
            connection.close().join();
        }
    }

    /** {@code cli.ts:521-577}. */
    private static int login(McpServerEntry entry, McpServerConnection connection, String url,
                             long timeoutMs, Options options, McpOAuthCredentialStore credentials) {
        var log = options.log();
        var error = options.error();
        var name = entry.name();
        // Connecting first answers whether a sign-in is needed and records the server's challenge.
        try {
            connection.getClient().join();
            log.accept("Already signed in to MCP server \"" + name + "\" ("
                    + connection.tools().size() + " tools).");
            return 0;
        } catch (RuntimeException | Error failure) {
            if (!"needs-auth".equals(connection.state().wire())) {
                error.accept("MCP server \"" + name + "\" failed to connect: "
                        + (connection.error() == null ? "unknown error" : connection.error()));
                return 1;
            }
        }
        var openUrl = options.openUrl() == null
                ? (Consumer<String>) value -> BrowserLauncher.open(value, log) : options.openUrl();
        var interactive = McpCommandSupport.interactive(options);
        try {
            McpSignIn.signIn(new McpSignIn.Options(
                    url, credentials.forServer(name, url), connection.oauthSettings(),
                    connection.challenge(),
                    new McpSignInPrompt() {
                        @Override
                        public void showAuthorizationUrl(java.net.URI authorizationUrl) {
                            log.accept("Sign in to MCP server \"" + name + "\" in your browser:\n"
                                    + authorizationUrl);
                            openUrl.accept(authorizationUrl.toString());
                        }

                        @Override
                        public @Nullable String promptForRedirectUrl(AbortSignal signal) {
                            return McpRedirectPrompt.waitForRedirectUrl(signal,
                                    new McpRedirectPrompt.Options(timeoutMs, interactive,
                                            options.input(), options.error()));
                        }
                    },
                    options.clientName()));
        } catch (McpSignInCancelledError cancelled) {
            error.accept("Sign-in to MCP server \"" + name + "\" was cancelled or not completed"
                    + " within " + Math.round(timeoutMs / 1000.0) + " seconds.");
            return 1;
        } catch (Exception signInError) {
            error.accept("Sign-in to MCP server \"" + name + "\" failed: "
                    + McpCommandSupport.errorMessage(signInError));
            return 1;
        }
        connection.clearChallenge();
        try {
            connection.reconnect().join();
        } catch (RuntimeException | Error connectError) {
            error.accept("Signed in, but " + McpCommandSupport.errorMessage(connectError));
            return 1;
        }
        log.accept("Signed in to MCP server \"" + name + "\" (" + connection.tools().size()
                + " tools).");
        return 0;
    }

}
