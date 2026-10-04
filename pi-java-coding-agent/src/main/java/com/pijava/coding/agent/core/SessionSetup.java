package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.pijava.agent.harness.QueueMode;
import com.pijava.agent.skill.Skill;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.skill.SkillDiscovery;

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

    /**
     * CLI thinking 参数 → {@link ModelThinkingLevel}。
     *
     * <p>档位照 pi {@code agent-session.ts:1992-2005}：<b>① 显式值</b>（{@code --thinking}）
     * → <b>② per-model 设置</b> → <b>③ 全局默认</b>。java 没有 ② 的设置键 ⇒ 登记不做
     * （{@code 原 docs/46 §7 B15-残留-5}）；模型名后缀（{@code claude:high}）是 pi-java
     * 自己的 CLI 形态，插在 ① 与 ③ 之间。</p>
     *
     * @param defaultThinkingLevel {@code settings.defaultThinkingLevel}；可为 {@code null}
     */
    static ModelThinkingLevel thinkingLevelFor(Args args, String defaultThinkingLevel) {
        if (args.thinking() != null) {
            return ThinkingLevels.parse(args.thinking());
        }
        var fromModel = ThinkingLevels.parseFromModelPattern(args.model());
        if (fromModel != null) {
            return fromModel;
        }
        return ThinkingLevels.parse(defaultThinkingLevel);
    }

    /**
     * pi-java 默认的**表达风格条目** —— 进系统提示的 {@code rules} 段
     * （pi {@code BuildSystemPromptOptions.promptGuidelines}，{@code system-prompt.ts:23-24}）。
     *
     * <p>包 A4b 之前这些条目是 {@code AgentSession.DEFAULT_SYSTEM_PROMPT} 的一部分（拼在
     * preamble 正文里）。pi 的形状里 preamble 只是定位句、条目归 {@code rules} 段 ⇒ 移到
     * 这里，正文与渲染顺序都保持。</p>
     *
     * <p>⚠️ 「简洁」与「写清文件路径」两条**不在这里**：pi 的 {@code buildRules} 有同义的两条
     * <b>固定兜底</b>（{@code :115-116}），而 {@code buildRules} 按 trim 后的字面值去重
     * （{@code :87-93}）。两条都写的话（pi-java 的原文带句号、pi 的兜底不带）会去重不掉，
     * 段落里就多出一条重复项 —— 故这里只留五条，那两条交给兜底。</p>
     */
    static final List<String> DEFAULT_PROMPT_GUIDELINES = List.of(
        "Tool calls are displayed to the user as cards with their results; "
            + "do not repeat their contents in text",
        "Before the first tool call, say in one sentence what you are about to do",
        "While working, give a one-sentence update at key moments only "
            + "(a finding, a direction change, a blocker)",
        "Never end a sentence with a colon right before a tool call; use a period instead",
        "End each turn with a one- or two-sentence summary: what changed and what is next");

    /**
     * 车道级自定义提示 —— pi 的 {@code customPrompt}。
     *
     * <p>只有 {@code --system-prompt} 给了值才非空。⚠️ pi 里这个入参**替换**默认 preamble
     * 并**抑制** {@code tools}/{@code rules}/{@code docs} 三段（{@code system-prompt.ts:139-141}）
     * —— 包 A4b 之前 pi-java 把它当成整份提示拼进基础串（工具清单仍由 builder 追加），
     * 现在按 pi 的语义：给了自定义提示就没有那三段。</p>
     */
    static String customPromptFor(Args args) {
        return args.systemPrompt() == null ? "" : args.systemPrompt();
    }

    /** {@code --append-system-prompt}（pi 的 {@code appendSystemPrompt} → {@code addendum} 段）。 */
    static String appendSystemPromptFor(Args args) {
        return args.appendSystemPrompt().isEmpty()
            ? "" : String.join("\n\n", args.appendSystemPrompt());
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

    /** 发现技能（USER + PROJECT + 显式路径），--no-skills 时为空。 */
    static Map<String, Skill> discoverSkills(Args args) {
        if (args.noSkills()) {
            return Map.of();
        }
        var cwd = Path.of(System.getProperty("user.dir"));
        var discovery = new SkillDiscovery(cwd, FileSettingsStorage.defaultAgentDir());
        var explicit = args.skills() == null ? List.<Path>of()
            : args.skills().stream().map(Path::of).toList();
        var result = discovery.discoverAll(true, explicit);
        var map = new LinkedHashMap<String, Skill>();
        for (var skill : result.skills()) {
            map.put(skill.name(), skill);
        }
        return map;
    }
}
