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
import com.pijava.coding.agent.pkg.PackageAutoDiscovery;
import com.pijava.coding.agent.pkg.PackageManager;
import com.pijava.coding.agent.pkg.ResolvedPaths;
import com.pijava.coding.agent.pkg.ResolvedResource;
import com.pijava.coding.agent.skill.SkillDiscovery;

/**
 * AgentSession 的静态装配助手。
 *
 * <p>从 {@code AgentSession} 抽出以控制文件行数（CLAUDE.md ≤500 行约束）。
 * 全部方法为纯函数式转换，无会话状态。</p>
 */
final class SessionSetup {

    private SessionSetup() {}

    /** pi {@code sdk.ts:280-294} 的装配解析（B236/docs/11 §3.2）。 */
    static ToolSelection.Resolved resolveToolSelection(Args args, SettingsManager settings) {
        return ToolSelection.resolve(
            args.tools(), args.excludeTools(),
            args.noTools(), args.noBuiltinTools(),
            settings.accessors().getDefaultTools());
    }

    /**
     * pi 的激活面（agent-session.ts:3566-3585）：allowlist 在册 ⇒ 注册工具中匹配者
     * 全激活（「点名/匹配即激活」，含默认不激活的 powershell）；否则 ⇒ 初始激活名表
     * ∩ 注册面。{@code _pendingToolNames} 的晚注册臂（MCP）随 B221 ⑫b（窄差 D4）。
     */
    static Set<AgentTool<?, ?>> activeTools(ToolSelection.Resolved selection,
                                            List<AgentTool<?, ?>> registered) {
        return registered.stream()
            .filter(t -> selection.isActiveOnLoad(t.name()))
            .collect(Collectors.toSet());
    }

    /**
     * CLI thinking 参数 → {@link ModelThinkingLevel}。
     *
     * <p>档位照 pi {@code main.ts buildSessionOptions} ＋ {@code sdk.ts:262-270}：
     * <b>① 显式值</b>（{@code --thinking}）→ <b>② {@code --model :suffix}</b>
     * （pi-java CLI 形态）→ <b>③ scope 初始模型的显式级别</b>（{@code --models "p:high"}，
     * B137）→ <b>④ per-model 设置键</b>（{@code modelThinkingLevels}，批 11 T2，
     * B15-残留-5 销号）→ <b>⑤ 全局默认</b>。</p>
     *
     * @param defaultThinkingLevel {@code settings.defaultThinkingLevel}；可为 {@code null}
     * @param scopedLevel          scope 初始模型携带的显式级别；可为 {@code null}
     * @param perModelLevel        初始模型命中 {@code modelThinkingLevels} 的级别；
     *                             可为 {@code null}
     */
    static ModelThinkingLevel thinkingLevelFor(Args args, String defaultThinkingLevel,
                                                String scopedLevel, String perModelLevel) {
        if (args.thinking() != null) {
            return ThinkingLevels.parse(args.thinking());
        }
        var fromModel = ThinkingLevels.parseFromModelPattern(args.model());
        if (fromModel != null) {
            return fromModel;
        }
        if (scopedLevel != null) {
            return ThinkingLevels.parse(scopedLevel);
        }
        if (perModelLevel != null) {
            return ThinkingLevels.parse(perModelLevel);
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
        return customPromptFor(args, null, null);
    }

    /**
     * 批 21（T3，E4-6）：pi {@code resource-loader.ts:656-658} 对位——
     * {@code systemPromptSource = CLI ?? discoverSystemPromptFile()}，再过
     * {@link #resolvePromptInput}（file-or-literal）。发现的 SYSTEM.md 进的是
     * <b>替换型</b> customPrompt（抑制 tools/rules/docs 三段，与 {@code --system-prompt}
     * 同语义）。
     *
     * @param settings 信任门读取口（null ⇒ 不信任 ⇒ 项目臂不读）
     * @param cwd 项目目录（null ⇒ 无项目臂）
     */
    static String customPromptFor(Args args, SettingsManager settings, Path cwd) {
        var source = args.systemPrompt() != null ? args.systemPrompt()
            : discoverSystemPromptFile(settings, cwd);
        var resolved = resolvePromptInput(source, "system prompt");
        return resolved == null ? "" : resolved;
    }

    /** {@code --append-system-prompt}（pi 的 {@code appendSystemPrompt} → {@code addendum} 段）。 */
    static String appendSystemPromptFor(Args args) {
        return appendSystemPromptFor(args, null, null);
    }

    /**
     * 批 21（T3，E4-6）：pi {@code resource-loader.ts:662-671} 对位——CLI
     * {@code --append-system-prompt} 全缺席才走 {@code APPEND_SYSTEM.md} 发现；
     * 多源各过 {@link #resolvePromptInput} 后 {@code join("\n\n")}。
     */
    static String appendSystemPromptFor(Args args, SettingsManager settings, Path cwd) {
        List<String> sources = args.appendSystemPrompt().isEmpty()
            ? java.util.Optional.ofNullable(discoverAppendSystemPromptFile(settings, cwd))
                .map(List::of).orElse(List.of())
            : args.appendSystemPrompt();
        var resolved = sources.stream()
            .map(source -> resolvePromptInput(source, "append system prompt"))
            .filter(java.util.Objects::nonNull)
            .toList();
        return resolved.isEmpty() ? "" : String.join("\n\n", resolved);
    }

    /**
     * pi {@code resolvePromptInput}（resource-loader.ts:167-182 逐字）：源是现存
     路径 ⇒ 读文件内容（BOM 剥离；读失败告警后按字面量用）；否则按字面量。
     */
    static String resolvePromptInput(String input, String description) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        if (java.nio.file.Files.exists(Path.of(input))) {
            try {
                return com.pijava.coding.agent.theme.Themes.stripBom(
                    java.nio.file.Files.readString(Path.of(input)));
            } catch (java.io.IOException | RuntimeException e) {
                // pi console.error(chalk.yellow(...)) 对位（DefaultProviders 先例）。
                System.err.println("Warning: Could not read " + description + " file "
                    + input + ": " + e);
                return input;
            }
        }
        return input;
    }

