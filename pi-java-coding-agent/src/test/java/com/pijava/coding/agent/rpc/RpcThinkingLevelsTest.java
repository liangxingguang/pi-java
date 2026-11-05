package com.pijava.coding.agent.rpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W9（B259）：RPC 思考档按模型能力过滤（pi agent-session.ts:2726-2753、
 * rpc-mode.ts:502-512）。
 */
class RpcThinkingLevelsTest {

    @TempDir
    Path fakeHome;

    private String realHome;

    /** plain（非 reasoning）＋ r1（reasoning，minimal 显式 null、xhigh 有映射）。 */
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

    /** 哑 provider（只让 provider 解析命中；不流式）。 */
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

    @Test
    void nonReasoningModelReturnsOnlyOffAndCycleIsNull() {
        try (var session = session("plain")) {
            assertThat(RpcSessionPayloads.availableThinkingLevels(session))
                .containsExactly("off");
            var before = session.harness().getThinkingLevel();
            assertThat(RpcSessionPayloads.cycleThinking(session))
                .as("不支持思考 ⇒ null（pi undefined）")
                .isNull();
            assertThat(session.harness().getThinkingLevel()).isEqualTo(before);
        }
    }

    @Test
    void reasoningModelCyclesOnlyOverAvailableLevels() {
        try (var session = session("r1")) {
            assertThat(RpcSessionPayloads.availableThinkingLevels(session))
                .containsExactly("off", "low", "medium", "high", "xhigh");

            session.setThinkingLevel(ModelThinkingLevel.off());
            assertThat(RpcSessionPayloads.cycleThinking(session))
                .as("off 的下一个可用档是 low（minimal 被映射剔除）")
                .isEqualTo(ModelThinkingLevel.parse("low").orElseThrow());
            session.setThinkingLevel(ModelThinkingLevel.parse("high").orElseThrow());
            var xhigh = RpcSessionPayloads.cycleThinking(session);
            assertThat(xhigh).as("high 之后跳到 xhigh")
                .isEqualTo(ModelThinkingLevel.parse("xhigh").orElseThrow());
            session.setThinkingLevel(xhigh);
            assertThat(RpcSessionPayloads.cycleThinking(session))
                .as("xhigh 之后回到 off（max 未映射被剔除）")
                .isEqualTo(ModelThinkingLevel.off());
        }
    }

    @Test
    void uncataloguedModelKeepsSevenLevels() {
        try (var session = session("zz-b259-uncat")) {
            assertThat(RpcSessionPayloads.availableThinkingLevels(session))
                .containsExactly("off", "minimal", "low", "medium", "high", "xhigh", "max");
            assertThat(RpcSessionPayloads.cycleThinking(session)).isNotNull();
        }
    }
}
