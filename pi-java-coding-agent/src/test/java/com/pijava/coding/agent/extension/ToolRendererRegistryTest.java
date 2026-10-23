package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 16（T4，docs/27 §7）：渲染器责任链（pi runner.ts:789-795 逐字）＋
 * 内置 MCP resolver（extensions/mcp/index.ts:384-386＋tools.ts:298-339 文本级对位）。
 */
class ToolRendererRegistryTest {

    private static ToolCallRenderer named(String tag) {
        return new ToolCallRenderer() {
            @Override
            public List<String> renderCall(String toolName, Map<String, Object> args,
                                           boolean expanded) {
                return List.of(tag + ":" + toolName);
            }

            @Override
            public List<String> renderResult(String toolName, String textOutput,
                                             boolean isError, boolean expanded) {
                return List.of(tag + ":" + textOutput);
            }
        };
    }

    /**
     * 责任链加载序（pi runner.ts:789-795 逐字）：resolver 的返回值<b>直接</b>是链的
     * 结果——「不管」必须显式调 {@code next()} 委托；返回 null 即链止于 null
     * （base 也不到——pi 语义：null ≙ undefined 直返，见 MCP resolver 的
     * {@code next() ?? ...} 写法）。
     */
    @Test
    void resolversRunInRegistrationOrder() {
        var registry = new ToolRendererRegistry();
        registry.register((name, next) -> next.get());     // 不管 ⇒ 显式委托
        registry.register((name, next) -> named("second"));
        registry.register((name, next) -> named("third")); // 不得到达
        assertThat(registry.resolve("anything", null).renderCall("anything", Map.of(), false))
            .containsExactly("second:anything");
    }

    /** null 直返 ⇒ 链止于 null（pi 逐字）；全员委托到尾 ⇒ base（调用方回落）。 */
    @Test
    void nullChainFallsToBase() {
        var registry = new ToolRendererRegistry();
        registry.register((name, next) -> null); // 不委托 ⇒ 链止于此
        assertThat(registry.resolve("x", () -> named("base")))
            .as("pi：resolver 返回 undefined 直返，base 不被咨询").isNull();

        var delegating = new ToolRendererRegistry();
        delegating.register((name, next) -> next.get());
        assertThat(delegating.resolve("x", () -> named("base")))
            .as("全员委托 ⇒ 链尾落 base").isNotNull();
        assertThat(delegating.resolve("x", null)).isNull();
    }

    /** next() 委托链的其余部分（pi 的 () => resolve(index + 1) 对位）。 */
    @Test
    void nextDelegatesToRemainingResolvers() {
        var registry = new ToolRendererRegistry();
        registry.register((name, next) -> next.get()); // 全委托
        registry.register((name, next) -> named("tail"));
        assertThat(registry.resolve("x", null).renderCall("x", Map.of(), false))
            .containsExactly("tail:x");
    }

    /** pi MCP 形逐字：next() ?? (match ? renderers : undefined)——委托优先。 */
    @Test
    void mcpResolverPrefersDelegation() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        registry.register((name, next) -> named("user-ext"));
        // MCP resolver 先注册，但 next() 命中 ⇒ 用户扩展赢（pi 逐字）。
        assertThat(registry.resolve("mcp__srv__tool", null)
            .renderCall("mcp__srv__tool", Map.of(), false))
            .containsExactly("user-ext:mcp__srv__tool");
    }

    /** MCP 正则 label：mcp__a__b ⇒ a/b（pi tools.ts:298-339）。 */
    @Test
    void mcpResolverLabelsServerSlashTool() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        var renderer = registry.resolve("mcp__github__create_issue", null);
        assertThat(renderer).isNotNull();
        assertThat(renderer.renderCall("mcp__github__create_issue",
                Map.of("title", "bug"), false))
            .containsExactly("github/create_issue title=\"bug\"");
        assertThat(registry.resolve("read", null)).as("非 mcp__ 名 ⇒ 不管").isNull();
    }

    /** 折叠臂：首 5 行（OUTPUT_PREVIEW_LINES）＋提示行；展开臂全量；空结果 ⇒ 空表。 */
    @Test
    void mcpResultCollapsesToFiveLinesPlusHint() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        var renderer = registry.resolve("mcp__s__t", null);
        var seven = "l1\nl2\nl3\nl4\nl5\nl6\nl7";
        assertThat(renderer.renderResult("mcp__s__t", seven, false, false))
            .containsExactly("l1", "l2", "l3", "l4", "l5", "... (2 more lines)");
        assertThat(renderer.renderResult("mcp__s__t", seven, false, true))
            .as("展开臂全量").hasSize(7);
        assertThat(renderer.renderResult("mcp__s__t", "short", false, false))
            .as("不超过 5 行 ⇒ 无提示行").containsExactly("short");
        assertThat(renderer.renderResult("mcp__s__t", "  \n ", false, false))
            .as("空 output ⇒ 空表（pi 空组件对位）").isEmpty();
    }

    /** 折叠参数行：100 字符截断（pi COLLAPSED_ARGS_CHARS）。 */
    @Test
    void collapsedArgsCutAtHundredChars() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        var renderer = registry.resolve("mcp__s__t", null);
        var longValue = "x".repeat(200);
        var lines = renderer.renderCall("mcp__s__t", Map.of("data", longValue), false);
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).startsWith("s/t data=");
            assertThat(line).endsWith("...");
        });
    }
}
