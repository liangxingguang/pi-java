package com.pijava.coding.agent.extension;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T1，docs/28 §7-1）：{@link ExtensionUI#NOOP} 的 28 成员 default 语义
 * 全过——逐字对位 pi {@code noOpUIContext}（runner.ts:324-352）。
 */
class ExtensionUiNoOpTest {

    private final ExtensionUI ui = ExtensionUI.NOOP;

    @Test
    void blockingFiveReturnCancelValues() {
        assertThat(ui.select("t", List.of("a"), null)).isNull();
        assertThat(ui.confirm("t", "m", null)).isFalse();
        assertThat(ui.input("t", null, null)).isNull();
        assertThat(ui.editor("t", null)).isNull();
        assertThat(ui.<Object>custom((env, keys, done) -> null, null)).isNull();
    }

    @Test
    void fireAndForgetMembersAreSilentNoOps() {
        ui.notify("m", NotifyType.ERROR);
        ui.setStatus("k", "v");
        ui.setWidget("k", new ExtensionWidgetContent.Lines(List.of("x")), null);
        ui.setFooter((env, data) -> null);
        ui.setHeader(env -> null);
        ui.setTitle("t");
        ui.setWorkingMessage("m");
        ui.setWorkingVisible(true);
        ui.setWorkingIndicator(new WorkingIndicatorOptions(List.of("●"), 80L));
        ui.setHiddenThinkingLabel("l");
        ui.pasteToEditor("t");
        ui.setEditorText("t");
        ui.addAutocompleteProvider(java.util.function.UnaryOperator.identity());
        ui.setEditorComponent((env, keys) -> null);
        ui.setToolsExpanded(true);
        // 无异常即通过（pi 空操作逐字）
    }

    @Test
    void queryMembersReturnEmptyDefaults() {
        assertThat(ui.getEditorText()).isEmpty();
        assertThat(ui.getEditorComponent()).isNull();
        assertThat(ui.getAllThemes()).isEmpty();
        assertThat(ui.getTheme("dark")).isNull();
        assertThat(ui.getToolsExpanded()).isFalse();
    }

    @Test
    void setThemeFailsWithVerbatimMessage() {
        var result = ui.setTheme("nord");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("UI not available");
    }

    @Test
    void onTerminalInputReturnsEmptyUnsubscribe() {
        Runnable unsubscribe = ui.onTerminalInput(data -> null);
        unsubscribe.run(); // 空闭包不抛
    }

    @Test
    void requestChannelImmediatelyCancels() {
        var response = ui.request(new com.pijava.coding.agent.rpc.RpcExtensionUIRequest(
            "id-1", "input", java.util.Map.of()));
        assertThat(response.id()).isEqualTo("id-1");
        assertThat(response.confirmed()).isFalse();
        assertThat(response.cancelled()).isTrue();
    }

    @Test
    void noopFactoryReturnsSingleton() {
        assertThat(ExtensionUI.noop()).isSameAs(ExtensionUI.NOOP);
    }
}
