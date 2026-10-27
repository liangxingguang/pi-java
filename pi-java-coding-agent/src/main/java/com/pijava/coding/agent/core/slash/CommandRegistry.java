package com.pijava.coding.agent.core.slash;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pijava.coding.agent.core.slash.builtin.MiscCommands;
import com.pijava.coding.agent.core.slash.builtin.ModelCommands;
import com.pijava.coding.agent.core.slash.builtin.SessionCommands;
import com.pijava.coding.agent.core.slash.builtin.SettingsCommands;
import com.pijava.coding.agent.core.slash.builtin.SkillsCommands;
import com.pijava.coding.agent.core.slash.builtin.ThinkingCommands;

/**
 * Slash command registry (Phase 3 design §14.1).
 *
 * <p>Commands that open TUI selectors return a {@code "@ui:*"} marker string;
 * the TUI layer interprets these markers and opens the corresponding screen.
 * An input that does not start with {@code "/"} is not a command —
 * {@link #dispatch} returns {@code null} so callers treat it as a prompt.</p>
 */
public final class CommandRegistry {

    /**
     * 批 20（B237）：命令来源标签——pi 的扩展命令表（{@code ExtensionRunner.commands}）
     * 与 TUI 内置件是<b>两个</b>注册面：内置命令只在 TUI 分发，扩展命令由
     * {@code AgentSession.prompt} 全模式统一执行（agent-session.ts:1978-1984）。
     * java 同池 ⇒ 打标区分执行面（批 3 窄差 D5 的同源问题，本批只解执行面，
     * {@code get_commands} 扩展臂仍随 B221）。
     */
    public enum CommandSource { BUILTIN, EXTENSION }

    private record Registration(SlashCommand command, CommandSource source) {}

    // UI marker constants returned by selector-opening commands
    public static final String UI_MODEL_SELECTOR = "@ui:model-selector";
    public static final String UI_SESSION_SELECTOR = "@ui:session-selector";
    public static final String UI_TREE_SELECTOR = "@ui:tree-selector";
    /** docs/18（§3.8）：{@code /fork}（空参）与 {@code /tree} 拆 marker——fork 旧行为、tree 走导航流。 */
    public static final String UI_FORK_SELECTOR = "@ui:fork-selector";
    public static final String UI_SETTINGS = "@ui:settings";
    public static final String UI_SCOPED_MODELS = "@ui:scoped-models";
    /** 批 15（T7）：{@code /bug} 故障上报流；hint 由 TUI 从原始输入行切（裁决⑦）。 */
    public static final String UI_BUG_REPORT = "@ui:bug-report";
    /** 批 15（T8）：{@code /thinking}（空参）的思考级别选择器（裁决⑥简版）。 */
    public static final String UI_THINKING_SELECTOR = "@ui:thinking-selector";
    /**
     * 批 15（T2c/B256）：致命错误 marker <b>前缀</b>——载荷是去前缀的错误文案，
     * TUI 打错误＋崩溃指引后 exit 1（pi {@code handleFatalRuntimeError}）。
     */
    public static final String UI_FATAL_EXIT_PREFIX = "@ui:fatal-exit:";

    /**
     * 批 23（docs/34 T4）：一条扩展命令的溯源记录——pi {@code RegisteredCommand}
     * 对位（loader.ts:303-319：注册时盖 {@code extension.sourceInfo} 章，
     * 扩展不可覆盖）。
     *
     * @param extensionPath 注册扩展的路径身份（pi {@code extension.path}；可空＝旧无溯源路）
     * @param sourceInfo    来源章（可空＝旧无溯源路，RPC 线格式省略）
     * @param command       命令本体
     */
    public record ExtensionCommandEntry(String extensionPath,
                                        com.pijava.coding.agent.core.source.SourceInfo sourceInfo,
                                        SlashCommand command) {}

