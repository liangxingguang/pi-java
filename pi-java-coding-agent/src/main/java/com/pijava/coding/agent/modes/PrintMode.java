package com.pijava.coding.agent.modes;

import java.util.List;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.mode.JsonEventMapper;
import com.pijava.coding.agent.cli.Args;

/**
 * Non-interactive print mode (Phase 3 design §10).
 *
 * <p>Streams the assistant reply to stdout as it arrives. Tool details are
 * hidden by default; errors go to stderr and the run status decides the
 * exit code.</p>
 */
public final class PrintMode {

    private PrintMode() {}

    /**
     * Run one print-mode session.
     *
     * @param messages positional prompt messages (joined into one prompt)
     * @param args     parsed CLI arguments
     * @return process exit code
     */
    public static int run(List<String> messages, Args args) {
        if (messages.isEmpty()) {
            System.err.println("error: -p requires a prompt message");
            return 1;
        }
        var prompt = String.join(" ", messages);
        try (var session = AgentSession.create(args)) {
            var result = session.processPrompt(prompt, PromptConfig.defaults());
            result.stream().forEach(event -> renderEvent(event, args.verbose()));
            return result.status().exitCode();
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * JSON 变体（P6-5e，--mode json）：print 模式只输出事件行（剥除 partial），
     * 不读 stdin、单次 prompt 后退出（对齐 pi {@code print-mode.ts}）。
     *
     * @return process exit code
     */
    public static int runJson(List<String> messages, Args args) {
        if (messages.isEmpty()) {
            System.err.println("error: -p requires a prompt message");
            return 1;
        }
        var prompt = String.join(" ", messages);
        try (var session = AgentSession.create(args)) {
            var result = session.processPrompt(prompt, PromptConfig.defaults());
            result.stream().forEach(event ->
                System.out.println(JsonEventMapper.toStreamEventWire(event)));
            return result.status().exitCode();
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Render a stream event to stdout/stderr (dedicated output path).
     *
     * @param verbose when true, thinking deltas and tool-call summaries are
     *                printed (default hides tool details, per §10)
     */
    static void renderEvent(StreamEvent event, boolean verbose) {
        switch (event) {
            case StreamEvent.Start ignored -> { }
            case StreamEvent.TextStart ignored -> { }
            case StreamEvent.TextDelta(var contentIndex, var delta, var partial) ->
                System.out.print(delta);
            case StreamEvent.TextEnd ignored -> { }
            case StreamEvent.ThinkingStart ignored -> { }
            case StreamEvent.ThinkingDelta(var contentIndex, var delta, var partial) -> {
                if (verbose) {
                    System.out.print(delta);
                }
            }
            case StreamEvent.ThinkingEnd ignored -> { }
            case StreamEvent.ToolCallStart ignored -> { }
            case StreamEvent.ToolCallDelta ignored -> { }
            case StreamEvent.ToolCallEnd(
                    var contentIndex, var id, var name, var arguments, var partial) -> {
                if (verbose) {
                    System.out.println("🔧 " + name + " " + arguments);
                } else {
                    System.out.println();
                }
            }
            case StreamEvent.UsageInfo ignored -> { }
            case StreamEvent.StreamDone ignored -> { }
            case StreamEvent.StreamError(var reason, var error, var partial) ->
                System.err.println("error: " + reason
                    + (error != null ? ": " + error.getMessage() : ""));
        }
    }
}
