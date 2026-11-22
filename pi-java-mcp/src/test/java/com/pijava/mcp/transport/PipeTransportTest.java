package com.pijava.mcp.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.protocol.jsonrpc.McpConnectionClosedError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP over a program that something else runs (pi {@code transports/pipe.ts}).
 */
class PipeTransportTest {

    private static PipeTransport transport(PipeFixture pipe) {
        return new PipeTransport(new PipeTransport.Options(pipe.opener()));
    }

    private static PipeTransport transport(PipeFixture pipe, int maxMessageBytes) {
        return new PipeTransport(new PipeTransport.Options(pipe.opener(), maxMessageBytes, null, 0));
    }

    @Test
    void parsesMessagesThatArriveInChunks() throws Exception {
        var pipe = new PipeFixture();
        var messages = new ArrayList<Object>();
        var transport = transport(pipe);
        transport.onMessage(messages::add);
        transport.start();

        pipe.stdout("{\"jsonrpc\":\"2.0\",\"id\":1,");
        assertThat(messages).isEmpty();
        pipe.stdout("\"result\":{}}\n");

        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("id")).isEqualTo(1);
    }

    @Test
    void sendWritesOneFramedLine() throws Exception {
        var pipe = new PipeFixture();
        var transport = transport(pipe);
        transport.start();
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", "initialize");

        transport.send(message);

        assertThat(pipe.written()).containsExactly(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n");
    }

    @Test
    void anExitReportsAnIncompleteMessageThenCloses() throws Exception {
        var pipe = new PipeFixture();
        var errors = new ArrayList<Throwable>();
        var closed = new AtomicInteger();
        var transport = transport(pipe);
        transport.onError(errors::add);
        transport.onClose(closed::incrementAndGet);
        transport.start();

        pipe.stdout("{partial");
        pipe.exit();

        assertThat(errors).singleElement().satisfies(error -> assertThat(error)
                .hasMessage("MCP server closed with an incomplete JSON-RPC message"));
        assertThat(closed).hasValue(1);
    }

    @Test
    void aRejectedExitIsAnErrorThenClose() throws Exception {
        var pipe = new PipeFixture();
        var errors = new ArrayList<Throwable>();
        var closed = new AtomicInteger();
        var transport = transport(pipe);
        transport.onError(errors::add);
        transport.onClose(closed::incrementAndGet);
        transport.start();

        pipe.fail(new IllegalStateException("the program died"));

        assertThat(errors).singleElement().satisfies(error ->
                assertThat(error).hasMessage("the program died"));
        assertThat(closed).hasValue(1);
    }

    @Test
    void closingWhileTheProgramStartsShutsItDownAndFails() {
        var pipe = new PipeFixture();
        var transport = new PipeTransport[1];
        transport[0] = new PipeTransport(new PipeTransport.Options(handlers -> {
            transport[0].close();
            return pipe;
        }));

        assertThatThrownBy(() -> transport[0].start())
                .isInstanceOf(McpConnectionClosedError.class);
        // The program started after close() found nothing to shut down: it is closed here.
        assertThat(pipe.closed()).isTrue();
    }

    @Test
    void closingClosesStdinAndKillsAProgramThatLingers() throws Exception {
        var pipe = new PipeFixture();
        var transport = new PipeTransport(new PipeTransport.Options(pipe.opener(), 0, null, 50));
        transport.start();

        transport.close();

        assertThat(pipe.stdinClosed()).isTrue();
        assertThat(pipe.closed()).isTrue();
    }

    @Test
    void aProgramThatExitsAfterStdinClosesIsNotKilled() throws Exception {
        var pipe = new PipeFixture().exitWhenStdinCloses();
        var transport = transport(pipe);
        transport.start();

        transport.close();

        assertThat(pipe.stdinClosed()).isTrue();
        assertThat(pipe.closed()).isFalse();
    }

    @Test
    void closingAfterTheProgramEndedDoesNotTouchItsStdin() throws Exception {
        var pipe = new PipeFixture();
        var transport = transport(pipe);
        transport.start();
        pipe.exit();

        transport.close();

        assertThat(pipe.stdinClosed()).isFalse();
    }

    @Test
    void sendingAfterTheProgramEndedFails() throws Exception {
        var pipe = new PipeFixture();
        var transport = transport(pipe);
        transport.start();
        pipe.exit();

        assertThatThrownBy(() -> transport.send(Map.of("jsonrpc", "2.0")))
                .isInstanceOf(McpConnectionClosedError.class);
    }

    @Test
    void anOversizedIncompleteMessageIsAnError() throws Exception {
        var pipe = new PipeFixture();
        var errors = new ArrayList<Throwable>();
        var transport = transport(pipe, 10);
        transport.onError(errors::add);
        transport.start();

        pipe.stdout("0123456789ABCDEF");

        assertThat(errors).singleElement().satisfies(error ->
                assertThat(error).hasMessage("MCP message exceeds 10 bytes"));
    }

    @Test
    void stderrKeepsATailAndReportsEveryChunk() throws Exception {
        var chunks = new ArrayList<String>();
        var pipe = new PipeFixture();
        var transport = new PipeTransport(new PipeTransport.Options(pipe.opener(), 0, chunks::add, 0));
        transport.start();

        pipe.stderr("warning: hi");

        assertThat(transport.stderr()).isEqualTo("warning: hi");
        assertThat(chunks).containsExactly("warning: hi");
    }

    @Test
    void startingTwiceFails() throws Exception {
        var pipe = new PipeFixture();
        var transport = transport(pipe);
        transport.start();

        assertThatThrownBy(transport::start)
                .isInstanceOf(IOException.class)
                .hasMessage("MCP pipe transport already started");
    }

    @Test
    void aClosedTransportReportsCloseWithoutAProgram() {
        var transport = new PipeTransport(new PipeTransport.Options(handlers -> {
            throw new IllegalStateException("never opened");
        }));
        var closed = new AtomicInteger();
        transport.onClose(closed::incrementAndGet);

        transport.close();

        assertThat(closed).hasValue(1);
    }
}
