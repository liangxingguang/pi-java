package com.pijava.coding.agent.core;

import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * pi {@code PackageSourceSchema}（settings-schema.ts:170-190）——string 与 object
 * 二选一联合的 java 表达。字符串形在反序列化层归一成对象形；写回时当且仅当
 * 初始为裸串才序列化回字符串（{@link #stringForm} 标记），保持写回不胀形——
 * 批 19 的 {@code settings.packages} 写回链依赖此性质。
 *
 * <p>对象形字段语义（pi 逐字）：</p>
 * <ul>
 *   <li>{@code autoload=false}：start empty and only apply explicit resource patterns.</li>
 *   <li>{@code extensions/skills/prompts/themes}：资源 pattern 表（{@code !}/{@code +}/
 *       {@code -} 前缀代数，见 pkg {@code ResourcePatterns}）。</li>
 * </ul>
 */
public record PackageSource(
    @JsonProperty("source") String source,
    @JsonProperty("autoload") Boolean autoload,
    @JsonProperty("extensions") List<String> extensions,
    @JsonProperty("skills") List<String> skills,
    @JsonProperty("prompts") List<String> prompts,
    @JsonProperty("themes") List<String> themes,
    @JsonIgnore boolean stringForm
) {

    /** 字符串形（pi union 的 string 臂）：装载包内全部资源。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PackageSource of(String source) {
        return new PackageSource(source, null, null, null, null, null, true);
    }

    /** 对象形（pi union 的 object 臂）。 */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static PackageSource of(
        @JsonProperty("source") String source,
        @JsonProperty("autoload") Boolean autoload,
        @JsonProperty("extensions") List<String> extensions,
        @JsonProperty("skills") List<String> skills,
        @JsonProperty("prompts") List<String> prompts,
        @JsonProperty("themes") List<String> themes
    ) {
        return new PackageSource(source, autoload, extensions, skills, prompts, themes, false);
    }

    /** 程序化构造对象形（非 JSON 边界，恒 {@code stringForm=false}）。 */
    public static PackageSource object(String source, Boolean autoload, List<String> extensions,
            List<String> skills, List<String> prompts, List<String> themes) {
        return new PackageSource(source, autoload, extensions, skills, prompts, themes, false);
    }

    /**
     * 写回不胀形：裸串来的写回裸串；对象形只写非 null 键（pi 对象臂全字段
     * optional，缺席键不应出现在写回 JSON 里）。
     */
    @JsonValue
    public Object toJson() {
        if (stringForm) {
            return source;
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("source", source);
        if (autoload != null) {
            map.put("autoload", autoload);
        }
        if (extensions != null) {
            map.put("extensions", extensions);
        }
        if (skills != null) {
            map.put("skills", skills);
        }
        if (prompts != null) {
            map.put("prompts", prompts);
        }
        if (themes != null) {
            map.put("themes", themes);
        }
        return map;
    }
}
