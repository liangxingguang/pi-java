package com.pijava.coding.agent.subcommand;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.mcp.McpJson;
import com.pijava.mcp.config.McpConfig;
import com.pijava.mcp.config.McpConfigValidation;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerConfigs;

/**
 * {@code pi-java mcp add} and {@code pi-java mcp remove} (pi {@code cli.ts:277-432}).
 */
final class McpAddRemoveCommand {

    private McpAddRemoveCommand() {
    }
    // ---------------------------------------------------------------------- add

    /** {@code cli.ts:277-394}. */
    static int add(String[] args, Path projectConfig, McpCommand.Options options) {
        var log = options.log();
        var error = options.error();
        var usage = "Usage: " + McpCliHelp.APP_NAME
                + " mcp add <server> [options] (--url <url> | -- <command> [args...])\n"
                + McpCliHelp.HINT;
        var known = Map.ofEntries(
                Map.entry("local", McpCliOptions.Kind.FLAG),
                Map.entry("url", McpCliOptions.Kind.VALUE),
                Map.entry("env", McpCliOptions.Kind.LIST),
                Map.entry("cwd", McpCliOptions.Kind.VALUE),
                Map.entry("header", McpCliOptions.Kind.LIST),
                Map.entry("bearer-token-env-var", McpCliOptions.Kind.VALUE),
                Map.entry("oauth-client-id", McpCliOptions.Kind.VALUE),
                Map.entry("oauth-client-secret", McpCliOptions.Kind.VALUE),
                Map.entry("oauth-callback-port", McpCliOptions.Kind.VALUE),
                Map.entry("oauth-client-name", McpCliOptions.Kind.VALUE),
                Map.entry("exposure", McpCliOptions.Kind.VALUE),
                Map.entry("description", McpCliOptions.Kind.VALUE));
        // Two positionals end the options, so a command's own flags pass through.
        var parsed = McpCliOptions.parse(args, known, error, 2);
        if (parsed == null) {
            return 1;
        }
        var positional = parsed.positional();
        String name = positional.isEmpty() ? null : positional.get(0);
        var command = positional.size() > 1 ? positional.subList(1, positional.size()) : List.<String>of();
        var url = parsed.value("url");
        // Exactly one of url and command: they cannot both be present, nor both be absent.
        if (name == null || (url == null) == command.isEmpty()) {
            error.accept(usage);
            return 1;
        }
        List<String> httpOnly = List.of("header", "bearer-token-env-var", "oauth-client-id",
                "oauth-client-secret", "oauth-callback-port", "oauth-client-name");
        List<String> stdioOnly = List.of("env", "cwd");
        var misplaced = (url == null ? httpOnly : stdioOnly).stream()
                .filter(option -> parsed.values().containsKey(option) || parsed.lists().containsKey(option))
                .findFirst().orElse(null);
        if (misplaced != null) {
            error.accept("--" + misplaced + " only applies to "
                    + (url == null ? "HTTP servers (--url)" : "stdio servers") + ".");
            return 1;
        }
        var config = McpJson.mapper().createObjectNode();
        if (url != null) {
            var headers = McpCliOptions.parsePairs("header", parsed.lists().get("header"), error);
            if (headers == null) {
                return 1;
            }
            var headerNode = McpJson.mapper().createObjectNode();
            headers.forEach(headerNode::put);
            var bearer = parsed.value("bearer-token-env-var");
            if (bearer != null) {
                // The literal reference is written to the file; it is resolved when connecting.
                headerNode.put("Authorization", "Bearer ${" + bearer + "}");
            }
            var oauth = McpJson.mapper().createObjectNode();
            if (parsed.value("oauth-client-id") != null) {
                oauth.put("clientId", parsed.value("oauth-client-id"));
            }
            if (parsed.value("oauth-client-secret") != null) {
                oauth.put("clientSecret", parsed.value("oauth-client-secret"));
            }
            if (parsed.value("oauth-callback-port") != null) {
                var port = McpCommandSupport.numberOrNull(parsed.value("oauth-callback-port"));
                oauth.set("callbackPort", McpCommandSupport.numberNode(port));
            }
            if (parsed.value("oauth-client-name") != null) {
                oauth.put("clientName", parsed.value("oauth-client-name"));
            }
            // Key order is the file's key order, so the object is assembled the way pi writes it.
            config.put("url", url);
            if (!headerNode.isEmpty()) {
                config.set("headers", headerNode);
            }
            if (!oauth.isEmpty()) {
                config.set("oauth", oauth);
            }
        } else {
            var env = McpCliOptions.parsePairs("env", parsed.lists().get("env"), error);
            if (env == null) {
                return 1;
            }
            config.put("command", command.get(0));
            if (command.size() > 1) {
                var argsNode = config.putArray("args");
                command.subList(1, command.size()).forEach(argsNode::add);
            }
            if (!env.isEmpty()) {
                var envNode = config.putObject("env");
                env.forEach(envNode::put);
            }
            if (parsed.value("cwd") != null) {
                config.put("cwd", parsed.value("cwd"));
            }
        }
        if (parsed.value("exposure") != null) {
            config.put("exposure", parsed.value("exposure"));
        }
        if (parsed.value("description") != null) {
            config.put("description", parsed.value("description"));
        }
        var validated = McpServerConfigs.validate(name, config);
        if (validated instanceof McpConfigValidation.Invalid invalid) {
            error.accept(invalid.message());
            return 1;
        }
        var serverConfig = ((McpConfigValidation.Valid) validated).config();

        var project = parsed.has("local");
        var path = project ? projectConfig : options.agentDir().resolve("mcp.json");
        var scope = project ? "project" : "global";
        boolean replaced;
        try {
            replaced = McpConfig.add(path, name, serverConfig);
        } catch (RuntimeException addError) {
            error.accept("Could not update " + path + ": " + McpCommandSupport.errorMessage(addError));
            return 1;
        }
        log.accept((replaced ? "Replaced" : "Added") + " " + scope + " MCP server \"" + name
                + "\" in " + path + ".");
        if (project && !McpCommandSupport.projectTrusted(options)) {
            log.accept("The project is not trusted, so " + path + " is ignored until you start "
                    + McpCliHelp.APP_NAME + " in the project and trust it.");
        }
        // HTTP servers without an Authorization header may use OAuth.
        var mayNeedSignIn = serverConfig instanceof McpServerConfig.Http http
                && !McpCommandSupport.hasAuthorizationHeader(http.headers());
        log.accept("Check it with: " + McpCliHelp.APP_NAME + " mcp list"
                + (mayNeedSignIn ? ". If it requires sign-in: " + McpCliHelp.APP_NAME
                        + " mcp login " + name : ""));
        return 0;
    }

