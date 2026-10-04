package com.pijava.agent.prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;

/**
 * pi {@code packages/coding-agent/src/core/system-prompt.ts} 的移植：结构化系统提示的
 * <b>构建</b>（{@link #buildSections}）、<b>状态</b>（{@link #state}）、<b>差分</b>
 * （{@link #diff}）与<b>整份渲染</b>（{@link #build}）。
 *
 * <p>pi 的系统提示不是一段字符串，而是一张「具名有序段」表：{@code preamble} 不带标签，
 * 其余每段包成 {@code <name>…</name>}（{@code :179-183}）。段表进转录后，中途的变化以
 * **按名补丁**下发（值 {@code null} ＝ 删掉该段），重放见
 * {@code com.pijava.ai.api.Transcripts#getCurrentSystemMessage}。</p>
 *
 * <p><b>本类与 pi 的差异</b>（{@code 原 docs/52 §9 R2}）：pi 的默认正文里 {@code docs} 段是一份
 * 指向 <b>pi 仓库</b>文件的索引（{@code docs/extensions.md}、{@code docs/tui.md}…），
 * 那批文件在 pi-java 里不存在，逐字照抄会主动误导模型。故<b>结构与段名逐字对齐，文本按
 * pi-java 的实际内容</b>：{@code preamble} 用 pi-java 的定位句，{@code docs} 段指向 pi-java
 * 真实存在的文档（且**没有 {@code examples/}**，{@code 原 docs/52 §3 F12}）。</p>
 */
public final class SystemPrompts {

    private SystemPrompts() {}

    /** pi 的保留段名：不带标签、排第一、且**禁止**由调用方声明（{@code :132-136}）。 */
    public static final String PREAMBLE = "preamble";

    /** pi 的段名校验（{@code :48}）：小写字母打头，后续字母/数字/下划线/连字符。 */
    private static final Pattern SECTION_NAME = Pattern.compile("^[a-z][a-z0-9_-]*$");

    /**
     * pi-java 的默认定位句 —— pi 的 {@code preamble} 默认正文（{@code :144}）的对应物。
     *
     * <p>⚠️ pi 的默认 preamble 是那段 pi 专属的自我介绍；pi-java 的正文放在这里
     * （{@code 原 docs/52 §9 R2-B}）。它只是**定位句**：pi-java 原有的表达风格条目改由
     * {@code promptGuidelines} 承载，渲染进 {@code rules} 段 —— 那正是 pi 为这类条目准备的
     * 通道（{@code BuildSystemPromptOptions.promptGuidelines}）。</p>
     */
    public static final String DEFAULT_PREAMBLE =
        "You are pi-java, an AI coding assistant. Help the user write, read, "
            + "and understand code. Use the provided tools when useful.";

    /** pi {@code buildSystemPromptState} 的返回形状（{@code :186-194}）。 */
    public record State(String content, Map<String, String> sections) {}

    /**
     * pi {@code buildSystemPromptSections}（{@code :120-183}）—— 段表。
     *
     * <p>顺序即语义：{@code preamble} 第一且**不加标签**；其余按「默认段 → addendum →
     * project_context → skills → cwd → 自定义段」的产出序包标签。自定义段可**覆盖**同名默认段，
     * 但不得叫 {@code preamble}（抛 {@link IllegalArgumentException}）。</p>
     */
    public static Map<String, String> buildSections(SystemPromptOptions options) {
        validateSectionNames(options.sections());

        var promptSections = new LinkedHashMap<String, String>();
        if (isPresent(options.customPrompt())) {
            promptSections.put(PREAMBLE, options.customPrompt());
        } else {
            promptSections.put(PREAMBLE, DEFAULT_PREAMBLE);
            promptSections.put("tools", toolsSection(options));
            promptSections.put("rules", buildRules(options.selectedTools(),
                options.toolGuidelines(), options.promptGuidelines()));
            var docs = docsSection(options.documentation());
            if (docs != null) {
                promptSections.put("docs", docs);
            }
        }

        if (isPresent(options.appendSystemPrompt())) {
            promptSections.put("addendum", options.appendSystemPrompt());
        }
        if (!options.contextFiles().isEmpty()) {
            promptSections.put("project_context", renderProjectContext(options.contextFiles()));
        }
        var skillReadTool = skillReadTool(options.selectedTools());
        if (skillReadTool != null && !options.skills().isEmpty()) {
            var skillsPrompt = SkillsPrompt.format(options.skills(), skillReadTool).trim();
            if (!skillsPrompt.isEmpty()) {
                promptSections.put("skills", skillsPrompt);
            }
        }
        promptSections.put("cwd", options.cwd().replace('\\', '/'));

        for (var entry : options.sections().entrySet()) {
            if (isPresent(entry.getValue())) {
                promptSections.put(entry.getKey(), entry.getValue());
            }
        }
        return tag(promptSections);
    }

