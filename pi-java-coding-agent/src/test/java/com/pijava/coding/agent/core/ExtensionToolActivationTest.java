package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 16（T2，docs/27 §7）：激活门——装配后扩展工具进 activeTools（pi
 * agent-session.ts:3603 注册即激活臂）、isAllowed 门（含 mcp__ 保留臂）、
 * setActiveToolsByName 未知名静默忽略、harness.setActiveTools 不清注册表
 * （裁决① 回归钉）、扩展面三件套（types.ts:1737-1750）。
 */
class ExtensionToolActivationTest {

    static final String PROBE_TOOL = "activation-probe-tool";
    static final String PROBE_MCP_TOOL = "mcp__probe-srv__probe-tool";
    static final String REACH_TOOL = "activation-reach-tool";
    /** W2（B276）：可比较引用的 reachesTools 函数。 */
    static final java.util.function.Function<Map<String, Object>, com.pijava.agent.tool.ToolReach>
        REACH = params -> new com.pijava.agent.tool.ToolReach.Words(List.of("read"));
    /** 探针捕获的 per-extension 上下文（扩展面三件套的驱动口）。 */
    static final List<ExtensionContext> CAPTURED = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 探针扩展：注册一个普通工具＋一个 mcp__ 名工具，捕获上下文。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "activation-probe"; }

        @Override public void register(ExtensionContext ctx) {
            CAPTURED.add(ctx);
            ctx.tools().register(probeTool(PROBE_TOOL));
            ctx.tools().register(probeTool(PROBE_MCP_TOOL));
            ctx.tools().register(reachTool());
        }
    }

    /** W2：带 reachesTools 的探针工具（函数为本类静态引用，便于判等）。 */
    private static AgentTool<Map<String, Object>, Void> reachTool() {
        return new AgentTool<>() {
            @Override public String name() { return REACH_TOOL; }
            @Override public String label() { return REACH_TOOL; }
            @Override public String description() { return "reach probe"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }
            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Parallel();
            }
            @Override
            public java.util.function.Function<Map<String, Object>,
                    com.pijava.agent.tool.ToolReach> reachesTools() {
                return REACH;
            }
            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate, ToolContext context) {
                return ToolResult.success("done");
            }
        };
    }

    private static AgentTool<Map<String, Object>, Void> probeTool(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "activation probe"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }
            @Override public ExecutionMode executionMode() {
                return new ExecutionMode.Parallel();
            }
            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                    AbortSignal signal, ToolUpdateCallback<Void> onUpdate, ToolContext context) {
                return ToolResult.success("done");
            }
        };
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        CAPTURED.clear();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Probe.class.getName() + "\n");
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

    private AgentSession session(String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            // 真 home settings 继承坑（memory settings-json-test-inheritance）：夹具给空存储。
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    /** T2d：扩展工具装配后即激活（pi 注册即激活臂；无 defaultActive 闸，窄差 D89）。 */
    @Test
    void extensionToolsAreActiveAfterAssembly() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            assertThat(s.getActiveToolNames()).contains(PROBE_TOOL, PROBE_MCP_TOOL);
        }
    }

    /** T2d 门：allowlist 不匹配 ⇒ 注册但不激活（mcp__ 名经保留臂放行）。 */
    @Test
    void allowlistGatesActivationButKeepsMcpTools() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--tools", "read")) {
            assertThat(s.services().tools().get(PROBE_TOOL))
                .as("注册面不受影响").isNotNull();
            assertThat(s.getActiveToolNames())
                .as("allowlist 不匹配 ⇒ 不激活").doesNotContain(PROBE_TOOL)
                .as("mcp__ 保留臂（批 1 裁决③）⇒ 激活").contains(PROBE_MCP_TOOL)
                .contains("read");
        }
    }

    /** pi setActiveToolsByName（:1518-1525）：未知名静默忽略，已知名全量替换。 */
    @Test
    void setActiveToolsByNameIgnoresUnknown() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            s.setActiveToolsByName(List.of("read", "no-such-tool", "read"));
            assertThat(s.getActiveToolNames()).containsExactly("read");
        }
    }

    /** 裁决① 回归钉：harness.setActiveTools 不再清/重填注册表。 */
    @Test
    void harnessSetActiveToolsLeavesRegistryAlone() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            s.harness().setActiveTools(Set.of());
            assertThat(s.getActiveToolNames()).isEmpty();
            assertThat(s.services().tools().get("read"))
                .as("注册表不被激活面抹掉").isNotNull();
            assertThat(s.services().tools().get(PROBE_TOOL)).isNotNull();
        }
    }

    /** 扩展面三件套（types.ts:1737-1750）：getActiveTools/getAllTools/setActiveTools。 */
    @Test
    void extensionContextExposesToolControl() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            var ctx = CAPTURED.getFirst();
            assertThat(ctx.getActiveTools()).contains("read", PROBE_TOOL);
            assertThat(ctx.getAllTools())
                .as("窄化投影（裁决⑥/D91）：name＋description")
                .anySatisfy(info -> {
                    assertThat(info.name()).isEqualTo("read");
                    assertThat(info.description()).isNotBlank();
                });
            ctx.setActiveTools(List.of("bash"));
            assertThat(s.getActiveToolNames()).containsExactly("bash");
        }
    }

    /** W2（B276）：getAllTools 投影 reachesTools（pi agent-session.ts:1530-1545）。 */
    @Test
    void getAllToolsProjectsReachesTools() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            var info = s.getAllTools().stream()
                .filter(tool -> tool.name().equals(REACH_TOOL))
                .findFirst().orElseThrow();
            assertThat(info.reachesTools())
                .as("扩展面拿到的函数与工具本体同一引用")
                .isSameAs(REACH);
            assertThat(info.reachesTools().apply(Map.of()))
                .isInstanceOf(com.pijava.agent.tool.ToolReach.Words.class);
            assertThat(s.getAllTools().stream()
                .filter(tool -> !tool.name().equals(REACH_TOOL))
                .filter(tool -> !tool.name().equals(
                    com.pijava.coding.agent.extension.toolsearch.ToolSearchTool.NAME))
                .map(com.pijava.coding.agent.extension.ExtensionToolInfo::reachesTools))
                .as("未声明者投影为 null（pi 的缺席）")
                .containsOnlyNulls();
        }
    }

    /** W2：tool_search 的 reachesTools:()=>"all"（pi tool.ts:320-323）经投影可见。 */
    @Test
    void toolSearchReachesAllIsProjected() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            var info = s.getAllTools().stream()
                .filter(tool -> tool.name()
                    .equals(com.pijava.coding.agent.extension.toolsearch.ToolSearchTool.NAME))
                .findFirst().orElseThrow();
            assertThat(info.reachesTools().apply(Map.of()))
                .isInstanceOf(com.pijava.agent.tool.ToolReach.All.class);
        }
    }

    /**
     * W2：同名集合乱序重设 ⇒ 声明顺序不变（pi {@code _applyToolLoadout}
     * 保持 kept 序；Java 以注册表序为稳定源，D219）。
     */
    @Test
    void sameNameSetResetKeepsDeclarationOrder() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            var before = s.getActiveToolNames();
            var reversed = new java.util.ArrayList<>(before);
            java.util.Collections.reverse(reversed);
            s.setActiveToolsByName(reversed);
            assertThat(s.getActiveToolNames()).containsExactlyElementsOf(before);
        }
    }

    /** 未绑定会话的基础上下文：三件套抛（sendMessage 同旨的「需绑定会话」）。 */
    @Test
    void unboundToolControlThrows() {
        var bare = new com.pijava.coding.agent.extension.DefaultExtensionContext(
            null, new com.pijava.agent.skill.SkillManager(),
            com.pijava.coding.agent.extension.ExtensionUI.noop(),
            new com.pijava.coding.agent.extension.ExtensionEventBus());
        assertThatThrownBy(bare::getActiveTools)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires a bound session");
    }
}
