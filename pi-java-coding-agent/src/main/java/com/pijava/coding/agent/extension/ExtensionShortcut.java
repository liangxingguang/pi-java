package com.pijava.coding.agent.extension;

/**
 * 批 26（docs/37 T9，E5-6）：pi {@code ExtensionShortcut}（types.ts:2066-2071）——
 * 扩展注册的键盘快捷键。
 *
 * @param keyId         pi {@code KeyId} 串（{@code "ctrl+shift+x"} 形；注册点已解析校验）
 * @param description   可选描述（/hotkeys Extensions 段显示；缺省回落 extensionPath）
 * @param handler       处理器（pi {@code (ctx) => Promise<void> | void}；java 同步接口，
 *                      宿主在虚拟线程上调用 ⇒ 可阻塞）
 * @param extensionPath 注册来源（pi {@code extensionPath}；诊断与归属用）
 */
public record ExtensionShortcut(String keyId, String description, ShortcutHandler handler,
                                String extensionPath) {

    /** pi 的 shortcut handler 签名对位。 */
    @FunctionalInterface
    public interface ShortcutHandler {
        /** 运行快捷键处理器；异常由宿主隔离上报（pi showError 逐字前缀）。 */
        void run(ExtensionContext ctx) throws Exception;
    }
}
