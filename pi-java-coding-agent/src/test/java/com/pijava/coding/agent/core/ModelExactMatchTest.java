package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import org.junit.jupiter.api.Test;

/**
 * 批 15（T9）：pi {@code findExactModelReferenceMatch}（model-resolver.ts:88-130）
 * 对拍——全形/拆形/裸 id/歧义/空/大小写；＋ /model 命令的 scoped 收口与直设。
 */
class ModelExactMatchTest {

    private static ModelInfo model(String provider, String id) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            128_000, 8_192, false, PricingInfo.UNKNOWN, ThinkingLevelMap.empty(),
            Map.of(), Map.of(), ModelCompat.NONE);
    }

    private static final List<ModelInfo> MODELS = List.of(
        model("anthropic", "claude-fable-5"),
        model("openai", "gpt-6"),
        model("openrouter", "gpt-6"));    // 与 openai 同裸 id（歧义源）

    @Test
    void canonicalFullFormUniqueHit() {
        var hit = ModelExactMatch.findExact("Anthropic/Claude-Fable-5", MODELS);
        assertThat(hit).isNotNull();
        assertThat(hit.id()).isEqualTo(ModelId.of("anthropic", "claude-fable-5"));
    }

    @Test
    void slashSplitExactMatch() {
        // 全形不命中（大小写差异外的前后端空格）⇒ 拆形臂。
        var hit = ModelExactMatch.findExact("openai / gpt-6", MODELS);
        assertThat(hit).isNotNull();
        assertThat(hit.id()).isEqualTo(ModelId.of("openai", "gpt-6"));
    }

    @Test
    void bareIdUniqueHit() {
        var hit = ModelExactMatch.findExact("claude-fable-5", MODELS);
        assertThat(hit).isNotNull();
        assertThat(hit.id().provider()).isEqualTo("anthropic");
    }

    @Test
    void bareIdAmbiguousReturnsNull() {
        assertThat(ModelExactMatch.findExact("gpt-6", MODELS)).isNull();
    }

    @Test
    void blankOrUnknownReturnsNull() {
        assertThat(ModelExactMatch.findExact("   ", MODELS)).isNull();
        assertThat(ModelExactMatch.findExact("no/such", MODELS)).isNull();
        assertThat(ModelExactMatch.findExact("missing", MODELS)).isNull();
    }

    // ── /model 命令级 ──────────────────────────────────────

    /** 挂固定目录的哑 provider。 */
    private static final class CatalogProvider implements Provider {
        private final ModelCatalog catalog;

        CatalogProvider(ModelCatalog catalog) {
            this.catalog = catalog;
        }

        @Override public String name() { return "cat"; }
        @Override public String displayName() { return "Catalog"; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of();
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override public ModelCatalog builtinModels() { return catalog; }
    }

    private AgentSession session() {
        var registry = ProviderRegistry.create();
        registry.register(new CatalogProvider(BuiltinCatalog.of(List.of(
            model("cat", "m1"), model("cat", "m2")))));
        return AgentSession.create(ArgsParser.parse(new String[] {
                "--provider", "cat", "--model", "m1", "--no-session"}),
            registry,
            new ToolContext(System.getProperty("user.dir"), Map.of(),
                new DefaultShellExecutor(null), new DefaultFileSystem()));
    }

    @Test
    void emptyArgsOpensSelectorMarker() throws Exception {
        try (var session = session()) {
            var result = session.services().slashCommands()
                .dispatch("/model", SlashContext.of(session))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(CommandRegistry.UI_MODEL_SELECTOR);
        }
    }

    @Test
    void scopedModelsNonEmptyMissClosesToSelector() throws Exception {
        try (var session = session()) {
            session.setScopedModels(List.of(
                com.pijava.ai.model.ScopedModel.of(model("cat", "m1"))));
            // scoped 臂未命中不开刷新、直接收口选择器（pi :5220）。
            var result = session.services().slashCommands()
                .dispatch("/model no-such-model", SlashContext.of(session))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(CommandRegistry.UI_MODEL_SELECTOR);
        }
    }

    @Test
    void scopedModelsHitSetsModel() throws Exception {
        try (var session = session()) {
            session.setScopedModels(List.of(
                com.pijava.ai.model.ScopedModel.of(model("cat", "m2"))));
            var result = session.services().slashCommands()
                .dispatch("/model m2", SlashContext.of(session))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(result).isEqualTo("Model: m2");
            assertThat(session.harness().getModel())
                .isEqualTo(ModelId.of("cat", "m2"));
        }
    }
}
