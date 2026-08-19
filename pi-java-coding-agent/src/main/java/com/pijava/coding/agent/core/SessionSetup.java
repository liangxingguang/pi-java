package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.pijava.agent.harness.QueueMode;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ThinkingLevels;

/**
 * AgentSession 的静态装配助手。
 *
 * <p>从 {@code AgentSession} 抽出以控制文件行数（CLAUDE.md ≤500 行约束）。
 * 全部方法为纯函数式转换，无会话状态。</p>
 */
final class SessionSetup {

    private SessionSetup() {}

    /** 按 CLI 过滤参数确定激活的工具集。 */
    static Set<AgentTool<?, ?>> activeTools(Args args, List<AgentTool<?, ?>> toolList) {
        if (args.noTools() || args.noBuiltinTools()) {
            return Set.of();
        }
        if (args.tools() != null && !args.tools().isEmpty()) {
            var allow = Set.copyOf(args.tools());
            return toolList.stream()
                .filter(t -> allow.contains(t.name()))
                .collect(Collectors.toSet());
        }
        if (args.excludeTools() != null && !args.excludeTools().isEmpty()) {
            var deny = Set.copyOf(args.excludeTools());
            return toolList.stream()
                .filter(t -> !deny.contains(t.name()))
                .collect(Collectors.toSet());
        }
        return Set.copyOf(toolList);
    }

    /** CLI thinking 参数 → ModelThinkingLevel（无则回落模型模式 / off）。 */
    static ModelThinkingLevel thinkingLevelFor(Args args) {
        if (args.thinking() != null) {
            return ThinkingLevels.parse(args.thinking());
        }
        var fromModel = ThinkingLevels.parseFromModelPattern(args.model());
        return fromModel != null ? fromModel : ModelThinkingLevel.off();
    }

    /** 系统提示：CLI 显式值 → 默认提示 + append 段。 */
    static String systemPromptFor(Args args) {
        var base = args.systemPrompt() != null
            ? args.systemPrompt() : AgentSession.DEFAULT_SYSTEM_PROMPT;
        if (args.appendSystemPrompt().isEmpty()) {
            return base;
        }
        return base + "\n\n" + String.join("\n\n", args.appendSystemPrompt());
    }

    /** 会话解析：--no-session 直接返回，否则按持久后端 resolve。 */
    static AgentSession resolveSession(AgentSession session, Args args) {
        if (args.noSession()) {
            return session;
        }
        if (session.persistentRepository() != null) {
            return SessionPersistence.resolvePersistent(session, args);
        }
        return SessionPersistence.resolveInMemory(session, args);
    }

    /** 队列模式 wire 值 → QueueMode。 */
    static QueueMode queueMode(String mode) {
        if ("all".equals(mode)) {
            return new QueueMode.All();
        }
        return new QueueMode.OneAtATime();
    }
}
