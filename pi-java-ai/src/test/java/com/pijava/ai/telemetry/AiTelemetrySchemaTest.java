package com.pijava.ai.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.telemetry.TelemetryAttributeDefinition;
import com.pijava.telemetry.TelemetryAttributeDefinition.NumberAttribute;
import com.pijava.telemetry.TelemetryAttributeDefinition.StringAttribute;
import com.pijava.telemetry.TelemetryCardinality;
import com.pijava.telemetry.TelemetryMetricDefinition;
import com.pijava.telemetry.TelemetryParentDefinition;
import com.pijava.telemetry.TelemetrySchemaDefinition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/13 §2.1／§6.1：AI telemetry schema 逐项对位 pi `ai/src/telemetry.ts:14-105`。
 *
 * <p>只断言 schema 的声明面（名字、description、required、values、unit、cardinality、
 * status）；运行时不做 schema 校验（D201）。</p>
 */
class AiTelemetrySchemaTest {

    private static final TelemetrySchemaDefinition SCHEMA =
        AiTelemetrySchema.AI_TELEMETRY_SCHEMA;

    @Test
    void declaresVersionTwoSpansAndFourMetrics() {
        assertThat(SCHEMA.version()).isEqualTo(1);
        assertThat(SCHEMA.spans()).containsOnlyKeys("pi.ai.request", "pi.ai.poll");
        assertThat(SCHEMA.metrics()).containsOnlyKeys(
            "pi.ai.request.duration",
            "pi.ai.first_chunk.duration",
            "pi.ai.tokens",
            "pi.ai.cost");
    }

    @Test
    void requestSpanCarriesPiStartAttributes() {
        var request = SCHEMA.spans().get("pi.ai.request");

        assertThat(request.description())
            .isEqualTo("One logical model request, including authentication and provider setup.");
        assertThat(request.parents()).isInstanceOf(TelemetryParentDefinition.Any.class);

        var start = request.startAttributes();
        assertThat(start).containsOnlyKeys("provider", "api", "model", "reasoning");
        assertThat((StringAttribute) start.get("provider"))
            .extracting(StringAttribute::description, StringAttribute::required)
            .containsExactly("Provider identifier.", true);
        assertThat((StringAttribute) start.get("api"))
            .extracting(StringAttribute::description, StringAttribute::required)
            .containsExactly("API identifier.", true);
        assertThat((StringAttribute) start.get("model"))
            .extracting(StringAttribute::description, StringAttribute::required)
            .containsExactly("Requested model identifier.", true);
        assertThat((StringAttribute) start.get("reasoning"))
            .extracting(StringAttribute::description, StringAttribute::required)
            .containsExactly("Scalar requested reasoning level.", false);
    }

    @Test
    void requestSpanEndAttributesMatchPi() {
        var end = SCHEMA.spans().get("pi.ai.request").endAttributes();

        assertThat(end).containsOnlyKeys(
            "stop", "usage.input", "usage.output", "usage.cache_read",
            "usage.cache_write", "cost.total", "response.id");

        var stop = (StringAttribute) end.get("stop");
        assertThat(stop.description()).isEqualTo("Normalized stop reason.");
        assertThat(stop.values()).containsExactly(
            "pending", "stop", "length", "toolUse", "error", "aborted", "deferred");
        assertThat(stop.required()).isNull();

        assertThat(((NumberAttribute) end.get("usage.input")).description())
            .isEqualTo("Uncached input tokens.");
        assertThat(((NumberAttribute) end.get("usage.output")).description())
            .isEqualTo("Output tokens.");
        assertThat(((NumberAttribute) end.get("usage.cache_read")).description())
            .isEqualTo("Cache-read tokens.");
        assertThat(((NumberAttribute) end.get("usage.cache_write")).description())
            .isEqualTo("Cache-write tokens.");
        assertThat(((NumberAttribute) end.get("cost.total")).description())
            .isEqualTo("Total request cost in USD.");

        var responseId = (StringAttribute) end.get("response.id");
        assertThat(responseId.description()).isEqualTo("Provider response identifier.");
        assertThat(responseId.cardinality()).isEqualTo(TelemetryCardinality.HIGH);
        assertThat(responseId.required()).isNull();
    }

    @Test
    void requestSpanEventsAreContentFree() {
        var events = SCHEMA.spans().get("pi.ai.request").events();

        assertThat(events).containsOnlyKeys("pi.ai.first_chunk", "pi.ai.block");
        assertThat(events.get("pi.ai.first_chunk").description())
            .isEqualTo("First normalized stream event delivered.");
        assertThat(events.get("pi.ai.first_chunk").attributes()).isEmpty();

        var block = events.get("pi.ai.block");
        assertThat(block.description())
            .isEqualTo("A completed content block; content is never recorded.");
        assertThat(block.attributes()).containsOnlyKeys("type", "index", "chars", "tool");

        var type = (StringAttribute) block.attributes().get("type");
        assertThat(type.description()).isEqualTo("Block kind.");
        assertThat(type.values()).containsExactly("text", "thinking", "tool_call");
        assertThat(type.required()).isTrue();

        assertThat((NumberAttribute) block.attributes().get("index"))
            .extracting(NumberAttribute::description, NumberAttribute::required)
            .containsExactly("Content block index.", true);
        assertThat((NumberAttribute) block.attributes().get("chars"))
            .extracting(NumberAttribute::description, NumberAttribute::required)
            .containsExactly("UTF-16 character count.", true);
        assertThat((StringAttribute) block.attributes().get("tool"))
            .extracting(StringAttribute::description, StringAttribute::required)
            .containsExactly("Tool name, for tool-call blocks only.", false);
    }

