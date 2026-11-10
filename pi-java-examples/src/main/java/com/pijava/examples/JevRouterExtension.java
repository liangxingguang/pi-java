package com.pijava.examples;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.core.virtual.ModelRoute;
import com.pijava.coding.agent.core.virtual.ModelRouteReason;
import com.pijava.coding.agent.core.virtual.ModelRouteRequest;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionVirtualModel;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * Ports pi's {@code jev-router} example.
 *
 * <p>Registers the virtual model {@code jev/auto}. The router keeps a two-phase
 * state ({@code planning}/{@code implementation}): without state it picks a
 * planning model (previous Sol/Terra wins, otherwise Terra as pi's fallback when
 * no classifier is available); a planning turn that edited files switches to Luna
 * for implementation. Direct requests always use Luna and keep state untouched.</p>
 */
public final class JevRouterExtension implements PiExtension {

    /** Extension name shown by the host registry. */
    public static final String NAME = "jev-router";

    /** Provider hosting the virtual router model (pi {@code registerVirtualModel}). */
    public static final String VIRTUAL_PROVIDER = "jev";

    /** Provider hosting the router's physical models. */
    public static final String PROVIDER = "openai-codex";

    /** Virtual model id registered under {@link #VIRTUAL_PROVIDER}. */
    public static final String VIRTUAL_MODEL_ID = "auto";

    private static final List<ModelThinkingLevel> THINKING_LEVELS = List.of(
            ModelThinkingLevel.off(),
            ModelThinkingLevel.of(new ThinkingLevel.Low()),
            ModelThinkingLevel.of(new ThinkingLevel.Medium()),
            ModelThinkingLevel.of(new ThinkingLevel.High()),
            ModelThinkingLevel.of(new ThinkingLevel.XHigh()));

    static final String SOL = "gpt-5.6-sol";
    static final String TERRA = "gpt-5.6-terra";
    static final String LUNA = "gpt-5.6-luna";

    static final String PLANNING = "planning";
    static final String IMPLEMENTATION = "implementation";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.registerVirtualModel(new ExtensionVirtualModel(
                VIRTUAL_PROVIDER, VIRTUAL_MODEL_ID, "Auto (Jev)",
                THINKING_LEVELS, 272000, 128000, List.of("text", "image"),
                JevRouterExtension::route));
    }

    static ModelRoute route(ModelRouteRequest request, ExtensionContext ctx) {
        Decision decision = decide(request);
        return new ModelRoute(resolve(ctx, decision.modelId()), request.thinkingLevel(),
                decision.state());
    }

    /**
     * Pure routing decision (no catalog lookup).
     *
     * @param request router input
     * @return chosen physical model id plus the state to persist (null = keep)
     */
    static Decision decide(ModelRouteRequest request) {
        if (request.reason() == ModelRouteReason.DIRECT) {
            return new Decision(LUNA, null);
        }
        JevState current = stateOf(request.state());
        if (current == null) {
            String planning = choosePlanningModel(request);
            return new Decision(planning, state(PLANNING, planning));
        }
        if (PLANNING.equals(current.phase()) && editedThisTurn(request.messages())) {
            return new Decision(LUNA, state(IMPLEMENTATION, LUNA));
        }
        return new Decision(current.model(), null);
    }

    /** Parse a persisted router state; malformed or empty state returns null. */
    static JevState stateOf(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return null;
        }
        String phase = raw.path("phase").asText("");
        String model = raw.path("model").asText("");
        if ((!PLANNING.equals(phase) && !IMPLEMENTATION.equals(phase)) || model.isEmpty()) {
            return null;
        }
        return new JevState(phase, model);
    }

    static JsonNode state(String phase, String model) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("phase", phase);
        node.put("model", model);
        return node;
    }

    private static String choosePlanningModel(ModelRouteRequest request) {
        ModelRouteRequest.RoutePrevious previous = request.previous();
        if (previous != null && previous.model() != null
                && PROVIDER.equals(previous.model().provider())) {
            String id = previous.model().modelName();
            if (SOL.equals(id) || TERRA.equals(id)) {
                return id;
            }
        }
        return TERRA;
    }

    private static boolean editedThisTurn(List<Message> messages) {
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.UserMessage) {
                lastUser = i;
            }
        }
        if (lastUser < 0) {
            return false;
        }
        for (int i = lastUser + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.ToolResultMessage result
                    && ("edit".equals(result.toolName()) || "write".equals(result.toolName()))
                    && !result.isError()) {
                return true;
            }
        }
        return false;
    }

    private static ModelId<?> resolve(ExtensionContext ctx, String model) {
        return ctx.providers().get(PROVIDER)
                .flatMap(provider -> provider.getModels().stream()
                        .filter(info -> info.id().modelName().equals(model))
                        .findFirst())
                .map(info -> (ModelId<?>) info.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Model " + PROVIDER + "/" + model + " is not in the catalog."));
    }

    /** Router state persisted through the custom entry data. */
    record JevState(String phase, String model) {
    }

    /** Result of the pure routing decision. */
    record Decision(String modelId, JsonNode state) {
    }
}
