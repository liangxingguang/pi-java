package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.pijava.coding.agent.core.slash.SlashCommandInfo;
import com.pijava.coding.agent.core.source.SourceInfo;

import org.junit.jupiter.api.Test;

/** docs/27 W6：commands 验收——来源过滤、分组条目、选中回查与路径确认。 */
class CommandsExtensionTest {

    private static final SourceInfo EXT_SOURCE = new SourceInfo("/proj/.pi/ext/a.ts", "extension",
        SourceInfo.Scope.PROJECT, SourceInfo.Origin.TOP_LEVEL, null);

    private static SlashCommandInfo info(String name, String description, String source,
                                         String path) {
        var sourceInfo = path == null ? null : new SourceInfo(path, source,
            SourceInfo.Scope.PROJECT, SourceInfo.Origin.TOP_LEVEL, null);
        return new SlashCommandInfo(name, description, source, sourceInfo);
    }

    private static List<SlashCommandInfo> sample() {
        return List.of(
            info("alpha", "first command", "extension", EXT_SOURCE.path()),
            info("beta", null, "extension", null),
            info("tpl", "a prompt", "prompt", "/proj/.pi/prompts/tpl.md"),
            info("skill:lint", "lint skill", "skill", "/proj/.pi/skills/lint/SKILL.md"));
    }

    @Test
    void argumentCompletionsFilterByPrefix() {
        assertThat(CommandsExtension.sourceCompletions("")).containsExactly(
            "extension", "prompt", "skill");
        assertThat(CommandsExtension.sourceCompletions("e")).containsExactly("extension");
        assertThat(CommandsExtension.sourceCompletions("s")).containsExactly("skill");
        assertThat(CommandsExtension.sourceCompletions("x")).isNull();
        assertThat(CommandsExtension.sourceCompletions(null)).hasSize(3);
    }

    @Test
    void formatCommandMatchesPiTemplate() {
        assertThat(CommandsExtension.formatCommand(info("a", "desc", "extension", null)))
            .isEqualTo("/a - desc");
        assertThat(CommandsExtension.formatCommand(info("b", null, "extension", null)))
            .isEqualTo("/b");
        assertThat(CommandsExtension.formatCommand(info("c", "", "extension", null)))
            .isEqualTo("/c");
    }

    @Test
    void buildItemsGroupsBySourceInFixedOrder() {
        var items = CommandsExtension.buildItems(sample());

        assertThat(items).containsExactly(
            "--- Extensions ---",
            "/alpha - first command",
            "/beta",
            "--- Prompts ---",
            "/tpl - a prompt",
            "--- Skills ---",
            "/skill:lint - lint skill");
    }

    @Test
    void commandNameOfStripsSlashAndTrailer() {
        assertThat(CommandsExtension.commandNameOf("/alpha - first command"))
            .isEqualTo("alpha");
        assertThat(CommandsExtension.commandNameOf("/beta")).isEqualTo("beta");
    }

    @Test
    void emptyCommandListNotifies() {
        var w = new W3Context();
        new CommandsExtension().register(w.ctx);

        w.command("commands", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No commands found");
        assertThat(w.ui.dialogOptions()).isEmpty();
    }

    @Test
    void filterWithoutMatchNotifiesWithSource() {
        var w = new W3Context();
        w.commandInfos = sample();
        new CommandsExtension().register(w.ctx);

        w.command("commands", "  bogus ");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("No bogus commands found");
    }

    @Test
    void selectingCommandOffersSourcePath() {
        var w = new W3Context();
        w.commandInfos = sample();
        new CommandsExtension().register(w.ctx);

        w.ui.answer("/alpha - first command");
        w.ui.confirmAnswer(true);
        w.command("commands", "extension");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("/proj/.pi/ext/a.ts");
    }

    @Test
    void decliningPathShowsNothing() {
        var w = new W3Context();
        w.commandInfos = sample();
        new CommandsExtension().register(w.ctx);

        w.ui.answer("/alpha - first command");
        w.ui.confirmAnswer(false);
        w.command("commands", "");

        assertThat(w.ui.notices()).isEmpty();
    }

    @Test
    void headerSelectionAndPathlessCommandsAreNoOps() {
        var w = new W3Context();
        w.commandInfos = sample();
        new CommandsExtension().register(w.ctx);

        // 选中分组表头 ⇒ 直接返回
        w.ui.answer("--- Extensions ---");
        w.command("commands", "");
        assertThat(w.ui.notices()).isEmpty();

        // 选中没有 sourceInfo.path 的命令 ⇒ 不问路径
        w.ui.answer("/beta");
        w.command("commands", "");
        assertThat(w.ui.notices()).isEmpty();
        assertThat(w.ui.dialogOptions()).hasSize(2);
    }
}
