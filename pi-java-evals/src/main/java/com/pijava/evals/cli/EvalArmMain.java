package com.pijava.evals.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.evals.api.EvalReporter;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.report.CaseStatus;
import com.pijava.evals.report.EvalObservation;

/**
 * 批 32（docs/44 §7.1）：容器内单臂入口 —— {@code eval-entrypoint} 对位。
 *
 * <p>{@code --list-cases} 以 JSON 行输出已注册用例；单臂模式按 env
 * （{@code PI_EVAL_VARIANT}/{@code PI_PROVIDER}/{@code PI_MODEL}/{@code PI_EVAL_RUN}）
 * 执行，写 observation 与 {@code tasks/<arm-id>/junit.xml}。Docker 侧只读后者。</p>
 */
public final class EvalArmMain {

    /** Variant environment variable. */
    public static final String VARIANT_ENV = "PI_EVAL_VARIANT";

    /** Run-number environment variable. */
    public static final String RUN_ENV = "PI_EVAL_RUN";

    /** Artifact-root environment variable. */
    public static final String ARTIFACTS_ENV = "PI_EVAL_ARTIFACTS";

    private static final String DEFAULT_ARTIFACTS = "/artifacts";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalArmMain() {
    }

    /**
     * Container entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int code;
        try {
            code = execute(args, System.getenv(), System.out);
        } catch (IllegalArgumentException e) {
            System.err.println("eval-arm: " + e.getMessage());
            code = 2;
        } catch (RuntimeException | IOException e) {
            System.err.println("eval-arm failed: " + e.getMessage());
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Execute with explicit environment (tests).
     *
     * @param args raw arguments
     * @param env  environment overrides
     * @param out  progress stream
     * @return process exit code (0 passed, 1 failed/errored, 2 usage error)
     * @throws IOException when artifacts cannot be written
     */
    public static int execute(String[] args, Map<String, String> env, PrintStream out)
            throws IOException {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        var options = Options.parse(args, env);
        if (options.listCases()) {
            for (var suite : EvalCatalog.suites()) {
                for (var descriptor : suite.discoverCases(DocumentationVariant.WITH_DOCS)) {
                    out.println(caseJson(descriptor.evalSet(), descriptor.caseName()));
                }
            }
            return 0;
        }
        var suite = findSuite(options.suite(), options.caseName());
        var evalCase = findCase(suite, options.suite(), options.caseName());
        if (options.testNamePattern() != null
                && !Pattern.compile(options.testNamePattern()).matcher(evalCase.descriptor()
                    .caseName()).matches()) {
            throw new IllegalArgumentException("Case '" + options.caseName()
                + "' does not match --testNamePattern " + options.testNamePattern());
        }
        var variant = variant(env);
        var run = run(env);
        var artifacts = options.artifacts();
        var provider = blankToNull(env.get("PI_PROVIDER"));
        var model = blankToNull(env.get("PI_MODEL"));
        EvalObservation observation;
        String sessionFile;
        try {
            var result = HarnessArmRunner.run(suite, evalCase, variant, run, artifacts,
                provider, model);
            observation = result.observation();
            sessionFile = result.sessionFile();
        } catch (Exception e) {
            observation = errored(evalCase, variant, run, e);
            sessionFile = null;
        }
        EvalReporter.write(observation, sessionFile, artifacts);
        writeJUnit(artifacts, HarnessArmRunner.armId(evalCase, variant, run), observation);
        out.println("status=" + observation.status().wireName());
        return observation.status() == CaseStatus.PASSED ? 0 : 1;
    }

    private static EvalSuite findSuite(String suiteId, String caseName) {
        var suites = EvalCatalog.suites();
        for (var suite : suites) {
            if (suite.harnessCases().stream().anyMatch(evalCase ->
                    evalCase.descriptor().caseName().equals(caseName)
                        && evalCase.descriptor().evalSet().equals(suiteId))) {
                return suite;
            }
        }
        for (var suite : suites) {
            if (suite.name().equals(suiteId)
                    || suite.getClass().getSimpleName().equals(suiteId)) {
                return suite;
            }
        }
        throw new IllegalArgumentException("Unknown eval suite: " + suiteId);
    }

    private static HarnessCase findCase(EvalSuite suite, String suiteId, String caseName) {
        for (var evalCase : suite.harnessCases()) {
            if (evalCase.descriptor().caseName().equals(caseName)
                    && evalCase.descriptor().evalSet().equals(suiteId)) {
                return evalCase;
            }
        }
        throw new IllegalArgumentException(
            "Unknown eval case: " + suiteId + "/" + caseName);
    }

