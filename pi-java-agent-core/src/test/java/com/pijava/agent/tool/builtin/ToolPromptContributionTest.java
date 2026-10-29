package com.pijava.agent.tool.builtin;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.agent.tool.AgentTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 30（T2，B94）：pi {@code tool-system-prompt-contributions.test.ts} 对位——
 * 8 个内置工具的 {@code promptSnippet}/{@code promptGuidelines} 与各自的
 * contribution 常量逐字相等；bash/powershell 的会话环境准则受
 * {@code exposeSessionEnvironment=false} 门控（pi 测试两臂同形）。
 */
class ToolPromptContributionTest {

    /** pi 测试的 cases 表（8 工具 × 期望贡献）。 */
    private record Case(AgentTool<?, ?> tool, String snippet, List<String> guidelines) {}

    private static final List<Case> CASES = List.of(
        new Case(ReadTool.create(), "Read file contents",
            List.of("Use read to examine files instead of cat or sed.")),
        new Case(BashTool.create(), "Execute bash commands (ls, grep, find, etc.)",
            List.of("You can inspect PI_* environment variables for current model "
                + "and session details.")),
        new Case(PowerShellTool.create(), "Execute PowerShell commands",
            List.of("You can inspect PI_* environment variables for current model "
                + "and session details.")),
        new Case(EditTool.create(),
            "Make precise file edits with exact text replacement, "
                + "including multiple disjoint edits in one call",
            List.of(
                "Use edit for precise changes (edits[].oldText must match exactly)",
                "When changing multiple separate locations in one file, use one edit call "
                    + "with multiple entries in edits[] instead of multiple edit calls",
                "Each edits[].oldText is matched against the original file, not after "
                    + "earlier edits are applied. Do not emit overlapping or nested edits. "
                    + "Merge nearby changes into one edit.",
                "Keep edits[].oldText as small as possible while still being unique in the "
                    + "file. Do not pad with large unchanged regions.")),
        new Case(WriteTool.create(), "Create or overwrite files",
            List.of("Use write only for new files or complete rewrites.")),
        new Case(GrepTool.create(), "Search file contents for patterns (respects .gitignore)",
            List.of()),
        new Case(FindTool.create(), "Find files by glob pattern (respects .gitignore)",
            List.of()),
        new Case(LsTool.create(), "List directory contents", List.of()));

    @Test
    void allEightBuiltinToolsCarryTheirContribution() {
        for (var c : CASES) {
            assertThat(c.tool().promptSnippet()).as("%s snippet", c.tool().name())
                .isEqualTo(c.snippet());
            assertThat(c.tool().promptGuidelines()).as("%s guidelines", c.tool().name())
                .isEqualTo(c.guidelines());
        }
    }

    @Test
    void bashAndPowershellGuidelinesAreGatedOnSessionEnvironment() {
        // pi 测试第二臂：exposeSessionEnvironment=false ⇒ guidelines 空。
        assertThat(BashTool.create(null, false).promptGuidelines()).isEmpty();
        assertThat(PowerShellTool.create(false).promptGuidelines()).isEmpty();
        // 开关不门 snippet（pi 同）。
        assertThat(BashTool.create(null, false).promptSnippet())
            .isEqualTo("Execute bash commands (ls, grep, find, etc.)");
        assertThat(PowerShellTool.create(false).promptSnippet())
            .isEqualTo("Execute PowerShell commands");
    }
}
