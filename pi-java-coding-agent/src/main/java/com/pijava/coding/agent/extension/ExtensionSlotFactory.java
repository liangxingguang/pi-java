package com.pijava.coding.agent.extension;

/**
 * 批 17（T1，docs/28）：widget（工厂臂）与 header 的组件工厂——pi
 * {@code (tui, theme) => Component & { dispose?(): void }}（types.ts:186/196）
 * 的 java 对位。
 */
@FunctionalInterface
public interface ExtensionSlotFactory {

    /**
     * 创建槽位组件。
     *
     * @param env 宿主环境（终端尺寸/重绘/主题色投影）
     * @return 槽位组件
     */
    ExtensionComponent create(ExtensionUiHostEnv env);
}
