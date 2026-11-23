package com.pijava.coding.agent.extension;

import java.util.Set;

import com.pijava.agent.skill.SkillManager;
import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W6（pi types.ts:1570/1586/1590-1594＋loader.ts:253,367-372＋runner.ts:797-805）：
 * 六值消息型、注册生效面、链内按 {@code context.messageType} 过滤（不命中原样透传）。
 */
class MarkdownTransformerSurfaceTest {

    private static MarkdownTransformContext context(MarkdownMessageType type) {
        return new MarkdownTransformContext(type, false, -1);
    }

    @Test
    void defaultMessageTypesCoverTheThreeCoreSurfaces() {
        var transformer = (MarkdownTransformer) (markdown, ctx) -> "T[" + markdown + "]";
        assertThat(transformer.messageTypes()).containsExactlyInAnyOrder(
            MarkdownMessageType.USER, MarkdownMessageType.ASSISTANT,
            MarkdownMessageType.ASSISTANT_THINKING);
    }

    @Test
    void explicitMessageTypesFilterTheChain() {
        var base = new DefaultExtensionContext(null, new SkillManager());
        var surface = base.surface();
        surface.registerMarkdownTransformer("ext-a",
            (markdown, ctx) -> "SUMMARY[" + markdown + "]", Set.of(MarkdownMessageType.SUMMARY));

        var chain = surface.markdownTransformers();
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).transform("x", context(MarkdownMessageType.SUMMARY)))
            .isEqualTo("SUMMARY[x]");
        assertThat(chain.get(0).transform("x", context(MarkdownMessageType.ASSISTANT)))
            .as("未注册的面原样透传").isEqualTo("x");
        assertThat(chain.get(0).transform("x", context(MarkdownMessageType.CUSTOM)))
            .isEqualTo("x");
    }

    @Test
    void oneArgRegistrationUsesTheTransformerMessageTypes() {
        var base = new DefaultExtensionContext(null, new SkillManager());
        var surface = base.surface();
        surface.registerMarkdownTransformer("ext-b",
            (markdown, ctx) -> "(ctx)" + markdown);

        var chain = surface.markdownTransformers();
        assertThat(chain.get(0).transform("x", context(MarkdownMessageType.ASSISTANT_THINKING)))
            .isEqualTo("(ctx)x");
        assertThat(chain.get(0).transform("x", context(MarkdownMessageType.CHANGELOG)))
            .isEqualTo("x");
    }
}
