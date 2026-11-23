package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.tui.util.ProgramStatusProtocol.Kind;
import com.pijava.tui.util.ProgramStatusProtocol.State;
import com.pijava.tui.util.ProgramStatusProtocol.Status;

/** docs/26 §4.3（K39）：OSC 7501 编码。 */
class ProgramStatusProtocolTest {

    private static String decodeMessage(String sequence) {
        String marker = ":msg=";
        int start = sequence.indexOf(marker);
        if (start < 0) {
            return null;
        }
        String encoded = sequence.substring(start + marker.length(), sequence.length() - 2);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Test
    void workingCarriesAppAndSessionName() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.WORKING, "pi", null, "My session"));
        assertThat(sequence).startsWith("\u001b]7501;state=working:app=pi:msg=");
        assertThat(sequence).endsWith("\u001b\\");
        assertThat(decodeMessage(sequence)).isEqualTo("My session");
    }

    @Test
    void blockedCarriesKind() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.BLOCKED, "pi", Kind.QUESTION, "Pick one"));
        assertThat(sequence).contains("state=blocked:app=pi:kind=question:msg=");
        assertThat(decodeMessage(sequence)).isEqualTo("Pick one");
    }

    @Test
    void kindOnlyAppearsWhenBlocked() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.WORKING, "pi", Kind.AUTH, null));
        assertThat(sequence).doesNotContain("kind=");
    }

    @Test
    void blockedWithoutKindOmitsKind() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.BLOCKED, "pi", null, "Q"));
        assertThat(sequence).doesNotContain("kind=");
    }

    @Test
    void invalidAppIsOmitted() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.IDLE, "not valid app", null, null));
        assertThat(sequence).isEqualTo("\u001b]7501;state=idle\u001b\\");
    }

    @Test
    void controlCharactersBecomeSpacesAndTrim() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.ERROR, "pi", null, "  boom\nnext\tline  "));
        assertThat(decodeMessage(sequence)).isEqualTo("boom next line");
    }

    @Test
    void longMessageTruncatesToUtf8Limit() {
        String sequence = ProgramStatusProtocol.format(
            new Status(State.DONE, "pi", null, "x".repeat(3000)));
        assertThat(decodeMessage(sequence)).hasSize(2048);
    }

    @Test
    void clearHasNoPayload() {
        assertThat(ProgramStatusProtocol.format(Status.of(State.CLEAR)))
            .isEqualTo("\u001b]7501;state=clear\u001b\\");
    }

    @Test
    void replyDetection() {
        assertThat(ProgramStatusProtocol.isReply("\u001b]7501;?\u001b\\")).isTrue();
        assertThat(ProgramStatusProtocol.isReply("\u001b]7501;?\u0007")).isTrue();
        assertThat(ProgramStatusProtocol.isReply("\u001b]7501;state=idle\u001b\\")).isFalse();
        assertThat(ProgramStatusProtocol.isReply(null)).isFalse();
    }

    @Test
    void enabledHonorsEnvironmentOverride() {
        assertThat(ProgramStatusProtocol.enabled(Map.of("PI_PROGRAM_STATUS", "1"))).isTrue();
        assertThat(ProgramStatusProtocol.enabled(Map.of("PI_PROGRAM_STATUS", "0"))).isFalse();
        assertThat(ProgramStatusProtocol.enabled(Map.of())).isTrue();
    }
}
