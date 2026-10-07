package com.pijava.coding.agent.subcommand;

import com.pijava.coding.agent.cli.Version;

/**
 * The usage text of {@code pi-java mcp} (pi {@code HELP}, {@code cli.ts:33-75}).
 *
 * <p>pi builds this with chalk; pi-java writes plain text, since nothing in it is coloured. The
 * two literals pi interpolates are pinned here instead: {@code APP_NAME} is {@code pi-java} and
 * {@code CONFIG_DIR_NAME} is {@code .pi-java}.</p>
 */
final class McpCliHelp {

    /** What every usage error ends with ({@code cli.ts:75}). */
    static final String HINT = "Use \"pi-java mcp --help\" for usage.";

    /** The {@code pi} command name pi-java's CLI answers to. */
    static final String APP_NAME = "pi-java";

    /** The project configuration directory (pi's {@code CONFIG_DIR_NAME}). */
    static final String CONFIG_DIR_NAME = ".pi-java";

    static final String HELP = """
            Usage:
              pi-java mcp add <server> [options] -- <command> [args...]
              pi-java mcp add <server> [options] --url <url>
              pi-java mcp remove <server> [-l]
              pi-java mcp list [--json]
              pi-java mcp login <server> [--timeout <seconds>]
              pi-java mcp logout <server>

            Configure and check MCP servers and sign in to OAuth servers without starting a session.
            Reads ~/.pi-java/agent/mcp.json and, in trusted projects, .pi-java/mcp.json.

            Commands:
              add <server>            Add or replace a server in mcp.json
              remove <server>         Remove a server from mcp.json
              list                    Show state, tools, and errors (exits 1 on failure)
              login <server>          Sign in through the browser
              logout <server>         Delete the stored OAuth credentials

            Options for add and remove:
              -l, --local             Use .pi-java/mcp.json in the current project instead of the global file

            Options for add:
              --url <url>             Streamable HTTP server URL (instead of a command)
              --env <KEY=VALUE>       Environment variable for a stdio server (repeatable)
              --cwd <dir>             Working directory for a stdio server
              --header <KEY=VALUE>    HTTP header (repeatable)
              --bearer-token-env-var <NAME>
                                      Send "Authorization: Bearer ${NAME}"
              --oauth-client-id <id>  Pre-registered OAuth client id
              --oauth-client-secret <secret>
                                      OAuth client secret (may be ${NAME} or !command)
              --oauth-callback-port <port>
                                      Fixed OAuth callback port
              --oauth-client-name <name>
                                      Client name sent when registering with the OAuth server
              --exposure <mode>       codemode (default), deferred, direct, or hidden
              --description <text>    What the server offers, shown in the system prompt

            Other options:
              --json                  Print the list as JSON
              --timeout <seconds>     How long login waits for the browser (default: 300)""";

    private McpCliHelp() {
    }

    /** The version the CLI reports to MCP servers, for callers that need it here. */
    static String clientVersion() {
        return Version.VERSION;
    }
}
