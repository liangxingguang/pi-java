package com.pijava.coding.agent.extension;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.core.KeybindingsManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/22：pi {@code Component.capturesViewportInput} 的默认值——普通扩展组件
 * 不接管视口键，只有显式覆写的组件（如 /mcp 菜单）才接管。
 */
class ExtensionComponentTest {

    @Test
    void componentsDoNotCaptureViewportInputByDefault() {
        var component = new ExtensionComponent() {
            @Override
            public List<String> render(int width) {
                return List.of();
            }

            @Override
            public void handleInput(KeybindingsManager.KeyStroke key) {
                // Render-only component: keys are not used.
            }
        };
        assertThat(component.capturesViewportInput()).isFalse();
    }
}