    /**
     * pi {@code discoverSystemPromptFile}（resource-loader.ts:1213-1225 逐字）：
     * 项目 {@code .pi-java/SYSTEM.md}（<b>项目信任门</b>）→ 全局
     * {@code <agentDir>/SYSTEM.md}；都没有 ⇒ null。
     */
    static String discoverSystemPromptFile(SettingsManager settings, Path cwd) {
        if (cwd != null && settings != null && settings.isProjectTrusted()) {
            var projectPath = cwd.resolve(".pi-java").resolve("SYSTEM.md");
            if (java.nio.file.Files.exists(projectPath)) {
                return projectPath.toString();
            }
        }
        var globalPath = FileSettingsStorage.defaultAgentDir().resolve("SYSTEM.md");
        if (java.nio.file.Files.exists(globalPath)) {
            return globalPath.toString();
        }
        return null;
    }

    /** pi {@code discoverAppendSystemPromptFile}（:1227-1240 逐字同形）。 */
    static String discoverAppendSystemPromptFile(SettingsManager settings, Path cwd) {
        if (cwd != null && settings != null && settings.isProjectTrusted()) {
            var projectPath = cwd.resolve(".pi-java").resolve("APPEND_SYSTEM.md");
            if (java.nio.file.Files.exists(projectPath)) {
                return projectPath.toString();
            }
        }
        var globalPath = FileSettingsStorage.defaultAgentDir().resolve("APPEND_SYSTEM.md");
        if (java.nio.file.Files.exists(globalPath)) {
            return globalPath.toString();
        }
        return null;
    }

    /** 会话解析：--no-session 直接返回，否则按持久后端 resolve。 */
    static AgentSession resolveSession(AgentSession session, Args args) {
        return resolveSession(session, args, null);
    }

    /** 批 20（B257）：带 {@code parentSessionId} 的形态（RPC {@code new_session}）。 */
    static AgentSession resolveSession(AgentSession session, Args args,
                                       String parentSessionId) {
        return resolveSession(session, args, parentSessionId, true);
    }

