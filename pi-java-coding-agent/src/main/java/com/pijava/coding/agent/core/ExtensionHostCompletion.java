package com.pijava.coding.agent.core;

import java.util.function.BooleanSupplier;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionNewSessionOptions;
import com.pijava.coding.agent.extension.ExtensionNewSessionResult;

/**
 * docs/50 B284c/B284d：{@link AgentSession} 的扩展宿主缝实现簇（FileLength 拆分，
 * 行为零变化）——一次性模型补全（{@code ModelCompletion} 晚绑定）与新会话接管。
 */
final class ExtensionHostCompletion {

    private final AgentSession owner;
    private ModelCompletion modelCompletion;

    ExtensionHostCompletion(AgentSession owner) {
        this.owner = owner;
    }

    void modelCompletion(ModelCompletion completion) {
        this.modelCompletion = completion;
    }

    Message.AssistantMessage completeModel(ModelId<?> model, Context context,
                                           StreamOptions options, BooleanSupplier aborted) {
        if (modelCompletion == null) {
            throw new IllegalStateException("model completion not bound");
        }
        return modelCompletion.complete(model, context, options, aborted);
    }

    /**
     * pi {@code ExtensionContext.newSession}——
     * {@code session_before_switch("new", null)}（可取消）→
     * {@code createReplacing(args, previousId, parentSession)} →
     * {@code closeForReplacement("new", 新 id)} → 经 {@code SlashContext} 接管 →
     * {@code withSession} 回调（收到新会话的扩展上下文）。
     */
    ExtensionNewSessionResult newSession(ExtensionNewSessionOptions options) {
        var opts = options == null ? ExtensionNewSessionOptions.none() : options;
        if (owner.discovery().fireBeforeSwitch("new", null)) {
            return new ExtensionNewSessionResult(true);
        }
        var replacement = AgentSession.createReplacing(
            owner.sessionArgs(), owner.sessionId(), opts.parentSession());
        owner.closeForReplacement("new", replacement.sessionId());
        var slashContext = owner.prompts().currentSlashContext();
        if (slashContext != null) {
            slashContext.onSwitchSession().accept(replacement);
        }
        var newContext = replacement.extensionContext();
        if (opts.withSession() != null && newContext != null) {
            opts.withSession().accept(newContext);
        }
        return new ExtensionNewSessionResult(false);
    }
}
