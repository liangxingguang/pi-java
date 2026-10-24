package com.pijava.coding.agent.pkg;

import java.util.List;
import java.util.Locale;

/**
 * pi {@code ResourceType}（package-manager.ts:211-213）——纯常量闭集 ⇒ enum；
 * id 同时是包内约定目录名（{@code extensions/skills/prompts/themes}）。
 */
public enum ResourceType {
    EXTENSIONS,
    SKILLS,
    PROMPTS,
    THEMES;

    /** pi {@code RESOURCE_TYPES} 迭代序。 */
    public static final List<ResourceType> ALL = List.of(values());

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
