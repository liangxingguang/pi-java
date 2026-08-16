package com.pijava.agent.loop;

import com.pijava.agent.harness.AgentHarness;
import com.pijava.ai.message.AssistantMessage;

/**
 * Agent loop — outer driver that runs {@code peekAction()} →
 * {@code executeAction()} until the run completes.
 *
 * <p>Thin layer: StreamFn is held by AgentHarness (via HarnessConfig).
 * The loop only knows about the harness's manual-drive API.</p>
 *
 * <p>Aligned with pi's {@code agentLoop()} function. Phase 2a: single
 * turn (user prompt → LLM → response), no tool calls.</p>
 */
public class AgentLoop {

    private final AgentHarness harness;

    /** Create an agent loop that drives the given harness. */
    public AgentLoop(AgentHarness harness) {
        this.harness = harness;
    }

    /**
     * Run a single turn: user prompt → LLM response.
     * Phase 2a: no tool calls, returns after first assistant response.
     *
     * @param userPrompt the user's input text
     * @return the final assistant message
     */
    public AssistantMessage run(String userPrompt) {
        // 1. Initiate the run — writes entries, returns first AppendEntry
        var action = harness.run(userPrompt);

        // 2. Drive the loop: execute → next until null
        while (action != null) {
            action = harness.executeAction(action);
        }

        // 3. Return the final assistant message
        return harness.lastAssistantMessage();
    }
}
