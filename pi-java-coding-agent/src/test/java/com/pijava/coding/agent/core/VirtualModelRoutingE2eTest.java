package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.virtual.ModelRouteReason;
import com.pijava.coding.agent.core.virtual.VirtualModelRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 27（B162/T10+T11）：虚拟模型路由端到端——pi {@code virtual-models.test.ts} /
 * {@code jev-router-example.test.ts} 的 java 镜像。选型 {@code --model jev/auto}
 * （CLI 自定义 id 兜底直通），每请求路由到 faux-vm/hello：
 *
 * <ul>
 *   <li>请求实际发到路由目标（assistant 消息记物理模型，pi 契约逐字义）</li>
 *   <li>首个 user 请求 reason=user、state 缺席；路由态写回
 *       {@code pi.virtual-model-state} custom 条目（pi :829-835）</li>
 *   <li>第二 prompt：路由器读到上次的态（分支扫描），值不变 ⇒ 不再写条目</li>
 *   <li>注销后下一 prompt 以 "is not registered." 错误收场（pi :998-999）</li>
 * </ul>
 *
 * <p>闸注记：物理/凭据两门对 faux 目标不可证（目录与凭证链都不识 faux）——
 * 测试经注册表的宿主闸覆写（生产默认不变；两门本身由
 * {@code VirtualModelRegistryTest} 单测钉住）。</p>
 */
class VirtualModelRoutingE2eTest {

    @TempDir Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        VirtualRouterExtension.REQUESTS.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            VirtualRouterExtension.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession session(List<List<StreamEvent>> replies) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-vm", "--model", "jev/auto", "--no-session", "--no-mcp"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-vm", replies));
        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
        // 测试闸：faux 目标过物理/凭据两门（生产默认不变，见类注释）
        session.virtualModels().physicalGate(
            (provider, id) -> "faux-vm".equals(provider) && "hello".equals(id));
        session.virtualModels().authGate(provider -> true);
        return session;
    }

    @Test
    void routesEveryRequestAndPersistsRouterStateOnTheBranch() throws Exception {
        try (var session = session(List.of(textTurn("r1"), textTurn("r2")))) {
            var first = session.processPrompt("p1", PromptConfig.defaults());
            assertThat(first.status().exitCode()).isZero();

            // 路由被调（reason=user、首态缺席）
            assertThat(VirtualRouterExtension.REQUESTS).hasSize(1);
            assertThat(VirtualRouterExtension.REQUESTS.get(0).reason())
                .isEqualTo(ModelRouteReason.USER);
            assertThat(VirtualRouterExtension.REQUESTS.get(0).state()).isNull();

            // assistant 消息记物理模型（pi：响应只记物理模型名）
            var assistant = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Message m
                    && m.message() instanceof com.pijava.ai.message.Message.AssistantMessage)
                .map(e -> (com.pijava.ai.message.Message.AssistantMessage)
                    ((Entry.Message) e).message())
                .reduce((a, b) -> b).orElseThrow();
            assertThat(assistant.provider()).isEqualTo("faux-vm");
            assertThat(assistant.model()).isEqualTo("hello");

            // 路由态写回（pi :829-835——custom 条目，append-only）
            var stateEntries = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Custom c
                    && VirtualModelRegistry.STATE_ENTRY.equals(c.customType()))
                .toList();
            assertThat(stateEntries).hasSize(1);

            // 第二 prompt：路由器读到上次的态；值不变 ⇒ 不再写
            var second = session.processPrompt("p2", PromptConfig.defaults());
            assertThat(second.status().exitCode()).isZero();
            assertThat(VirtualRouterExtension.REQUESTS).hasSize(2);
            assertThat(VirtualRouterExtension.REQUESTS.get(1).state()).isNotNull();
            assertThat(VirtualRouterExtension.REQUESTS.get(1).state().get("phase").asText())
                .isEqualTo("routed");
            assertThat(session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Custom c
                    && VirtualModelRegistry.STATE_ENTRY.equals(c.customType()))
                .toList()).hasSize(1);
        }
    }

    @Test
    void unroutedSelectionAfterUnregisterFlowsToTheProviderFallback() throws Exception {
        try (var session = session(List.of(textTurn("r1")))) {
            // 注销（E5-27 面）后选型失效：pi 的目录条目随注销消失、路由在
            // resolveModel 抛 "is not registered."（单测已钉）；java 的注册表判别式
            // 同时失效 ⇒ 路由钩弃权 ⇒ 请求带虚拟名直发——被「未知 provider 兜底」
            // 臂接住（既存方言，窄差 D171：pi 此处 unroutedStream 抛
            // "must be routed before streaming"）。本断言钉现状：消息记虚拟名。
            session.virtualModels().unregister("jev", "auto");
            var result = session.processPrompt("p1", PromptConfig.defaults());
            assertThat(result.status().exitCode()).isZero();
            var last = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Message m
                    && m.message() instanceof com.pijava.ai.message.Message.AssistantMessage)
                .map(e -> (com.pijava.ai.message.Message.AssistantMessage)
                    ((Entry.Message) e).message())
                .reduce((a, b) -> b).orElseThrow();
            assertThat(last.provider()).isEqualTo("jev");
            assertThat(last.model()).isEqualTo("auto");
        }
    }
}
