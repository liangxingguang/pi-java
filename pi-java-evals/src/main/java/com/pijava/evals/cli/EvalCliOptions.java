package com.pijava.evals.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批 32（docs/44 §6）：{@code EvalCli} 的参数。
 *
 * @param selectors      eval 标识（suite 名/类名，或 {@code evalSet/case}）
 * @param testNamePattern case 名正则（{@code null} ⇒ 不过滤）
 * @param provider       provider name（显式或 {@code PI_PROVIDER}）
 * @param model          model id（显式或 {@code PI_MODEL}）
 * @param runsPerVariant 每个变体的 run 数
 * @param host           host eval 模式（不配对）
 */
public record EvalCliOptions(
    List<String> selectors,
    String testNamePattern,
    String provider,
    String model,
    int runsPerVariant,
    boolean host
) {

    /** Environment variable for the default run count. */
    public static final String RUNS_ENV = "PI_EVAL_RUNS_PER_VARIANT";

    /** Normalizes selectors and validates the run count. */
    public EvalCliOptions {
        selectors = selectors == null ? List.of() : List.copyOf(selectors);
        if (runsPerVariant < 1) {
            throw new IllegalArgumentException("runsPerVariant must be >= 1");
        }
        testNamePattern = testNamePattern == null || testNamePattern.isBlank()
            ? null : testNamePattern;
    }

    /**
     * Parse command-line arguments using the process environment.
     *
     * @param args raw arguments
     * @return parsed options
     */
    public static EvalCliOptions parse(String[] args) {
        return parse(args, System.getenv());
    }

    /**
     * Parse command-line arguments.
     *
     * @param args raw arguments
     * @param env  environment overrides
     * @return parsed options
     * @throws IllegalArgumentException on unknown options or an unpaired provider/model
     */
    public static EvalCliOptions parse(String[] args, Map<String, String> env) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        var selectors = new ArrayList<String>();
        String testNamePattern = null;
        String provider = null;
        String model = null;
        Integer runs = null;
        boolean host = false;
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            switch (arg) {
                case "-t", "--testNamePattern" -> {
                    testNamePattern = requireValue(args, ++i, arg);
                }
                case "--provider" -> provider = requireValue(args, ++i, arg);
                case "--model" -> model = requireValue(args, ++i, arg);
                case "--runs-per-variant" -> runs = parseRuns(requireValue(args, ++i, arg));
                case "--host" -> host = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown eval option: " + arg);
                    }
                    selectors.add(arg);
                }
            }
        }
        var selectedProvider = blankToNull(provider, env.get("PI_PROVIDER"));
        var selectedModel = blankToNull(model, env.get("PI_MODEL"));
        if ((selectedProvider == null) != (selectedModel == null)) {
            throw new IllegalArgumentException(
                "Provider and model must be provided together (--provider/--model or "
                    + "PI_PROVIDER/PI_MODEL).");
        }
        var runsPerVariant = runs == null ? envRuns(env) : runs;
        return new EvalCliOptions(selectors, testNamePattern, selectedProvider, selectedModel,
            runsPerVariant, host);
    }

    /**
     * {@code true} when a model selection is available.
     *
     * @return whether provider and model are both set
     */
    public boolean hasModelSelection() {
        return provider != null && model != null;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static int parseRuns(String value) {
        try {
            var runs = Integer.parseInt(value);
            if (runs < 1) {
                throw new IllegalArgumentException("runs-per-variant must be >= 1");
            }
            return runs;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("runs-per-variant must be an integer: " + value, e);
        }
    }

    private static int envRuns(Map<String, String> env) {
        var value = env.get(RUNS_ENV);
        return value == null || value.isBlank() ? 1 : parseRuns(value.trim());
    }

    private static String blankToNull(String explicit, String fallback) {
        var value = explicit == null || explicit.isBlank() ? fallback : explicit;
        return value == null || value.isBlank() ? null : value.trim();
    }
}
