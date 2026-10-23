package com.pijava.coding.agent.core.slash.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 15（T8）：pi {@code getAvailableThinkingLevels}（models.ts:1224-1233）与
 * {@code handleThinkingCommand}（interactive-mode.ts:5145-5161）对拍——七值
 * 全集、off 臂、映射过滤（null 剔除／xhigh 未映射剔除）、大小写不敏感命中、
 * 未知级文案、空参 marker。
 *
 * <p>会话目录＝{@code ModelsJsonConfig.allModels()}（bundled ∪
 * {@code agentDir/models.json}）⇒ 夹具重定向 {@code user.home} 种 models.json
 * （tearDown 前不复原——settings 懒解析夹具坑）。</p>
 */
class ThinkingCommandsTest {

    @TempDir
    Path fakeHome;

    private String realHome;

    /** 种 models.json：plain（非 reasoning）＋ r1（reasoning，minimal 显式 null、
     *  xhigh 有映射、max 未映射）。 */
    private static final String MODELS_JSON = """
        {
          "providers": {
            "cat": {
              "api": "openai-responses",
              "baseUrl": "http://127.0.0.1:1/v1",
              "models": [
                { "id": "plain", "name": "Plain", "reasoning": false },
                { "id": "r1", "name": "R1", "reasoning": true,
                  "thinkingLevelMap": { "minimal": null, "xhigh": "xhigh" } }
              ]
            }
          }
        }
        """;

    @BeforeEach
    void plantModelsJson() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        var agentDir = fakeHome.resolve(".pi-java").resolve("agent");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("models.json"), MODELS_JSON);
    }

    @AfterEach
    void restoreHome() {
        if (realHome != null) {
            System.setProperty("user.home", realHome);
        }
    }

    /** 哑 provider（只让 provider 解析命中；不流式——/thinking 不触 LLM）。 */
    private static final class CatalogProvider implements Provider {
        @Override public String name() { return "cat"; }
        @Override public String displayName() { return "Catalog"; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of();
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }
    }

    private AgentSession session(String model) {
        var registry = ProviderRegistry.create();
        registry.register(new CatalogProvider());
        return AgentSession.create(ArgsParser.parse(new String[] {
                "--provider", "cat", "--model", model, "--no-session"}),
            registry,
            new ToolContext(System.getProperty("user.dir"), Map.of(),
                new DefaultShellExecutor(null), new DefaultFileSystem()));
    }

    private static String dispatch(AgentSession session, String input) throws Exception {
        return session.services().slashCommands()
            .dispatch(input, SlashContext.of(session))
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static List<String> labels(List<ModelThinkingLevel> levels) {
        return levels.stream().map(ModelThinkingLevel::label).toList();
    }

    @Test
    void uncataloguedModelYieldsFullSevenLevels() throws Exception {
        // 目录未编目 ⇒ 七值全集（pi THINKING_LEVEL_OPTIONS 逐字序；唯一名防模糊命中）。
        try (var session = session("zz-b15-uncat")) {
            assertThat(labels(ThinkingCommands.availableLevels(session)))
                .containsExactly("off", "minimal", "low", "medium", "high", "xhigh", "max");
        }
    }

    @Test
    void nonReasoningModelYieldsOnlyOff() throws Exception {
        try (var session = session("plain")) {
            assertThat(labels(ThinkingCommands.availableLevels(session)))
                .containsExactly("off");
        }
    }

    @Test
    void reasoningModelMapFiltersNullAndGatesXhigh() throws Exception {
        try (var session = session("r1")) {
            // minimal 显式 null ⇒ 剔除；xhigh 有映射 ⇒ 放行；max 未映射 ⇒ 剔除。
            assertThat(labels(ThinkingCommands.availableLevels(session)))
                .containsExactly("off", "low", "medium", "high", "xhigh");
        }
    }

    @Test
    void emptyArgsReturnsSelectorMarker() throws Exception {
        try (var session = session("r1")) {
            assertThat(dispatch(session, "/thinking"))
                .isEqualTo(CommandRegistry.UI_THINKING_SELECTOR);
        }
    }

    @Test
    void exactMatchSetsLevelCaseInsensitively() throws Exception {
        try (var session = session("r1")) {
            var result = dispatch(session, "/thinking HIGH");
            assertThat(result).isEqualTo("Thinking level: high");
            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(
                    new com.pijava.ai.thinking.ThinkingLevel.High()));
        }
    }

    @Test
    void unknownLevelYieldsVerbatimError() throws Exception {
        try (var session = session("zz-b15-uncat")) {
            assertThat(dispatch(session, "/thinking turbo"))
                .isEqualTo("Unknown thinking level \"turbo\"."
                    + " Available levels: off, minimal, low, medium, high, xhigh, max.");
        }
    }

    @Test
    void registrationShapeMatchesPi() {
        var registry = CommandRegistry.withBuiltins();
        var command = registry.get("thinking");
        assertThat(command.description()).isEqualTo("Set thinking level");
        assertThat(command.argumentHint()).isEqualTo("<level>");
    }
}
