package com.pijava.ai.telemetry;

import java.util.List;
import java.util.Map;

import com.pijava.telemetry.TelemetryAttributeDefinition;
import com.pijava.telemetry.TelemetryAttributeDefinition.NumberAttribute;
import com.pijava.telemetry.TelemetryAttributeDefinition.StringAttribute;
import com.pijava.telemetry.TelemetryCardinality;
import com.pijava.telemetry.TelemetryEventDefinition;
import com.pijava.telemetry.TelemetryMetricDefinition;
import com.pijava.telemetry.TelemetryParentDefinition;
import com.pijava.telemetry.TelemetrySchemaDefinition;
import com.pijava.telemetry.TelemetrySchemas;
import com.pijava.telemetry.TelemetrySpanDefinition;
import com.pijava.telemetry.TelemetryStatusDefinition;

/**
 * pi {@code AI_TELEMETRY_SCHEMA}（{@code packages/ai/src/telemetry.ts:14-105}）的
 * Java 声明面。
 *
 * <p>只声明 span／event／metric 的词汇与元数据；运行时不做校验（D201）。两个 span
 * 只有 description 不同，其余五个组件共用同一份工厂产物，避免手写漂移。</p>
 */
public final class AiTelemetrySchema {

    private static final String ERROR_WHEN =
        "Stop reason is error: provider_error with at most 500 error-message "
            + "characters. Aborted stays ok.";

    private static final StringAttribute PROVIDER =
        string("Provider identifier.", true, null, null);
    private static final StringAttribute MODEL =
        string("Requested model identifier.", true, null, null);
    private static final StringAttribute API =
        string("API identifier.", true, null, null);
    private static final StringAttribute REASONING =
        string("Scalar requested reasoning level.", false, null, null);

    private static final List<String> STOP_VALUES = List.of(
        "pending", "stop", "length", "toolUse", "error", "aborted", "deferred");
    private static final StringAttribute STOP_END =
        string("Normalized stop reason.", null, STOP_VALUES, null);
    private static final StringAttribute STOP_METRIC =
        string("Normalized stop reason.", true, STOP_VALUES, null);

    private static final StringAttribute RESPONSE_ID =
        string("Provider response identifier.", null, null, TelemetryCardinality.HIGH);

    private static final NumberAttribute USAGE_INPUT = number("Uncached input tokens.");
    private static final NumberAttribute USAGE_OUTPUT = number("Output tokens.");
    private static final NumberAttribute USAGE_CACHE_READ = number("Cache-read tokens.");
    private static final NumberAttribute USAGE_CACHE_WRITE = number("Cache-write tokens.");
    private static final NumberAttribute COST_TOTAL = number("Total request cost in USD.");

    private static final StringAttribute BLOCK_TYPE = string(
        "Block kind.", true, List.of("text", "thinking", "tool_call"), null);
    private static final NumberAttribute BLOCK_INDEX = number("Content block index.", true);
    private static final NumberAttribute BLOCK_CHARS = number("UTF-16 character count.", true);
    private static final StringAttribute BLOCK_TOOL =
        string("Tool name, for tool-call blocks only.", false, null, null);

    private static final StringAttribute TOKEN_KIND = string(
        "Token usage category.", true,
        List.of("input", "output", "cache_read", "cache_write"), null);

    /** pi 的 {@code AI_TELEMETRY_SCHEMA}；从 models.ts 重新导出的包级公共面。 */
    public static final TelemetrySchemaDefinition AI_TELEMETRY_SCHEMA =
        TelemetrySchemas.defineTelemetrySchema(new TelemetrySchemaDefinition(
            1,
            Map.of(
                "pi.ai.request", requestSpan(
                    "One logical model request, including authentication and provider setup."),
                "pi.ai.poll", requestSpan(
                    "One deferred-response poll, not the deferred request's lifetime.")),
            metrics()));

    private AiTelemetrySchema() {
    }

    private static TelemetrySpanDefinition requestSpan(String description) {
        return new TelemetrySpanDefinition(
            description,
            new TelemetryParentDefinition.Any(),
            Map.of(
                "provider", PROVIDER,
                "api", API,
                "model", MODEL,
                "reasoning", REASONING),
            Map.of(
                "stop", STOP_END,
                "usage.input", USAGE_INPUT,
                "usage.output", USAGE_OUTPUT,
                "usage.cache_read", USAGE_CACHE_READ,
                "usage.cache_write", USAGE_CACHE_WRITE,
                "cost.total", COST_TOTAL,
                "response.id", RESPONSE_ID),
            Map.of(
                "pi.ai.first_chunk", new TelemetryEventDefinition(
                    "First normalized stream event delivered.", Map.of()),
                "pi.ai.block", new TelemetryEventDefinition(
                    "A completed content block; content is never recorded.",
                    Map.of(
                        "type", BLOCK_TYPE,
                        "index", BLOCK_INDEX,
                        "chars", BLOCK_CHARS,
                        "tool", BLOCK_TOOL))),
            new TelemetryStatusDefinition(ERROR_WHEN));
    }

    private static Map<String, TelemetryMetricDefinition> metrics() {
        return Map.of(
            "pi.ai.request.duration", new TelemetryMetricDefinition.Histogram(
                "Logical request or poll duration.", "ms",
                Map.of("provider", PROVIDER, "model", MODEL, "stop", STOP_METRIC),
                null),
            "pi.ai.first_chunk.duration", new TelemetryMetricDefinition.Histogram(
                "Time to the first normalized stream event.", "ms",
                Map.of("provider", PROVIDER, "model", MODEL),
                null),
            "pi.ai.tokens", new TelemetryMetricDefinition.Counter(
                "Reported model token usage; pending and deferred polls are excluded.",
                "token",
                Map.of("provider", PROVIDER, "model", MODEL, "kind", TOKEN_KIND)),
            "pi.ai.cost", new TelemetryMetricDefinition.Counter(
                "Reported model cost; pending and deferred polls are excluded.",
                "USD",
                Map.of("provider", PROVIDER, "model", MODEL)));
    }

    private static StringAttribute string(String description, Boolean required,
                                          List<String> values, TelemetryCardinality cardinality) {
        return new TelemetryAttributeDefinition.StringAttribute(
            description, null, cardinality, required, values, null);
    }

    private static NumberAttribute number(String description) {
        return number(description, null);
    }

    private static NumberAttribute number(String description, Boolean required) {
        return new TelemetryAttributeDefinition.NumberAttribute(
            description, null, null, required, null, null);
    }
}
