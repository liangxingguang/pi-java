package com.pijava.coding.agent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Unmatched;

/**
 * CLI argument parsing with picocli (Phase 3 design §9.2).
 *
 * <p>Known flags map to {@link Args} fields; unknown flags and bare tokens are
 * captured via {@link Unmatched} and post-processed exactly like pi's
 * {@code args.ts}: {@code @file} → fileArgs, {@code --key=value} /
 * {@code --key value} → extension flags (kept in {@code unmatched}), bare
 * tokens → messages. Multi-character short flags ({@code -nt}, {@code -xt},
 * …) are expanded to their long forms before picocli parses, so they never
 * collide with the single-char {@code -n} ({@code --name}).</p>
 */
@Command(name = "pi-java")
public final class ArgsParser {

    @Option(names = "--provider")
    String provider;

    @Option(names = "--model")
    String model;

    @Option(names = "--api-key")
    String apiKey;

    @Option(names = "--system-prompt")
    String systemPrompt;

    @Option(names = "--append-system-prompt")
    List<String> appendSystemPrompt = new ArrayList<>();

    @Option(names = "--thinking")
    String thinking;

    @Option(names = {"--continue", "-c"})
    boolean continue_;

    @Option(names = {"--resume", "-r"})
    boolean resume;

    @Option(names = {"--help", "-h"})
    boolean help;

    @Option(names = {"--version", "-v"})
    boolean version;

    @Option(names = "--mode")
    String mode;

    @Option(names = {"--name", "-n"})
    String name;

    @Option(names = "--no-session")
    boolean noSession;

    @Option(names = "--session")
    String session;

    @Option(names = "--session-id")
    String sessionId;

    @Option(names = "--fork")
    String fork;

    @Option(names = "--session-dir")
    String sessionDir;

    @Option(names = "--models")
    String models;

    @Option(names = {"--tools", "-t"})
    String tools;

    @Option(names = "--exclude-tools")
    String excludeTools;

    @Option(names = "--no-tools")
    boolean noTools;

    @Option(names = "--no-builtin-tools")
    boolean noBuiltinTools;

    @Option(names = {"--extension", "-e"})
    List<String> extensions = new ArrayList<>();

    @Option(names = "--no-extensions")
    boolean noExtensions;

    @Option(names = "--skill")
    List<String> skills = new ArrayList<>();

    @Option(names = "--no-skills")
    boolean noSkills;

    @Option(names = "--prompt-template")
    List<String> promptTemplates = new ArrayList<>();

    @Option(names = "--no-prompt-templates")
    boolean noPromptTemplates;

    @Option(names = "--theme")
    List<String> themes = new ArrayList<>();

    @Option(names = "--no-themes")
    boolean noThemes;

    @Option(names = "--no-context-files")
    boolean noContextFiles;

    @Option(names = {"--print", "-p"})
    boolean print;

    @Option(names = "--export")
    String export;

    @Option(names = "--list-models", arity = "0..1", fallbackValue = "")
    String listModels;

    @Option(names = "--offline")
    boolean offline;

    @Option(names = "--tui-mode")
    String tuiMode;

    @Option(names = "--verbose")
    boolean verbose;

    @Option(names = "--debug")
    boolean debug;

    @Option(names = {"--approve", "-a"})
    boolean approve;

    @Option(names = "--no-approve")
    boolean noApprove;

    @Unmatched
    List<String> unmatched = new ArrayList<>();

    /** Parse CLI arguments into an {@link Args} record. */
    public static Args parse(String[] args) {
        var diagnostics = new ArrayList<Args.ArgDiagnostic>();
        var commandLine = new CommandLine(new ArgsParser());
        commandLine.setUnmatchedArgumentsAllowed(true);
        commandLine.setOverwrittenOptionsAllowed(true);

        ParseResult result;
        try {
            result = commandLine.parseArgs(expandShortFlags(args));
        } catch (ParameterException e) {
            diagnostics.add(new Args.ArgDiagnostic("error", e.getMessage()));
            var parser = (ArgsParser) e.getCommandLine().getCommand();
            return parser.toArgs(diagnostics);
        }
        var parser = (ArgsParser) result.commandSpec().userObject();
        return parser.toArgs(diagnostics);
    }

    private Args toArgs(List<Args.ArgDiagnostic> diagnostics) {
        if (mode != null && !Set.of("text", "json", "rpc").contains(mode)) {
            diagnostics.add(new Args.ArgDiagnostic("error",
                "Invalid --mode \"" + mode + "\". Valid values: text, json, rpc"));
        }
        if (tuiMode != null
                && !Set.of("regular", "fullscreen").contains(tuiMode)) {
            diagnostics.add(new Args.ArgDiagnostic("error",
                "Invalid --tui-mode \"" + tuiMode
                    + "\". Valid values: regular, fullscreen"));
        }
        if (thinking != null && !ThinkingLevels.isValid(thinking)) {
            diagnostics.add(new Args.ArgDiagnostic("warning",
                "Invalid thinking level \"" + thinking + "\". Valid values: "
                    + String.join(", ", ThinkingLevels.validValues())));
        }

        var parsedMessages = new ArrayList<String>();
        var parsedFileArgs = new ArrayList<String>();
        var parsedUnmatched = new ArrayList<String>();
        for (int i = 0; i < unmatched.size(); i++) {
            var token = unmatched.get(i);
            if (token.startsWith("@")) {
                parsedFileArgs.add(token.substring(1));
            } else if (token.startsWith("--")) {
                parsedUnmatched.add(token);
                var eqIndex = token.indexOf('=');
                if (eqIndex == -1 && i + 1 < unmatched.size()
                        && !unmatched.get(i + 1).startsWith("-")
                        && !unmatched.get(i + 1).startsWith("@")) {
                    parsedUnmatched.add(unmatched.get(++i));
                }
            } else if (token.startsWith("-")) {
                diagnostics.add(new Args.ArgDiagnostic("error",
                    "Unknown option: " + token));
            } else {
                parsedMessages.add(token);
            }
        }

        Boolean trustOverride = null;
        if (approve) {
            trustOverride = true;
        }
        if (noApprove) {
            trustOverride = false;
        }

        return new Args(
            provider, model, apiKey, systemPrompt, List.copyOf(appendSystemPrompt),
            thinking, continue_, resume, help, version, mode, name, noSession,
            session, sessionId, fork, sessionDir,
            split(models), split(tools), split(excludeTools),
            noTools, noBuiltinTools, List.copyOf(extensions), noExtensions,
            List.copyOf(skills), noSkills,
            List.copyOf(promptTemplates), noPromptTemplates,
            List.copyOf(themes), noThemes, noContextFiles,
            print, export, listModels, offline, tuiMode, verbose, debug, trustOverride,
            parsedMessages, parsedFileArgs, parsedUnmatched, diagnostics);
    }

    private static List<String> split(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(commaSeparated.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static String[] expandShortFlags(String[] args) {
        var result = new ArrayList<String>(args.length);
        for (var arg : args) {
            result.add(switch (arg) {
                case "-nt" -> "--no-tools";
                case "-nbt" -> "--no-builtin-tools";
                case "-xt" -> "--exclude-tools";
                case "-ns" -> "--no-skills";
                case "-np" -> "--no-prompt-templates";
                case "-nc" -> "--no-context-files";
                case "-ne" -> "--no-extensions";
                case "-na" -> "--no-approve";
                default -> arg;
            });
        }
        return result.toArray(String[]::new);
    }
}
