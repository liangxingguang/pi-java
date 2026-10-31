package com.pijava.evals.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.evals.harness.HarnessUsage;
import com.pijava.evals.plan.DocumentationVariant;

/**
 * 批 32（docs/44 §5.3）：observation 的归一 JSON 读写与落盘。
 *
 * <p>键集合固定：{@code evalSet/case/variant/run/status/score/reason/usage/
 * durationMs/sessionArtifact/metrics}。{@code score} 缺席写作 JSON {@code null}；
 * usage 不可用写作字符串 {@code "unavailable"}（不折成 0）。</p>
 */
public final class ObservationJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> METRICS_TYPE =
        new TypeReference<>() { };

    private ObservationJson() {
    }

    /**
     * Serialize one observation as a single JSON line (no newline).
     *
     * @param observation observation to serialize
     * @return JSON object text
     */
    public static String toJson(EvalObservation observation) {
        var node = MAPPER.createObjectNode();
        node.put("evalSet", observation.evalSet());
        node.put("case", observation.caseName());
        node.put("variant", observation.variant().wireName());
        node.put("run", observation.run());
        node.put("status", observation.status().wireName());
        if (observation.score() == null) {
            node.putNull("score");
        } else {
            node.put("score", observation.score());
        }
        node.put("reason", observation.reason());
        if (observation.usage() == null) {
            node.put("usage", "unavailable");
        } else {
            node.set("usage", usageNode(observation.usage()));
        }
        node.put("durationMs", observation.durationMs());
        if (observation.sessionArtifact() == null) {
            node.putNull("sessionArtifact");
        } else {
            node.put("sessionArtifact", observation.sessionArtifact());
        }
        node.set("metrics", MAPPER.valueToTree(observation.metrics()));
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Observation is not JSON-serializable", e);
        }
    }

    /**
     * Parse one observation JSON line.
     *
     * @param line JSON object text
     * @return parsed observation
     * @throws IllegalArgumentException if the line violates the normalized shape
     */
    public static EvalObservation fromJson(String line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Observation is not valid JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Observation must be a JSON object");
        }
        var scoreNode = node.get("score");
        Double score = scoreNode == null || scoreNode.isNull() ? null : scoreNode.asDouble();
        var usageNode = node.get("usage");
        HarnessUsage usage = usageNode == null || (usageNode.isTextual()
            && "unavailable".equals(usageNode.asText())) ? null : parseUsage(usageNode);
        var metrics = node.hasNonNull("metrics")
            ? MAPPER.convertValue(node.get("metrics"), METRICS_TYPE)
            : Map.<String, Object>of();
        return new EvalObservation(
            requireText(node, "evalSet"),
            requireText(node, "case"),
            DocumentationVariant.fromWire(requireText(node, "variant")),
            requireInt(node, "run"),
            CaseStatus.fromWire(requireText(node, "status")),
            score,
            textOrEmpty(node, "reason"),
            usage,
            requireLong(node, "durationMs"),
            nullableText(node, "sessionArtifact"),
            metrics);
    }

    /**
     * Append one observation as a JSONL line, creating parent directories.
     *
     * @param jsonl       observations file
     * @param observation observation to append
     * @throws IOException if the file cannot be written
     */
    public static void append(Path jsonl, EvalObservation observation) throws IOException {
        var parent = jsonl.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(jsonl, toJson(observation) + System.lineSeparator(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Read every observation from a JSONL file.
     *
     * @param jsonl observations file
     * @return observations in file order; empty when the file does not exist
     * @throws IOException if the file cannot be read
     */
    public static List<EvalObservation> read(Path jsonl) throws IOException {
        if (!Files.isRegularFile(jsonl)) {
            return List.of();
        }
        var observations = new ArrayList<EvalObservation>();
        for (var line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                observations.add(fromJson(line));
            }
        }
        return List.copyOf(observations);
    }

    /**
     * Persist one arm: copy the session snapshot (when available) and append the
     * observation to {@code observations.jsonl}.
     *
     * @param observation observation to persist
     * @param sessionFile session JSONL path, blank/null ⇒ no artifact is written
     * @param artifactDir artifact root
     * @return the observation with {@code sessionArtifact} recorded
     * @throws IOException if writing fails
     */
    public static EvalObservation persist(EvalObservation observation, String sessionFile,
                                          Path artifactDir) throws IOException {
        var stored = observation;
        if (sessionFile != null && !sessionFile.isBlank()) {
            var source = Path.of(sessionFile);
            if (Files.isRegularFile(source)) {
                var content = Files.readString(source, StandardCharsets.UTF_8);
                var target = artifactDir.resolve(observation.variant().wireName())
                    .resolve("sessions").resolve(sha256(content)).resolve("session.jsonl");
                Files.createDirectories(Objects.requireNonNull(target.getParent(),
                    "session artifact parent"));
                Files.writeString(target, content, StandardCharsets.UTF_8);
                stored = observation.withSessionArtifact(target.toString());
            }
        }
        append(artifactDir.resolve("observations.jsonl"), stored);
        return stored;
    }

    private static ObjectNode usageNode(HarnessUsage usage) {
        var node = MAPPER.createObjectNode();
        node.put("provider", usage.provider());
        node.put("model", usage.model());
        node.put("inputTokens", usage.inputTokens());
        node.put("outputTokens", usage.outputTokens());
        node.put("cacheReadTokens", usage.cacheReadTokens());
        node.put("cacheWriteTokens", usage.cacheWriteTokens());
        node.put("totalTokens", usage.totalTokens());
        node.put("toolCalls", usage.toolCalls());
        if (usage.estimatedCostUsd() == null) {
            node.putNull("estimatedCostUsd");
        } else {
            node.put("estimatedCostUsd", usage.estimatedCostUsd());
        }
        return node;
    }

    private static HarnessUsage parseUsage(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Observation usage must be an object or 'unavailable'");
        }
        var costNode = node.get("estimatedCostUsd");
        Double cost = costNode == null || costNode.isNull() ? null : costNode.asDouble();
        return new HarnessUsage(
            textOrEmpty(node, "provider"),
            textOrEmpty(node, "model"),
            doubleOrZero(node, "inputTokens"),
            doubleOrZero(node, "outputTokens"),
            doubleOrZero(node, "cacheReadTokens"),
            doubleOrZero(node, "cacheWriteTokens"),
            doubleOrZero(node, "totalTokens"),
            longOrZero(node, "toolCalls"),
            cost);
    }

    private static String requireText(JsonNode node, String field) {
        var value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Observation is missing '" + field + "'");
        }
        return value;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        var value = nullableText(node, field);
        return value == null ? "" : value;
    }

    private static String nullableText(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int requireInt(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Observation is missing numeric '" + field + "'");
        }
        return value.asInt();
    }

    private static long requireLong(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Observation is missing numeric '" + field + "'");
        }
        return value.asLong();
    }

    private static double doubleOrZero(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? 0.0 : value.asDouble();
    }

    private static long longOrZero(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? 0L : value.asLong();
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
