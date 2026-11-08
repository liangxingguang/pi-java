package com.pijava.evals.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.evals.api.EvalReporter;
import com.pijava.evals.api.EvalSuite;
import com.pijava.evals.contents.ContentsCatalog;
import com.pijava.evals.harness.HarnessCase;
import com.pijava.evals.plan.DocumentationVariant;
import com.pijava.evals.plan.EvalCaseDescriptor;
import com.pijava.evals.plan.TaskPlan;
import com.pijava.evals.report.CaseStatus;
import com.pijava.evals.report.ComparisonReport;
import com.pijava.evals.report.ComparisonReportText;
import com.pijava.evals.report.EvalObservation;
import com.pijava.evals.report.EvalSetComparison;
import com.pijava.evals.report.OperationalMetricTotal;
import com.pijava.evals.report.PairedMetricSummary;
import com.pijava.evals.report.VariantTotals;

/**
 * 批 32（docs/44 §6）：eval 运行器 CLI —— pi {@code cli.ts} 对位。
 *
 * <p>执行序：发现（{@link EvalCatalog}）→ cohort 校验 → {@link TaskPlan} 展开 →
 * 逐臂执行（单臂失败记 observation，计划继续）→ 配对报告 → blocked 则退出码 1。
 * {@code --host} 模式不配对，任一 {@code failed/errored} 即退出码 1。</p>
 */
public final class EvalCli {

    /**
     * report.json schema 版本（L6 W1，D228）：3 起结构为 per-eval-set
     * {@code comparisons} ＋ 按变体 {@code operationalTotals}，与此前的全局单份汇总不互读。
     */
    public static final int REPORT_SCHEMA_VERSION = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Executes one arm; injectable for tests. */
    @FunctionalInterface
    public interface ArmRunner {

        /**
         * Run one arm.
         *
         * @param suite       owning suite
         * @param evalCase    case definition
         * @param variant     docs variant
         * @param run         1-based run number
         * @param artifactDir artifact root
         * @return observation and durable session path (nullable)
         * @throws Exception when the arm cannot complete
         */
        ArmResult run(EvalSuite suite, HarnessCase evalCase, DocumentationVariant variant,
                      int run, Path artifactDir) throws Exception;
    }

    /**
     * Result of one arm.
     *
     * @param observation observation (not yet persisted)
     * @param sessionFile durable session snapshot path, {@code null} when absent
     */
    public record ArmResult(EvalObservation observation, String sessionFile) {

        /** Rejects a null observation. */
        public ArmResult {
            Objects.requireNonNull(observation, "observation");
        }
    }

    private record SelectedCase(EvalSuite suite, HarnessCase harnessCase) {
    }

    private final List<EvalSuite> suites;
    private final ArmRunner runner;
    private final ArtifactLayout layout;
    private final PrintStream out;

