package com.pijava.coding.agent.cli;

/**
 * CLI help text, aligned with pi's {@code printHelp} (Phase 3 design §9.4).
 */
public final class HelpText {

    private HelpText() {}

    /** Render the full help text. */
    public static String text() {
        return text(java.util.List.of());
    }

    /**
     * 批 16（T1e，docs/27）：带扩展 flag 段的完整帮助（pi {@code printHelp}
     * args.ts:273-283 对位）——无扩展 flag ⇒ 段落整体消失（逐字）。
     */
    public static String text(
            java.util.List<com.pijava.coding.agent.extension.ExtensionFlag> extensionFlags) {
        var base = BASE;
        if (extensionFlags == null || extensionFlags.isEmpty()) {
            return base;
        }
        var section = new StringBuilder("\nExtension CLI Flags:\n");
        for (var flag : extensionFlags) {
            // pi 逐字：--name（string 型补 " <value>"）padEnd(30)＋description，
            // 缺省回落 Registered by <扩展名>（pi 的 extensionPath 对位）。
            var value = flag.type()
                == com.pijava.coding.agent.extension.ExtensionFlag.Type.STRING
                ? " <value>" : "";
            var description = flag.description() != null ? flag.description()
                : "Registered by " + flag.extensionName();
            var label = "  --" + flag.name() + value;
            section.append(label);
            if (label.length() < 30) {
                section.append(" ".repeat(30 - label.length()));
            }
            section.append(description).append("\n");
        }
        return base + section;
    }

    /** W10：pi args.ts:465 行逐字（源行超 120 列 ⇒ 拼接，输出仍为单行）。 */
    private static final String SELF_UPDATE_ENV_LINE =
        "  PI_SELF_UPDATE_COMMAND           - Command pi-java update runs to update pi-java itself "
            + "({packageName}, {version}, {installSpec})\n";

    /** pi printHelp 的静态主体（扩展段在其后追加）。 */
    private static final String BASE = """
            pi-java - AI coding assistant with read, bash, edit, write tools

            Usage:
              pi-java [options] [--] [@files...] [messages...]

            Commands:
              pi-java install <source> [-l]     Install extension source and add to settings
              pi-java remove <source> [-l]      Remove extension source from settings
              pi-java uninstall <source> [-l]   Alias for remove
              pi-java update [source|self|pi]   Update pi-java, extensions, or model catalogs
              pi-java list                      List installed extensions from settings
              pi-java config [-l]               Open TUI to enable/disable package resources (Tab switches scope)
              pi-java auth <command>            Print credentials or check provider readiness
              pi-java mcp <command>             Manage MCP servers
              pi-java <command> --help          Show help for install/remove/uninstall/update/list/config/auth/mcp

            Options:
              --provider <name>              Provider to search for --model (requires --model)
              --model <pattern>              Model pattern or ID (supports "provider/id" and optional ":<thinking>")
              --api-key <key>                API key (defaults to env vars)
              --base-url <url>              API endpoint override (OpenAI/Anthropic-compatible relay)
              --system-prompt <text>         System prompt (default: coding assistant prompt)
              --append-system-prompt <text>  Append text or file contents to the system prompt (can be used multiple times)
              --mode <mode>                  Output mode: text (default), json, or rpc
              --thinking <level>             Set thinking level: off, minimal, low, medium, high, xhigh, max
              --continue, -c                 Continue the most recent session for the current directory
              --resume, -r                   Pick a session to resume
              --session <path|id>            Open a session by file path or full/partial ID
              --session-id <id>              Use the current directory's existing session with the ID, creating it otherwise
              --fork <path|id>               Fork a session by file path or full/partial ID into the current directory
              --session-dir <dir>            Directory for session storage and lookup
              --no-session                   Don't save session (ephemeral)
              --name, -n <name>              Set session display name
              --models <patterns>            Comma-separated model patterns for Ctrl+P cycling
                                             Supports globs (anthropic/*, *sonnet*) and fuzzy matching
              --tools, -t <tools>            Comma-separated allowlist of tool names or patterns (*) to enable
                                             Keeps MCP tools unless an entry starts with mcp__
                                             Only +name/-name entries add to or remove from the defaults
              --exclude-tools, -xt <tools>   Comma-separated denylist of tool names or patterns (*) to disable
                                             Applies to all tools, MCP tools included
              --no-tools, -nt                Disable all tools by default (built-in and extension)
              --no-builtin-tools, -nbt       Disable built-in tools by default but keep extension/custom tools enabled
              --approve, -a                  Trust project-local files for this run
              --no-approve, -na              Ignore project-local files for this run
              --extension, -e <source>       Load an extension JAR or builtin:<name> (repeatable; explicit paths still load with -ne)
              --no-extensions, -ne           Disable extension discovery and built-in extensions (explicit -e paths still work)
              --no-mcp                       Disable built-in MCP support: no servers connect and no MCP tools
              --skill <name>                 Load skill (repeatable; Phase 6)
              --no-skills, -ns               Disable skills discovery
              --prompt-template <name>       Load prompt template (repeatable; Phase 6)
              --no-prompt-templates, -np     Disable prompt template discovery
              --theme <path>                 Load a theme file or directory (can be used multiple times)
              --no-themes                    Disable theme discovery and loading
              --use-theme <name[/name]>      Set the initial interactive theme for this run
              --no-context-files, -nc        Disable AGENTS.md/CLAUDE.md discovery
              --print, -p                    Non-interactive mode: process prompt and exit
              --export <file> [output]       Export session file to HTML and exit
              --list-models [search]         List available models (with optional fuzzy search)
              --tui-mode <mode>              TUI mode: fullscreen (default) or regular
              --offline                      Disable startup network operations (same as PI_OFFLINE=1)
              --verbose                      Force verbose startup (overrides quietStartup setting)
              --debug                        Enable debug logging (DEBUG level)
              --trace-payloads               Record LLM request/response payloads to the trace file
              --help, -h                     Show help
              --version, -v                  Show version

            Environment Variables:
              PI_JAVA_CODING_AGENT_DIR         - Config directory (default: ~/.pi-java/agent)
              PI_OFFLINE                       - Disable startup network operations when set to 1/true/yes
            """ + SELF_UPDATE_ENV_LINE + """
              PI_TELEMETRY                     - Override install telemetry when set to 1/true/yes or 0/false/no
              PI_SHARE_VIEWER_URL              - Base URL for /share command (default: https://pi.dev/session/)

            @file arguments are added to the initial message.
            """;
}
