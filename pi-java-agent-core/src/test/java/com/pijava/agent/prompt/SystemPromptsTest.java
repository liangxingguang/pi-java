package com.pijava.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.agent.skill.Skill;

/**
 * 包 A4b：pi {@code packages/coding-agent/test/system-prompt-updates.test.ts} 的**纯函数**部分
 * 的移植，外加把 {@code system-prompt.ts} 的每条分支钉住。
 *
 * <p>oracle 的三条（pi 实测 8/8 绿，其中纯函数两条 ＋ {@code buildSystemPromptState} 一条）：
 * {@code diffs sections into a patch}、{@code keeps the preamble untagged and replaces it like
 * any section}。⚠️ pi 那条 {@code buildSystemPromptState({forceSystemPrompt})} 的断言
 * <b>没有移植</b>：那个分支要扩展系统，java 侧不可达（{@code docs/52 §1.2}、登记 L-I），
 * 而没有它之后 {@code state()} 的断言会退化成同义反复 —— 本仓在包 A1 的复核里已删过一条同型
 * 断言（{@code docs/48 §10.1}）。</p>
 *
 * <p>⚠️ 夹具一律**不给** {@code documentation}：pi 的 {@code docs} 段总是产出（它的三个路径恒定
 * 成立于 pi 仓库），而 java 侧没有靶子时不产出（{@code docs/52 §3 F12}）—— 只要两侧一致地
 * 缺席/在场，{@code diff} 的期望值与 pi 逐字相同。</p>
 */
class SystemPromptsTest {

    // ═══════════════════════════════════════════════════════════
    // 差分（pi 的两条 oracle 中的第一条）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code :82-91} —— 逐字期望值。 */
    @Test
    void diffsSectionsIntoAPatch() {
        var previous = SystemPrompts.buildSections(options()
            .cwd("/tmp").sections(Map.of("plan_mode", "Plan only.")).build());
        var current = SystemPrompts.buildSections(options()
            .cwd("/tmp").sections(Map.of("plan_mode", "Implementation allowed.")).build());

        assertThat(SystemPrompts.diff(previous, current))
            .containsExactly(entry("plan_mode", "<plan_mode>\nImplementation allowed.\n</plan_mode>"));
        assertThat(SystemPrompts.diff(previous, previous))
            .as("无事发生 ⇒ null（pi 的 undefined）").isNull();
        assertThat(SystemPrompts.diff(previous,
            SystemPrompts.buildSections(options().cwd("/tmp").build())))
            .as("段消失 ⇒ 删除项（⚠️ 删除项的值是 null，Map.of 装不下它）")
            .containsExactly(entry("plan_mode", null));
    }

    /** 补丁的**插入序**：先全部变更/新增段（按 current 序），后全部删除段（按 previous 序）。 */
    @Test
    void patchListsChangedSectionsBeforeRemovedOnes() {
        var previous = SystemPrompts.buildSections(options().cwd("/tmp")
            .sections(ordered("a", "1", "b", "1", "c", "1")).build());
        var current = SystemPrompts.buildSections(options().cwd("/tmp")
            .sections(ordered("b", "2", "d", "1")).build());

        var patch = SystemPrompts.diff(previous, current);

        assertThat(patch).isNotNull();
        // b 变更（按 current 序）、d 新增、a 删除、c 删除
        assertThat(patch.keySet()).containsExactly("b", "d", "a", "c");
        assertThat(patch).containsEntry("a", null).containsEntry("c", null);
        assertThat(patch.get("b")).isEqualTo("<b>\n2\n</b>");
    }

    /** 出参**可以含 null 值** —— 这条是给调用方的警告（不能喂 `Map.copyOf`）。 */
    @Test
    void removalEntriesAreNullValuedAndSurvive() {
        var patch = SystemPrompts.diff(ordered("gone", "<gone>\n1\n</gone>"), Map.of());

        assertThat(patch).hasSize(1).containsEntry("gone", null);
        assertThat(patch.containsKey("gone")).isTrue();
    }

    // ═══════════════════════════════════════════════════════════
    // preamble 的不对称（pi 的第二条 oracle）
    // ═══════════════════════════════════════════════════════════

