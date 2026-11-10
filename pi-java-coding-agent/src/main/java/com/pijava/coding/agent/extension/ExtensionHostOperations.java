package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Optional;

import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.AgentSession;

/**
 * docs/50 B284：{@link ExtensionContext} 新增 10 成员的实现簇（FileLength 拆分，
 * 行为零变化）。{@link DefaultExtensionContext} 只留一行委托。
 */
final class ExtensionHostOperations {

    private final DefaultExtensionContext base;

    ExtensionHostOperations(DefaultExtensionContext base) {
        this.base = base;
    }

    /** 晚绑定宿主；未绑定 ⇒ B284 新增成员统一错文。 */
    AgentSession requireHost(String member) {
        var session = base.hostSessionOrNull();
        if (session == null) {
            throw new IllegalStateException("extension context not bound: " + member);
        }
        return session;
    }

    Optional<ExtensionModelInfo> getModel() {
        var session = requireHost("getModel");
        var modelId = session.harness().getModel();
        if (modelId == null) {
            return Optional.empty();
        }
        for (var definition : session.virtualModels().list()) {
            if (definition.provider().equals(modelId.provider())
                    && definition.id().equals(modelId.modelName())) {
                return Optional.of(ExtensionModelInfo.from(definition));
            }
        }
        return session.modelCatalog().find(modelId).map(ExtensionModelInfo::from);
    }

    Optional<ExtensionContextUsage> getContextUsage() {
        requireHost("getContextUsage");
        var model = getModel();
        if (model.isEmpty() || model.get().contextWindow() <= 0) {
            return Optional.empty();
        }
        int contextWindow = model.get().contextWindow();
        var branch = base.sessionManager().getBranch();
        int lastCompaction = -1;
        for (int i = branch.size() - 1; i >= 0; i--) {
            if (branch.get(i) instanceof Entry.Compaction) {
                lastCompaction = i;
                break;
            }
        }
        if (lastCompaction >= 0 && !hasCredibleUsageAfter(branch, lastCompaction)) {
            // pi agent-session.ts:4337-4340：压缩后没有可信用量 ⇒ 字段 null。
            return Optional.of(new ExtensionContextUsage(null, contextWindow, null));
        }
        var contextEntries = ContextEntries.contextEntries(branch);
        var projected = ContextEntries.projectEntries(contextEntries);
        var estimate = ContextUsageEstimator.estimateProjectedContextTokens(projected, branch);
        int tokens = (int) Math.round(estimate.tokens());
        double percent = tokens / (double) contextWindow * 100;
        return Optional.of(new ExtensionContextUsage(tokens, contextWindow, percent));
    }

    /** pi {@code hasTrustworthyUsageAfterCompaction}（agent-session.ts:4301-4314）。 */
    private static boolean hasCredibleUsageAfter(List<Entry> branch, int afterIndex) {
        for (int i = afterIndex + 1; i < branch.size(); i++) {
            if (branch.get(i) instanceof Entry.Message entry
                    && entry.message() instanceof Message.AssistantMessage assistant
                    && assistant.usage() != null
                    && assistant.stopReason() != StopReason.ABORTED
                    && assistant.stopReason() != StopReason.ERROR
                    && ContextUsageEstimator.calculateContextTokens(assistant.usage()) > 0) {
                return true;
            }
        }
        return false;
    }

    void compact(ExtensionCompactOptions options) {
        var session = requireHost("compact");
        var opts = options == null ? ExtensionCompactOptions.none() : options;
        try {
            var result = session.compact(opts.customInstructions());
            if (opts.onComplete() != null) {
                opts.onComplete().accept(result);
            }
        } catch (RuntimeException e) {
            if (opts.onError() != null) {
                opts.onError().accept(e);
                return;
            }
            throw e;
        }
    }

    void reload() {
        requireHost("reload").reload();
    }

    void shutdown() {
        requireHost("shutdown").closeForReplacement("quit", null);
    }

    boolean isIdle() {
        return !requireHost("isIdle").isRunning();
    }

    String getSystemPrompt() {
        var prompt = requireHost("getSystemPrompt").harness().currentSystemPrompt();
        return prompt == null ? "" : prompt;
    }

    boolean isProjectTrusted() {
        return requireHost("isProjectTrusted").services().settings().isProjectTrusted();
    }

    ExtensionNewSessionResult newSession(ExtensionNewSessionOptions options) {
        return requireHost("newSession").newSession(options);
    }
}
