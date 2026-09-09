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

    /**
     * 会话开始时被调用，可贡献额外资源路径（对齐 pi {@code resources_discover}）。
     *
     * <p>返回的资源路径在会话装配时并入 CLI 参数：skillPaths 追加到技能发现，
     * promptPaths 追加到提示模板注册，themePaths 作为主题候选（启动时选第一个
     * 可用）。抛异常被隔离（记为告警），贡献视为空。</p>
     */
    default ResourcePaths sessionStartResources() {
        return ResourcePaths.none();
    }
}
