package com.pijava.tui.app;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.SettingsScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B244（docs/12 §3.3/§3.5）：skill 补全目录装配（受 {@code enableSkillCommands}
 * 门）＋设置屏 "Skill commands" 字段。不跑 runner —— 补全目录在
 * {@link PiTuiApp} 构造器装配，直接驱动 {@link ChatScreen} 补全 API。
 */
class SkillCommandsTuiTest {

    @TempDir
    Path sessionDir;

    @Test
    void slashCatalogListsSkillCommandsWhenEnabled() throws Exception {
        try (var session = createSession()) {
            session.harness().skillManager().register(
                skill("test-skill", "Test skill"));
            var chatScreen = assemble(session);

            chatScreen.insertText("/skill:tes");

            assertThat(chatScreen.completerActive())
                .as("开关默认开 ⇒ skill:<name> 进补全目录")
                .isTrue();
            assertThat(chatScreen.applyCompletion()).isTrue();
            assertThat(chatScreen.inputText()).isEqualTo("/skill:test-skill");
        }
    }

    @Test
    void slashCatalogOmitsSkillCommandsWhenDisabled() throws Exception {
        try (var session = createSession()) {
            session.harness().skillManager().register(
                skill("test-skill", "Test skill"));
            session.services().settings().accessors().setEnableSkillCommands(false);
            var chatScreen = assemble(session);

            chatScreen.insertText("/skill:tes");

            assertThat(chatScreen.completerActive())
                .as("开关关 ⇒ 补全目录无 skill 条目（内置命令无一前缀命中）")
                .isFalse();
        }
    }

    @Test
    void settingsScreenHasSkillCommandsFieldThatCyclesAccessors() throws Exception {
        try (var session = createSession()) {
            var screen = new SettingsScreen(session);
            var index = skillCommandsIndex(screen);
            setSelectedField(screen, index);

            // 缺省 true ⇒ 首次 Enter 落到 false；再 Enter 回 true（往返）。
            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
            assertThat(session.services().settings().accessors()
                    .getEnableSkillCommands())
                .as("cycle 写 accessors（true→false）")
                .isFalse();

            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
            assertThat(session.services().settings().accessors()
                    .getEnableSkillCommands())
                .as("再 cycle 往返回 true")
                .isTrue();
        }
    }

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

    /** 装配 app（构造器即装补全目录），返回挂在 app 上的 ChatScreen。 */
    private static ChatScreen assemble(AgentSession session) {
        var chatScreen = new ChatScreen();
        new PiTuiApp(new InteractiveMode(session), chatScreen,
            new KeybindingsManager(), new TuiEventDispatcher());
        return chatScreen;
    }

    /** 反射读 fields（批 12 起为实例装配）中 "Skill commands" 的下标（断言字段存在）。 */
    private static int skillCommandsIndex(SettingsScreen screen) throws Exception {
        Field fields = SettingsScreen.class.getDeclaredField("fields");
        fields.setAccessible(true);
        List<?> list = (List<?>) fields.get(screen);
        Method label = null;
        for (int i = 0; i < list.size(); i++) {
            var item = list.get(i);
            if (label == null) {
                label = item.getClass().getDeclaredMethod("label");
                label.setAccessible(true);
            }
            if ("Skill commands".equals(label.invoke(item))) {
                return i;
            }
        }
        throw new AssertionError("SettingsScreen.fields 无 \"Skill commands\" 字段");
    }

    private static void setSelectedField(SettingsScreen screen, int index)
            throws Exception {
        Field selected = SettingsScreen.class.getDeclaredField("selected");
        selected.setAccessible(true);
        selected.setInt(screen, index);
    }

    private static Skill skill(String name, String description) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return description; }
            @Override public String systemPrompt() { return ""; }
            @Override public SkillSource sourceInfo() { return SkillSource.PROJECT; }
        };
    }
}