    /**
     * 批 23（T4）：解析后的扩展命令——pi {@code ResolvedCommand} 对位
     * （runner.ts:800-803：{@code invocationName} 是撞名 {@code :N} 编号后的调用名）。
     */
    public record ResolvedExtensionCommand(
            String invocationName, SlashCommand command,
            com.pijava.coding.agent.core.source.SourceInfo sourceInfo) {}

    private final ConcurrentMap<String, Registration> commands;
    /**
     * 扩展与内置撞名时的扩展件——pi 双命名空间语义（TUI 分发内置赢、
     * {@code prompt} 臂扩展赢）：主表保内置供 dispatch，本表保扩展供
     * {@link #extensionCommand}。
     */
    private final ConcurrentMap<String, SlashCommand> extensionOverrides;
    /**
     * 批 23（T4）：扩展命令登记目（跨视图共享，注册序＝pi 扁平化序——装配期
     * 逐扩展加载 ⇒ 追加序与 pi「扩展序×注册序」展开一致）。同扩展同名再注册
     * 先除后添（pi 扩展内 {@code Map.set} 覆盖语义）。
     */
    private final java.util.List<ExtensionCommandEntry> extensionCatalog;
    private final CommandSource defaultSource;
    /** 批 23（T4）：本视图的溯源章（扩展视图专用；null ⇒ 旧无溯源路）。 */
    private final String extensionPath;
    private final com.pijava.coding.agent.core.source.SourceInfo extensionSourceInfo;

