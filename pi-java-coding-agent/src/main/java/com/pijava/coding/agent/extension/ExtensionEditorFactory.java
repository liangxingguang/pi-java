package com.pijava.coding.agent.extension;

import com.pijava.coding.agent.core.KeybindingsManager;

/**
 * 批 17（T1，docs/28）：pi {@code EditorFactory}（types.ts:153：
 * {@code (tui, theme: EditorTheme, keybindings) => EditorComponent}）的 java 对位
 * ——自定义编辑器组件工厂（{@code setEditorComponent} 的参数型）。
 */
@FunctionalInterface
public interface ExtensionEditorFactory {

    /**
     * 创建编辑器组件。
     *
     * @param env         宿主环境（终端尺寸/重绘/主题色投影）
     * @param keybindings 应用键位表（pi 第三参对位）
     * @return 编辑器组件
     */
    ExtensionComponent create(ExtensionUiHostEnv env, KeybindingsManager keybindings);
}
