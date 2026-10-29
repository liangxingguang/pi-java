package com.pijava.coding.agent.extension;

import com.pijava.agent.tool.ToolAnnotations;
import com.pijava.agent.tool.ToolExposure;
import com.pijava.agent.tool.ToolNamespace;

/**
 * 批 16（T2b，docs/27 裁决⑥）：pi {@code ToolInfo}（types.ts:2093-2099）的投影。
 *
 * <p>批 27（B162 裁决④）：{@code exposure}/{@code parameters}/{@code namespace}
 * 三字段落地（tool_search 的候选构建需要——pi {@code searchAndLoad} 读
 * {@code tool.exposure}/{@code tool.namespace}，tool.ts:196-214）。旧三参构造保留
 * （存量构造点零改签，新字段给默认：direct／空 schema／无命名空间——pi 的缺席默认）。</p>
 *
 * <p>批 30（B203 收编）：三字段的源改到 {@code AgentTool} 本体（pi
 * {@code ToolDefinition} 本体对位）；同批补 {@code annotations}（pi
 * {@code ToolInfo} 有，types.ts:2094-2099——D91 余量收窄为「仅 sourceInfo 无对位」）。</p>
 */
public record ExtensionToolInfo(String name, String description,
                                java.util.List<String> promptGuidelines,
                                ToolExposure exposure,
                                java.util.Map<String, Object> parameters,
                                ToolNamespace namespace,
                                ToolAnnotations annotations) {

    /** 批 30 之前的六参形状（默认无 annotations——pi 的缺席）。 */
    public ExtensionToolInfo(String name, String description,
                             java.util.List<String> promptGuidelines,
                             ToolExposure exposure,
                             java.util.Map<String, Object> parameters,
                             ToolNamespace namespace) {
        this(name, description, promptGuidelines, exposure, parameters, namespace, null);
    }

    /** 批 27 之前的三参形状（默认 direct／空 schema／无命名空间）。 */
    public ExtensionToolInfo(String name, String description,
                             java.util.List<String> promptGuidelines) {
        this(name, description, promptGuidelines, ToolExposure.DIRECT,
            java.util.Map.of("type", "object"), null, null);
    }

    /** Defensive copies. */
    public ExtensionToolInfo {
        promptGuidelines = java.util.List.copyOf(promptGuidelines);
        parameters = java.util.Map.copyOf(parameters);
    }
}