    /**
     * Create a CLI instance.
     *
     * @param suites registered suites
     * @param runner arm executor
     * @param layout artifact layout
     * @param out    progress output
     */
    public EvalCli(List<EvalSuite> suites, ArmRunner runner, ArtifactLayout layout,
                   PrintStream out) {
        this.suites = List.copyOf(Objects.requireNonNull(suites, "suites"));
        this.runner = Objects.requireNonNull(runner, "runner");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * CLI entry point.
     *
     * @param args raw arguments
     */
    public static void main(String[] args) {
        int code;
        try {
            ContentsCatalog.install();
            var options = EvalCliOptions.parse(args);
            var layout = ArtifactLayout.create(Path.of("").toAbsolutePath());
            var runner = (ArmRunner) (suite, evalCase, variant, run, dir) ->
                HarnessArmRunner.run(suite, evalCase, variant, run, dir,
                    options.provider(), options.model());
            code = new EvalCli(EvalCatalog.suites(), runner, layout, System.out).execute(options);
            System.out.println("artifacts: " + layout.root());
        } catch (IllegalArgumentException e) {
            System.err.println("eval: " + e.getMessage());
            code = 2;
        } catch (IOException | RuntimeException e) {
            System.err.println("eval failed: " + e.getMessage());
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Execute the eval run.
     *
     * @param options parsed options
     * @return process exit code (0 ok, 1 blocked/failed)
     * @throws IOException if artifacts cannot be written
     */
    public int execute(EvalCliOptions options) throws IOException {
        var selectedSuites = byAuditGate(EvalCatalog.select(suites, options.selectors()),
            options);
        validateCohort(selectedSuites, options);
        var selected = new ArrayList<SelectedCase>();
        for (var suite : selectedSuites) {
            for (var evalCase : filteredCases(suite, options)) {
                selected.add(new SelectedCase(suite, evalCase));
            }
        }
        if (!selected.isEmpty() && !options.hasModelSelection()) {
            throw new IllegalArgumentException(
                "Select --provider/--model or set both PI_PROVIDER and PI_MODEL.");
        }
        return options.host() ? runHost(selected, options) : runDocsLift(selected, options);
    }

    private int runDocsLift(List<SelectedCase> selected, EvalCliOptions options)
            throws IOException {
        var plan = TaskPlan.expand(selected.stream()
            .map(entry -> entry.harnessCase().descriptor()).toList(),
            options.runsPerVariant());
        writeProtocol(options, plan);
        writeExpectedRuns(plan);
        var byDescriptor = indexCases(selected);
        var observations = new ArrayList<EvalObservation>();
        for (var arm : plan.arms()) {
            observations.add(runArm(byDescriptor.get(arm.evalCase()), arm.variant(), arm.run(),
                options));
        }
        var report = ComparisonReport.summarize(observations, plan.ids());
        Files.writeString(layout.reportText(), ComparisonReportText.render(report),
            StandardCharsets.UTF_8);
        Files.writeString(layout.reportJson(), pretty(reportNode(report)),
            StandardCharsets.UTF_8);
        out.println("report: " + layout.reportText());
        return report.blocked() ? 1 : 0;
    }

    private int runHost(List<SelectedCase> selected, EvalCliOptions options)
            throws IOException {
        writeProtocol(options, null);
        Files.writeString(layout.expectedRuns(), "[]\n", StandardCharsets.UTF_8);
        var observations = new ArrayList<EvalObservation>();
        for (var entry : selected) {
            observations.add(runArm(entry, DocumentationVariant.WITH_DOCS, 1, options));
        }
        var failed = observations.stream()
            .filter(o -> o.status() == CaseStatus.FAILED || o.status() == CaseStatus.ERRORED)
            .count();
        var passed = observations.stream()
            .filter(o -> o.status() == CaseStatus.PASSED).count();
        var text = "host eval: passed=" + passed + " failed-or-errored=" + failed
            + " total=" + observations.size() + "\n";
        Files.writeString(layout.reportText(), text, StandardCharsets.UTF_8);
        out.println("report: " + layout.reportText());
        return failed == 0 ? 0 : 1;
    }

    private EvalObservation runArm(SelectedCase entry, DocumentationVariant variant, int run,
                                   EvalCliOptions options) throws IOException {
        var descriptor = entry.harnessCase().descriptor();
        try {
            var result = runner.run(entry.suite(), entry.harnessCase(), variant, run,
                layout.root());
            return EvalReporter.write(result.observation(), result.sessionFile(),
                layout.root());
        } catch (Exception e) {
            var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            var observation = new EvalObservation(descriptor.evalSet(),
                descriptor.caseName(), variant, run, CaseStatus.ERRORED, null, message,
                null, 0L, null, Map.of("error", e.getClass().getSimpleName()));
            return EvalReporter.write(observation, null, layout.root());
        }
    }

    /**
     * L6 W5（docs/26 §6）：{@code --host} 默认不跑 expensive 套件，{@code --audit} 只跑它们。
     *
     * <p>{@code --audit} 与显式选择器并存时，显式点名优先——pi 同样允许
     * 直接给审计文件名绕过项目默认。</p>
     */
    private static List<EvalSuite> byAuditGate(List<EvalSuite> selected,
                                               EvalCliOptions options) {
        if (options.audit()) {
            if (!options.selectors().isEmpty()) {
                return selected;
            }
            return selected.stream().filter(EvalSuite::expensive).toList();
        }
        if (!options.host() || !options.selectors().isEmpty()) {
            return selected;
        }
        return selected.stream().filter(suite -> !suite.expensive()).toList();
    }

    private static Map<EvalCaseDescriptor, SelectedCase> indexCases(
            List<SelectedCase> selected) {
        var byDescriptor = new LinkedHashMap<EvalCaseDescriptor, SelectedCase>();
        for (var entry : selected) {
            var previous = byDescriptor.putIfAbsent(entry.harnessCase().descriptor(), entry);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "Duplicate eval case: " + entry.harnessCase().descriptor().id());
            }
        }
        return byDescriptor;
    }

    private void validateCohort(List<EvalSuite> selectedSuites, EvalCliOptions options) {
        for (var suite : selectedSuites) {
            var with = filteredDescriptors(suite.discoverCases(DocumentationVariant.WITH_DOCS),
                suite, options);
            var without = filteredDescriptors(
                suite.discoverCases(DocumentationVariant.WITHOUT_DOCS), suite, options);
            if (!new HashSet<>(with).equals(new HashSet<>(without))) {
                throw new IllegalArgumentException(
                    "Eval cohort mismatch in " + suite.name() + ": " + with + " vs " + without);
            }
        }
    }

    private static List<EvalCaseDescriptor> filteredDescriptors(
            List<EvalCaseDescriptor> descriptors, EvalSuite suite, EvalCliOptions options) {
        var pattern = testPattern(options);
        var suiteWide = suiteWide(suite, options.selectors());
        var caseSelectors = EvalCatalog.caseSelectors(suite, options.selectors());
        return descriptors.stream()
            .filter(descriptor -> suiteWide || caseSelectors.contains(descriptor.id()))
            .filter(descriptor -> pattern == null
                || pattern.matcher(descriptor.caseName()).find())
            .toList();
    }

    private static List<HarnessCase> filteredCases(EvalSuite suite, EvalCliOptions options) {
        var pattern = testPattern(options);
        var suiteWide = suiteWide(suite, options.selectors());
        var caseSelectors = EvalCatalog.caseSelectors(suite, options.selectors());
        return suite.harnessCases().stream()
            .filter(evalCase -> suiteWide || caseSelectors.contains(
                evalCase.descriptor().id()))
            .filter(evalCase -> pattern == null
                || pattern.matcher(evalCase.descriptor().caseName()).find())
            .toList();
    }

    private static boolean suiteWide(EvalSuite suite, List<String> selectors) {
        return selectors.isEmpty() || selectors.stream().anyMatch(selector ->
            suite.name().equals(selector)
                || suite.getClass().getSimpleName().equals(selector));
    }

    private static Pattern testPattern(EvalCliOptions options) {
        return options.testNamePattern() == null
            ? null : Pattern.compile(options.testNamePattern());
    }

    private void writeProtocol(EvalCliOptions options, TaskPlan plan) throws IOException {
        var node = MAPPER.createObjectNode();
        node.put("provider", options.provider());
        node.put("model", options.model());
        node.put("host", options.host());
        node.put("runsPerVariant", options.runsPerVariant());
        node.put("startedAt", Instant.now().toString());
        node.put("artifactDir", layout.root().toString());
        var selectors = node.putArray("selectors");
        options.selectors().forEach(selectors::add);
        if (options.testNamePattern() != null) {
            node.put("testNamePattern", options.testNamePattern());
        }
        node.put("armCount", plan == null ? 0 : plan.size());
        Files.writeString(layout.protocol(), pretty(node), StandardCharsets.UTF_8);
    }

    private void writeExpectedRuns(TaskPlan plan) throws IOException {
        var array = MAPPER.createArrayNode();
        for (var arm : plan.arms()) {
            var node = array.addObject();
            node.put("id", arm.id());
            node.put("evalSet", arm.evalCase().evalSet());
            node.put("case", arm.evalCase().caseName());
            node.put("variant", arm.variant().wireName());
            node.put("run", arm.run());
        }
        Files.writeString(layout.expectedRuns(), pretty(array), StandardCharsets.UTF_8);
    }

    private static ObjectNode reportNode(ComparisonReport report) {
        var node = MAPPER.createObjectNode();
        node.put("schemaVersion", REPORT_SCHEMA_VERSION);
        node.put("blocked", report.blocked());
        node.put("withheld", report.withheld());
        var comparisons = node.putArray("comparisons");
        for (var comparison : report.comparisons()) {
            comparisons.add(comparisonNode(comparison));
        }
        var blocked = node.putArray("blockedPairs");
        for (var pair : report.blockedPairs()) {
            var item = blocked.addObject();
            item.put("id", pair.id());
            var reasons = item.putArray("reasons");
            pair.reasons().forEach(reasons::add);
        }
        var totals = node.putArray("operationalTotals");
        for (var variant : report.variantTotals()) {
            totals.add(variantTotalsNode(variant));
        }
        var counts = node.putObject("statusCounts");
        report.statusCounts().forEach((status, count) ->
            counts.put(status.wireName(), count));
        node.put("totalDurationMs", report.totalDurationMs());
        node.put("usageUnavailable", report.usageUnavailable());
        var cases = node.putArray("cases");
        for (var comparison : report.cases()) {
            var item = cases.addObject();
            item.put("id", comparison.id());
            item.put("controlStatus", comparison.controlStatus().wireName());
            item.put("treatmentStatus", comparison.treatmentStatus().wireName());
            putNullable(item, "controlScore", comparison.controlScore());
            putNullable(item, "treatmentScore", comparison.treatmentScore());
            putNullable(item, "delta", comparison.delta());
            item.put("blocked", comparison.blocked());
            item.put("reason", comparison.reason());
        }
        return node;
    }

    private static ObjectNode comparisonNode(EvalSetComparison comparison) {
        var node = MAPPER.createObjectNode();
        node.put("evalSet", comparison.evalSet());
        node.put("totalPairs", comparison.totalPairs());
        node.put("eligiblePairs", comparison.eligiblePairs());
        node.put("blockedPairs", comparison.blockedPairs());
        putNullable(node, "controlPassRate", comparison.controlPassRate());
        putNullable(node, "treatmentPassRate", comparison.treatmentPassRate());
        putNullable(node, "lift", comparison.lift());
        var flags = node.putArray("flags");
        comparison.flags().forEach(flags::add);
        node.set("totalTokens", pairedNode(comparison.totalTokens()));
        node.set("toolCalls", pairedNode(comparison.toolCalls()));
        node.set("totalMs", pairedNode(comparison.totalMs()));
        node.set("estimatedCostUsd", pairedNode(comparison.estimatedCostUsd()));
        return node;
    }

    private static ObjectNode pairedNode(PairedMetricSummary metric) {
        var node = MAPPER.createObjectNode();
        node.put("eligiblePairs", metric.eligiblePairs());
        putNullable(node, "controlMean", metric.controlMean());
        putNullable(node, "treatmentMean", metric.treatmentMean());
        putNullable(node, "meanDelta", metric.meanDelta());
        return node;
    }

    private static ObjectNode variantTotalsNode(VariantTotals totals) {
        var node = MAPPER.createObjectNode();
        node.put("variant", totals.variant().wireName());
        node.put("runs", totals.runs());
        node.set("inputTokens", operationalNode(totals.inputTokens()));
        node.set("outputTokens", operationalNode(totals.outputTokens()));
        node.set("cacheReadTokens", operationalNode(totals.cacheReadTokens()));
        node.set("cacheWriteTokens", operationalNode(totals.cacheWriteTokens()));
        node.set("totalTokens", operationalNode(totals.totalTokens()));
        node.set("toolCalls", operationalNode(totals.toolCalls()));
        node.set("totalMs", operationalNode(totals.totalMs()));
        node.set("estimatedCostUsd", operationalNode(totals.estimatedCostUsd()));
        return node;
    }

    private static ObjectNode operationalNode(OperationalMetricTotal metric) {
        var node = MAPPER.createObjectNode();
        node.put("availableRuns", metric.availableRuns());
        putNullable(node, "total", metric.total());
        return node;
    }

    private static void putNullable(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to render eval report JSON", e);
        }
    }
}
