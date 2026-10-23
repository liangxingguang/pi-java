package com.pijava.coding.agent.extension;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.KeybindingsManager;

/**
 * 批 17（T1，docs/28）：{@code ctx.ui.custom} 的组件工厂——pi
 * {@code (tui, theme, keybindings, done) => Component}（types.ts:213-217）的
 * java 对位。
 *
 * <p>{@code done(result)} 是组件的**唯一**正常出口（幂等——pi 的 {@code closed} 闸
 * 对位，interactive-mode.ts:2982-2992）。pi 允许工厂返回 Promise（异步组件）；
 * java 的 custom 是阻塞语义，工厂同步返回（窄化，docs/28 §3-T5 注记）。</p>
 *
 * @param <T> {@code done} 回送的结果类型
 */
@FunctionalInterface
public interface ExtensionComponentFactory<T> {

    /**
     * 创建组件。
     *
     * @param env         宿主环境（终端尺寸/重绘/主题色投影）
     * @param keybindings 应用键位表（pi 第三参对位）
     * @param done        结果回送（调用即关闭组件）
     * @return 挂屏组件
     */
    ExtensionComponent create(ExtensionUiHostEnv env, KeybindingsManager keybindings,
                              Consumer<T> done);
}
