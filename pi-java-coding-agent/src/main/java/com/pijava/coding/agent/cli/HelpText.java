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

    /** pi printHelp 的静态主体（扩展段在其后追加）。 */
    private static final String BASE = """
            pi-java - AI coding assistant with read, bash, edit, write tools

            Usage:
              pi-java [options] [@files...] [messages...]

            Commands:
              pi-java install <source> [-l]     Install extension source (Phase 6)
              pi-java remove <source> [-l]      Remove extension source (Phase 6)
              pi-java uninstall <source> [-l]   Alias for remove (Phase 6)
              pi-java update [source|self|pi]   Update pi / extensions (Phase 6)
              pi-java list                      List installed extensions (Phase 6)
              pi-java config [-l]               Open TUI resource switches (Phase 6)
              pi-java auth <command>            Print credentials or check provider readiness

            Options:
              --provider <name>              Provider name (default: google)
              --model <pattern>              Model pattern or ID (supports "provider/id" and ":<thinking>")
              --api-key <key>                API key (defaults to env vars)
              --base-url <url>              API endpoint override (OpenAI/Anthropic-compatible relay)
              --system-prompt <text>         System prompt (default: coding assistant prompt)
              --append-system-prompt <text>  Append text or file contents to the system prompt
              --mode <mode>                  Output mode: text (default), json, rpc (json/rpc in Phase 6)
              --thinking <level>             off|minimal|low|medium|high|xhigh|max
              --continue, -c                 Continue the most recent session
              --resume, -r                   Pick a session to resume
              --session <file|partial-id>    Specify session file or partial UUID
              --session-id <id>              Exact project session ID
              --fork <session>               Fork from an existing session
              --session-dir <dir>            Session storage directory (Phase 4)
              --no-session                   Do not save a session
              --name, -n <name>              Session display name
              --models <list>                Ctrl+P cycling model list (comma separated)
              --tools, -t <tools>            Comma-separated allowlist of tool names or patterns (*) to enable
                                             Keeps MCP tools unless an entry starts with mcp__
                                             Only +name/-name entries add to or remove from the defaults
              --exclude-tools, -xt <tools>   Comma-separated denylist of tool names or patterns (*) to disable
                                             Applies to all tools, MCP tools included
              --no-tools, -nt                Disable all tools by default (built-in and extension)
              --no-builtin-tools, -nbt       Disable built-in tools by default but keep extension/custom tools enabled
              --approve, -a                  Trust project local files for this run
              --no-approve, -na              Ignore project local files for this run
              --extension, -e <source>       Load an extension JAR (repeatable; explicit paths still load with -ne)
              --no-extensions, -ne           Disable extension discovery
              --skill <name>                 Load skill (repeatable; Phase 6)
              --no-skills, -ns               Disable skills discovery
              --prompt-template <name>       Load prompt template (repeatable; Phase 6)
              --no-prompt-templates, -np     Disable prompt template discovery
              --theme <path>                 Load a theme file or directory (can be used multiple times)
              --no-themes                    Disable theme discovery and loading
              --use-theme <name[/name]>      Set the initial interactive theme for this run
              --no-context-files, -nc        Disable AGENTS.md/CLAUDE.md discovery
              --print, -p                    Non-interactive print mode
              --export <path>                Export session to HTML and exit (Phase 6)
              --list-models [search]         List models (optional fuzzy search)
              --tui-mode <mode>              fullscreen (default) or regular
              --offline                      Disable startup network operations
              --verbose                      Force verbose startup
              --debug                        Enable debug logging (DEBUG level)
              --trace-payloads               Record LLM request/response payloads to the trace file
              --help, -h                     Show help
              --version, -v                  Show version

            @file arguments are added to the initial message.
            """;
}
