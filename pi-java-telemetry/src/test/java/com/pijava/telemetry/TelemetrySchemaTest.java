package com.pijava.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetrySchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defineTelemetrySchemaReturnsTheSameSerializableData() throws Exception {
        Map<String, TelemetryAttributeDefinition> attributes = new LinkedHashMap<>();
        attributes.put("string", new TelemetryAttributeDefinition.StringAttribute(
            "string", null, TelemetryCardinality.LOW, true, List.of("in"), List.of("in")));
        attributes.put("number", new TelemetryAttributeDefinition.NumberAttribute(
            "number", null, null, true, List.of(1.0), List.of(1.0)));
        attributes.put("boolean", new TelemetryAttributeDefinition.BooleanAttribute(
            "boolean", null, null, false, List.of(true), List.of(false)));
        attributes.put("strings", new TelemetryAttributeDefinition.StringArrayAttribute(
            "strings", null, null, false, List.of("a"), List.of(List.of("a"))));
        attributes.put("numbers", new TelemetryAttributeDefinition.NumberArrayAttribute(
            "numbers", null, null, false, List.of(1.0), List.of(List.of(1.0))));
        attributes.put("booleans", new TelemetryAttributeDefinition.BooleanArrayAttribute(
            "booleans", null, null, false, List.of(true), List.of(List.of(false))));
        var span = new TelemetrySpanDefinition(
            "Request",
            new TelemetryParentDefinition.Any(),
            attributes,
            Map.of(),
            Map.of("finished", new TelemetryEventDefinition("Finished", Map.of())),
            new TelemetryStatusDefinition("outcome == \"error\""));
        var counter = new TelemetryMetricDefinition.Counter(
            "Encoded bytes", "By", Map.of());
        var schema = new TelemetrySchemaDefinition(
            1, Map.of("request", span), Map.of("bytes", counter));

        assertThat(TelemetrySchemas.defineTelemetrySchema(schema)).isSameAs(schema);

        var json = mapper.readTree(mapper.writeValueAsString(schema));
        assertThat(json.at("/spans/request/parents/kind").asText()).isEqualTo("any");
        assertThat(json.at("/spans/request/status/default").asText()).isEqualTo("ok");
        assertThat(json.at("/metrics/bytes/kind").asText()).isEqualTo("counter");
        assertThat(json.at("/spans/request/startAttributes/string/type").asText()).isEqualTo("string");
        assertThat(json.at("/spans/request/startAttributes/number/type").asText()).isEqualTo("number");
        assertThat(json.at("/spans/request/startAttributes/boolean/type").asText()).isEqualTo("boolean");
        assertThat(json.at("/spans/request/startAttributes/strings/type").asText()).isEqualTo("string[]");
        assertThat(json.at("/spans/request/startAttributes/numbers/type").asText()).isEqualTo("number[]");
        assertThat(json.at("/spans/request/startAttributes/booleans/type").asText()).isEqualTo("boolean[]");
        assertThat(json.at("/spans/request/events/finished/description").asText()).isEqualTo("Finished");
    }

    @Test
    void optionalSchemaFieldsAreAbsentInsteadOfJsonNull() throws Exception {
        var span = new TelemetrySpanDefinition(
            "Request", new TelemetryParentDefinition.Any(), Map.of(), Map.of(), null,
            new TelemetryStatusDefinition("failed"));
        var schema = new TelemetrySchemaDefinition(1, Map.of("request", span), null);

        var json = mapper.readTree(mapper.writeValueAsString(schema));

        assertThat(json.has("metrics")).isFalse();
        assertThat(json.at("/spans/request").has("events")).isFalse();
    }

    @Test
    void typedSpanStarterBindsExplicitParentsWithoutRuntimeSchemaValidation() {
        var telemetry = new InMemoryTelemetryContext();
        var schema = new TelemetrySchemaDefinition(1, Map.of(), null);
        var starter = TelemetrySchemas.createTypedSpanStarter(telemetry, schema);

        var result = starter.startSpan("unknown.root", Map.of("free", "value"), (root, startChild) ->
            startChild.startSpan("unknown.child", Map.of(), (child, startGrandchild) ->
                startGrandchild.startSpan("unknown.grandchild", Map.of(), (grandchild, unused) -> 7)));

        assertThat(result).isEqualTo(7);
        var spans = telemetry.getSpans();
        assertThat(spans).extracting(RecordedTelemetrySpan::name)
            .containsExactly("unknown.root", "unknown.child", "unknown.grandchild");
        assertThat(spans.get(0).parentId()).isNull();
        assertThat(spans.get(1).parentId()).isEqualTo(spans.get(0).id());
        assertThat(spans.get(2).parentId()).isEqualTo(spans.get(1).id());
        assertThat(spans.get(0).attributes()).containsEntry("free", "value");
    }
}
