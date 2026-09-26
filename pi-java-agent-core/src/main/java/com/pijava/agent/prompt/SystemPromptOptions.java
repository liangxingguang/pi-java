package com.pijava.agent.prompt;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.skill.Skill;

/**
 * pi {@code BuildSystemPromptOptions}（{@code system-prompt.ts:9-38}）＋
 * {@code normalizeBuildSystemPromptOptions}（{@code :50-70}）的移植。
 *
 * <p>pi 把「全可选的入参」与「归一后全在场、集合已拷」分开成两个类型
 * （{@code BuildSystemPromptOptions} / {@code NormalizedBuildSystemPromptOptions}）；Java 用
 * 一个 record 承载，**紧凑构造器就是那个归一函数** —— 于是「未归一」这个状态在类型上不存在。</p>
 *
 * <p>归一为什么是差分稳定性的前提：{@link SystemPrompts#diff} 拿「想要的段」与「转录里的段」
 * 比，若两侧的缺省值不同（一侧 {@code null}、一侧 {@code ""}），每一轮都会吐出一条无意义的
 * 段补丁。所以每个集合/字符串在这里都落到一个确定的值上。</p>
 *
 * <p>⚠️ <b>{@code forceSystemPrompt} 不在本类型里</b>：那个分支要 {@code before_agent_start}
 * 扩展钩子把整份文本塞进 {@code content}（{@code system-prompt.ts:186-194}），而 Java 没有
 * 扩展系统，该写入面不可达（{@code docs/52 §1.2}、登记 L-I）。</p>
 *
 * @param customPrompt       替换默认 preamble 的自定义提示；{@code null}／空串 ＝ 用默认
 * @param selectedTools      参与提示的工具名（决定 {@code tools} 与 {@code rules} 两段）
 * @param toolSnippets       工具名 → 一行片段（进 {@code tools} 段；**没有片段的工具不进表**）
 * @param toolGuidelines     工具名 → 该工具贡献的准则条目（进 {@code rules} 段）
 * @param promptGuidelines   追加到默认准则之后的条目（进 {@code rules} 段）
 * @param appendSystemPrompt 追加提示（进 {@code addendum} 段）
 * @param sections           额外段：段名 → 内容（**空内容不进段**；段名受校验）
 * @param cwd                工作目录（进 {@code cwd} 段，反斜杠一律换正斜杠）
 * @param contextFiles       预载的项目上下文文件（非空时进 {@code project_context} 段）
 * @param skills             预载的技能（非空且启用了 read/bash 时进 {@code skills} 段）
 * @param documentation      pi-java 自身文档的路径（进 {@code docs} 段）；{@code null} ＝ 不产出该段
 */
