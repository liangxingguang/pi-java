package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;

/**
 * 批 16（T4a，docs/27 裁决⑦）：pi {@code ToolRenderers}（types.ts:658-662）的
 * java <b>文本级</b>对位——pi 产富 Component（VisualLinePreview/keyHint/expanded
 * 交互），java 产已转义的文本行，着色/状态由 tui 适配层按 isError/expanded 补
 * （窄差 D92）。模块分层：coding-agent 不依赖 tui。
 *
 * <p>docs/60（L7 W1b）：两个方法都是<b>可选</b>的，与 pi 逐方法合并语义
 * （{@code renderers/index.ts:51-63}）一致——{@code null} ≙ pi 的 undefined
 * （"无意见"），调用方回落默认渲染；空表则表达"渲染器认为结果为空"。</p>
 */
public interface ToolCallRenderer {

    /**
     * pi {@code renderCall}：工具名＋参数的展示行（已转义，可含多行——
     * 展开臂的参数逐行形）。
     *
     * @return 文本行；{@code null} ≙ 无意见（调用方回落默认渲染）
     */
    default List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
        return null;
    }

    /**
     * pi {@code renderResult}：结果文本的展示行（已转义）；空结果 ⇒ 空表
     * （pi 的空 output 产空组件对位）。
     *
     * @return 文本行；{@code null} ≙ 无意见（调用方回落默认渲染，例如保留
     *         {@code DiffContent} 的彩色 diff）
     */
    default List<String> renderResult(String toolName, String textOutput, boolean isError,
                                      boolean expanded) {
        return null;
    }
}