    /** pi {@code :94-98} —— {@code preamble} 裸着，别的段包标签；替换与其它段同法。 */
    @Test
    void keepsThePreambleUntaggedAndReplacesItLikeAnySection() {
        var previous = SystemPrompts.buildSections(options().customPrompt("You are A.").cwd("/tmp").build());
        var current = SystemPrompts.buildSections(options().customPrompt("You are B.").cwd("/tmp").build());

        assertThat(previous.get("preamble")).isEqualTo("You are A.");
        assertThat(SystemPrompts.diff(previous, current))
            .containsExactly(entry("preamble", "You are B."));
    }

    /** 自定义提示**抑制**默认三段的产出（pi 把它们放在同一个 `else` 里）。 */
    @Test
    void aCustomPromptSuppressesToolsRulesAndDocs() {
        var sections = SystemPrompts.buildSections(options().customPrompt("Only this.").cwd("/tmp").build());

        assertThat(sections.keySet()).containsExactly("preamble", "cwd");
        assertThat(sections.get("preamble")).isEqualTo("Only this.");
    }

    /** 默认分支：preamble 裸着，其余每段包标签，`preamble` 排第一。 */
    @Test
    void tagsEverySectionExceptThePreamble() {
        var sections = SystemPrompts.buildSections(options().cwd("/tmp").build());

        assertThat(sections.keySet()).containsExactly("preamble", "tools", "rules", "cwd");
        assertThat(sections.get("preamble")).doesNotStartWith("<preamble>");
        assertThat(sections.get("cwd")).isEqualTo("<cwd>\n/tmp\n</cwd>");
        assertThat(sections.get("rules")).startsWith("<rules>\n").endsWith("\n</rules>");
    }

    // ═══════════════════════════════════════════════════════════
    // tools 段
    // ═══════════════════════════════════════════════════════════

    /** 只有**带片段**的工具进表；一个都没有时写 `(none)`。 */
    @Test
    void onlyToolsWithSnippetsAppearInTheToolsSection() {
        var options = options().cwd("/tmp")
            .selectedTools(List.of("read", "bash", "edit"))
            .toolSnippets(ordered("read", "Read a file", "edit", "Edit a file"))
            .build();

        var tools = SystemPrompts.buildSections(options).get("tools");

        assertThat(tools).startsWith("<tools>\n- read: Read a file\n- edit: Edit a file\n");
        assertThat(tools).doesNotContain("bash");
        assertThat(tools).contains("In addition to the tools above");
    }

    /** 过滤后为空 ⇒ `(none)`（凭空造 "(read, bash)" 之类的表格是错的）。 */
    @Test
    void writesNoneWhenNoSelectedToolHasASnippet() {
        var tools = SystemPrompts.buildSections(
            options().cwd("/tmp").selectedTools(List.of("bash")).build()).get("tools");

        assertThat(tools).startsWith("<tools>\n(none)\n");
    }

    // ═══════════════════════════════════════════════════════════
    // rules 段
    // ═══════════════════════════════════════════════════════════

    /** 去重按 trim 后的字面值，且两条固定兜底总在最后。 */
    @Test
    void rulesDedupeByTrimmedTextAndAlwaysAppendTheTwoFallbacks() {
        var options = options().cwd("/tmp")
            .promptGuidelines(List.of("  Only once  ", "Only once", "", "Second"))
            .build();

        var rules = SystemPrompts.buildSections(options).get("rules");

        // 缺省 selectedTools 是 pi 的四件套（含 bash、不含 grep/find/ls）⇒ 文件操作规则在最前
        assertThat(rules).isEqualTo("<rules>\n"
            + "- Use bash for file operations like ls, rg, find\n"
            + "- Only once\n"
            + "- Second\n"
            + "- Be concise in your responses\n"
            + "- Show file paths clearly when working with files\n"
            + "</rules>");
    }

    /** 工具准则按 `selectedTools` 的顺序取（不是按 Map 的顺序）。 */
    @Test
    void toolGuidelinesFollowTheSelectedToolOrder() {
        var options = options().cwd("/tmp")
            .selectedTools(List.of("second", "first"))
            .toolGuidelines(Map.of(
                "first", List.of("First guideline"),
                "second", List.of("Second guideline")))
            .build();

        var rules = SystemPrompts.buildSections(options).get("rules");

        assertThat(rules).contains("- Second guideline\n- First guideline\n");
    }

