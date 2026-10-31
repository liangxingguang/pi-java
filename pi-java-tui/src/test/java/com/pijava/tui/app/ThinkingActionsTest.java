package com.pijava.tui.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.slash.builtin.ThinkingCommands;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.component.ChatMessage;
import com.pijava.tui.component.MessageBubbleVisibility;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 34（K7/K9/K8）：三个此前落 default 死码的键动作接线对拍——pi
 * {@code cycleThinkingLevel}（:4527-4536）、{@code toggleThinkingBlockVisibility}
 * （:4589-4594）、{@code toggleToolOutputExpansion}（:4557/:4561）。
 */
class ThinkingActionsTest {

    @TempDir
    Path sessionDir;

    private AgentSession session;
    private ChatScreen chatScreen;
    private TuiEventDispatcher dispatcher;
    private PiTuiApp app;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
            session = null;
        }
        MessageBubbleVisibility.installToolsExpanded(null);
        MessageBubbleVisibility.install(null);
    }

    /** 内联装配（{@code runner == null}）：键经 onInlineEvent 直驱全局分派。 */
    private void newRig(String... extraArgs) throws Exception {
        var argv = new ArrayList<String>();
        argv.add("--session-dir");
        argv.add(sessionDir.toString());
        argv.addAll(List.of(extraArgs));
        session = AgentSession.create(ArgsParser.parse(argv.toArray(String[]::new)));
        chatScreen = new ChatScreen();
        dispatcher = new TuiEventDispatcher();
        app = new PiTuiApp(new InteractiveMode(session), chatScreen,
            new KeybindingsManager(), dispatcher);
    }

    private void press(KeyEvent event) {
        app.onInlineEvent(event);
        dispatcher.drain();
    }

    private static KeyEvent ctrl(char key) {
        return KeyEvent.ofChar(key, KeyModifiers.CTRL);
    }

    private static KeyEvent shiftTab() {
        return new KeyEvent(KeyCode.TAB, KeyModifiers.SHIFT, 0);
    }

    private List<String> systemLines() {
        return chatScreen.messages().stream()
            .filter(m -> m instanceof ChatMessage.System)
            .map(m -> ((ChatMessage.System) m).text())
            .toList();
    }

    /** ① 模型无 THINKING 能力 ⇒ 提示行且级不变（pi :4529-4530 逐字）。 */
    @Test
    void shiftTabOnNonThinkingModelShowsNotice() throws Exception {
        newRig("--provider", "mistral", "--model", "codestral-latest");
        var before = session.harness().getThinkingLevel();

        press(shiftTab());

        assertThat(systemLines())
            .contains("Current model does not support thinking");
        assertThat(session.harness().getThinkingLevel()).isEqualTo(before);
    }

    /** ② reasoning 模型 ⇒ 按可用集正向轮转＋状态行；末项回卷首项。 */
    @Test
    void shiftTabCyclesThinkingLevelAndWraps() throws Exception {
        newRig("--models", "gemini-2.5*");
        var levels = ThinkingCommands.availableLevels(session);
        assertThat(levels).isNotEmpty();
        session.setThinkingLevel(levels.get(0));

        press(shiftTab());
        assertThat(session.harness().getThinkingLevel()).isEqualTo(levels.get(1));
        assertThat(systemLines()).contains("Thinking level: " + levels.get(1).label());

        // 末项 ⇒ 回卷首项（(index+1)%size）。
        session.setThinkingLevel(levels.get(levels.size() - 1));
        press(shiftTab());
        assertThat(session.harness().getThinkingLevel()).isEqualTo(levels.get(0));
    }

    /** ③ 当前级不在可用集（目录变更后）⇒ 取首项（index=-1 兼容臂）。 */
    @Test
    void unknownCurrentLevelFallsBackToFirst() throws Exception {
        newRig("--models", "gemini-2.5*");
        var levels = ThinkingCommands.availableLevels(session);
        // max 通常不在 gemini 的映射集内（only off..high）⇒ index=-1。
        session.setThinkingLevel(
            com.pijava.ai.thinking.ModelThinkingLevel.parse("max").orElseThrow());

        press(shiftTab());

        assertThat(session.harness().getThinkingLevel()).isEqualTo(levels.get(0));
    }

    /** ④ Ctrl+T：设置项置反，现读缝随之为真/假，文案逐字（pi :4593）。 */
    @Test
    void ctrlTTogglesThinkingBlockVisibility() throws Exception {
        newRig("--models", "gemini-2.5*");

        press(ctrl('t'));
        assertThat(session.services().settings().accessors().getHideThinkingBlock())
            .isTrue();
        assertThat(systemLines()).contains("Thinking blocks: hidden");

        press(ctrl('t'));
        assertThat(session.services().settings().accessors().getHideThinkingBlock())
            .isFalse();
        assertThat(systemLines()).contains("Thinking blocks: visible");
    }

    /** ⑤ Ctrl+O：全局展开态翻转＋状态行；同态重复设置幂等短路（pi :4562）。 */
    @Test
    void ctrlOTogglesToolOutputExpansion() throws Exception {
        newRig("--models", "gemini-2.5*");

        press(ctrl('o'));
        assertThat(app.toolOutputExpanded).isTrue();
        assertThat(systemLines()).contains("Tool output: expanded");

        press(ctrl('o'));
        assertThat(app.toolOutputExpanded).isFalse();
        assertThat(systemLines()).contains("Tool output: collapsed");

        int before = systemLines().size();
        app.setToolsExpanded(false);    // 同态 ⇒ 短路，无新行
        assertThat(systemLines()).hasSize(before);
    }
}
