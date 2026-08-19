package com.pijava.evals.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.provider.Provider;

/**
 * Shared context available to every {@link EvalCase}.
 *
 * <p>{@link #harness()} may be {@code null} for ChatApi-only suites.</p>
 */
public interface EvalContext {

    /** Provider under test (Faux or real). */
    Provider provider();

    /** Cached ChatApi for the provider. */
    ChatApi chatApi();

    /** Agent harness for extension tests; may be {@code null}. */
    AgentHarness harness();

    /** Shared JSON mapper. */
    ObjectMapper json();
}
