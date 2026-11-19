package com.pijava.examples;

import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/model-status.ts:1-31} 移植（docs/27 W5）——
 * {@code model_select} 钩子把当前模型写进状态栏。
 *
 * <p>逐字对位：{@code source != "restore"} 才通知 {@code "Model: <provider>/<id>"}；
 * 状态段恒写 {@code "🤖 <id>"}（键 {@code model}）。pi 末尾的 {@code console.log}
 * 只进调试输出、不改用户可见面——java 不产生 stdout 残留，故略（{@code CLAUDE.md}
 * 「无 System.out.println 残留」）。</p>
 */
public final class ModelStatusExtension implements PiExtension {

    /** pi L26：状态键（逐字）。 */
    static final String STATUS_KEY = "model";

    @Override
    public String name() {
        return "model-status";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.MODEL_SELECT, (event, c) -> {
            var model = event.model();
            if (!"restore".equals(event.source())) {
                c.ui().notify("Model: " + nextLabel(model), NotifyType.INFO);
            }
            c.ui().setStatus(STATUS_KEY, statusText(model));
            return null;
        });
    }

    /** pi L17：{@code `${model.provider}/${model.id}`}。 */
    static String nextLabel(ModelId<?> model) {
        return model.provider() + "/" + model.modelName();
    }

    /** pi L18：{@code previousModel ? provider/id : "none"}。 */
    static String previousLabel(ModelId<?> previousModel) {
        return previousModel == null ? "none" : nextLabel(previousModel);
    }

    /** pi L26：{@code `🤖 ${model.id}`}（id 是模型名，不是 provider）。 */
    static String statusText(ModelId<?> model) {
        return "🤖 " + model.modelName();
    }
}
