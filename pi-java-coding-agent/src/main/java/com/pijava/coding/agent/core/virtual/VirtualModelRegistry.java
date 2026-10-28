package com.pijava.coding.agent.core.virtual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.auth.Credentials;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Virtual model registry and router — pi {@code model-runtime.ts} 的虚拟模型部分
 * （:952-1033）＋{@code virtual-models.ts} 的判别/解析内核的 java 对位。
 *
 * <p>裁决①（docs/38 §3）：判别式<b>外在化</b>——java 的选型货币 {@code ModelId}
 * 无 {@code api} 字段，{@code isVirtual} 走本注册表查找（pi 自己的
 * {@code getBranchSelection} 也经 {@code getModel} 解析后判别，同构）。虚拟条目
 * 不进 {@code ModelCatalog}（选择器列表挂账 map/05 屏域变量③，裁决⑨）。</p>
 *
 * <p>错误文案与 pi 逐字（模型可见面）。</p>
 */
public final class VirtualModelRegistry {

    /** pi {@code VIRTUAL_MODEL_STATE_ENTRY}（virtual-models.ts:32）。 */
    public static final String STATE_ENTRY = "pi.virtual-model-state";

    /**
     * 目录查物理模型（pi {@code getPhysicalModel} 的目录臂——java 目录不含虚拟
     * 条目，查到即物理）。
     */
    private volatile BiPredicate<String, String> physicalModelExists;
    /**
     * pi {@code hasConfiguredAuth} 对位——生产默认走活凭证链
     * （{@code Credentials.resolveCredential}）；宿主/测试可经
     * {@link #authGate} 换闸（pi 的 auth 是注册表快照、测试夹具直接预置，
     * java 的静态链无注入点 ⇒ 显式闸）。
     */
    private volatile java.util.function.Predicate<String> authGate =
        provider -> Credentials.resolveCredential(provider).isPresent();
    /** 注册表：provider →（id → definition）。 */
    private final Map<String, Map<String, VirtualModelDefinition>> virtualModels =
        new LinkedHashMap<>();
    /**
     * pi :967-973 的 snapshot 标记对位：注册过虚拟模型的 provider 视为「已配置」
     * （纯虚拟 provider 无凭据需求——session 恢复/扩展 setModel 的 auth 门豁免源）。
     */
    private final java.util.Set<String> virtualAuthExempt = new java.util.HashSet<>();

    /**
     * @param physicalModelExists 目录物理模型查询（{@code (provider, id) → 在册}）
     */
    public VirtualModelRegistry(BiPredicate<String, String> physicalModelExists) {
        this.physicalModelExists = physicalModelExists;
    }

    /** 宿主/测试覆写 auth 闸（见字段注释）。 */
    public void authGate(java.util.function.Predicate<String> gate) {
        this.authGate = gate;
    }

    /** 宿主/测试覆写物理模型闸。 */
    public void physicalGate(BiPredicate<String, String> gate) {
        this.physicalModelExists = gate;
    }

    /** pi {@code isVirtualModel}（virtual-models.ts:105-107）的注册表形。 */
    public synchronized boolean isVirtual(ModelId<?> model) {
        return model != null
            && virtualModels.getOrDefault(model.provider(), Map.of())
                .containsKey(model.modelName());
    }

    /** pi {@code registerVirtualModel}（model-runtime.ts:952-976）。同 provider+id 重复注册＝替换。 */
    public synchronized void register(VirtualModelDefinition definition) {
        var providerId = definition.provider();
        var id = definition.id();
        if (providerId == null || providerId.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                "Virtual model provider and id must not be empty.");
        }
        if (physicalModelExists.test(providerId, id)) {
            throw new IllegalArgumentException(
                "Virtual model " + providerId + "/" + id + " conflicts with a physical model.");
        }
        virtualModels.computeIfAbsent(providerId, k -> new LinkedHashMap<>())
            .put(id, definition);
        // pi :967-973：纯虚拟 provider 无凭据需求，注册即在 snapshot 标记 configured
        virtualAuthExempt.add(providerId);
    }

    /** pi {@code unregisterVirtualModel}（model-runtime.ts:978-985）。 */
    public synchronized void unregister(String provider, String id) {
        var models = virtualModels.get(provider);
        if (models == null || models.remove(id) == null) {
            return;
        }
        if (models.isEmpty()) {
            virtualModels.remove(provider);
            virtualAuthExempt.remove(provider);
        }
    }

    /**
     * pi 的 auth 豁免面（:967-973 的 effect）：该 provider 是否因注册过虚拟模型
     * 而视为已配置。
     */
    public synchronized boolean isAuthExempt(String provider) {
        return virtualAuthExempt.contains(provider);
    }

    /** 当前注册的虚拟模型定义快照（诊断/列表用）。 */
    public synchronized List<VirtualModelDefinition> list() {
        var all = new ArrayList<VirtualModelDefinition>();
        for (var models : virtualModels.values()) {
            all.addAll(models.values());
        }
        return List.copyOf(all);
    }

    /**
     * pi {@code resolveModel}（model-runtime.ts:996-1027 逐字序）：解析路由器、
     * 构造 previous/failed、调路由、校验目标是物理模型且有凭据。
     *
     * @param failed 重试时的失败请求（reason=retry 时由调用方推导；无 ⇒ null）
     * @return 路由结果（含路由态）
     */
    public ModelRoute resolve(ModelId<?> model, List<Message> messages,
                              ModelThinkingLevel thinkingLevel, ModelRouteReason reason,
                              JsonNode state, ModelRouteRequest.RouteFailure failed,
                              AbortSignal signal) {
        var name = "Virtual model " + model.provider() + "/" + model.modelName();
        VirtualModelDefinition virtual;
        synchronized (this) {
            virtual = virtualModels.getOrDefault(model.provider(), Map.of())
                .get(model.modelName());
        }
        if (virtual == null) {
            throw new IllegalStateException(name + " is not registered.");
        }
        // pi :1002-1008：previous＝最近一次成功响应的物理模型（失败路由的消息上留
        // 虚拟名 ⇒ 查不到物理 ⇒ previous 缺席）
        ModelRouteRequest.RoutePrevious previous = null;
        var latest = VirtualModelSelections.findLatestResponse(messages);
        if (latest != null && latest.provider() != null && latest.model() != null
                && physicalModelExists.test(latest.provider(), latest.model())) {
            previous = new ModelRouteRequest.RoutePrevious(
                ModelId.of(latest.provider(), latest.model()), latest.thinkingLevel());
        }
        // pi :1009-1010：失败路由指虚拟名 ⇒ 无物理请求可报（failed.model 缺席化）
        var resolvedFailed = failed != null
                && physicalModelExists.test(failed.model().provider(), failed.model().modelName())
            ? failed : null;
        var route = virtual.route().route(new ModelRouteRequest(
            model, thinkingLevel, reason, previous, resolvedFailed, state, messages, signal));
        // pi :1013-1016：目标必须是物理模型且有凭据（文案逐字）
        var target = route.model();
        var routed = name + " routed to " + target.provider() + "/" + target.modelName();
        if (!physicalModelExists.test(target.provider(), target.modelName())
                || isVirtual(target)) {
            throw new IllegalStateException(routed + ", which is not a physical model.");
        }
        if (!isAuthExempt(target.provider()) && !authGate.test(target.provider())) {
            throw new IllegalStateException(routed + ", which has no credentials.");
        }
        // pi :1017 的 clampThinkingLevel(target, route.thinkingLevel)——java 的级别
        // 夹取在车道侧按模型能力处理（AnthropicThinking.resolve，既有口径），透传。
        return route;
    }
}
