package com.pijava.coding.agent.core;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.pijava.ai.model.DefaultModelResolver;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ScopedModel;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;

/**
 * L5 W6b 拆件（docs/25 §7；pi {@code main.ts:476-490} 的 {@code resolveCliModel} 接线）：
 * 初始模型解析——fork 携带 &gt; scope 首条 &gt; CLI 解析三级优先，CLI 臂带
 * {@code hasConfiguredAuth} 可用性判据与 {@code --api-key} 恒 false 语义。
 *
 * <p>从 {@link SessionSetup} 顶出以守住文件 ≤500 行闸门（行为零变化）。</p>
 */
final class CliModelWiring {

    private CliModelWiring() {
    }

    /**
     * 解析会话的初始模型。
     *
     * <p>优先级：<b>① fork 携带</b>（父会话已解析的模型）→ <b>② scope 首条</b>
     * （{@code --models} 命中集）→ <b>③ CLI 解析</b>（{@code --model}／settings
     * {@code defaultModel}）。</p>
     *
     * @param args          CLI 参数（{@code --model}/{@code --provider}/{@code --api-key}）
     * @param settings      settings 读取口（{@code hasConfiguredAuth} 的 provider key 源）
     * @param models        模型目录解析器
     * @param modelPattern  待解析的模型引用（{@code --model} 或 settings 默认）
     * @param providerName  回落 provider（非显式提示）
     * @param carryModel    fork 携带的父会话模型（可空）
     * @param scopedInitial scope 初始模型（可空）
     * @param providers     本次装配的 provider 注册表（可空）
     * @return 解析出的模型 id
     * @throws IllegalStateException 显式 {@code --model} 解析失败（文案即 CLI 错误文案）
     */
    static ModelId<?> resolveInitialModel(
            Args args, SettingsManager settings, DefaultModelResolver models,
            String modelPattern, String providerName, String carryModel,
            ScopedModel scopedInitial, ProviderRegistry providers) {
        if (carryModel != null) {
            // fork 携带的是父会话<b>已解析</b>的模型：直接采用，不再走目录解析——
            // 父会话可能用运行时注册的 provider（faux／扩展）或自定义 id。
            var slash = carryModel.indexOf('/');
            return slash > 0
                ? ModelId.of(carryModel.substring(0, slash), carryModel.substring(slash + 1))
                : models.resolve(carryModel, null);
        }
        if (scopedInitial != null) {
            return scopedInitial.model().id();
        }
        var inlineApiKey = args.apiKey() != null && !args.apiKey().isBlank();
        var effective = settings.effective();
        Predicate<String> usable = name -> !inlineApiKey
            && DefaultProviders.hasConfiguredAuth(name, effective);
        // 运行时注册的 provider（Faux／扩展 provider）不在静态目录里 ⇒ 显式 --provider
        // 指向它时按自定义 id 处理（pi 的 models 列表天然含它们）。查本次注册表与
        // 进程全局注册表两处：部分装配路径（无参 create／ServiceLoader）把 provider
        // 注册在全局实例上。
        Predicate<String> knownProvider = name ->
            (providers != null && providers.get(name).isPresent())
                || ProviderRegistry.global().get(name).isPresent();
        // 显式 --provider 才算「provider 提示」；缺省时 providerName 只是回落值，
        // 不得把裸未知 id 兜成该 provider 的自定义模型（pi resolveCliModel 同）。
        var explicitProvider = args.provider() != null && !args.provider().isBlank()
            ? args.provider() : null;
        // pi main.ts:455-476：显式 --model 走 resolveCliModel（解析失败即报错）；
        // settings 的 defaultModel 走「找不到就回落可用模型」臂（pi findInitialModel
        // 第 3 级：faux／扩展 provider 的模型不在静态目录里，不得让启动失败）。
        var explicitModel = args.model() != null && !args.model().isBlank();
        try {
            return models.resolve(modelPattern, providerName, explicitProvider, usable,
                inlineApiKey, knownProvider);
        } catch (IllegalStateException e) {
            // pi main.ts:894-934：解析失败进 diagnostics，非交互模式
            // reportDiagnostics 逐行 "Error: <msg>" 后 exit 1——不冒栈。
            if (explicitModel) {
                throw SessionResolutionExit.error(e.getMessage());
            }
            return models.resolve(Set.of(ModelCapability.TEXT),
                Optional.ofNullable(providerName).filter(p -> !p.isBlank()));
        }
    }
}
