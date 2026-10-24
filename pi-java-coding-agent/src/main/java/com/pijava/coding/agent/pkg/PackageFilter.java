package com.pijava.coding.agent.pkg;

import java.util.List;

import com.pijava.coding.agent.core.PackageSource;

/**
 * pi {@code PackageFilter}（package-manager.ts:206-212 一带）——对象形包源的
 * 资源过滤面（{@code autoload}＋四类 pattern 表）。
 */
public record PackageFilter(
    Boolean autoload,
    List<String> extensions,
    List<String> skills,
    List<String> prompts,
    List<String> themes
) {

    /** 对象形 {@link PackageSource} ⇒ filter（pi：字符串形 ⇒ undefined filter）。 */
    public static PackageFilter from(PackageSource source) {
        return new PackageFilter(source.autoload(), source.extensions(), source.skills(),
            source.prompts(), source.themes());
    }

    public List<String> patternsOf(ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> extensions;
            case SKILLS -> skills;
            case PROMPTS -> prompts;
            case THEMES -> themes;
        };
    }
}
