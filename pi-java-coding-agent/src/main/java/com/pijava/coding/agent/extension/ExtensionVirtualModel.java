package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.function.BiFunction;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.core.virtual.ModelRoute;
import com.pijava.coding.agent.core.virtual.ModelRouteRequest;

/**
 * Virtual model registered via {@code registerVirtualModel()} — pi
 * {@code ExtensionVirtualModel}（types.ts:1896-1900 逐字）：字段同
 * {@code VirtualModelDefinition}，{@code route} 多带一个扩展上下文。
 *
 * <p>语义（pi types.ts:1874-1881 注释要点）：选型（{@code ctx.model}、
 * {@code model_change} 条目）记虚拟模型名；assistant 消息记路由器选出的物理模型与
 * thinking level；{@code provider} 可以是有物理模型的 provider，可挂多个虚拟模型；
 * 同 provider+id 重复注册即替换。</p>
 *
 * @param route pi 的 {@code route(request, ctx)}——java 同步化（虚拟线程上阻塞
 *              等价 pi 的 await，见 {@code VirtualModelDefinition} 类注释）
 */
public record ExtensionVirtualModel(
    String provider,
    String id,
    String name,
    List<ModelThinkingLevel> thinkingLevels,
    int contextWindow,
    int maxTokens,
    List<String> input,
    BiFunction<ModelRouteRequest, ExtensionContext, ModelRoute> route
) {

    /** 最小形态：provider/id/name/route，其余默认（thinkingLevels ["off"]、input [text,image]）。 */
    public static ExtensionVirtualModel of(String provider, String id, String name,
            BiFunction<ModelRouteRequest, ExtensionContext, ModelRoute> route) {
        return new ExtensionVirtualModel(provider, id, name, null, 0, 0, null, route);
    }
}
