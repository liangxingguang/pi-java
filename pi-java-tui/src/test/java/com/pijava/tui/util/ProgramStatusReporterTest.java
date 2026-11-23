package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.util.ProgramStatusProtocol.Kind;
import com.pijava.tui.util.ProgramStatusReporter.BlockedStatus;

/** docs/26 §4.3（K39）：OSC 7501 状态机。 */
class ProgramStatusReporterTest {

    private final List<String> emitted = new ArrayList<>();
    private final ProgramStatusReporter reporter =
        new ProgramStatusReporter(emitted::add, () -> "sess", true);

    private static Message.AssistantMessage assistant(StopReason reason, String error) {
        return new Message.AssistantMessage(List.of(), reason, null,
            "api", "provider", "model", Usage.of(0, 0), Instant.EPOCH, error, null);
    }

    private String last() {
        assertThat(emitted).isNotEmpty();
        return emitted.get(emitted.size() - 1);
    }

    private static String decodeMessage(String sequence) {
        int start = sequence.indexOf(":msg=");
        if (start < 0) {
            return null;
        }
        String encoded = sequence.substring(start + 5, sequence.length() - 2);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Test
    void turnReportsWorkingThenSettledDone() {
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        assertThat(last()).contains("state=working");
        assertThat(decodeMessage(last())).isEqualTo("sess");

        reporter.handleEvent(new AgentSessionEvent.AgentSettled(false));
        assertThat(last()).contains("state=done");
        assertThat(decodeMessage(last())).isEqualTo("sess");
    }

    @Test
    void assistantErrorUsesFirstLine() {
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        reporter.handleEvent(new AgentSessionEvent.MessageEnd(
            assistant(StopReason.ERROR, "boom\nstack trace")));
        reporter.handleEvent(new AgentSessionEvent.AgentSettled(false));

        assertThat(last()).contains("state=error");
        assertThat(decodeMessage(last())).isEqualTo("boom");
    }

    @Test
    void abortedSettleReportsIdle() {
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        reporter.handleEvent(new AgentSessionEvent.AgentSettled(true));
        assertThat(last()).contains("state=idle");
        assertThat(last()).doesNotContain(":msg=");
    }

    @Test
    void blockedStackUsesMostRecentSource() {
        reporter.setBlocked("a", BlockedStatus.question("Q1"));
        assertThat(last()).contains("state=blocked:app=pi:kind=question");
        assertThat(decodeMessage(last())).isEqualTo("Q1");

        reporter.setBlocked("b", new BlockedStatus(Kind.PERMISSION, "Allow?"));
        assertThat(decodeMessage(last())).isEqualTo("Allow?");

        reporter.setBlocked("b", null);
        assertThat(decodeMessage(last())).isEqualTo("Q1");

        reporter.setBlocked("a", null);
        assertThat(last()).contains("state=idle");
    }

    @Test
    void deduplicatesIdenticalReports() {
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        int before = emitted.size();
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        assertThat(emitted).hasSize(before);
    }

    @Test
    void clearIsIdempotentAndStopsDuplicates() {
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        reporter.clear();
        assertThat(last()).isEqualTo("\u001b]7501;state=clear\u001b\\");
        int before = emitted.size();
        reporter.clear();
        assertThat(emitted).hasSize(before);
    }

    @Test
    void disabledReporterEmitsNothing() {
        reporter.setEnabled(false);
        reporter.handleEvent(new AgentSessionEvent.AgentStart());
        reporter.setBlocked("a", BlockedStatus.question("Q"));
        reporter.clear();
        assertThat(emitted).isEmpty();
    }
}
