package com.pijava.tui.app;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.source.SourceInfo;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B247（docs/13 §3.4）：模板补全目录装配 —— 无条件（pi 无门）、序在内置
 * 之后 skill 之前（pi :820-823 内置→模板→扩展→skill）、argumentHint 随行。
 * 不跑 runner —— 补全目录在 {@link PiTuiApp} 构造器装配。
 */
class TemplateCommandsTuiTest {

    @TempDir
    Path sessionDir;

    @Test
    void slashCatalogListsTemplateCommandsUnconditionally() throws Exception {
        try (var session = createSession()) {
            session.services().promptTemplates().register(
                template("review-code", "Review the code", null));
            var chatScreen = assemble(session).screen();

            // pi :787-792 无门（对照 skill 臂：enableSkillCommands 关掉模板照列）。
            session.services().settings().accessors().setEnableSkillCommands(false);
            chatScreen.insertText("/review-co");

            assertThat(chatScreen.completerActive())
                .as("模板进补全目录（无门）")
                .isTrue();
            assertThat(chatScreen.applyCompletion()).isTrue();
            assertThat(chatScreen.inputText()).isEqualTo("/review-code");
        }
    }

    @Test
    void templateEntriesSortBetweenBuiltinsAndSkills() throws Exception {
        try (var session = createSession()) {
            session.services().promptTemplates().register(
                template("zztemplate", "T", null));
            session.harness().skillManager().register(new SkillCommandsTuiTestSkill());
            var items = loadSlashItems(assemble(session).app());

            int lastBuiltin = lastBuiltinIndex(items);
            int template = indexOf(items, "zztemplate");
            int skill = indexOf(items, "skill:zzskill");
            // pi :820-823 内置→模板→（扩展）→skill；java 无扩展臂。
            assertThat(template).as("模板在内置之后").isGreaterThan(lastBuiltin);
            assertThat(skill).as("skill 在模板之后").isGreaterThan(template);
        }
    }

    @Test
    void templateArgumentHintReachesCommandItem() throws Exception {
        try (var session = createSession()) {
            session.services().promptTemplates().register(
                template("review", "Review code", "<file> [focus]"));
            var items = loadSlashItems(assemble(session).app());

            assertThat(items.get(indexOf(items, "review")).hint())
                .as("pi :790：argumentHint 随行（CommandItem.hint）")
                .isEqualTo("<file> [focus]");
            assertThat(items.get(indexOf(items, "review")).description())
                .as("项目域模板 [p] 前缀")
                .isEqualTo("[p] Review code");
        }
    }

    private record Assembly(PiTuiApp app, ChatScreen screen) {}

    /** 隔离 user.home 的夹具会话（FileSettingsStorage 构造时捕获路径）。 */
    private AgentSession createSession() {
        var home = sessionDir.resolve("home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            return AgentSession.create(ArgsParser.parse(
                new String[] {"--session-dir", sessionDir.toString()}));
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    /** 装配 app（构造器即装补全目录）。 */
    private static Assembly assemble(AgentSession session) {
        var chatScreen = new ChatScreen();
        var app = new PiTuiApp(new InteractiveMode(session), chatScreen,
            new KeybindingsManager(), new TuiEventDispatcher());
        return new Assembly(app, chatScreen);
    }

    /** 反射调私有 loadSlashItems 取目录（断言序/hint 需要全清单）。 */
    @SuppressWarnings("unchecked") // reflective call returns the catalog list
    private static List<com.pijava.tui.component.SlashCompleter.CommandItem> loadSlashItems(
            PiTuiApp app) throws Exception {
        Method m = PiTuiApp.class.getDeclaredMethod("loadSlashItems");
        m.setAccessible(true);
        return (List<com.pijava.tui.component.SlashCompleter.CommandItem>) m.invoke(app);
    }

    private static int indexOf(List<com.pijava.tui.component.SlashCompleter.CommandItem> items,
            String name) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("补全目录无条目 " + name);
    }

    /** 最后一个内置条目的下标（内置在前、无 {@code skill:} 且非模板注册名）。 */
    private static int lastBuiltinIndex(
            List<com.pijava.tui.component.SlashCompleter.CommandItem> items) {
        int template = indexOf(items, "zztemplate");
        for (int i = template - 1; i >= 0; i--) {
            if (!items.get(i).name().startsWith("skill:")) {
                return i;
            }
        }
        throw new AssertionError("模板前无内置条目");
    }

    private static PromptTemplate template(String name, String description,
            String argumentHint) {
        // filePath 不参与补全，从简给 null；sourceInfo 项目域（[p] 标签断言用）。
        return new PromptTemplate(name, description, "body", argumentHint, null,
            new SourceInfo("/proj/.pi-java/prompts/" + name + ".md", "local",
                SourceInfo.Scope.PROJECT, SourceInfo.Origin.TOP_LEVEL,
                "/proj/.pi-java/prompts"));
    }

    /** 占位技能（只要名字进目录）。 */
    private static final class SkillCommandsTuiTestSkill implements com.pijava.agent.skill.Skill {
        @Override public String name() { return "zzskill"; }
        @Override public String label() { return "zzskill"; }
        @Override public String description() { return "Z skill"; }
        @Override public String systemPrompt() { return ""; }
    }
}
