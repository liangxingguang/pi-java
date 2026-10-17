package com.pijava.coding.agent.prompt;

import java.util.regex.Pattern;

/**
 * pi {@code expandPromptTemplate}（{@code prompt-templates.ts:304-320}）对位（map #64）。
 *
 * <p>{@code /<name> [args]} 命中已注册模板 ⇒ 参数替换后的正文；否则原文透传。
 * 消费序在 {@code /skill:} 展开之后（pi :2008-2009 / :2191-2192）。</p>
 */
public final class PromptTemplateExpansion {

    private static final Pattern COMMAND = Pattern.compile("^/([^\\s]+)(?:\\s+([\\s\\S]*))?$");

    private PromptTemplateExpansion() {}

    /** 展开 {@code /<template> [args]}；非命令形 / 未知名 ⇒ 原文透传。 */
    public static String expand(String text, PromptTemplateRegistry registry) {
        if (!text.startsWith("/")) {
            return text;
        }
        var m = COMMAND.matcher(text);
        if (!m.matches()) {
            return text;
        }
        var template = registry.find(m.group(1));
        if (template == null) {
            return text;
        }
        var args = PromptTemplates.parseCommandArgs(m.group(2) == null ? "" : m.group(2));
        return PromptTemplates.substituteArgs(template.content(), args);
    }
}