    /**
     * 批 20 评审回补（I-2）：{@code applyInitialName=false} 的形态——pi 的
     * {@code --name} 只在<b>启动会话解析后</b>应用一次（main.ts:709-715），
     * {@code runtime.newSession} 不重放：createReplacing 链（/new、RPC
     * new_session）传 {@code false}，否则每个替换会话都被冠上启动名并多写一行
     * session_info。
     */
    static AgentSession resolveSession(AgentSession session, Args args,
                                       String parentSessionId, boolean applyInitialName) {
        AgentSession resolved;
        if (args.noSession()) {
            resolved = session;
        } else if (session.persistentRepository() != null) {
            resolved = SessionPersistence.resolvePersistent(session, args, parentSessionId);
        } else {
            resolved = SessionPersistence.resolveInMemory(session, args);
        }
        // 批 20（B32，pi main.ts:709-715）：--name 写 session_info（解析后无条件臂）。
        if (applyInitialName) {
            SessionPersistence.applyInitialName(resolved, args);
        }
        return resolved;
    }

    /** 队列模式 wire 值 → QueueMode。 */
    static QueueMode queueMode(String mode) {
        if ("all".equals(mode)) {
            return new QueueMode.All();
        }
        return new QueueMode.OneAtATime();
    }

    // ── 批 18（T6）：resolve() 统辖的资源装配（裁决⑨）──────────────────

    /**
     * 包管理器装配（pi {@code DefaultPackageManager} 构造对位，resource-loader.ts:
     * 382-390）。{@code resolve()} 相对管理器实例无状态 ⇒ 各消费点现建现用；
     * {@code /reload} 经同一入口自然重放（pi resource-loader.reload 对位）。
     */
    static PackageManager packageManagerFor(SettingsManager settings) {
        return packageManagerFor(settings, Path.of(PackageAutoDiscovery.getHomeDir()));
    }

    /** 可测形（homeDir 注入——真 home 的 {@code ~/.agents} 不泄进夹具，评审 M7）。 */
    static PackageManager packageManagerFor(SettingsManager settings, Path homeDir) {
        // 批 19（T4）：装配体收敛到公共缝 PackageManagers（CLI 面共用）。
        return PackageManagers.create(Path.of(System.getProperty("user.dir")),
            FileSettingsStorage.defaultAgentDir(), settings, homeDir);
    }

    /**
     * 一次装配的 resolve 结果三元组（评审 M4：pi resource-loader 每次 reload 只
     * 跑<b>一遍</b> resolve＋一遍 resolveExtensionSources——temporary 未 pin git
     * 源每遍都真 {@code git pull}，重复跑既有副作用也有会话内不一致窗口）。
     * 初装（wireSession）与 {@code /reload} 各自现算一份，三消费面共享。
     */
    record ResolvedAssembly(PackageManager pm, ResolvedPaths resolved, ResolvedPaths cli) {}

    static ResolvedAssembly resolveAll(Args args, SettingsManager settings) {
        return resolveAll(args, packageManagerFor(settings));
    }

    static ResolvedAssembly resolveAll(Args args, PackageManager pm) {
        return new ResolvedAssembly(pm, pm.resolve(null), resolveCliSources(args, pm));
    }

