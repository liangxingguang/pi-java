package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;

/**
 * 批 26（docs/37 T10，E5-8，裁决③）：pi {@code MessageRenderer}
 * （types.ts:1524-1528）的 java <b>文本级</b>对位——pi 产富 Component，java 产
 * 原始文本行（转义在渲染臂；着色/展开交互不做，D92 同族窄差）。
 */
@FunctionalInterface
public interface ExtensionMessageRenderer {

    /**
     * pi {@code (message, options, theme) => Component | undefined}。
     *
     * @param customType 注册时声明的 customType
     * @param content    消息内容（进 LLM 上下文的那份）
     * @param details    扩展私有元数据
     * @param expanded   pi {@code MessageRenderOptions.expanded}
     * @param outputPad  pi {@code MessageRenderOptions.outputPad}
     * @return 展示行；空表 ≙ pi 返回 undefined（调用方回落默认渲染）
     */
    List<String> render(String customType, CustomMessageContent content,
                        Map<String, Object> details, boolean expanded, int outputPad);
}
