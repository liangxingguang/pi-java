package com.pijava.coding.agent.core;

import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.model.ModelResolver;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.prompt.PromptTemplateRegistry;

/**
 * DI container bundling the services {@link AgentSession} needs (Phase 3
 * design §15).
 *
 * <p>Converged subset: the harness itself is constructed by
 * {@code AgentSession.create()} rather than injected (construction
 * relationship), and session storage / skills / extensions arrive in later
 * phases (InMemorySessionRepository and the harness internals cover Phase 3).</p>
 *
 * @param settings          global + project settings manager
 * @param trust             批 23（docs/34 T3，裁决②）：项目信任存储
 *                          （{@code <agentDir>/trust.json}，pi ProjectTrustStore 对位；
 *                          旧 TrustManager 标记文件已随换代删除）
 * @param providers         registered LLM providers
 * @param models            model catalog resolver
 * @param tools             tool registry (active tools registered on the harness)
 * @param slashCommands     22 built-in slash commands
 * @param sessionRepository persistent session repository (Phase 4)
 * @param promptTemplates   session prompt-template registry (CLI + extension fed)
 */
public record SessionServices(
    SettingsManager settings,
    ProjectTrustStore trust,
    ProviderRegistry providers,
    ModelResolver models,
    ToolRegistry tools,
    CommandRegistry slashCommands,
    SessionRepository<?, ?, ?> sessionRepository,
    PromptTemplateRegistry promptTemplates
) {}
