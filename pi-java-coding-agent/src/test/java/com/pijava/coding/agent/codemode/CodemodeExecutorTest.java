package com.pijava.coding.agent.codemode;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.ContentBlock;
import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.Protocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Executor tests with a scripted VM launcher; no Endive runtime is started. */
class CodemodeExecutorTest {

    @Test
    void recordsNestedCallsAndKeepsHeader() throws Exception {
        FakeContext ctx = new FakeContext();
        AgentTool<?, ?> tool = CodemodeLoadoutTest.Tools.of("read", "Read file",
            com.pijava.agent.tool.ToolExposure.DIRECT);
        PiLoop.ToolOutcome nested = ScriptedContext.outcome("call-1", "file text", false);
        ctx.callable(tool).outcome("read", nested);
        CodeModeData.Result scripted = CodeModeData.Result.success("file text", List.of(),
            List.of(new CodeModeData.CallRecord("read", "ok", 1.0)), CodeModeData.StoreWrites.empty());
        ScriptedLauncher launcher = new ScriptedLauncher(scripted, "read");
        var result = new CodemodeExecutor(ctx, launcher, (bytes, extension) -> "spilled.txt")
            .execute("root", Map.of("code", "return await tools.read({path:'x'})"), null, null);
        assertThat(result.details().calls()).hasSize(1);
        assertThat(result.details().calls().get(0).id()).isEqualTo("call-1");
        assertThat(result.details().calls().get(0).status()).isEqualTo("ok");
        assertThat(text(result)).startsWith("Script completed\nWall time ");
    }