    // ------------------------------------------------------------------- remove

    /** {@code cli.ts:396-432}. */
    static int remove(String[] args, Path projectConfig, McpCommand.Options options) {
        var log = options.log();
        var error = options.error();
        var parsed = McpCliOptions.parse(args,
                Map.of("local", McpCliOptions.Kind.FLAG), error, Integer.MAX_VALUE);
        if (parsed == null) {
            return 1;
        }
        var positional = parsed.positional();
        String name = positional.isEmpty() ? null : positional.get(0);
        if (name == null || positional.size() > 1) {
            error.accept("Usage: " + McpCliHelp.APP_NAME + " mcp remove <server> [-l]\n"
                    + McpCliHelp.HINT);
            return 1;
        }
        var project = parsed.has("local");
        var path = project ? projectConfig : options.agentDir().resolve("mcp.json");
        var scope = project ? "project" : "global";
        boolean removed;
        try {
            removed = McpConfig.remove(path, name);
        } catch (RuntimeException removeError) {
            error.accept("Could not update " + path + ": " + McpCommandSupport.errorMessage(removeError));
            return 1;
        }
        if (removed) {
            log.accept("Removed " + scope + " MCP server \"" + name + "\" from " + path + ".");
            return 0;
        }
        var other = McpConfig.load(options.agentDir(), options.cwd(), true).servers().stream()
                .filter(server -> server.name().equals(name)
                        && !scope.equals(McpCommandSupport.scopeName(server)))
                .findFirst().orElse(null);
        error.accept("No " + scope + " MCP server named \"" + name + "\" in " + path + "."
                + (other == null ? "" : " It is defined in " + other.source()
                        + (McpCommandSupport.scopeName(other).equals("project") ? "; use --local" : "; omit --local")
                        + "."));
        return 1;
    }

}
