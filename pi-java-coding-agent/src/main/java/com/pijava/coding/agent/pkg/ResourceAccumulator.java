package com.pijava.coding.agent.pkg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pi {@code ResourceAccumulator}（:165-170）——四张 LinkedHashMap（<b>保序</b>，
 * pi Map 插入序语义；同 rank 时先插入者胜）。
 */
public final class ResourceAccumulator {

    /** 单资源槽（pi {@code {metadata, enabled}}）。 */
    public record Slot(PathMetadata metadata, boolean enabled) {}

    private final Map<String, Slot> extensions = new LinkedHashMap<>();
    private final Map<String, Slot> skills = new LinkedHashMap<>();
    private final Map<String, Slot> prompts = new LinkedHashMap<>();
    private final Map<String, Slot> themes = new LinkedHashMap<>();

    /** pi {@code getTargetMap}（:2602-2614）。 */
    public Map<String, Slot> target(ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> extensions;
            case SKILLS -> skills;
            case PROMPTS -> prompts;
            case THEMES -> themes;
        };
    }

    /** pi {@code addResource}（:2616-2626）：first-wins（已存在不覆写）。 */
    public void addResource(ResourceType type, String path, PathMetadata metadata, boolean enabled) {
        if (path == null || path.isEmpty()) {
            return;
        }
        target(type).computeIfAbsent(path, k -> new Slot(metadata, enabled));
    }
}
