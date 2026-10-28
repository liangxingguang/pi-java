package com.pijava.coding.agent.core.virtual;

import java.util.List;

import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * A registered virtual model (pi {@code VirtualModelDefinition}，
 * virtual-models.ts:84-102 逐字段）。
 *
 * <p>pi 的 {@code route} 可返回 Promise；java 的路由器在虚拟线程上同步调用——
 * 需要异步（如调分类器模型）的扩展在实现内自行 {@code .join()}（异步任务在
 * 虚拟线程上阻塞无代价，java 方言）。</p>
 */
public record VirtualModelDefinition(
    /** Provider the virtual model is listed under（可以是有物理模型的 provider）。 */
    String provider,
    /** Model id. Must not be the id of a physical model of {@code provider}. */
    String id,
    /** Display name. */
    String name,
    /** Thinking levels offered for selection. Defaults to {@code ["off"]}. */
    List<ModelThinkingLevel> thinkingLevels,
    /** Limits shown before the first response（之后用应答物理模型的）；0 ≙ 未知。 */
    int contextWindow,
    /** 见 {@link #contextWindow}。 */
    int maxTokens,
    /** Input types accepted for selection（默认 text+image；路由到无图模型的图片得占位符）。 */
    List<String> input,
    /** Pick the physical model and thinking level for one request. */
    Router route
) {

    /** pi {@code route(request)}（同步化，见类注释）。 */
    @FunctionalInterface
    public interface Router {

        /** Route one request. */
        ModelRoute route(ModelRouteRequest request);
    }

    /** Defaults：pi :90-99——thinkingLevels 缺省 ["off"]、input 缺省 ["text","image"]。 */
    public VirtualModelDefinition {
        if (thinkingLevels == null || thinkingLevels.isEmpty()) {
            thinkingLevels = List.of(ModelThinkingLevel.off());
        } else {
            thinkingLevels = List.copyOf(thinkingLevels);
        }
        input = input == null ? List.of("text", "image") : List.copyOf(input);
    }

    /** 最小形态：provider/id/name/route，其余全默认。 */
    public static VirtualModelDefinition of(String provider, String id, String name,
                                            Router route) {
        return new VirtualModelDefinition(provider, id, name, null, 0, 0, null, route);
    }
}
