package com.pijava.coding.agent.codemode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolExposure;
import com.pijava.agent.tool.ToolLoadout;
import com.pijava.agent.tool.ToolLoadoutChanges;
import com.pijava.agent.tool.ToolResult;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.core.SettingsManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B288 loadout hook tests. */
class CodemodeLoadoutTest {

    @Test
    void onModeAnnotatesDeclaredToolsAndKeepsDirectToolsDeclared() {
        ExtensionContext ctx = Contexts.settings("on", 3000);
        CodemodeTool tool = new CodemodeTool(ctx, CodemodeExecutor::new);
        AgentTool<?, ?> direct = Tools.of("read", "Read file", ToolExposure.DIRECT);
        var changes = tool.prepareLoadout(loadout(List.of(tool, direct), List.of(tool, direct))).orElseThrow();
        assertThat(changes.descriptions()).containsKey("read");
        assertThat(changes.descriptions().get("read")).contains("Codemode: `tools.read(args)`");
        assertThat(changes.hiddenDeclarations()).isEmpty();
        assertThat(changes.descriptions().get(CodemodeTool.NAME)).doesNotContain("### `read`");
    }

    @Test
    void onlyModeHidesDirectDeclarationsAndListsEveryCallableTool() {
        ExtensionContext ctx = Contexts.settings("only", 3000);
        CodemodeTool tool = new CodemodeTool(ctx, CodemodeExecutor::new);
        AgentTool<?, ?> direct = Tools.of("read", "Read file", ToolExposure.DIRECT);
        AgentTool<?, ?> deferred = Tools.of("slow", "Slow", ToolExposure.DEFERRED);
        var changes = tool.prepareLoadout(
            loadout(List.of(tool, direct), List.of(tool, direct, deferred))).orElseThrow();
        assertThat(changes.hiddenDeclarations()).containsExactly("read");
        assertThat(changes.descriptions().get(CodemodeTool.NAME)).contains("### `read`");
        assertThat(changes.descriptions().get(CodemodeTool.NAME)).contains("Slow");
    }

    @Test
    void toolsWithoutHookKeepTheirDescriptions() {
        AgentTool<?, ?> plain = Tools.of("read", "Read file", ToolExposure.DIRECT);
        Optional<ToolLoadoutChanges> changes = plain.prepareLoadout(
            loadout(List.of(plain), List.of(plain)));
        assertThat(changes).isEmpty();
    }

    @Test
    void toolMetadataMatchesPiContract() {
        CodemodeTool tool = new CodemodeTool(Contexts.settings("on", 3000), CodemodeExecutor::new);
        assertThat(tool.exposure()).isEqualTo(ToolExposure.MODEL_ONLY);
        assertThat(tool.defaultActive()).isFalse();
        assertThat(tool.reachesTools().apply(Map.of("code", "searchTools('x')")))
            .isInstanceOf(com.pijava.agent.tool.ToolReach.All.class);
        assertThat(tool.inputSchema().get("properties")).isEqualTo(
            Map.of("code", Map.of("type", "string", "description", "Raw JavaScript source.")));
    }

    private static ToolLoadout loadout(List<AgentTool<?, ?>> declared, List<AgentTool<?, ?>> callable) {
        return new ToolLoadout(declared, callable, Set.of(),
            name -> declared.stream().filter(t -> t.name().equals(name)).findFirst()
                .map(AgentTool::exposure).orElse(ToolExposure.CODEMODE),
            name -> null, name -> List.of());
    }

    /** Minimal context supplying settings only. */
    static final class Contexts {
        private Contexts() {
        }

        static ExtensionContext settings(String mode, int budget) {
            SettingsManager manager = SettingsManager.load(null);
            manager.global().codemode = new com.pijava.coding.agent.core.Settings.Codemode(mode, budget);
            return new FakeContext(manager);
        }
    }

    /** Shared minimal AgentTool implementation. */
    static final class Tools {
        private Tools() {
        }

        static AgentTool<?, ?> of(String name, String description, ToolExposure exposure) {
            return of(name, description, exposure, null);
        }

        static AgentTool<?, ?> of(String name, String description, ToolExposure exposure,
                                  Map<String, Object> outputSchema) {
            return new AgentTool<>() {
                @Override public String name() { return name; }
                @Override public String label() { return name; }
                @Override public String description() { return description; }
                @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
                @Override public Map<String, Object> outputSchema() { return outputSchema; }
                @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
                @Override public ToolExposure exposure() { return exposure; }
                @Override public ToolResult<Object> execute(String id, Object params,
                        com.pijava.ai.AbortSignal signal,
                        com.pijava.agent.tool.ToolUpdateCallback<Object> update,
                        com.pijava.agent.tool.ToolContext context) { return ToolResult.success(""); }
            };
        }
    }
}
