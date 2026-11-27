package com.pijava.agent.tool;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ConstrainedSampling;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link AgentTool} view with a rewritten description (B288).
 *
 * @param <P> parameter type
 * @param <D> details type
 */
public final class ToolLoadoutView<P, D> implements AgentTool<P, D> {
    private final AgentTool<P, D> delegate;
    private final String description;

    /**
     * Wraps a tool with a replacement description.
     *
     * @param tool original tool
     * @param description replacement description
     */
    @SuppressWarnings("unchecked")
    public ToolLoadoutView(AgentTool<?, ?> tool, String description) {
        this.delegate = (AgentTool<P, D>) tool;
        this.description = description;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String label() {
        return delegate.label();
    }

    @Override
    public String promptSnippet() {
        return delegate.promptSnippet();
    }

    @Override
    public List<String> promptGuidelines() {
        return delegate.promptGuidelines();
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> outputSchema() {
        return delegate.outputSchema();
    }

    @Override
    public ToolExposure exposure() {
        return delegate.exposure();
    }

    @Override
    public ToolNamespace namespace() {
        return delegate.namespace();
    }

    @Override
    public Function<Map<String, Object>, ToolReach> reachesTools() {
        return delegate.reachesTools();
    }

    @Override
    public ToolAnnotations annotations() {
        return delegate.annotations();
    }

    @Override
    public boolean defaultActive() {
        return delegate.defaultActive();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public ExecutionMode executionMode() {
        return delegate.executionMode();
    }

    @Override
    public ConstrainedSampling constrainedSampling() {
        return delegate.constrainedSampling();
    }

    @Override
    public P prepareArguments(Map<String, Object> raw) {
        return delegate.prepareArguments(raw);
    }

    @Override
    public Optional<ToolLoadoutChanges> prepareLoadout(ToolLoadout loadout) {
        return delegate.prepareLoadout(loadout);
    }

    @Override
    public ToolResult<D> execute(String toolCallId, P params, AbortSignal signal,
                                  ToolUpdateCallback<D> onUpdate, ToolContext context) throws Exception {
        return delegate.execute(toolCallId, params, signal, onUpdate, context);
    }
}