    @Test
    void formatsOutputAndWritesStoreOnSuccess() throws Exception {
        FakeContext ctx = new FakeContext();
        CodeModeData.Result scripted = CodeModeData.Result.success("done",
            List.of(new CodeModeData.Text("first", false),
                new CodeModeData.Text("second", false),
                new CodeModeData.Text("log line", true),
                new CodeModeData.Image(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), "image/png")),
            List.of(), new CodeModeData.StoreWrites(Map.of("k", "v"), List.of("old")));
        var result = new CodemodeExecutor(ctx, new ScriptedLauncher(scripted),
            (bytes, extension) -> "image.png")
            .execute("root", Map.of("code", "return 1"), null, null);
        assertThat(result.content().stream()
            .filter(block -> block instanceof ContentBlock.TextContent)
            .map(block -> ((ContentBlock.TextContent) block).text()))
            .anyMatch(text -> text.contains("==> text 1/3 <==\nfirst"))
            .anyMatch(text -> text.contains("==> text 2/3 <==\nsecond"))
            .anyMatch(text -> text.contains("==> text 3/3 <==\ndone"))
            .anyMatch(text -> text.contains("<console_output>\nlog line\n</console_output>"))
            .anyMatch(text -> text.contains("[Image saved to image.png (image/png, 3 B)]"));
        assertThat(ctx.appended()).hasSize(1);
        assertThat(ctx.appended().get(0)).containsEntry("__customType", CodemodeStore.ENTRY_TYPE);
    }

    @Test
    void truncatesOutputAndRecordsFullOutputPath() throws Exception {
        FakeContext ctx = new FakeContext();
        String longText = "x".repeat(400);
        CodeModeData.Result scripted = CodeModeData.Result.success(null,
            List.of(new CodeModeData.Text(longText, false)), List.of(), CodeModeData.StoreWrites.empty());
        var result = new CodemodeExecutor(ctx, new ScriptedLauncher(scripted),
            (bytes, extension) -> "full.txt")
            .execute("root", Map.of("code", "// @options: {\"max_output_tokens\": 10}\nreturn 1"), null, null);
        assertThat(text(result)).contains("Warning: truncated output",
            "[Full output: full.txt (read with offset/limit)]");
        assertThat(result.details().fullOutputPath()).isEqualTo("full.txt");
    }

    @Test
    void failureTailKeepsPartialOutputAndCallSummary() throws Exception {
        FakeContext ctx = new FakeContext();
        CodeModeData.Result scripted = CodeModeData.Result.failure(
            CodeModeData.CodemodeError.of("script", "boom"),
            List.of(new CodeModeData.Text("partial", false)), List.of());
        var result = new CodemodeExecutor(ctx, new ScriptedLauncher(scripted), (bytes, extension) -> "x")
            .execute("root", Map.of("code", "throw new Error('boom')"), null, null);
        assertThat(text(result)).startsWith("Script failed\n")
            .contains("partial", "Script error:\n", "No tool calls were made.");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void structuredContentWinsWhenOutputSchemaDeclaresIt() throws Exception {
        FakeContext ctx = new FakeContext();
        AgentTool<?, ?> tool = CodemodeLoadoutTest.Tools.of("read", "Read file",
            com.pijava.agent.tool.ToolExposure.DIRECT, Map.of("type", "object"));
        ctx.callable(tool);
        var result = new ToolResult<>(List.of(new ContentBlock.TextContent("plain")), null, null, false,
            List.of(), false, Map.of("ok", true));
        var message = new com.pijava.ai.message.Message.ToolResultMessage(
            "nested-1", "read", result.content(), false);
        ctx.outcome("read", new PiLoop.ToolOutcome(message, result, false));
        Object value = CodemodeExecutorSupport.toScriptValue(tool,
            new PiLoop.ToolOutcome(message, result, false));
        assertThat(value).isEqualTo(Map.of("ok", true));
    }

    private static String text(ToolResult<CodemodeDetails> result) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : result.content()) {
            if (block instanceof ContentBlock.TextContent content) {
                text.append(content.text());
            }
        }
        return text.toString();
    }

    /** Launcher that plays a scripted call/terminal exchange; no Endive runtime is started. */
    private static final class ScriptedLauncher implements Supplier<CodeModeVmLauncher> {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
        private final CodeModeData.Result scripted;
        private final String callTool;

        ScriptedLauncher(CodeModeData.Result scripted) {
            this(scripted, null);
        }

        ScriptedLauncher(CodeModeData.Result scripted, String callTool) {
            this.scripted = scripted;
            this.callTool = callTool;
        }

        @Override
        public CodeModeVmLauncher get() {
            return (data, events) -> {
                Channel channel = new Channel(events, scripted);
                if (callTool == null) {
                    channel.emitTerminal();
                } else {
                    events.message(new Protocol.Call(1, "tool", callTool, "{}"));
                }
                return channel;
            };
        }

        /** Channel that sends the terminal message after the host answers the scripted call. */
        private static final class Channel implements CodeModeVmLauncher.VmChannel {
            private final CodeModeVmLauncher.VmEvents events;
            private final CodeModeData.Result scripted;
            private boolean terminal;

            private Channel(CodeModeVmLauncher.VmEvents events, CodeModeData.Result scripted) {
                this.events = events;
                this.scripted = scripted;
            }

            @Override
            public void post(Protocol.HostToVm message) {
                if (message instanceof Protocol.Result) {
                    emitTerminal();
                }
            }

            @Override
            public void stop() {
            }

            private void emitTerminal() {
                if (terminal) {
                    return;
                }
                terminal = true;
                for (CodeModeData.OutputItem item : scripted.output()) {
                    events.message(new Protocol.Output(item));
                }
                if (scripted.ok()) {
                    events.message(new Protocol.DoneOk(json(scripted.value()),
                        writesJson(scripted.storeWrites())));
                } else {
                    events.message(new Protocol.DoneError(errorJson(scripted.error())));
                }
            }
        }

        private static String json(Object value) {
            if (value == null) {
                return null;
            }
            try {
                return JSON.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String errorJson(CodeModeData.CodemodeError error) {
            var payload = new java.util.LinkedHashMap<String, Object>();
            if (error.name() != null) {
                payload.put("name", error.name());
            }
            payload.put("message", error.message() == null ? error.kind() : error.message());
            return json(payload);
        }

        private static String writesJson(CodeModeData.StoreWrites writes) {
            var entries = new java.util.ArrayList<java.util.List<String>>();
            for (var entry : writes.set().entrySet()) {
                entries.add(List.of(entry.getKey(),
                    entry.getValue() == null ? "null" : json(entry.getValue())));
            }
            for (String key : writes.delete()) {
                entries.add(List.of(key));
            }
            return json(entries);
        }
    }
}
