package com.pijava.coding.agent.core.virtual;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Router input (pi {@code ModelRouteRequest}，virtual-models.ts:52-70 逐字段）。
 *
 * <p>批 27 裁决②：pi 的泛型 {@code TState}（运行时实为 unknown/结构化 JSON）在
 * java 落成 {@link JsonNode}——持久化经 custom 条目的 data 往返，免类型见证。</p>
 *
 * @param model         the selected virtual model
 * @param thinkingLevel the selected thinking level (its meaning is up to the router)
 * @param reason        why this request is being routed
 * @param previous      physical model and thinking level of the latest successful
 *                      response in {@code messages}（无 ⇒ null ≙ pi 的 undefined）
 * @param failed        for {@code retry}: the failed request, which
 *                      {@code messages} no longer contains（pi 口径；java 方言见
 *                      docs/38 §3 裁决③——重试时失败消息仍在工作副本，failed 从
 *                      上下文推导）。路由器自身失败时缺席
 * @param state         router state last returned on this session branch
 *                      （首个状态前与 direct 请求为 null）
 * @param messages      conversation for this request, including system messages
 * @param signal        abort signal（可为 null）
 */
public record ModelRouteRequest(
    ModelId<?> model,
    ModelThinkingLevel thinkingLevel,
    ModelRouteReason reason,
    RoutePrevious previous,
    RouteFailure failed,
    JsonNode state,
    List<Message> messages,
    AbortSignal signal
) {

    /**
     * pi {@code previous}（:60-61）：{ model, thinkingLevel? }——最近一次成功响应的
     * 物理模型与 thinking level。java 方言：thinkingLevel 用消息上记录的
     * <b>标签字符串</b>（{@code AssistantMessage.thinkingLevel} 的存储形）。
     */
    public record RoutePrevious(ModelId<?> model, String thinkingLevel) {}

    /**
     * pi {@code failed}（:62-67）：{ model, thinkingLevel?, message }——message 携带
     * stopReason 与 errorMessage。thinkingLevel 同上（标签字符串）。
     */
    public record RouteFailure(ModelId<?> model, String thinkingLevel,
                               Message.AssistantMessage message) {}
}
