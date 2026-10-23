package com.pijava.coding.agent.extension;

import java.util.List;

import com.pijava.coding.agent.core.KeybindingsManager;

/**
 * 批 17（T1，docs/28 裁决③）：扩展自有组件——pi {@code Component} 的**文本级**对位
 * （coding-agent 不依赖 TamboUI，批 16 裁决⑦的文本级先例）。
 *
 * <p>TUI 侧经适配层把 {@code render(width)} 行集挂进模态层/槽位、把终端按键转成
 * 中性 {@link KeybindingsManager.KeyStroke} 喂给 {@link #handleInput}。</p>
 */
public interface ExtensionComponent {

    /** 按给定宽度渲染为终端行（pi {@code render(width): string[]}）。 */
    List<String> render(int width);

    /** 处理一次按键（pi {@code handleInput(data)}；KeyStroke 为中性键表示）。 */
    void handleInput(KeybindingsManager.KeyStroke key);

    /** 卸载清理（pi {@code dispose?()}）。 */
    default void dispose() { }
}
