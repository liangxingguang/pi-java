package com.pijava.coding.agent.core;

import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.ModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.slash.CommandRegistry;

/**
 * DI container bundling the services {@link AgentSession} needs (Phase 3
 * design §15).
 *
 * <p>Converged subset: the harness itself is constructed by
 * {@code AgentSession.create()} rather than injected (construction
 * relationship), and session storage / skills / extensions arrive in later
 * phases (InMemorySessionRepository and the harness internals cover Phase 3).</p>
 *
 * @param settings      global + project settings manager
 * @param trust         project trust decisions
 * @param providers     registered LLM providers
 * @param models        model catalog resolver
 * @param tools         tool registry (active tools registered on the harness)
 * @param slashCommands 22 built-in slash commands
 */
public record SessionServices(
    SettingsManager settings,
    TrustManager trust,
    ProviderRegistry providers,
    ModelResolver models,
    ToolRegistry tools,
    CommandRegistry slashCommands
) {}
