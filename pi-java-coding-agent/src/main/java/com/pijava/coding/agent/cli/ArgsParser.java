package com.pijava.coding.agent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.pijava.coding.agent.core.ToolSelection;

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

    /** pi assertValidSessionId（session-manager.ts:268-273）正则逐字。 */
    private static final java.util.regex.Pattern SESSION_ID_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$");

    @Option(names = "--provider")
    String provider;

    @Option(names = "--model")
    String model;

    @Option(names = "--api-key")
    String apiKey;

    @Option(names = "--base-url")
    String baseUrl;

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

    @Option(names = "--port")
    Integer port;

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

    /** --tools/-t 是否实给（pi result.tools 的 undefined vs [] 之分，args.ts:151-161）。 */
    boolean toolsFlagPresent;
    /** --exclude-tools/-xt 是否实给。 */
    boolean excludeToolsFlagPresent;

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

    @Option(names = "--use-theme")
    String useTheme;

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

    @Option(names = "--trace-payloads")
    boolean tracePayloads;

    @Option(names = {"--approve", "-a"})
    boolean approve;

    @Option(names = "--no-approve")
    boolean noApprove;

    @Unmatched
    List<String> unmatched = new ArrayList<>();

    /** Parse CLI arguments into an {@link Args} record. */
    public static Args parse(String[] args) {
        var diagnostics = new ArrayList<Args.ArgDiagnostic>();
        // pi args.ts:83-90（T12）：首个 "--" 之后全部停止解析——@x ⇒ fileArgs，
        // 其余 ⇒ messages（含 -/-- 起头，不再判旗）。"--" 前段才走短旗展开＋picocli。
        int sep = List.of(args).indexOf("--");
        var head = sep < 0 ? args : java.util.Arrays.copyOf(args, sep);
        var tail = sep < 0 ? new String[0]
            : java.util.Arrays.copyOfRange(args, sep + 1, args.length);
        var commandLine = new CommandLine(new ArgsParser());
        commandLine.setUnmatchedArgumentsAllowed(true);
        commandLine.setOverwrittenOptionsAllowed(true);

        ParseResult result;
        try {
            result = commandLine.parseArgs(expandShortFlags(head));
        } catch (ParameterException e) {
            var message = e.getMessage();
            // pi args.ts:204-205（T16）：--use-theme 缺值与 "-" 起头值同一诊断文案。
            if (message != null && message.contains("--use-theme")) {
                message = "--use-theme requires a theme name";
            }
            diagnostics.add(new Args.ArgDiagnostic("error", message));
            var parser = (ArgsParser) e.getCommandLine().getCommand();
            return parser.toArgs(diagnostics, tail);
        }
        var parser = (ArgsParser) result.commandSpec().userObject();
        parser.toolsFlagPresent = result.hasMatchedOption("--tools");
        parser.excludeToolsFlagPresent = result.hasMatchedOption("--exclude-tools");
        return parser.toArgs(diagnostics, tail);
    }

    private Args toArgs(List<Args.ArgDiagnostic> diagnostics) {
        return toArgs(diagnostics, new String[0]);
    }

    private Args toArgs(List<Args.ArgDiagnostic> diagnostics, String[] doubleDashTail) {
        if (mode != null && !Set.of("text", "json", "rpc", "web").contains(mode)) {
            diagnostics.add(new Args.ArgDiagnostic("error",
                "Invalid --mode \"" + mode + "\". Valid values: text, json, rpc, web"));
        }
        if (port != null && (port < 1 || port > 65535)) {
            diagnostics.add(new Args.ArgDiagnostic("error",
                "Invalid --port \"" + port + "\". Valid range: 1-65535"));
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
        // pi args.ts:204-205（T16）：值以 "-" 起头 ⇒ 同一诊断（缺值臂在 parse 的 catch 里）。
        if (useTheme != null && useTheme.startsWith("-")) {
            diagnostics.add(new Args.ArgDiagnostic("error",
                "--use-theme requires a theme name"));
        }

        // pi validateForkFlags（main.ts:299-310）：--fork 与
        // --session/--continue/--resume/--no-session 互斥（--session-id 不在列——F-b 组合合法）。
        if (fork != null) {
            var conflicts = java.util.stream.Stream.of(
                session != null ? "--session" : null,
                continue_ ? "--continue" : null,
                resume ? "--resume" : null,
                noSession ? "--no-session" : null).filter(java.util.Objects::nonNull).toList();
            if (!conflicts.isEmpty()) {
                diagnostics.add(new Args.ArgDiagnostic("error",
                    "--fork cannot be combined with " + String.join(", ", conflicts)));
            }
        }
        // pi validateSessionIdFlags（main.ts:312-336）：--session-id 与
        // --session/--continue/--resume 互斥（无 --no-session），冲突诊断后仍跑格式断言（pi :329-335 同序）。
        if (sessionId != null) {
            var conflicts = java.util.stream.Stream.of(
                session != null ? "--session" : null,
                continue_ ? "--continue" : null,
                resume ? "--resume" : null).filter(java.util.Objects::nonNull).toList();
            if (!conflicts.isEmpty()) {
                diagnostics.add(new Args.ArgDiagnostic("error",
                    "--session-id cannot be combined with " + String.join(", ", conflicts)));
            }
            if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
                // pi assertValidSessionId（session-manager.ts:268-273）文案逐字。
                diagnostics.add(new Args.ArgDiagnostic("error",
                    "Session id must be non-empty, contain only alphanumeric characters,"
                        + " '-', '_', and '.', and start and end with an alphanumeric character"));
            }
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
        // pi args.ts:83-90（T12）逐字："--" 后段路由——@x ⇒ fileArgs，其余 ⇒
        // messages（含 -/-- 起头，pi 不再判）。
        for (var token : doubleDashTail) {
            if (token.startsWith("@")) {
                parsedFileArgs.add(token.substring(1));
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

        // pi args.ts:151-161：--tools 解析期校验；失败 error 诊断且不写 tools（≙ null）。
        var toolEntries = splitOrNull(tools, toolsFlagPresent);
        if (toolEntries != null) {
            var error = ToolSelection.getToolListError(toolEntries);
            if (error != null) {
                // pi `${arg}: ${error}`；窄差 D1：picocli 不暴露实敲别名，前缀恒 --tools。
                diagnostics.add(new Args.ArgDiagnostic("error", "--tools: " + error));
                toolEntries = null;
            }
        }

        return new Args(
            provider, model, apiKey, baseUrl, systemPrompt, List.copyOf(appendSystemPrompt),
            thinking, continue_, resume, help, version, mode, port, name, noSession,
            session, sessionId, fork, sessionDir,
            split(models), toolEntries, splitOrNull(excludeTools, excludeToolsFlagPresent),
            noTools, noBuiltinTools, List.copyOf(extensions), noExtensions,
            List.copyOf(skills), noSkills,
            List.copyOf(promptTemplates), noPromptTemplates,
            List.copyOf(themes), noThemes, useTheme, noContextFiles,
            print, export, listModels, offline, tuiMode, verbose, debug, tracePayloads,
            trustOverride,
            parsedMessages, parsedFileArgs, parsedUnmatched, diagnostics);
    }

    /**
     * tools/excludeTools 专用 split：旗标缺席 ⇒ {@code null}（≙ pi undefined）；
     * 给了但全空白 ⇒ 空表（pi 的 [] —— {@code --tools ""} 是空 allowlist，零工具）。
     */
    private static List<String> splitOrNull(String commaSeparated, boolean present) {
        return present ? split(commaSeparated) : null;
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