    /** 文件操作规则只在「有 bash/PowerShell 且没有 grep/find/ls」时出现。 */
    @Test
    void theFileOperationsRuleOnlyAppearsWithoutSearchTools() {
        var onlyBash = SystemPrompts.buildSections(
            options().cwd("/tmp").selectedTools(List.of("bash")).build()).get("rules");
        assertThat(onlyBash).contains("- Use bash for file operations like ls, rg, find\n");

        var bothShells = SystemPrompts.buildSections(
            options().cwd("/tmp").selectedTools(List.of("bash", "powershell")).build()).get("rules");
        assertThat(bothShells).contains("- Use bash or PowerShell for file operations like listing, "
            + "searching, and finding files\n");

        var withGrep = SystemPrompts.buildSections(
            options().cwd("/tmp").selectedTools(List.of("bash", "grep")).build()).get("rules");
        assertThat(withGrep).doesNotContain("file operations like");
    }

    // ═══════════════════════════════════════════════════════════
    // 其余默认段
    // ═══════════════════════════════════════════════════════════

    /** pi {@code :175} —— cwd 的反斜杠一律换正斜杠（Windows 上就是这条在起作用）。 */
    @Test
    void normalizesBackslashesInTheCwd() {
        var sections = SystemPrompts.buildSections(options().cwd("D:\\work\\pi-java").build());

        assertThat(sections.get("cwd")).isEqualTo("<cwd>\nD:/work/pi-java\n</cwd>");
    }

    /** `addendum` / `project_context` 各自的出现条件。 */
    @Test
    void addendumAndProjectContextAppearOnlyWhenNonEmpty() {
        var empty = SystemPrompts.buildSections(options().cwd("/tmp").appendSystemPrompt("").build());
        assertThat(empty).doesNotContainKey("addendum").doesNotContainKey("project_context");

        var filled = SystemPrompts.buildSections(options().cwd("/tmp")
            .appendSystemPrompt("Extra.")
            .contextFiles(List.of(
                new SystemPromptOptions.ContextFile("AGENTS.md", "be nice"),
                new SystemPromptOptions.ContextFile("CLAUDE.md", "be terse")))
            .build());

        assertThat(filled.get("addendum")).isEqualTo("<addendum>\nExtra.\n</addendum>");
        assertThat(filled.get("project_context")).isEqualTo("<project_context>\n"
            + "Project-specific instructions and guidelines:\n\n"
            + "<project_instructions path=\"AGENTS.md\">\nbe nice\n</project_instructions>\n\n"
            + "<project_instructions path=\"CLAUDE.md\">\nbe terse\n</project_instructions>\n"
            + "</project_context>");
    }

    /** 自定义段可以**覆盖**同名默认段，且**空内容不进段**。 */
    @Test
    void customSectionsOverwriteDefaultsAndSkipEmptyValues() {
        var sections = SystemPrompts.buildSections(options().cwd("/tmp")
            .sections(ordered("tools", "Replaced tools", "empty_one", ""))
            .build());

        assertThat(sections.get("tools")).isEqualTo("<tools>\nReplaced tools\n</tools>");
        assertThat(sections).doesNotContainKey("empty_one");
    }

