package com.pijava.coding.agent.extension;

/**
 * 扩展 SPI —— 第三方以 ServiceLoader（META-INF/services）或 JAR 方式注册。
 *
 * <p>{@link #register(ExtensionContext)} 在装配期被调用，注册工具/命令/Provider/
 * 技能到会话。name 唯一，用于 {@code list-extensions} 与去重。</p>
 */
public interface PiExtension {

    /** 唯一扩展名，如 "my-tools"。 */
    String name();

    /** 扩展描述，用于 list-extensions。 */
    default String description() {
        return "";
    }

    /** 注册工具/命令/Provider/Skill。 */
    void register(ExtensionContext ctx);
}
