package com.pijava.ai.provider.faux;

import java.util.List;

import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.model.PricingInfo;

/**
 * docs/20 §3.2：pi {@code FauxModelDefinition}（{@code providers/faux.ts:47-58}）。
 *
 * <p>字段缺席（{@code null}）≙ pi 的 {@code undefined}，由 {@link FauxCore} 填默认：
 * 名称取 id、reasoning=false、input=text＋image、价格全 0、上下文 128000、
 * 输出上限 16384。</p>
 *
 * @param id           模型 id（必填）
 * @param name         显示名，或 {@code null}
 * @param reasoning    是否支持思考，或 {@code null}
 * @param input        输入模态（{@code "text"}／{@code "image"}），或 {@code null}
 * @param pricing      价格，或 {@code null}
 * @param contextWindow 输入窗口，或 {@code null}
 * @param maxTokens    输出上限，或 {@code null}
 * @param inputLimits  单请求输入限制（pi {@code Model.inputLimits}），或 {@code null}
 */
public record FauxModelDefinition(
    String id,
    String name,
    Boolean reasoning,
    List<String> input,
    PricingInfo pricing,
    Integer contextWindow,
    Integer maxTokens,
    InputLimits inputLimits
) {
    /** Compact constructor: id is required; input list copied defensively. */
    public FauxModelDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Faux model definition requires an id");
        }
        input = input == null ? null : List.copyOf(input);
    }

    /** 只有 id 的模型定义（其余全部取默认）。 */
    public static FauxModelDefinition of(String id) {
        return new FauxModelDefinition(id, null, null, null, null, null, null, null);
    }
}