    /** pi {@code buildSystemPromptState}（{@code :186-194}）：{@code content} 恒空、段表载提示。 */
    public static State state(SystemPromptOptions options) {
        return new State("", buildSections(options));
    }

    /**
     * pi {@code buildSystemPrompt}（{@code :196}）—— 按转录重放的方式渲染出的**整份**提示文本。
     *
     * <p>走 {@link MessageTexts#getSystemMessageText} 而不是自己拼：pi 用的就是
     * {@code getSystemMessageText({role:"system", ...state, timestamp: 0})}，
     * 于是「模型看到的提示」与「重放出来的提示」是同一条代码路径（{@code 原 docs/51 §12} 的
     * F11 更正：pi 生产路径上提示**只**经这一条路径产生）。</p>
     */
    public static String build(SystemPromptOptions options) {
        var state = state(options);
        return MessageTexts.getSystemMessageText(new Message.SystemMessage(
            state.content(), Instant.EPOCH, state.sections(), List.of(), List.of()));
    }

    /**
     * pi {@code diffSystemPromptSections}（{@code :199-215}）—— 最小段补丁。
     *
     * <p>补丁的**插入序** ＝ 「先全部变更/新增段（按 {@code current} 的序），后全部删除段
     * （按 {@code previous} 的序）」。值为 {@code null} 的条目是**删除**。</p>
     *
     * <p>⚠️ 返回的 Map **可以含 null 值**：调用方不得喂给
     * {@code Map.copyOf}/{@code Map.of}/{@code Map.entry}（在 null 值上 NPE）。
     * 无事发生返回 {@code null}（pi 的 {@code undefined}）。</p>
     *
     * @param previous 模型**当前**拥有的段 —— 来自转录重放，故实际上不会有 {@code null} 值
     * @param current  {@link #buildSections} 算出的期望段
     */
    public static Map<String, String> diff(Map<String, String> previous, Map<String, String> current) {
        var patch = new LinkedHashMap<String, String>();
        for (var entry : current.entrySet()) {
            if (!Objects.equals(previous.get(entry.getKey()), entry.getValue())) {
                patch.put(entry.getKey(), entry.getValue());
            }
        }
        for (var name : previous.keySet()) {
            if (!current.containsKey(name)) {
                patch.put(name, null);
            }
        }
        return patch.isEmpty() ? null : patch;
    }

    // ═══════════════════════════════════════════════════════════
    // 段正文
    // ═══════════════════════════════════════════════════════════

    /**
     * pi 的 {@code tools} 段（{@code :151-155}）。
     *
     * <p>两条容易写错的细节：**只有带片段的工具进表**（{@code filter(name => !!toolSnippets[name])}），
     * 过滤后为空写 {@code (none)}。</p>
     */
    private static String toolsSection(SystemPromptOptions options) {
        var visible = options.selectedTools().stream()
            .filter(name -> isPresent(options.toolSnippets().get(name)))
            .toList();
        var tools = visible.isEmpty()
            ? "(none)"
            : visible.stream()
                .map(name -> "- " + name + ": " + options.toolSnippets().get(name))
                .collect(Collectors.joining("\n"));
        return tools + "\n\nIn addition to the tools above, you may have access to other custom "
            + "tools depending on the project.";
    }

    /**
     * pi {@code buildRules}（{@code :81-119}）—— 准则条目。
     *
     * <p>去重按 **trim 后的字面值**（{@code seen} 集合，空串丢弃），顺序：
     * ① 文件操作规则（仅当 {@code bash|powershell} 在且 {@code grep/find/ls} **都不在**）→
     * ② 按 {@code selectedTools} 顺序逐工具取 {@code toolGuidelines} → ③ {@code promptGuidelines}
     * → ④ 两条固定兜底。每行前缀 {@code "- "}。</p>
     */
    static String buildRules(List<String> selectedTools, Map<String, List<String>> toolGuidelines,
                             List<String> promptGuidelines) {
        var rules = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        var hasBash = selectedTools.contains("bash");
        var hasPowerShell = selectedTools.contains("powershell");
        var hasGrep = selectedTools.contains("grep");
        var hasFind = selectedTools.contains("find");
        var hasLs = selectedTools.contains("ls");

        if ((hasBash || hasPowerShell) && !hasGrep && !hasFind && !hasLs) {
            if (hasBash && hasPowerShell) {
                addRule(rules, seen, "Use bash or PowerShell for file operations like listing, "
                    + "searching, and finding files");
            } else if (hasPowerShell) {
                addRule(rules, seen, "Use PowerShell for file operations like listing, searching, "
                    + "and finding files");
            } else {
                addRule(rules, seen, "Use bash for file operations like ls, rg, find");
            }
        }
        for (var name : selectedTools) {
            for (var rule : toolGuidelines.getOrDefault(name, List.of())) {
                addRule(rules, seen, rule);
            }
        }
        for (var rule : promptGuidelines) {
            addRule(rules, seen, rule);
        }
        addRule(rules, seen, "Be concise in your responses");
        addRule(rules, seen, "Show file paths clearly when working with files");
        return rules.stream().map(rule -> "- " + rule).collect(Collectors.joining("\n"));
    }

