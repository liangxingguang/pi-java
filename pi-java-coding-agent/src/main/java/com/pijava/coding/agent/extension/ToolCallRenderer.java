package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;

/**
 * 批 16（T4a，docs/27 裁决⑦）：pi {@code ToolRenderers}（types.ts:658-662）的
 * java <b>文本级</b>对位——pi 产富 Component（VisualLinePreview/keyHint/expanded
 * 交互），java 产已转义的文本行，着色/状态由 tui 适配层按 isError/expanded 补
 * （窄差 D92）。模块分层：coding-agent 不依赖 tui。
 */
public interface ToolCallRenderer {

    /**
     * pi {@code renderCall}：工具名＋参数的展示行（已转义，可含多行——
     * 展开臂的参数逐行形）。
     */
    List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded);

    /**
     * pi {@code renderResult}：结果文本的展示行（已转义）；空结果 ⇒ 空表
     * （pi 的空 output 产空组件对位）。
     */
    List<String> renderResult(String toolName, String textOutput, boolean isError,
                              boolean expanded);
}
