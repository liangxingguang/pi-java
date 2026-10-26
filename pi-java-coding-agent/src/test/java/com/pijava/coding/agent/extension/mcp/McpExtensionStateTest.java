package com.pijava.coding.agent.extension.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 §6）：谓词群与展示纯件（pi index.ts:132-154/231-278 逐字）。
 */
class McpExtensionStateTest {

    private static McpServerEntry entry(String name, McpExposure exposure, Boolean enabled) {
        return new McpServerEntry(name,
            new McpServerConfig.Stdio(null, exposure, null, null, enabled, null,
                "run-" + name, List.of(), null, null),
            java.nio.file.Path.of("/tmp/mcp.json"), McpScope.GLOBAL, null);
    }

    @Test
    void isEnabledOnlyFalseDisables() {
        assertThat(McpExtensionState.isEnabled(new McpServerHandle(entry("a", null, null))))
            .isTrue();
        assertThat(McpExtensionState.isEnabled(new McpServerHandle(entry("a", null, true))))
            .isTrue();
        assertThat(McpExtensionState.isEnabled(new McpServerHandle(entry("a", null, false))))
            .isFalse();
    }

    @Test
    void exposureDefaultsToCodemode() {
        assertThat(McpExtensionState.exposureOf(entry("a", null, null)))
            .isEqualTo(McpExposure.CODEMODE);
        assertThat(McpExtensionState.exposureOf(entry("a", McpExposure.DIRECT, null)))
            .isEqualTo(McpExposure.DIRECT);
    }

    @Test
    void directAndIndirectPredicates() {
        assertThat(McpExtensionState.hasDirectTools(entry("a", McpExposure.DIRECT, null)))
            .isTrue();
        assertThat(McpExtensionState.hasDirectTools(entry("a", McpExposure.DEFERRED, null)))
            .isFalse();
        assertThat(McpExtensionState.hasIndirectTools(entry("a", McpExposure.CODEMODE, null)))
            .isTrue();
        assertThat(McpExtensionState.hasIndirectTools(entry("a", McpExposure.DEFERRED, null)))
            .isTrue();
        assertThat(McpExtensionState.hasIndirectTools(entry("a", McpExposure.DIRECT, null)))
            .isFalse();
        assertThat(McpExtensionState.hasIndirectTools(entry("a", McpExposure.HIDDEN, null)))
            .isFalse();
    }

    @Test
    void describeStateCoversDisabledStartingAndConnectionStates() {
        var disabled = new McpServerHandle(entry("a", null, false));
        assertThat(McpExtensionState.describeState(disabled)).isEqualTo("disabled");
        var starting = new McpServerHandle(entry("a", null, null));
        assertThat(McpExtensionState.describeState(starting)).isEqualTo("starting");
    }

    @Test
    void attentionRankOrdersDisabledLast() {
        var disabled = new McpServerHandle(entry("a", null, false));
        var starting = new McpServerHandle(entry("b", null, null));
        assertThat(McpExtensionState.attentionRank(disabled)).isEqualTo(5);
        assertThat(McpExtensionState.attentionRank(starting)).isEqualTo(3);
    }

    @Test
    void describeTransportJoinsCommandAndArgs() {
        var e = new McpServerEntry("a",
            new McpServerConfig.Stdio(null, null, null, null, null, null,
                "npx", List.of("-y", "server"), null, null),
            java.nio.file.Path.of("/tmp/mcp.json"), McpScope.GLOBAL, null);
        assertThat(McpExtensionState.describeTransport(e)).isEqualTo("npx -y server");
    }

    @Test
    void lenientPathSurvivesWindowsColon() {
        // builtin:<name> 在 Windows 不是合法 Path ⇒ 退化下划线形，不抛
        assertThat(McpExtensionState.lenientPath("builtin:mcp")).hasToString("builtin_mcp");
        assertThat(McpExtensionState.lenientPath("/a/b.jar"))
            .isEqualTo(java.nio.file.Path.of("/a/b.jar"));
    }
}