    @Test
    void statusTextIsVerbatim() {
        assertThat(SCHEMA.spans().get("pi.ai.request").status().errorWhen())
            .isEqualTo("Stop reason is error: provider_error with at most 500 "
                + "error-message characters. Aborted stays ok.");
    }

    @Test
    void pollSpanDiffersOnlyByDescription() {
        var request = SCHEMA.spans().get("pi.ai.request");
        var poll = SCHEMA.spans().get("pi.ai.poll");

        assertThat(poll.description())
            .isEqualTo("One deferred-response poll, not the deferred request's lifetime.");
        assertThat(poll.parents()).isEqualTo(request.parents());
        assertThat(poll.startAttributes()).isEqualTo(request.startAttributes());
        assertThat(poll.endAttributes()).isEqualTo(request.endAttributes());
        assertThat(poll.events()).isEqualTo(request.events());
        assertThat(poll.status()).isEqualTo(request.status());
    }

    @Test
    void durationMetricsCarryRequiredStopCopy() {
        var requestDuration = (TelemetryMetricDefinition.Histogram)
            SCHEMA.metrics().get("pi.ai.request.duration");
        assertThat(requestDuration.description())
            .isEqualTo("Logical request or poll duration.");
        assertThat(requestDuration.unit()).isEqualTo("ms");
        assertThat(requestDuration.attributes()).containsOnlyKeys("provider", "model", "stop");
        assertThat(requestDuration.attributes().get("stop").required()).isTrue();
        assertThat(requestDuration.boundaries()).isNull();

        var firstChunk = (TelemetryMetricDefinition.Histogram)
            SCHEMA.metrics().get("pi.ai.first_chunk.duration");
        assertThat(firstChunk.description())
            .isEqualTo("Time to the first normalized stream event.");
        assertThat(firstChunk.unit()).isEqualTo("ms");
        assertThat(firstChunk.attributes()).containsOnlyKeys("provider", "model");
    }

    @Test
    void countersDeclareTokensAndCost() {
        var tokens = (TelemetryMetricDefinition.Counter) SCHEMA.metrics().get("pi.ai.tokens");
        assertThat(tokens.description())
            .isEqualTo("Reported model token usage; pending and deferred polls are excluded.");
        assertThat(tokens.unit()).isEqualTo("token");
        assertThat(tokens.attributes()).containsOnlyKeys("provider", "model", "kind");
        assertThat((StringAttribute) tokens.attributes().get("kind"))
            .extracting(StringAttribute::description, StringAttribute::required)
            .containsExactly("Token usage category.", true);
        assertThat(((StringAttribute) tokens.attributes().get("kind")).values())
            .containsExactly("input", "output", "cache_read", "cache_write");

        var cost = (TelemetryMetricDefinition.Counter) SCHEMA.metrics().get("pi.ai.cost");
        assertThat(cost.description())
            .isEqualTo("Reported model cost; pending and deferred polls are excluded.");
        assertThat(cost.unit()).isEqualTo("USD");
        assertThat(cost.attributes()).containsOnlyKeys("provider", "model");
    }

    @Test
    void serializesWithPiWireKeys() {
        var tree = new ObjectMapper().valueToTree(SCHEMA);

        assertThat(tree.path("spans").path("pi.ai.request").path("parents").path("kind").asText())
            .isEqualTo("any");
        assertThat(tree.path("spans").path("pi.ai.request").path("status").path("default").asText())
            .isEqualTo("ok");
        assertThat(tree.path("spans").path("pi.ai.request").path("startAttributes")
            .path("provider").path("type").asText()).isEqualTo("string");
        assertThat(tree.path("spans").path("pi.ai.request").path("startAttributes")
            .path("provider").path("required").asBoolean()).isTrue();
        assertThat(tree.path("metrics").path("pi.ai.request.duration").path("kind").asText())
            .isEqualTo("histogram");
        assertThat(tree.path("metrics").path("pi.ai.tokens").path("kind").asText())
            .isEqualTo("counter");
        var kindValues = tree.path("metrics").path("pi.ai.tokens").path("attributes")
            .path("kind").path("values");
        assertThat(kindValues.isArray()).isTrue();
        assertThat(kindValues).hasSize(4);
        assertThat(kindValues.get(0).asText()).isEqualTo("input");
        assertThat(kindValues.get(1).asText()).isEqualTo("output");
        assertThat(kindValues.get(2).asText()).isEqualTo("cache_read");
        assertThat(kindValues.get(3).asText()).isEqualTo("cache_write");
    }
}
