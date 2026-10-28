package com.pijava.coding.agent.extension;

/**
 * 批 16（T2b，docs/27 裁决⑥）：pi {@code ToolInfo}（types.ts:2093-2099）的投影。
 *
 * <p>批 27（B162 裁决④）：{@code exposure}/{@code parameters}/{@code namespace}
 * 三字段落地（tool_search 的候选构建需要——pi {@code searchAndLoad} 读
 * {@code tool.exposure}/{@code tool.namespace}，tool.ts:196-214），窄差 D91 收窄
 * （余 annotations/sourceInfo 无对位）。旧三参构造保留（存量构造点零改签，
 * 三新字段给默认：direct／空 schema／无命名空间——pi 的缺席默认）。</p>
 */
public record ExtensionToolInfo(String name, String description,
                                java.util.List<String> promptGuidelines,
                                ToolExposure exposure,
                                java.util.Map<String, Object> parameters,
                                com.pijava.coding.agent.core.ToolDefinitions.Namespace namespace) {

    /** 批 27 之前的三参形状（默认 direct／空 schema／无命名空间）。 */
    public ExtensionToolInfo(String name, String description,
                             java.util.List<String> promptGuidelines) {
        this(name, description, promptGuidelines, ToolExposure.DIRECT,
            java.util.Map.of("type", "object"), null);
    }

    /** Defensive copies. */
    public ExtensionToolInfo {
        promptGuidelines = java.util.List.copyOf(promptGuidelines);
        parameters = java.util.Map.copyOf(parameters);
    }
}
