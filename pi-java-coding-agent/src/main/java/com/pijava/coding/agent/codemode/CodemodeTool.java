package com.pijava.coding.agent.codemode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolExposure;
import com.pijava.agent.tool.ToolLoadout;
import com.pijava.agent.tool.ToolLoadoutChanges;
import com.pijava.agent.tool.ToolNamespace;
import com.pijava.agent.tool.ToolReach;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ConstrainedSampling;
import com.pijava.ai.api.GrammarSampling;
import com.pijava.codemode.api.CodeModeSource;
import com.pijava.coding.agent.extension.ExtensionContext;

/**
 * The {@code codemode} tool: the model writes JavaScript that calls other tools.
 * The description and loadout rewrite follow pi's {@code tool.ts:118-379}.
 */
public final class CodemodeTool implements AgentTool<Map<String, Object>, CodemodeDetails> {
    /** Tool name, verbatim from pi. */
    public static final String NAME = "codemode";

    private final ExtensionContext ctx;
    private final Function<ExtensionContext, CodemodeExecutor> executorFactory;

    /**
     * Creates the tool backed by a lazily-created executor.
     *
     * @param ctx extension context
     * @param executorFactory executor factory (injectable for tests)
     */
    public CodemodeTool(ExtensionContext ctx,
                        Function<ExtensionContext, CodemodeExecutor> executorFactory) {
        this.ctx = ctx;
        this.executorFactory = executorFactory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return NAME;
    }

    @Override
    public String description() {
        return CodemodeDescription.empty();
    }

    @Override
    public String promptSnippet() {
        return CodemodeDescription.SNIPPET;
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of(CodemodeDescription.GUIDELINE);
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties",
            Map.of("code", Map.of("type", "string", "description", "Raw JavaScript source.")),
            "required", List.of("code"));
    }

    @Override
    public ToolExposure exposure() {
        return ToolExposure.MODEL_ONLY;
    }

    @Override
    public boolean defaultActive() {
        return false;
    }

    @Override
    public ExecutionMode executionMode() {
        return new ExecutionMode.Sequential();
    }

    @Override
    public ConstrainedSampling constrainedSampling() {
        return new GrammarSampling(Map.of("openai_lark", CodeModeSource.CODEMODE_SOURCE_GRAMMAR));
    }

    @Override
    public Function<Map<String, Object>, ToolReach> reachesTools() {
        return input -> new ToolReach.All();
    }

    @Override
    public Optional<ToolLoadoutChanges> prepareLoadout(ToolLoadout loadout) {
        String mode = ctx.settings() == null ? "on" : ctx.settings().accessors().codemodeMode();
        int budget = ctx.settings() == null ? 3000 : ctx.settings().accessors().codemodeInlineBudget();
        boolean only = "only".equals(mode);
        List<AgentTool<?, ?>> callable = new ArrayList<>();
        for (AgentTool<?, ?> tool : loadout.callable()) {
            if (!NAME.equals(tool.name())) {
                callable.add(tool);
            }
        }
        Set<String> callableNames = new LinkedHashSet<>();
        for (AgentTool<?, ?> tool : callable) {
            callableNames.add(tool.name());
        }
        Map<String, String> descriptions = new LinkedHashMap<>();
        if (!only) {
            for (AgentTool<?, ?> tool : loadout.declared()) {
                if (callableNames.contains(tool.name())) {
                    descriptions.put(tool.name(), CodemodeDescription.describeScriptCall(tool));
                }
            }
        }
        List<AgentTool<?, ?>> listed = new ArrayList<>();
        for (AgentTool<?, ?> tool : callable) {
            ToolExposure exposure = loadout.exposureOf().apply(tool.name());
            if (only || exposure != ToolExposure.DIRECT) {
                listed.add(tool);
            }
        }
        Map<String, ToolNamespace> namespaces = new LinkedHashMap<>();
        Map<String, List<String>> guidelines = new LinkedHashMap<>();
        Set<String> deferred = new LinkedHashSet<>();
        for (AgentTool<?, ?> tool : listed) {
            ToolNamespace namespace = loadout.namespaceOf().apply(tool.name());
            if (namespace != null) {
                namespaces.put(tool.name(), namespace);
            }
            guidelines.put(tool.name(), loadout.guidelinesOf().apply(tool.name()));
            if (loadout.exposureOf().apply(tool.name()) == ToolExposure.DEFERRED) {
                deferred.add(tool.name());
            }
        }
        descriptions.put(NAME, CodemodeDescription.describe(listed, namespaces, deferred, guidelines, budget));
        List<String> hidden = new ArrayList<>();
        if (only) {
            Set<String> declaredNames = new LinkedHashSet<>();
            for (AgentTool<?, ?> tool : loadout.declared()) {
                declaredNames.add(tool.name());
            }
            for (AgentTool<?, ?> tool : callable) {
                if (loadout.exposureOf().apply(tool.name()) == ToolExposure.DIRECT
                        && declaredNames.contains(tool.name())) {
                    hidden.add(tool.name());
                }
            }
        }
        return Optional.of(new ToolLoadoutChanges(descriptions, hidden));
    }

    @Override
    public ToolResult<CodemodeDetails> execute(String toolCallId, Map<String, Object> params, AbortSignal signal,
                                               ToolUpdateCallback<CodemodeDetails> onUpdate,
                                               com.pijava.agent.tool.ToolContext context) throws Exception {
        return executorFactory.apply(ctx).execute(toolCallId, params, signal, onUpdate);
    }
}
