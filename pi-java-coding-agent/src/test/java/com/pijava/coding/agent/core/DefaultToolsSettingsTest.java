package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2-35（docs/11 §3.3）：settings {@code defaultTools} 键 —— pi {@code mergeDefaultTools}
 * 分层特判（settings-manager.ts:124-129 经 :139-145）＋ {@code getDefaultTools} 解析
 * （:1328-1333）。
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
        global.defaultTools = List.of("grep", "find");
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.effective().defaultTools).containsExactly("grep", "find");
        assertThat(manager.accessors().getDefaultTools()).containsExactly("grep", "find");
    }

    @Test
    void projectModifiersAppendToInheritedSelection() {
        // pi 特判：覆盖层全 +name/-name ⇒ 追加到继承选择之后（逐层修饰），不是替换。
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = List.of("grep", "find");
        storage.writeGlobal(global);
        var project = new Settings();
        project.defaultTools = List.of("+ls", "-find");
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.effective().defaultTools)
            .containsExactly("grep", "find", "+ls", "-find");
        assertThat(manager.accessors().getDefaultTools()).containsExactly("grep", "ls");
    }

    @Test
    void projectPlainListReplacesInheritedSelection() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = List.of("grep");
        storage.writeGlobal(global);
        var project = new Settings();
        project.defaultTools = List.of("read", "write");
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.effective().defaultTools).containsExactly("read", "write");
        assertThat(manager.accessors().getDefaultTools()).containsExactly("read", "write");
    }

    @Test
    void projectModifiersWithNoGlobalInheritPiDefaults() {
        // base 缺席 ⇒ merge 直取覆盖层；resolve 时纯修饰符叠在 DEFAULT_TOOL_NAMES 上。
        var storage = new InMemorySettingsStorage();
        var project = new Settings();
        project.defaultTools = List.of("+powershell");
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.effective().defaultTools).containsExactly("+powershell");
        assertThat(manager.accessors().getDefaultTools())
            .containsExactly("read", "bash", "edit", "write", "powershell");
    }

    @Test
    void defaultToolsSurviveStorageRoundTrip() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultTools = List.of("+grep", "-bash");
        storage.writeGlobal(global);

        var reloaded = SettingsManager.withStorage(storage).effective();
        assertThat(reloaded.defaultTools).containsExactly("+grep", "-bash");
    }
}
