package com.pijava.protocol;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-9a: CborCodec — 全 sealed 层次 round-trip。
 */
class CborCodecTest {

    private final CborCodec cbor = new CborCodec();

    private static SessionSnapshot snapshot() {
        return new SessionSnapshot(
            "s1", "name", "/cwd", 1000L, 2000L, SessionPhase.TURN,
            new ModelRef("anthropic", "claude-fable-5"),
            ProtocolThinkingLevel.MEDIUM, true, false, 3L,
            List.of(new TranscriptItem("message", Map.of("role", "assistant"))),
            List.of(Map.of("text", "hi")), 1);
    }

    @Test
    void clientMessageRoundTrip() {
        roundTrip(new ClientMessage.ClientHello(1));
        roundTrip(new ClientMessage.RequestEnvelope("id-1",
            new Command.Prompt("s1", "hello")));
    }

    @Test
    void serverMessageRoundTrip() {
        roundTrip(new ServerMessage.ServerHello(1, "conn-1",
            new ServerSnapshot("server-1", 1, 0L, List.of(), List.of())));
        roundTrip(new ServerMessage.ServerHelloError(
            ProtocolError.of(ProtocolErrorCode.VERSION, "bad version")));
        roundTrip(new ServerMessage.ResponseEnvelope("id-1",
            new CommandResult.PromptResult(snapshot()), null));
        roundTrip(new ServerMessage.EventEnvelope(
            new ServerEvent.SessionRemoved("s1")));
    }

    @Test
    void allCommandsRoundTrip() {
        roundTrip(new Command.List());
        roundTrip(new Command.Create("/cwd", "name",
            new ModelRef("openai", "gpt-5"), ProtocolThinkingLevel.HIGH));
        roundTrip(new Command.Attach("s1"));
        roundTrip(new Command.Detach("s1"));
        roundTrip(new Command.Prompt("s1", "hi"));
        roundTrip(new Command.Steer("s1", "steer"));
        roundTrip(new Command.Abort("s1"));
        roundTrip(new Command.SetModel("s1", new ModelRef("deepseek", "deepseek-chat")));
        roundTrip(new Command.SetThinking("s1", ProtocolThinkingLevel.LOW));
    }

    @Test
    void allCommandResultsRoundTrip() {
        roundTrip(new CommandResult.ListResult(List.of(
            new SessionMetadata("s1", 1000L, null, null, "name", "/cwd"))));
        roundTrip(new CommandResult.DetachResult("s1"));
        roundTrip(new CommandResult.CreateResult(snapshot()));
        roundTrip(new CommandResult.AttachResult(snapshot()));
        roundTrip(new CommandResult.PromptResult(snapshot()));
        roundTrip(new CommandResult.SteerResult(snapshot()));
        roundTrip(new CommandResult.AbortResult(snapshot()));
        roundTrip(new CommandResult.SetModelResult(snapshot()));
        roundTrip(new CommandResult.SetThinkingResult(snapshot()));
    }

    @Test
    void allServerEventsRoundTrip() {
        roundTrip(new ServerEvent.ServerSnapshotEvent(
            new ServerSnapshot("server-1", 1, 0L, List.of(), List.of())));
        roundTrip(new ServerEvent.SessionSnapshotEvent(snapshot()));
        roundTrip(new ServerEvent.SessionProgress("s1",
            new TranscriptProgress("text_delta", Map.of("delta", "hi"))));
        roundTrip(new ServerEvent.SessionRemoved("s1"));
    }

    private void roundTrip(Object value) {
        byte[] encoded = cbor.encode(value);
        var back = cbor.decode(encoded, value.getClass());
        assertThat(back).isEqualTo(value);
    }
}
