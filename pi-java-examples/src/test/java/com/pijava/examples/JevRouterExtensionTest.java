package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.core.virtual.ModelRouteReason;
import com.pijava.coding.agent.core.virtual.ModelRouteRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jev-router：注册虚拟模型 {@code openai-codex/auto}；无状态时选规划模型
 * （上次 Sol/Terra 保持，否则回退 Terra）；规划轮成功编辑后切 Luna 实现；
 * direct 请求恒 Luna 且不改状态。
 */
class JevRouterExtensionTest {

    private static final ModelThinkingLevel OFF = ModelThinkingLevel.off();

    @TempDir Path tmp;

    @Test
    void registersVirtualModelAndFallsBackToTerraWithoutState() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, JevRouterExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp, new CodexProvider(),
                    JevRouterExtension.TERRA)) {
                var definitions = session.virtualModels().list();
                assertThat(definitions).hasSize(1);
                assertThat(definitions.get(0).provider())
                        .isEqualTo(JevRouterExtension.VIRTUAL_PROVIDER);
                assertThat(definitions.get(0).id()).isEqualTo(JevRouterExtension.VIRTUAL_MODEL_ID);
                assertThat(definitions.get(0).name()).isEqualTo("Auto (Jev)");
                assertThat(definitions.get(0).contextWindow()).isEqualTo(272000);
                assertThat(definitions.get(0).maxTokens()).isEqualTo(128000);
                assertThat(definitions.get(0).thinkingLevels()).hasSize(5);

                session.virtualModels().physicalGate(
                        (provider, id) -> JevRouterExtension.PROVIDER.equals(provider));
                session.virtualModels().authGate(provider -> true);

                var route = session.virtualModels().resolve(
                        ModelId.of(JevRouterExtension.VIRTUAL_PROVIDER,
                                JevRouterExtension.VIRTUAL_MODEL_ID),
                        List.of(), OFF, ModelRouteReason.USER, null, null, null);

                assertThat(route.model().modelName()).isEqualTo(JevRouterExtension.TERRA);
                assertThat(route.state()).isNotNull();
                assertThat(route.state().path("phase").asText())
                        .isEqualTo(JevRouterExtension.PLANNING);
                assertThat(route.state().path("model").asText())
                        .isEqualTo(JevRouterExtension.TERRA);
            }
        }
    }

    @Test
    void keepsPreviousPlanningModelAndFallsBackWhenUnknown() {
        var previousSol = new ModelRouteRequest.RoutePrevious(
                ModelId.of(JevRouterExtension.PROVIDER, JevRouterExtension.SOL), null);
        var kept = JevRouterExtension.decide(
                request(ModelRouteReason.USER, null, previousSol, List.of()));
        assertThat(kept.modelId()).isEqualTo(JevRouterExtension.SOL);
        assertThat(kept.state().path("phase").asText())
                .isEqualTo(JevRouterExtension.PLANNING);

        var previousForeign = new ModelRouteRequest.RoutePrevious(
                ModelId.of("openai-codex-alt", JevRouterExtension.SOL), null);
        var fallback = JevRouterExtension.decide(
                request(ModelRouteReason.USER, null, previousForeign, List.of()));
        assertThat(fallback.modelId()).isEqualTo(JevRouterExtension.TERRA);
    }

    @Test
    void switchesPlanningTurnToLunaAfterSuccessfulEdit() {
        var state = JevRouterExtension.state(
                JevRouterExtension.PLANNING, JevRouterExtension.SOL);
        var edited = List.<Message>of(
                new Message.UserMessage(List.of(new ContentBlock.TextContent("change it"))),
                new Message.ToolResultMessage("call-1", "edit",
                        List.of(new ContentBlock.TextContent("ok")), false));

        var decision = JevRouterExtension.decide(
                request(ModelRouteReason.CONTINUATION, state, null, edited));
        assertThat(decision.modelId()).isEqualTo(JevRouterExtension.LUNA);
        assertThat(decision.state().path("phase").asText())
                .isEqualTo(JevRouterExtension.IMPLEMENTATION);

        var failedEdit = List.<Message>of(
                new Message.UserMessage(List.of(new ContentBlock.TextContent("change it"))),
                new Message.ToolResultMessage("call-2", "edit",
                        List.of(new ContentBlock.TextContent("no")), true));
        var kept = JevRouterExtension.decide(
                request(ModelRouteReason.CONTINUATION, state, null, failedEdit));
        assertThat(kept.modelId()).isEqualTo(JevRouterExtension.SOL);
        assertThat(kept.state()).isNull();
    }

    @Test
    void directRequestsUseLunaWithoutTouchingState() {
        var direct = JevRouterExtension.decide(
                request(ModelRouteReason.DIRECT, null, null, List.of()));
        assertThat(direct.modelId()).isEqualTo(JevRouterExtension.LUNA);
        assertThat(direct.state()).isNull();

        var implementation = JevRouterExtension.state(
                JevRouterExtension.IMPLEMENTATION, JevRouterExtension.LUNA);
        var continuation = JevRouterExtension.decide(
                request(ModelRouteReason.CONTINUATION, implementation, null, List.of()));
        assertThat(continuation.modelId()).isEqualTo(JevRouterExtension.LUNA);
        assertThat(continuation.state()).isNull();
    }

    private static ModelRouteRequest request(ModelRouteReason reason,
            com.fasterxml.jackson.databind.JsonNode state,
            ModelRouteRequest.RoutePrevious previous, List<Message> messages) {
        return new ModelRouteRequest(
                ModelId.of(JevRouterExtension.VIRTUAL_PROVIDER,
                        JevRouterExtension.VIRTUAL_MODEL_ID),
                OFF, reason, previous, null, state, messages, null);
    }

    /** 目录里有 Sol/Terra/Luna 的桩 provider。 */
    private static final class CodexProvider implements Provider {

        @Override
        public String name() {
            return JevRouterExtension.PROVIDER;
        }

        @Override
        public String displayName() {
            return "OpenAI Codex";
        }

        @Override
        public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override
        public ModelCatalog builtinModels() {
            return BuiltinCatalog.of(List.of(
                    model(JevRouterExtension.SOL),
                    model(JevRouterExtension.TERRA),
                    model(JevRouterExtension.LUNA)));
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            throw new UnsupportedOperationException("routing test never streams");
        }

        private static ModelInfo model(String id) {
            return new ModelInfo(ModelId.of(JevRouterExtension.PROVIDER, id), id,
                    ModelCapability.frontierDefaults(), 200000, 32000, false,
                    PricingInfo.UNKNOWN, Map.of(), Map.of());
        }
    }
}
