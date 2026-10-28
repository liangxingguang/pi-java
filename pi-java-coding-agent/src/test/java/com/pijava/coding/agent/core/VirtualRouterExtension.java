package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionVirtualModel;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.core.virtual.ModelRoute;
import com.pijava.coding.agent.core.virtual.ModelRouteRequest;

/**
 * 批 27（B162/T10 测试夹具）：jev-router 式虚拟模型扩展（pi
 * {@code examples/extensions/jev-router.ts:90-112} 的骨架镜像）——注册
 * {@code jev/auto}，路由到 {@code faux-vm/hello}；首个 user 请求回写路由态
 * {@code {phase:"routed"}}，之后保持不变（state 返 null）。
 *
 * <p>顶层 public 类是 ServiceLoader 的硬要求。</p>
 */
public final class VirtualRouterExtension implements PiExtension {

    /** 测试观测口：路由请求记录。 */
    public static final List<ModelRouteRequest> REQUESTS = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "virtual-router";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.registerVirtualModel(ExtensionVirtualModel.of("jev", "auto", "Auto (test)",
            (request, routeCtx) -> {
                REQUESTS.add(request);
                var state = request.state();
                return new ModelRoute(ModelId.of("faux-vm", "hello"), request.thinkingLevel(),
                    state == null
                        ? com.pijava.coding.agent.core.Json.mapper()
                            .valueToTree(java.util.Map.of("phase", "routed"))
                        : null);
            }));
    }
}
