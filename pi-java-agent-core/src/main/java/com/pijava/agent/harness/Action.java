package com.pijava.agent.harness;

import java.util.List;

import com.pijava.ai.message.Message;

/**
 * A pending action that the harness will execute (manual drive mode).
 *
 * <p>This sealed interface models the different kinds of work the
 * harness schedules: LLM calls, tool executions, and user prompts.</p>
 */
public sealed interface Action {

    /** Call the LLM with the given messages. */
    record LlmCall(List<Message> messages) implements Action {
        public LlmCall {
            messages = List.copyOf(messages);
        }
    }

    /** Execute a tool and feed the result back. */
    record ToolExecution(String toolCallId, String toolName, java.util.Map<String, Object> arguments)
            implements Action {}

    /** Prompt the user for input. */
    record UserPrompt(String prompt) implements Action {}
}
