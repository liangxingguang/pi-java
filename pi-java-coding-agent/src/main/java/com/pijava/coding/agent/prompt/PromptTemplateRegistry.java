package com.pijava.coding.agent.prompt;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话级提示模板注册表（运行时容器）。
 *
 * <p>对齐 pi {@code PromptTemplateRegistry}：按名字索引模板，支持注册、查询、
 * 列出。填充来源两路：CLI {@code --prompt-template} 路径与扩展的
 * {@code sessionStartResources()} promptPaths。注册到 {@code SessionServices}
 * 供 slash 命令 / prompt-template 与 TUI 消费。</p>
 */
public final class PromptTemplateRegistry {

    private final ConcurrentMap<String, PromptTemplates.PromptTemplate> templates =
        new ConcurrentHashMap<>();

    /** 注册一个模板（同名覆盖，后注册胜出）。 */
    public void register(PromptTemplates.PromptTemplate template) {
        templates.put(template.name(), template);
    }

    /** 批量注册。 */
    public void registerAll(Collection<PromptTemplates.PromptTemplate> templates) {
        for (var t : templates) {
            register(t);
        }
    }

    /** 按名字查询；未知名字抛 {@link UnknownPromptTemplateException}。 */
    public PromptTemplates.PromptTemplate get(String name) {
        var template = templates.get(name);
        if (template == null) {
            throw new UnknownPromptTemplateException(name);
        }
        return template;
    }

    /** 所有已注册模板（保序）。 */
    public List<PromptTemplates.PromptTemplate> all() {
        return List.copyOf(templates.values());
    }

    /** 模板数。 */
    public int size() {
        return templates.size();
    }

    /** 查询时模板不存在。 */
    public static final class UnknownPromptTemplateException extends RuntimeException {
        /** 创建未知模板异常。 */
        public UnknownPromptTemplateException(String name) {
            super("Unknown prompt template: " + name);
        }
    }
}