public record SystemPromptOptions(
    String customPrompt,
    List<String> selectedTools,
    Map<String, String> toolSnippets,
    Map<String, List<String>> toolGuidelines,
    List<String> promptGuidelines,
    String appendSystemPrompt,
    Map<String, String> sections,
    String cwd,
    List<ContextFile> contextFiles,
    List<Skill> skills,
    DocumentationPaths documentation
) {

    /** pi 的 {@code selectedTools} 缺省（{@code system-prompt.ts:53}）。 */
    public static final List<String> DEFAULT_SELECTED_TOOLS = List.of("read", "bash", "edit", "write");

    /** pi 的 {@code contextFiles} 元素形状（{@code :29}）。 */
    public record ContextFile(String path, String content) {
        /** 归一：path/content 都不可为 null（pi 的类型是必填 string）。 */
        public ContextFile {
            path = path == null ? "" : path;
            content = content == null ? "" : content;
        }
    }

    /**
     * {@code docs} 段的三个路径来源 —— pi {@code getReadmePath()}/{@code getDocsPath()}/
     * {@code getExamplesPath()}（{@code config.ts:440-452}）的对应物。
     *
     * <p>⚠️ pi-java **没有 {@code examples/} 目录**（{@code docs/52 §3 F12}）⇒ {@code examples}
     * 通常为 {@code null}，那一条就不渲染。整个 {@code DocumentationPaths} 为 {@code null}
     * 时 {@code docs} 段不产出（找不到 pi-java 发行根时就是这样）。</p>
     */
    public record DocumentationPaths(String readme, String docs, String examples) {
        /** 只有 {@code readme} 与 {@code docs} 都有靶子时才值得产出这一段。 */
        public static DocumentationPaths of(Path readme, Path docs) {
            return new DocumentationPaths(
                readme == null ? null : readme.toString(),
                docs == null ? null : docs.toString(),
                null);
        }
    }

    /** 紧凑构造器 ＝ pi 的 {@code normalizeBuildSystemPromptOptions}（{@code :50-70}）。 */
    public SystemPromptOptions {
        selectedTools = List.copyOf(selectedTools == null ? DEFAULT_SELECTED_TOOLS : selectedTools);
        contextFiles = contextFiles == null ? List.of() : List.copyOf(contextFiles);
        skills = skills == null ? List.of() : List.copyOf(skills);
        promptGuidelines = promptGuidelines == null ? List.of() : List.copyOf(promptGuidelines);
        appendSystemPrompt = appendSystemPrompt == null ? "" : appendSystemPrompt;
        toolSnippets = copyOf(toolSnippets);
        sections = copyOf(sections);
        toolGuidelines = copyGuidelines(toolGuidelines);
        cwd = cwd == null ? "" : cwd;
    }

    /** {@link #builder()}，字段缺省与 pi 的 {@code normalize} 一致。 */
    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, String> copyOf(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, String>(source.size());
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> copyGuidelines(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, List<String>>(source.size());
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 逐字段可选的构造器；{@code build()} 交给紧凑构造器归一。 */
    public static final class Builder {

        private String customPrompt;
        private List<String> selectedTools;
        private Map<String, String> toolSnippets = Map.of();
        private Map<String, List<String>> toolGuidelines = Map.of();
        private List<String> promptGuidelines = List.of();
        private String appendSystemPrompt = "";
        private Map<String, String> sections = Map.of();
        private String cwd = "";
        private List<ContextFile> contextFiles = List.of();
        private List<Skill> skills = List.of();
        private DocumentationPaths documentation;

        /** 自定义提示（替换默认 preamble）。 */
        public Builder customPrompt(String value) {
            this.customPrompt = value;
            return this;
        }

        /** 参与提示的工具名。 */
        public Builder selectedTools(List<String> value) {
            this.selectedTools = value;
            return this;
        }

        /** 工具名 → 一行片段。 */
        public Builder toolSnippets(Map<String, String> value) {
            this.toolSnippets = value;
            return this;
        }

        /** 工具名 → 准则条目。 */
        public Builder toolGuidelines(Map<String, List<String>> value) {
            this.toolGuidelines = value;
            return this;
        }

        /** 追加到默认准则之后的条目。 */
        public Builder promptGuidelines(List<String> value) {
            this.promptGuidelines = value;
            return this;
        }

        /** 追加提示（{@code addendum} 段）。 */
        public Builder appendSystemPrompt(String value) {
            this.appendSystemPrompt = value;
            return this;
        }

        /** 额外段（段名受校验、空内容不进段）。 */
        public Builder sections(Map<String, String> value) {
            this.sections = value;
            return this;
        }

        /** 工作目录。 */
        public Builder cwd(String value) {
            this.cwd = value;
            return this;
        }

        /** 项目上下文文件。 */
        public Builder contextFiles(List<ContextFile> value) {
            this.contextFiles = value;
            return this;
        }

        /** 预载技能。 */
        public Builder skills(List<Skill> value) {
            this.skills = value;
            return this;
        }

        /** pi-java 自身文档的路径。 */
        public Builder documentation(DocumentationPaths value) {
            this.documentation = value;
            return this;
        }

        /** 归一后的不可变选项。 */
        public SystemPromptOptions build() {
            return new SystemPromptOptions(customPrompt, selectedTools, toolSnippets, toolGuidelines,
                promptGuidelines, appendSystemPrompt, sections, cwd, contextFiles, skills, documentation);
        }
    }
}
