package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;

/**
 * 批 26（docs/37 T10，E5-10，裁决③）：pi {@code EntryRenderer}
 * （types.ts:1533-1535）的 java <b>文本级</b>对位——pi 产富 Component，java 产
 * 原始文本行。custom 条目不进 LLM 上下文（pi 同）。
 */
@FunctionalInterface
public interface ExtensionEntryRenderer {

    /**
     * pi {@code (entry, options, theme) => Component | undefined}。
     *
     * @param customType 注册时声明的 customType
     * @param data       条目数据
     * @param expanded   pi {@code EntryRenderOptions.expanded}
     * @return 展示行；空表 ≙ pi 的 {@code !component.hasContent()}（不渲）
     */
    List<String> render(String customType, Map<String, Object> data, boolean expanded);
}
