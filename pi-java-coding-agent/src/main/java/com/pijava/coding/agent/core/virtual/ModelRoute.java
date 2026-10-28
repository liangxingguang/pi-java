package com.pijava.coding.agent.core.virtual;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Router output (pi {@code ModelRoute}，virtual-models.ts:73-82 逐字段）。
 *
 * @param model         the physical model to route to（必须有凭据）
 * @param thinkingLevel thinking level for the request
 * @param state         new router state, stored on the session branch。返回
 *                      {@code request.state} 同值或 {@code null} 表示保持不变；
 *                      必须可 JSON 序列化；direct 请求忽略（pi :77-80 注释逐字义）。
 *                      java 的写回判等是<b>值判等</b>（pi 是引用判等，窄差 D160）
 */
public record ModelRoute(ModelId<?> model, ModelThinkingLevel thinkingLevel, JsonNode state) {

    /** 不带新态的路由（state 保持）。 */
    public static ModelRoute of(ModelId<?> model, ModelThinkingLevel thinkingLevel) {
        return new ModelRoute(model, thinkingLevel, null);
    }
}
