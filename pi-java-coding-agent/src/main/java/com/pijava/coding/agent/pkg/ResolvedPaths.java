package com.pijava.coding.agent.pkg;

import java.util.List;

/** pi {@code ResolvedPaths}：四类资源的有序路径表。 */
public record ResolvedPaths(
    List<ResolvedResource> extensions,
    List<ResolvedResource> skills,
    List<ResolvedResource> prompts,
    List<ResolvedResource> themes
) {

    public List<ResolvedResource> ofType(ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> extensions;
            case SKILLS -> skills;
            case PROMPTS -> prompts;
            case THEMES -> themes;
        };
    }
}
