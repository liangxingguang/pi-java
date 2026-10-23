package com.pijava.coding.agent.core.slash.builtin;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.pijava.ai.catalog.ModelThinkingLevels;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import static com.pijava.coding.agent.core.slash.builtin.CommandUtil.simple;

/**
 * {@code /thinking}（批 15 T8，pi interactive-mode.ts:5145-5161
 * {@code handleThinkingCommand} 对位）：空参 ⇒ 选择器 marker；有参 ⇒
 * 大小写不敏感精确匹配可用集，未中 ⇒ 逐字错文，中 ⇒ 直设（persist=false——
 * pi 的 {@code selectThinkingLevel(level, false)}，会话级）。
 */
public final class ThinkingCommands {

    private ThinkingCommands() {}

    /** 注册（pi slash-commands.ts:23 逐字）。 */
    public static void registerAll(CommandRegistry registry) {
        registry.register(simple("thinking", "Set thinking level", "<level>",
            (args, ctx) -> {
                if (args.isBlank()) {
                    return CommandRegistry.UI_THINKING_SELECTOR;
                }
                var available = availableLevels(ctx.session());
                // pi :5152-5155：trim 小写后全等比较（候选也小写化——等价 equalsIgnoreCase）。
                var normalized = args.trim().toLowerCase(Locale.ROOT);
                var hit = available.stream()
                    .filter(level -> level.label().equalsIgnoreCase(normalized))
                    .findFirst();
                if (hit.isEmpty()) {
                    // pi :5156-5158 错文逐字【用户可见】（searchTerm＝trim 后原样）。
                    return "Unknown thinking level \"" + args.trim()
                        + "\". Available levels: " + available.stream()
                            .map(ModelThinkingLevel::label)
                            .collect(Collectors.joining(", ")) + ".";
                }
                ctx.session().setThinkingLevel(hit.get());
                // pi :5160（selectThinkingLevel persist=false 的状态行）逐字。
                return "Thinking level: " + hit.get().label();
            }));
    }

    /**
     * pi {@code getAvailableThinkingLevels}（agent-session.ts:2657-2660 +
     * interactive-mode.ts:5147）对位：model 缺 ⇒ 七值全集（pi
     * {@code THINKING_LEVEL_OPTIONS}，core/defaults.ts:4-13）；否则按目录
     * {@link ModelThinkingLevels#supported} 过滤（非 reasoning ⇒ {@code ["off"]}；
     * 映射 null 剔除；xhigh/max 要映射有定义）。目录未编目 ⇒ 全集（注记：pi 的
     * Model 对象恒有定义，java 目录外模型按「能力未知 ⇒ 全放行」处理）。
     */
    public static List<ModelThinkingLevel> availableLevels(AgentSession session) {
        var model = session.harness().getModel();
        if (model == null) {
            return ModelThinkingLevel.extended();
        }
        return session.modelCatalog().find(model)
            .map(ModelThinkingLevels::supported)
            .orElseGet(ModelThinkingLevel::extended);
    }
}