    /** 段名校验：形状不符或叫 `preamble` 都抛，文案与 pi 逐字相同。 */
    @Test
    void rejectsInvalidSectionNames() {
        assertThatThrownBy(() -> SystemPrompts.buildSections(
            options().cwd("/tmp").sections(Map.of("preamble", "x")).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid system prompt section name: preamble");

        assertThatThrownBy(() -> SystemPrompts.buildSections(
            options().cwd("/tmp").sections(Map.of("Bad Name", "x")).build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid system prompt section name");

        // pi 的告示：段名避开整数样的字符串（JSON 对象会重排它们）
        assertThatThrownBy(() -> SystemPrompts.buildSections(
            options().cwd("/tmp").sections(Map.of("1st", "x")).build()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ═══════════════════════════════════════════════════════════
    // skills 段（pi {@code skills.ts:355-392}）
    // ═══════════════════════════════════════════════════════════

    /** 只有在选了 `read` 或 `bash` 且技能非空时才产出；`read` 优先。 */
    @Test
    void theSkillsSectionNeedsAFileReadingTool() {
        var skills = List.<Skill>of(new TestSkill("code-review", "Reviews code", "D:/skills/code-review"));

        var withRead = SystemPrompts.buildSections(options().cwd("/tmp")
            .selectedTools(List.of("read", "bash")).skills(skills).build());
        assertThat(withRead.get("skills")).contains(
            "Use the read tool to load a skill's file when the task matches its description.");

        var withBashOnly = SystemPrompts.buildSections(options().cwd("/tmp")
            .selectedTools(List.of("bash")).skills(skills).build());
        assertThat(withBashOnly.get("skills")).contains(
            "Use bash to load a skill's file when the task matches its description.");

        var noReader = SystemPrompts.buildSections(options().cwd("/tmp")
            .selectedTools(List.of("edit")).skills(skills).build());
        assertThat(noReader).doesNotContainKey("skills");
    }

    /** 段正文的形状与转义（`&` 必须先换，否则会二次转义）。 */
    @Test
    void theSkillsSectionRendersEscapedXml() {
        var skills = List.<Skill>of(
            new TestSkill("a&b", "uses <tags> and \"quotes\"", "D:/x&y"),
            new TestSkill("hidden", "not visible", "D:/h", true));

        var section = SystemPrompts.buildSections(options().cwd("/tmp")
            .selectedTools(List.of("read")).skills(skills).build()).get("skills");

        assertThat(section).startsWith("<skills>\nThe following skills provide specialized instructions")
            .endsWith("</available_skills>\n</skills>");
        assertThat(section).contains("    <name>a&amp;b</name>");
        assertThat(section).contains("    <description>uses &lt;tags&gt; and &quot;quotes&quot;</description>");
        // ⚠️ `<location>` **不**做反斜杠归一：pi 的 cwd 段归一（`system-prompt.ts:175`），
        // 技能路径走的是它自己的 `join()`，在 Windows 上同样是反斜杠。故这里按平台取期望值，
        // 只钉转义本身。
        var location = java.nio.file.Path.of("D:/x&y").resolve("SKILL.md").toString()
            .replace("&", "&amp;");
        assertThat(section).contains("    <location>" + location + "</location>");
        assertThat(section).as("disableModelInvocation 的技能不进清单").doesNotContain("hidden");
    }

    /** 全部技能都不可见 ⇒ 不产出段（pi 的 `formatSkillsForPrompt` 返回空串）。 */
    @Test
    void allHiddenSkillsProduceNoSection() {
        var skills = List.<Skill>of(new TestSkill("hidden", "x", "D:/h", true));

        var sections = SystemPrompts.buildSections(options().cwd("/tmp")
            .selectedTools(List.of("read")).skills(skills).build());

        assertThat(sections).doesNotContainKey("skills");
    }

    // ═══════════════════════════════════════════════════════════
    // 整份渲染（pi {@code :196}）
    // ═══════════════════════════════════════════════════════════

    /** `build()` ＝ 按转录重放方式渲染：裸 preamble ＋ 各段的**正文**（标签留在段里）。 */
    @Test
    void buildRendersThePromptTheWayReplayDoes() {
        var prompt = SystemPrompts.build(options().cwd("/tmp").customPrompt("You are A.").build());

        assertThat(prompt).isEqualTo("You are A.\n\n<cwd>\n/tmp\n</cwd>");
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具
    // ═══════════════════════════════════════════════════════════

    private static SystemPromptOptions.Builder options() {
        return SystemPromptOptions.builder();
    }

    /** 保序表 —— `Map.of` 的迭代顺序未定义，凡顺序进入断言的夹具都显式保序。 */
    private static Map<String, String> ordered(String... keyValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 最小技能实现；`baseDir` 决定 `filePath`（＝ `baseDir/SKILL.md`）。 */
    private record TestSkill(String name, String description, String dir,
                             boolean disableModelInvocation) implements Skill {

        TestSkill(String name, String description, String dir) {
            this(name, description, dir, false);
        }

        @Override public String label() {
            return name;
        }

        @Override public String systemPrompt() {
            return "skill body";
        }

        @Override public java.nio.file.Path baseDir() {
            return java.nio.file.Path.of(dir);
        }
    }
}
