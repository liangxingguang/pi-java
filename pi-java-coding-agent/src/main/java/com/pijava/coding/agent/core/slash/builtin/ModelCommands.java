package com.pijava.coding.agent.core.slash.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.pijava.ai.catalog.FileModelsStore;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ScopedModel;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.ModelCatalogRefresh;
import com.pijava.coding.agent.core.ModelExactMatch;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import static com.pijava.coding.agent.core.slash.builtin.CommandUtil.async;

/**
 * Model-related slash commands (Phase 3 design §14.2 #2/#3).
 */
public final class ModelCommands {

    /** pi 刷新臂的 15s 超时（interactive-mode.ts:5230）。 */
    private static final long REFRESH_TIMEOUT_MS = 15_000;

    private ModelCommands() {}

    /**
     * pi :5226-5252 对位：15s 超时刷新全目录；返回警告文案（无警告 ⇒ null）。
     * 文案【用户可见】逐字：超时 ⇒ {@code Model refresh timed out; …}；部分失败
     * ⇒ {@code Could not refresh <providers>; …}；异常 ⇒ {@code Could not refresh
     * model catalogs: <msg>}。
     */
    private static String refreshCatalogs(AgentSession session) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() ->
                new ModelCatalogRefresh(FileModelsStore.defaultStore())
                    .refresh(session.services().providers().listAll(), true, true));
            try {
                var result = future.get(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (result.hasErrors()) {
                    return "Could not refresh "
                        + String.join(", ", result.errors().keySet())
                        + "; searching cached models.";
                }
                return null;
            } catch (TimeoutException e) {
                future.cancel(true);
                return "Model refresh timed out; searching cached models.";
            } catch (Exception e) {
                return "Could not refresh model catalogs: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }
    }

    /** Register {@code /model} and {@code /scoped-models}. */
    public static void registerAll(CommandRegistry registry) {
        // 批 15（T9，pi handleModelCommand interactive-mode.ts:5194-5216 对位）：
        // 空参 ⇒ 选择器；有参 ⇒ 精确匹配直设（scoped 臂未命中也收口）；
        // 无 scope 未命中 ⇒ 15s 超时刷新后再找 ⇒ 仍未 ⇒ 选择器（裸，D86）。
        registry.register(async("model", "Select model (opens selector UI)", "<provider/model>",
            (args, ctx) -> {
                if (args.isBlank()) {
                    return CommandRegistry.UI_MODEL_SELECTOR;
                }
                var session = ctx.session();
                var scoped = session.scopedModels();
                List<ModelInfo> candidates = !scoped.isEmpty()
                    ? scoped.stream().map(ScopedModel::model).toList()
                    : ModelsJsonConfig.allModels().listModels();
                var hit = ModelExactMatch.findExact(args, candidates);
                if (hit != null) {
                    session.setModel(hit.id());
                    return "Model: " + hit.id().modelName();  // pi :5207 状态文案逐字
                }
                if (!scoped.isEmpty()) {
                    // pi :5220：scoped 臂未命中不开刷新，直接收口选择器。
                    return CommandRegistry.UI_MODEL_SELECTOR;
                }
                session.flashStatus("Refreshing model catalogs…");   // pi :5225 逐字
                var warning = refreshCatalogs(session);
                if (warning != null) {
                    session.flashStatus(warning);                       // pi :5237-5249 逐字
                }
                hit = ModelExactMatch.findExact(args, ModelsJsonConfig.allModels().listModels());
                if (hit != null) {
                    session.setModel(hit.id());
                    return "Model: " + hit.id().modelName();
                }
                return CommandRegistry.UI_MODEL_SELECTOR;
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "scoped-models"; }
            @Override public String description() {
                return "Enable/disable Ctrl+P cycling models";
            }
            @Override public String argumentHint() { return "[+|-]<model>"; }
            @Override public CompletionStage<String> execute(String args, SlashContext context) {
                var accessors = context.session().services().settings().accessors();
                if (args.isBlank()) {
                    return CompletableFuture.completedFuture(CommandRegistry.UI_SCOPED_MODELS);
                }
                var current = new ArrayList<>(accessors.getEnabledModels());
                if (args.startsWith("+")) {
                    current.add(args.substring(1).trim());
                } else if (args.startsWith("-")) {
                    current.remove(args.substring(1).trim());
                } else {
                    current.add(args.trim());
                }
                accessors.setEnabledModels(current);
                context.session().services().settings().flush();
                return CompletableFuture.completedFuture(
                    "Enabled models: " + current);
            }
        });
    }
}