    /** pi {@code mergePaths}：保序去重（先到者胜）。 */
    static List<String> mergePaths(List<String> first, List<String> second) {
        var merged = new java.util.LinkedHashSet<String>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    /** pi resource-loader 的 enabled 过滤＋路径提取（:545-553）。 */
    static List<String> enabledPaths(List<ResolvedResource> resources) {
        return resources.stream().filter(ResolvedResource::enabled)
            .map(ResolvedResource::path).toList();
    }

    /** {@code -e} 显式源（含 npm:/git:）经 temporary resolve（pi
     *  {@code resolveExtensionSources(additionalExtensionPaths, {temporary:true})}
     *  对位，resource-loader.ts:532-534）。 */
    static ResolvedPaths resolveCliSources(Args args, PackageManager pm) {
        return pm.resolveExtensionSources(args.extensions(), false, true);
    }

    /**
     * 批 16（T5b）＋批 18（T6）：提示模板载入——resolve 输出（enabled 臂）并入
     * 显式车道（pi resource-loader :594-600 合并臂：CLI 先行、settings/包/自动
     * 发现随后、{@code --no-prompt-templates} 只裁 resolve 臂不裁 CLI）。
     * {@code wireSession} 初装与 {@code /reload} 重扫共用。
     */
    static List<com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate>
            loadPromptTemplates(Args args, SettingsManager settings) {
        return loadPromptTemplates(args, resolveAll(args, settings));
    }

    static List<com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate>
            loadPromptTemplates(Args args, ResolvedAssembly assembly) {
        var fs = new com.pijava.agent.tool.DefaultFileSystem();
        // scope 随 resolve 元数据走（CLI 车道＝temporary，B247 打标惯例）。
        var scoped = new java.util.ArrayList<
            com.pijava.coding.agent.prompt.PromptTemplates.ScopedTemplatePath>();
        for (var r : assembly.cli().prompts()) {
            if (r.enabled()) {
                scoped.add(new com.pijava.coding.agent.prompt.PromptTemplates
                    .ScopedTemplatePath(r.path(), scopeOf(r)));
            }
        }
        if (!args.noPromptTemplates()) {
            for (var r : assembly.resolved().prompts()) {
                if (r.enabled()) {
                    scoped.add(new com.pijava.coding.agent.prompt.PromptTemplates
                        .ScopedTemplatePath(r.path(), scopeOf(r)));
                }
            }
        }
        if (args.promptTemplates() != null) {
            for (var p : args.promptTemplates()) {
                scoped.add(new com.pijava.coding.agent.prompt.PromptTemplates
                    .ScopedTemplatePath(p,
                        com.pijava.coding.agent.core.source.SourceInfo.Scope.TEMPORARY));
            }
        }
        if (scoped.isEmpty()) {
            return List.of();
        }
        // 同路径先到者胜（mergePaths 语义在 scoped 列表上的对位）。
        var seen = new java.util.LinkedHashSet<String>();
        scoped.removeIf(s -> !seen.add(s.path()));
        var loaded = com.pijava.coding.agent.prompt.PromptTemplates
            .loadScoped(fs, scoped).templates();
        // pi dedupePrompts（resource-loader.ts:1160-1184）：同名<b>先到先得</b>——
        // 注册表（PromptTemplateRegistry.put）是后注册胜出，不先去重会把 resolve
        // 序整体反转（评审 I1 回补：project 必须压 user、显式 CLI 撞名必输）。
        var byName = new java.util.LinkedHashMap<String,
            com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate>();
        for (var template : loaded) {
            byName.putIfAbsent(template.name(), template);
        }
        return List.copyOf(byName.values());
    }

    /** PathMetadata.scope → SourceInfo.Scope。 */
    private static com.pijava.coding.agent.core.source.SourceInfo.Scope scopeOf(
            ResolvedResource resource) {
        return switch (resource.metadata().scope()) {
            case USER -> com.pijava.coding.agent.core.source.SourceInfo.Scope.USER;
            case PROJECT -> com.pijava.coding.agent.core.source.SourceInfo.Scope.PROJECT;
            case TEMPORARY -> com.pijava.coding.agent.core.source.SourceInfo.Scope.TEMPORARY;
        };
    }

    /**
     * 发现技能——批 18（T6 裁决⑨）改由 {@code resolve()} 统辖：CLI（-e 包技能
     * ＋ --skill 显式）先行，settings 条目/包/自动发现按 pi 五档优先级随后
     * （<b>行为变化</b>：同名技能 project 从此压过 user，旧臂 USER→PROJECT 直扫
     * 退役）；{@code --no-skills} 只裁 resolve 臂（pi :594 对位），CLI 臂仍收。
     */
    static Map<String, Skill> discoverSkills(Args args, SettingsManager settings) {
        return discoverSkills(args, resolveAll(args, settings));
    }

    static Map<String, Skill> discoverSkills(Args args, ResolvedAssembly assembly) {
        var cliSkills = enabledPaths(assembly.cli().skills());
        var explicit = args.skills() == null ? List.<String>of() : args.skills();
        var ordered = args.noSkills()
            ? mergePaths(cliSkills, explicit)
            : mergePaths(mergePaths(cliSkills, enabledPaths(assembly.resolved().skills())),
                explicit);
        if (ordered.isEmpty()) {
            return Map.of();
        }
        var cwd = Path.of(System.getProperty("user.dir"));
        var discovery = new SkillDiscovery(cwd, FileSettingsStorage.defaultAgentDir());
        var result = discovery.discoverAll(false,
            ordered.stream().map(Path::of).toList());
        var map = new LinkedHashMap<String, Skill>();
        for (var skill : result.skills()) {
            map.put(skill.name(), skill);
        }
        return map;
    }
}
