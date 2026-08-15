package com.pijava.coding.agent.cli;

/**
 * CLI help text, aligned with pi's {@code printHelp} (Phase 3 design §9.4).
 */
public final class HelpText {

    private HelpText() {}

    /** Render the full help text. */
    public static String text() {
        return """
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
              --tools, -t <list>             Tool allowlist (comma separated)
              --exclude-tools, -xt <list>    Tool denylist (comma separated)
              --no-tools, -nt                Disable all tools
              --no-builtin-tools, -nbt       Disable built-in tools only
              --approve, -a                  Trust project local files for this run
              --no-approve, -na              Ignore project local files for this run
              --extension, -e <source>       Load extension (repeatable; Phase 6)
              --no-extensions, -ne           Disable extension discovery
              --skill <name>                 Load skill (repeatable; Phase 6)
              --no-skills, -ns               Disable skills discovery
              --prompt-template <name>       Load prompt template (repeatable; Phase 6)
              --no-prompt-templates, -np     Disable prompt template discovery
              --theme <file>                 Load theme file (Phase 3: dark|light only)
              --no-themes                    Disable theme discovery
              --no-context-files, -nc        Disable AGENTS.md/CLAUDE.md discovery
              --print, -p                    Non-interactive print mode
              --export <path>                Export session to HTML and exit (Phase 6)
              --list-models [search]         List models (optional fuzzy search)
              --tui-mode <mode>              fullscreen (default) or regular
              --offline                      Disable startup network operations
              --verbose                      Force verbose startup
              --debug                        Enable debug logging (DEBUG level)
              --help, -h                     Show help
              --version, -v                  Show version

            @file arguments are added to the initial message.
            """;
    }
}
