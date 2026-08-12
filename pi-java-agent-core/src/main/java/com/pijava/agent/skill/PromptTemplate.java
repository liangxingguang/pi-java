package com.pijava.agent.skill;

import java.util.Map;

/**
 * Named template for prompt generation.
 * Phase 2c: minimal interface; template registration deferred to Phase 3.
 */
public interface PromptTemplate {
    /** Template name. */
    String name();

    /** Render the template with the given variables. */
    String render(Map<String, Object> vars);
}
