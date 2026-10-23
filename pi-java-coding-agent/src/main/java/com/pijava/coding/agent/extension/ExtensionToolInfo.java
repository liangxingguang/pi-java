package com.pijava.coding.agent.extension;

/**
 * 批 16（T2b，docs/27 裁决⑥）：pi {@code ToolInfo}（types.ts:2093-2099）的
 * 窄化投影——java 工具定义只有 name/description/promptGuidelines，
 * pi 的 {@code parameters/exposure/namespace/annotations/sourceInfo} 无对位
 * （窄差 D91）。
 */
public record ExtensionToolInfo(String name, String description,
                                java.util.List<String> promptGuidelines) {}
