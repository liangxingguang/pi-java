package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2-35（docs/11 §3.3）：settings {@code defaultTools} 键 —— pi {@code mergeDefaultTools}
 * 分层特判（settings-manager.ts:124-129 经 :139-145）＋ {@code getDefaultTools} 解析
 * （:1328-1333）。批 24（docs/35 T8，D3 销号）：字段改原始 JsonNode 承载＋容忍读。
 */
class DefaultToolsSettingsTest {

    @Test
    void noLayerSetsDefaultToolsYieldsNull() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        // null ≙ pi undefined ⇒ 消费点回落 DEFAULT_TOOL_NAMES 四件。
        assertThat(manager.accessors().getDefaultTools()).isNull();
    }

    @Test
    void globalPlainListResolvesAsReplacement() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = Json.mapper().valueToTree(List.of("grep", "find"));
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);

        assertThat(ToolSelection.normalizeDefaultTools(manager.effective().defaultTools))
            .containsExactly("grep", "find");
        assertThat(manager.accessors().getDefaultTools()).containsExactly("grep", "find");
    }

    @Test
    void projectModifiersAppendToInheritedSelection() {
        // pi 特判：覆盖层全 +name/-name ⇒ 追加到继承选择之后（逐层修饰），不是替换。
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = Json.mapper().valueToTree(List.of("grep", "find"));
        storage.writeGlobal(global);
        var project = new Settings();
        project.defaultTools = Json.mapper().valueToTree(List.of("+ls", "-find"));
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        assertThat(ToolSelection.normalizeDefaultTools(manager.effective().defaultTools))
            .containsExactly("grep", "find", "+ls", "-find");
        assertThat(manager.accessors().getDefaultTools()).containsExactly("grep", "ls");
    }

    @Test
    void projectPlainListReplacesInheritedSelection() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = Json.mapper().valueToTree(List.of("grep"));
        storage.writeGlobal(global);
        var project = new Settings();
        project.defaultTools = Json.mapper().valueToTree(List.of("read", "write"));
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        assertThat(ToolSelection.normalizeDefaultTools(manager.effective().defaultTools))
            .containsExactly("read", "write");
        assertThat(manager.accessors().getDefaultTools()).containsExactly("read", "write");
    }

    @Test
    void projectModifiersWithNoGlobalInheritPiDefaults() {
        // base 缺席 ⇒ merge 直取覆盖层；resolve 时纯修饰符叠在 DEFAULT_TOOL_NAMES 上。
        var storage = new InMemorySettingsStorage();
        var project = new Settings();
        project.defaultTools = Json.mapper().valueToTree(List.of("+powershell"));
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        assertThat(ToolSelection.normalizeDefaultTools(manager.effective().defaultTools))
            .containsExactly("+powershell");
        assertThat(manager.accessors().getDefaultTools())
            .containsExactly("read", "bash", "edit", "write", "powershell");
    }

    @Test
    void defaultToolsSurviveStorageRoundTrip() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = Json.mapper().valueToTree(List.of("+grep", "-bash"));
        storage.writeGlobal(global);

        var reloaded = SettingsManager.withStorage(storage).effective();
        assertThat(ToolSelection.normalizeDefaultTools(reloaded.defaultTools))
            .containsExactly("+grep", "-bash");
    }

    // ── 批 24（D3，pi settings-manager.ts:1331 逐字）：容忍读 ──

    @Test
    void nonArrayDefaultToolsResolvesEmpty() {
        // pi：{@code defaultTools: "bash"}（标量）⇒ resolveDefaultTools([]) ⇒ 空选择
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = Json.mapper().valueToTree("bash");
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.accessors().getDefaultTools()).isEmpty();
    }

    @Test
    void nonStringMembersAreFiltered() {
        // pi：数组内非字符串成员滤掉（[123, "bash", {"x":1}] ⇒ ["bash"]）
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = Json.mapper().valueToTree(
            List.of(123, "bash", java.util.Map.of("x", 1)));
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.accessors().getDefaultTools()).containsExactly("bash");
    }
}
