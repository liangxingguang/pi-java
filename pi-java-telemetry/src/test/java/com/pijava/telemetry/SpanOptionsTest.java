package com.pijava.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpanOptionsTest {

    @Test
    void initialNullAttributesAreOmittedBeforeTheAdapterRuns() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("start", "value");
        attributes.put("ignored", null);

        var options = new SpanOptions("recording", attributes);

        assertThat(options.attributes()).containsOnly(Map.entry("start", "value"));
    }

    @Test
    void initialArrayAttributesAreDefensivelyCopied() {
        String[] tags = {"original"};
        var options = new SpanOptions("recording", Map.of("tags", tags));

        tags[0] = "mutated";

        assertThat((String[]) options.attributes().get("tags")).containsExactly("original");
    }
}
