package com.pijava.coding.agent.cli;

import java.util.List;

/**
 * Parsed CLI arguments, aligned with pi's {@code Args} interface (flat record,
 * Phase 3 design §9.2).
 *
 * @param provider              provider name (default: google)
 * @param model                 model pattern/ID (supports "provider/id" and ":thinking")
 * @param apiKey                API key (default: environment variable)
 * @param systemPrompt          system prompt (default: coding assistant)
 * @param appendSystemPrompt    extra system prompt fragments (repeatable)
 * @param thinking              raw thinking level string ("off".."max"), see §9.3
 * @param continue_             resume the most recent session (-c)
 * @param resume                pick a session to resume (-r)
 * @param help                  print help (-h/--help)
 * @param version               print version (-v/--version)
 * @param mode                  output mode: "text" (default) | "json" | "rpc"
 * @param name                  session display name
 * @param noSession             ephemeral session (do not save)
 * @param session               session file or partial UUID
 * @param sessionId             exact project session ID
 * @param fork                  fork from an existing session
 * @param sessionDir            session storage directory (Phase 4)
 * @param models                model patterns for Ctrl+P cycling
 * @param tools                 tool allowlist (comma separated)
 * @param excludeTools          tool denylist (comma separated)
 * @param noTools               disable all tools
 * @param noBuiltinTools        disable built-in tools only
 * @param extensions            extension sources (repeatable)
 * @param noExtensions          disable extension discovery
 * @param skills                skills to load (repeatable)
 * @param noSkills              disable skills discovery
 * @param promptTemplates       prompt templates to load (repeatable)
 * @param noPromptTemplates     disable prompt template discovery
 * @param themes                theme files to load (Phase 6)
 * @param noThemes              disable theme discovery
 * @param noContextFiles        disable AGENTS.md/CLAUDE.md discovery
 * @param print                 non-interactive print mode (-p)
 * @param export                export session file to HTML and exit (Phase 6)
 * @param listModels            null = not passed, "" = bare, non-empty = search term
 * @param offline               disable startup network operations
 * @param tuiMode               "regular" (default) | "fullscreen"
 * @param verbose               force verbose startup
 * @param projectTrustOverride  --approve=true / --no-approve=false / null=unset
 * @param messages              positional prompt messages
 * @param fileArgs              @file arguments joined into the initial message
 * @param unmatched             unknown flags + leftover tokens (extension flags)
 * @param diagnostics           parse warnings/errors (aligned with pi Args.diagnostics)
 */
public record Args(
        String provider,
        String model,
        String apiKey,
        String systemPrompt,
        List<String> appendSystemPrompt,
        String thinking,
        boolean continue_,
        boolean resume,
        boolean help,
        boolean version,
        String mode,
        String name,
        boolean noSession,
        String session,
        String sessionId,
        String fork,
        String sessionDir,
        List<String> models,
        List<String> tools,
        List<String> excludeTools,
        boolean noTools,
        boolean noBuiltinTools,
        List<String> extensions,
        boolean noExtensions,
        List<String> skills,
        boolean noSkills,
        List<String> promptTemplates,
        boolean noPromptTemplates,
        List<String> themes,
        boolean noThemes,
        boolean noContextFiles,
        boolean print,
        String export,
        String listModels,
        boolean offline,
        String tuiMode,
        boolean verbose,
        Boolean projectTrustOverride,
        List<String> messages,
        List<String> fileArgs,
        List<String> unmatched,
        List<ArgDiagnostic> diagnostics) {

    /** Parse diagnostic: type = "warning" | "error" (aligned with pi Args.diagnostics). */
    public record ArgDiagnostic(String type, String message) {}

    /** Compact constructor: defensive copies and null-safe defaults. */
    public Args {
        appendSystemPrompt = copy(appendSystemPrompt);
        models = copy(models);
        tools = copy(tools);
        excludeTools = copy(excludeTools);
        extensions = copy(extensions);
        skills = copy(skills);
        promptTemplates = copy(promptTemplates);
        themes = copy(themes);
        messages = copy(messages);
        fileArgs = copy(fileArgs);
        unmatched = copy(unmatched);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    private static List<String> copy(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