    /** 全零构造（默认注册即 {@link CommandSource#BUILTIN}）。 */
    public CommandRegistry() {
        this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            new java.util.concurrent.CopyOnWriteArrayList<>(), CommandSource.BUILTIN,
            null, null);
    }

    private CommandRegistry(ConcurrentMap<String, Registration> commands,
                            ConcurrentMap<String, SlashCommand> extensionOverrides,
                            java.util.List<ExtensionCommandEntry> extensionCatalog,
                            CommandSource defaultSource,
                            String extensionPath,
                            com.pijava.coding.agent.core.source.SourceInfo extensionSourceInfo) {
        this.commands = commands;
        this.extensionOverrides = extensionOverrides;
        this.extensionCatalog = extensionCatalog;
        this.defaultSource = defaultSource;
        this.extensionPath = extensionPath;
        this.extensionSourceInfo = extensionSourceInfo;
    }

    /**
     * 批 20（B237）：扩展注册面——{@link #register} 即打 {@link CommandSource#EXTENSION}
     * 标签，与主注册表<b>共享底层存储</b>（查询/补全/注销两视图一致）。
     * {@code DefaultExtensionContext.slashCommands()} 经此暴露给扩展。
     */
    public CommandRegistry extensionView() {
        return extensionView(null, null);
    }

    /**
     * 批 23（docs/34 T4）：带溯源章的扩展注册面——{@code DefaultExtensionContext}
     * 的 per-extension 委托经此暴露；注册即把 {@code sourceInfo} 盖进登记目
     * （pi loader.ts:317 {@code sourceInfo: extension.sourceInfo} 对位）。
     */
    public CommandRegistry extensionView(String extensionPath,
            com.pijava.coding.agent.core.source.SourceInfo extensionSourceInfo) {
        return new CommandRegistry(commands, extensionOverrides, extensionCatalog,
            CommandSource.EXTENSION, extensionPath, extensionSourceInfo);
    }

    /** Register a command by name (replaces an existing registration). */
    public void register(SlashCommand command) {
        if (defaultSource == CommandSource.EXTENSION) {
            // 批 23（T4）：登记目同扩展同名先除后添（pi 扩展内 Map.set 覆盖）。
            extensionCatalog.removeIf(entry ->
                java.util.Objects.equals(entry.extensionPath(), extensionPath)
                    && entry.command().name().equals(command.name()));
            extensionCatalog.add(
                new ExtensionCommandEntry(extensionPath, extensionSourceInfo, command));
            var existing = commands.get(command.name());
            if (existing != null && existing.source() == CommandSource.BUILTIN) {
                // 撞名：主表保内置（dispatch 内置赢），旁表保扩展（prompt 臂扩展赢）。
                extensionOverrides.put(command.name(), command);
                return;
            }
            commands.put(command.name(), new Registration(command, CommandSource.EXTENSION));
            return;
        }
        commands.put(command.name(), new Registration(command, CommandSource.BUILTIN));
        // 内置重注册压掉撞名旁表（扩展后注册仍可再撞回）；登记目同步摘除
        //（批 23 T4——旁表与目录同生命周期，否则编号视图复活已压掉的影子）。
        extensionOverrides.remove(command.name());
        extensionCatalog.removeIf(entry -> entry.command().name().equals(command.name()));
    }

    /** Remove a command by name. */
    public void unregister(String name) {
        commands.remove(name);
        extensionOverrides.remove(name);
        extensionCatalog.removeIf(entry -> entry.command().name().equals(name));
    }

    /**
     * 批 23（docs/34 T4）：pi {@code resolveRegisteredCommands}
     * （runner.ts:805-840 逐字）——按注册序扁平化；同名计数 {@code >1} 时第 N 次
     * 出现得 {@code name:N}；该名亦被占则自增后缀到空闲。
     */
    public java.util.List<ResolvedExtensionCommand> resolvedExtensionCommands() {
        var counts = new java.util.HashMap<String, Integer>();
        for (var entry : extensionCatalog) {
            counts.merge(entry.command().name(), 1, Integer::sum);
        }
        var seen = new java.util.HashMap<String, Integer>();
        var taken = new java.util.HashSet<String>();
        var out = new java.util.ArrayList<ResolvedExtensionCommand>();
        for (var entry : extensionCatalog) {
            var name = entry.command().name();
            int occurrence = seen.getOrDefault(name, 0) + 1;
            seen.put(name, occurrence);
            var invocationName = counts.get(name) > 1 ? name + ":" + occurrence : name;
            if (taken.contains(invocationName)) {
                int suffix = occurrence;
                do {
                    suffix++;
                    invocationName = name + ":" + suffix;
                } while (taken.contains(invocationName));
            }
            taken.add(invocationName);
            out.add(new ResolvedExtensionCommand(invocationName, entry.command(),
                entry.sourceInfo()));
        }
        return java.util.List.copyOf(out);
    }

    /** List all registered command names (sorted for stable output). */
    public List<String> names() {
        return commands.keySet().stream().sorted().toList();
    }

    /** Look up a command by name. */
    public SlashCommand get(String name) {
        var registration = commands.get(name);
        return registration == null ? null : registration.command();
    }

    /**
     * pi {@code isExtensionCommand}＋{@code extensionRunner.getCommand} 对位
     * （interactive-mode.ts:4771-4779／agent-session.ts:2118-2120）：从
     * {@code "/name args"} 切名字段（<b>空格两刀、{@code indexOf(" ")} 逐字</b>，
     * 非空白族 split），仅 {@link CommandSource#EXTENSION} 命中（含撞名旁表）。
     * 非斜杠开头 / 未注册 / 内置 ⇒ {@code null}。
     */
    public SlashCommand extensionCommand(String text) {
        if (text == null || !text.startsWith("/")) {
            return null;
        }
        int spaceIndex = text.indexOf(" ");
        String name = spaceIndex == -1 ? text.substring(1) : text.substring(1, spaceIndex);
        // 批 23（T4）：先按 invocationName 解析（pi getCommand 只按编号名命中，
        // runner.ts:848-850）——撞名（>1）时裸名不可调，须用 "name:N"。
        var resolved = resolvedExtensionCommands();
        for (var entry : resolved) {
            if (entry.invocationName().equals(name)) {
                return entry.command();
            }
        }
        // 撞名（同一名跨扩展注册 >1）时裸名不可调（pi 同——编号名才可达）。
        if (name.indexOf(':') == -1) {
            int count = 0;
            for (var entry : extensionCatalog) {
                if (entry.command().name().equals(name)) {
                    count++;
                }
            }
            if (count > 1) {
                return null;
            }
        }
        var override = extensionOverrides.get(name);
        if (override != null) {
            return override;
        }
        var registration = commands.get(name);
        return registration != null && registration.source() == CommandSource.EXTENSION
            ? registration.command() : null;
    }

    /**
     * One-line help listing every registered command.
     * Used by the {@code /help} command (pi has no {@code /help}; this is a
     * pi-java usability addition — full CLI help stays on {@code --help}).
     *
     * <p><b>批 25（docs/36 T8，裁决⑤）</b>：输出改 markdown 源（粗体标题＋管道
     * 表格），TUI 侧经 Markdown 渲染（pi 的 /hotkeys Markdown 组件对位，
     * interactive-mode.ts:6921；S24 判定不翻＝增强）。</p>
     */
    public String helpText() {
        var builder = new StringBuilder("**Slash commands**\n\n");
        builder.append("| Command | Description |\n|---------|-------------|\n");
        for (var name : names()) {
            var command = get(name);
            var usage = new StringBuilder("/").append(name);
            if (command.argumentHint() != null && !command.argumentHint().isEmpty()) {
                usage.append(' ').append(command.argumentHint());
            }
            builder.append("| `").append(usage).append("` | ")
                .append(command.description().replace("|", "\\|")).append(" |\n");
        }
        builder.append("\nFull CLI help: pi-java --help");
        return builder.toString();
    }

    /**
     * Match and execute a slash command.
     *
     * @param input   full input line
     * @param context command context
     * @return result future, or {@code null} when the input is not a command
     */
    public CompletionStage<String> dispatch(String input, SlashContext context) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        var parts = input.substring(1).split("\\s+", 2);
        var registration = commands.get(parts[0]);
        // 批 20（B237）：EXTENSION 标签不入 TUI 分发池——返回 null 落到 submit 链，
        // 由 processPrompt 的扩展命令臂全模式统一执行（pi AgentSession.prompt 单
        // 执行点对位，agent-session.ts:1978-1984）。
        if (registration != null && registration.source() == CommandSource.EXTENSION) {
            return null;
        }
        var command = registration == null ? null : registration.command();
        if (command == null) {
            // B244：/skill:<name> 不是注册命令——pi 里它只活在补全名单
            //（interactive-mode.ts:803-815），提交时随普通 prompt 走
            // _expandSkillCommand 展开（agent-session.ts:2008）。透传为 prompt
            //（null ≙ 非命令），未知名/读失败由展开器原文透传（批 4 语义）。
            if (parts[0].startsWith("skill:")) {
                return null;
            }
            // B247：已注册模板名同形——pi 提交处理器无模板分支，落 prompt 后
            // 由 expandPromptTemplate 展开（agent-session.ts:2009，java 批 4
            // 已挂 AgentSession.expandUserPrompt）。按注册表判定（窄差 D3：
            // pi 任何未识别 slash 都落 prompt，java 只放行 /skill: 前缀与
            // 已注册模板名）；模板名撞内置时上面已命中注册表，内置胜出
            //（pi 同形）。session 缺席（单测路）落 Unknown command。
            var session = context.session();
            if (session != null
                    && session.services().promptTemplates().find(parts[0]) != null) {
                return null;
            }
            return CompletableFuture.completedFuture(
                "Unknown command: /" + parts[0]
                    + " (try /hotkeys for the list of commands)");
        }
        return command.execute(parts.length > 1 ? parts[1] : "", context);
    }

    /** Registry with the 22 built-in commands (Phase 3 design §14.2). */
    public static CommandRegistry withBuiltins() {
        var registry = new CommandRegistry();
        ModelCommands.registerAll(registry);
        SessionCommands.registerAll(registry);
        SettingsCommands.registerAll(registry);
        MiscCommands.registerAll(registry);
        SkillsCommands.registerAll(registry);
        ThinkingCommands.registerAll(registry); // 批 15（T8）
        return registry;
    }
}
