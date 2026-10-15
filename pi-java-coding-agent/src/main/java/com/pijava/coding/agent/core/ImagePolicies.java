package com.pijava.coding.agent.core;

import java.util.Optional;

import com.pijava.agent.tool.ImagePolicy;
import com.pijava.ai.catalog.InputLimits;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;

/**
 * 图片入模策略解析（批 9/B85，docs/18 §4.5）。独立静态类是为了不经整会话即可单测。
 *
 * <p>对应 pi {@code agent-session.ts} 的 {@code _limitsModel()?.inputLimits?.images?.resize}
 * ＋ {@code settingsManager.getImageAutoResize()}（settings-manager.ts:1298-1300，
 * 缺省 true）。视觉位沿用 {@link ModelInfo#supportsImageInput()} 的三态语义
 * （目录未命中＝未知，按支持处理）。</p>
 */
final class ImagePolicies {

    private ImagePolicies() {}

    static ImagePolicy resolve(Settings effective, ModelCatalog catalog, ModelId<?> model) {
        boolean autoResize = effective.images == null
            || effective.images.autoResize() == null
            || effective.images.autoResize();

        InputLimits.ResizeOptions resize = null;
        boolean supportsImages = true;
        Optional<ModelInfo> info = catalog.find(model);
        if (info.isPresent()) {
            ModelInfo modelInfo = info.get();
            supportsImages = modelInfo.supportsImageInput();
            if (modelInfo.inputLimits() != null && modelInfo.inputLimits().images() != null) {
                resize = modelInfo.inputLimits().images().resize();
            }
        }
        return new ImagePolicy(autoResize, resize, supportsImages);
    }
}
