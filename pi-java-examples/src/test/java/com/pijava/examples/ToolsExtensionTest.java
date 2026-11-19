package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.ExtensionToolInfo;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;

/** docs/52 §2.3：tools 验收——分支恢复、过滤不存在工具、持久化、capturesViewportInput。 */
class ToolsExtensionTest {

    private static Entry custom(String type, Map<String, Object> data) {
        return new Entry.Custom("c", 0, null, Instant.ofEpochMilli(1), type, data);
    }

    @Test
    void savedToolsPicksLastConfigEntry() {
        var branch = List.of(
            custom("tools-config", Map.of("enabledTools", List.of("read"))),
            custom("other", Map.of("x", "y")),
            custom("tools-config", Map.of("enabledTools", List.of("bash", "edit"))));
        assertThat(ToolsExtension.savedTools(branch)).containsExactly("bash", "edit");
        assertThat(ToolsExtension.savedTools(List.of(custom("other", Map.of())))).isNull();
    }

    @Test
    void restoreFiltersUnknownToolsAndApplies() {
        var w = new W3Context();
        w.branch = List.of(custom("tools-config",
            Map.of("enabledTools", List.of("read", "ghost"))));
        w.allTools = List.of(tool("read"), tool("bash"));
        var ext = new ToolsExtension();
        ext.register(w.ctx);

        ext.restore(w.ctx);

        assertThat(ext.enabledTools()).containsExactly("read");
        assertThat(w.activeTools).containsExactly("read");
    }

    @Test
    void restoreWithoutSavedStateSyncsActiveTools() {
        var w = new W3Context();
        w.branch = List.of();
        w.activeTools = new java.util.ArrayList<>(List.of("bash", "ls"));
        w.allTools = List.of(tool("bash"), tool("ls"));
        var ext = new ToolsExtension();
        ext.register(w.ctx);

        ext.restore(w.ctx);

        assertThat(ext.enabledTools()).containsExactly("bash", "ls");
    }

    @Test
    void applyPersistsToolsConfig() {
        var w = new W3Context();
        w.allTools = List.of(tool("read"));
        var ext = new ToolsExtension();
        ext.register(w.ctx);
        ext.restore(w.ctx);

        ext.apply(w.ctx);

        assertThat(w.appended).contains("tools-config");
    }

    @Test
    void commandRequiresTui() {
        var w = new W3Context();
        w.mode = "print";
        new ToolsExtension().register(w.ctx);

        w.command("tools", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("/tools requires TUI mode");
        assertThat(w.ui.notices().get(0).type()).isEqualTo(NotifyType.ERROR);
    }

    @Test
    void componentCapturesViewportInput() {
        assertThat(new ToolsExtension.ToolsComponent().capturesViewportInput()).isTrue();
    }

    private static ExtensionToolInfo tool(String name) {
        return new ExtensionToolInfo(name, name, List.of());
    }
}
