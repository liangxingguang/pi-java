package com.pijava.ai.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider response-id assignment and first-value timing. */
class StreamPartialBuilderResponseIdTest {

    @Test
    void assignResponseIdUsesLatestAuthoritativeValue() {
        StreamPartialBuilder builder = new StreamPartialBuilder();
        assertThat(builder.snapshot().responseId()).isNull();
        builder.assignResponseId("created-id");
        assertThat(builder.snapshot().responseId()).isEqualTo("created-id");
        builder.assignResponseId("terminal-id");
        assertThat(builder.emitStart().partial().responseId()).isEqualTo("terminal-id");
    }

    @Test
    void firstNonBlankResponseIdIgnoresOnlyNullAndEmpty() {
        StreamPartialBuilder builder = new StreamPartialBuilder();
        builder.noteResponseIdFirstNonBlank(null);
        builder.noteResponseIdFirstNonBlank("");
        builder.noteResponseIdFirstNonBlank("first-id");
        builder.noteResponseIdFirstNonBlank("later-id");
        assertThat(builder.snapshot().responseId()).isEqualTo("first-id");

        StreamPartialBuilder whitespace = new StreamPartialBuilder();
        whitespace.noteResponseIdFirstNonBlank(" ");
        whitespace.noteResponseIdFirstNonBlank("later-id");
        assertThat(whitespace.snapshot().responseId()).isEqualTo(" ");
    }
}