    private static EvalObservation errored(HarnessCase evalCase, DocumentationVariant variant,
                                           int run, Exception error) {
        var message = error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage();
        return new EvalObservation(evalCase.descriptor().evalSet(),
            evalCase.descriptor().caseName(), variant, run, CaseStatus.ERRORED, null,
            message, null, 0L, null, Map.of("error", error.getClass().getSimpleName()));
    }

    private static void writeJUnit(Path artifacts, String armId, EvalObservation observation)
            throws IOException {
        var dir = Files.createDirectories(artifacts.resolve("tasks").resolve(armId));
        var status = observation.status();
        var tests = status == CaseStatus.UNSCORED || status == CaseStatus.MISSING ? 0 : 1;
        var failures = status == CaseStatus.FAILED ? 1 : 0;
        var errors = status == CaseStatus.ERRORED ? 1 : 0;
        var skipped = status == CaseStatus.SKIPPED ? 1 : 0;
        var xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(escape(observation.evalSet()))
            .append("\" tests=\"").append(tests)
            .append("\" failures=\"").append(failures)
            .append("\" errors=\"").append(errors)
            .append("\" skipped=\"").append(skipped)
            .append("\" time=\"").append(observation.durationMs() / 1000.0).append("\">\n");
        xml.append("  <testcase name=\"").append(escape(observation.caseName()))
            .append("\" classname=\"").append(escape(armId)).append("\">\n");
        if (failures > 0) {
            xml.append("    <failure message=\"").append(escape(observation.reason()))
                .append("\">").append(escape(observation.reason())).append("</failure>\n");
        }
        if (errors > 0) {
            xml.append("    <error message=\"").append(escape(observation.reason()))
                .append("\">").append(escape(observation.reason())).append("</error>\n");
        }
        if (skipped > 0) {
            xml.append("    <skipped message=\"").append(escape(observation.reason()))
                .append("\"/>\n");
        }
        xml.append("  </testcase>\n</testsuite>\n");
        Files.writeString(dir.resolve("junit.xml"), xml, StandardCharsets.UTF_8);
    }

    private static String caseJson(String evalSet, String caseName) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("evalSet", evalSet);
        node.put("case", caseName);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Case descriptor is not JSON-serializable", e);
        }
    }

    private static DocumentationVariant variant(Map<String, String> env) {
        var value = blankToNull(env.get(VARIANT_ENV));
        return value == null
            ? DocumentationVariant.WITH_DOCS : DocumentationVariant.fromWire(value);
    }

    private static int run(Map<String, String> env) {
        var value = blankToNull(env.get(RUN_ENV));
        if (value == null) {
            return 1;
        }
        try {
            var run = Integer.parseInt(value);
            if (run < 1) {
                throw new IllegalArgumentException("PI_EVAL_RUN must be >= 1");
            }
            return run;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PI_EVAL_RUN must be an integer: " + value, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private record Options(boolean listCases, String suite, String caseName,
                           String testNamePattern, Path artifacts) {

        static Options parse(String[] args, Map<String, String> env) {
            boolean listCases = false;
            String suite = null;
            String caseName = null;
            String pattern = null;
            String artifacts = null;
            for (int i = 0; i < args.length; i++) {
                var arg = args[i];
                switch (arg) {
                    case "--list-cases" -> listCases = true;
                    case "--suite" -> suite = requireValue(args, ++i, arg);
                    case "--case" -> caseName = requireValue(args, ++i, arg);
                    case "--artifacts" -> artifacts = requireValue(args, ++i, arg);
                    case "-t", "--testNamePattern" -> pattern = requireValue(args, ++i, arg);
                    default -> throw new IllegalArgumentException(
                        "Unknown eval-arm option: " + arg);
                }
            }
            if (!listCases && (suite == null || caseName == null)) {
                throw new IllegalArgumentException(
                    "Single-arm mode requires --suite and --case (or --list-cases).");
            }
            if (artifacts == null) {
                artifacts = blankToNull(env.get(ARTIFACTS_ENV));
            }
            var artifactsPath = artifacts == null
                ? Path.of(DEFAULT_ARTIFACTS) : Path.of(artifacts);
            return new Options(listCases, suite, caseName, pattern, artifactsPath);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