    /** pi 的 {@code addRule}（{@code :87-93}）：trim、丢空、首次出现才收。 */
    private static void addRule(List<String> rules, Set<String> seen, String rule) {
        var normalized = rule == null ? "" : rule.trim();
        if (normalized.isEmpty() || !seen.add(normalized)) {
            return;
        }
        rules.add(normalized);
    }

    /** pi {@code renderProjectContext}（{@code :72-79}）。 */
    private static String renderProjectContext(List<SystemPromptOptions.ContextFile> files) {
        return Stream.concat(
            Stream.of("Project-specific instructions and guidelines:"),
            files.stream().map(file -> "<project_instructions path=\"" + file.path() + "\">\n"
                + file.content() + "\n</project_instructions>"))
            .collect(Collectors.joining("\n\n"));
    }

    /**
     * pi 的 {@code docs} 段（{@code :157-168}）。
     *
     * <p>⚠️ 文本按 pi-java 的实际改写（{@code 原 docs/52 §9 R2-B}）：pi 的正文逐条点名
     * {@code docs/extensions.md}、{@code docs/themes.md} 等**pi 仓库的文件**，pi-java 没有它们；
     * pi-java 的 {@code docs/} 是编号设计文档。{@code documentation} 为 {@code null}
     * （没找到 pi-java 发行根）⇒ 整段不产出。</p>
     */
    private static String docsSection(SystemPromptOptions.DocumentationPaths documentation) {
        if (documentation == null || !isPresent(documentation.readme())
            || !isPresent(documentation.docs())) {
            return null;
        }
        var lines = new ArrayList<String>();
        lines.add("Pi-java documentation (read only when the user asks about pi-java itself, its SDK, "
            + "its design, or its alignment with pi):");
        lines.add("- Main documentation: " + documentation.readme());
        lines.add("- Additional docs: " + documentation.docs());
        if (isPresent(documentation.examples())) {
            lines.add("- Examples: " + documentation.examples());
        }
        lines.add("- The Additional docs directory holds the numbered design documents "
            + "(process, requirements, architecture, and one document per alignment package)");
        lines.add("- When reading pi-java docs, resolve docs/... under Additional docs, "
            + "not the current working directory");
        lines.add("- When working on pi-java topics, read the docs and follow .md cross-references "
            + "before implementing");
        lines.add("- Always read pi-java .md files completely and follow links to related docs");
        return String.join("\n", lines);
    }

    // ═══════════════════════════════════════════════════════════
    // 收尾
    // ═══════════════════════════════════════════════════════════

    /**
     * pi {@code :179-183} —— {@code preamble} 排第一且**不加标签**，其余包 {@code <name>…</name>}。
     *
     * <p>⚠️ 这个不对称是 oracle 钉住的：{@code system-prompt-updates.test.ts} 断言
     * {@code diffSystemPromptSections} 对自定义提示的产出是 <b>裸</b> {@code { preamble: "You are B." }}，
     * 而对 {@code plan_mode} 的产出**带** {@code <plan_mode>…</plan_mode>}。</p>
     */
    private static Map<String, String> tag(Map<String, String> promptSections) {
        var sections = new LinkedHashMap<String, String>();
        sections.put(PREAMBLE, promptSections.get(PREAMBLE));
        for (var entry : promptSections.entrySet()) {
            if (PREAMBLE.equals(entry.getKey())) {
                continue;
            }
            sections.put(entry.getKey(),
                "<" + entry.getKey() + ">\n" + entry.getValue() + "\n</" + entry.getKey() + ">");
        }
        return sections;
    }

    /** pi {@code :132-136} —— 段名校验，抛出的文案与 pi 逐字相同。 */
    private static void validateSectionNames(Map<String, String> customSections) {
        for (var name : customSections.keySet()) {
            if (!SECTION_NAME.matcher(name).matches() || PREAMBLE.equals(name)) {
                throw new IllegalArgumentException("Invalid system prompt section name: " + name);
            }
        }
    }

    /** pi {@code (["read","bash"] as const).find(...)}（{@code :170}）—— {@code read} 优先。 */
    private static String skillReadTool(List<String> selectedTools) {
        for (var candidate : List.of(SkillsPrompt.READ_TOOL, SkillsPrompt.BASH_TOOL)) {
            if (selectedTools.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** JS 里字符串的真值判据：{@code null} 与空串都是假。 */
    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }
}
