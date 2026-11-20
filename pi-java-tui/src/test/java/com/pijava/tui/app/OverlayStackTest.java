package com.pijava.tui.app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.screen.ScreenOverlay;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/64（L7 W4，pi {@code interactive-mode.ts:573/2799-2814}）：对话框栈——
 * 后开压栈、只挂栈顶、关栈顶回上一层、按引用关闭不误伤栈顶。
 */
class OverlayStackTest {

    @TempDir
    static Path sessionDir;

    private static class Stub implements ScreenOverlay {
        private final String name;
        private final AtomicBoolean done = new AtomicBoolean();
        private final List<String> log;

        Stub(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public boolean onKeyEvent(KeyEvent event) {
            return true;
        }

        @Override
        public boolean isDone() {
            return done.get();
        }

        @Override
        public void apply(AgentSession session, Consumer<AgentSession> switcher) {
            log.add(name);
        }

        @Override
        public Element render() {
            return null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static PiTuiApp app() {
        var session = AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
        return new PiTuiApp(new InteractiveMode(session), new ChatScreen(),
            new KeybindingsManager(), new TuiEventDispatcher());
    }

    @Test
    void overlaysStackAndOnlyTheTopIsCurrent() {
        var app = app();
        var a = new Stub("a", new java.util.ArrayList<>());
        var b = new Stub("b", new java.util.ArrayList<>());

        app.openOverlay(a);
        app.openOverlay(b);

        assertThat(app.currentOverlay()).isSameAs(b);
        assertThat(app.overlayDepth()).isEqualTo(2);
    }

    @Test
    void dismissingTheTopRevealsTheParent() {
        var app = app();
        var applied = new java.util.ArrayList<String>();
        var a = new Stub("a", applied);
        var b = new Stub("b", applied);

        app.openOverlay(a);
        app.openOverlay(b);
        app.dismissOverlay(b);

        assertThat(app.currentOverlay()).isSameAs(a);
        assertThat(app.overlayDepth()).isEqualTo(1);
        assertThat(applied).containsExactly("b");
    }

    @Test
    void dismissingABuriedOverlayKeepsTheTop() {
        var app = app();
        var a = new Stub("a", new java.util.ArrayList<>());
        var b = new Stub("b", new java.util.ArrayList<>());

        app.openOverlay(a);
        app.openOverlay(b);
        app.dismissOverlay(a);

        assertThat(app.currentOverlay()).isSameAs(b);
        assertThat(app.overlayDepth()).isEqualTo(1);
    }

    /** 完成栈顶 ⇒ 弹栈后 apply；apply 链式开新屏时新屏留在栈上。 */
    @Test
    void finishingTheTopPopsThenApplies() {
        var app = app();
        var applied = new java.util.ArrayList<String>();
        var next = new Stub("next", applied);
        var a = new Stub("a", applied) {
            @Override
            public void apply(AgentSession session, Consumer<AgentSession> switcher) {
                super.apply(session, switcher);
                app.openOverlay(next);
            }
        };

        app.openOverlay(a);
        ((Stub) a).done.set(true);
        app.closeCompletedOverlay();

        assertThat(applied).containsExactly("a");
        assertThat(app.currentOverlay()).isSameAs(next);
        assertThat(app.overlayDepth()).isEqualTo(1);
    }

    @Test
    void anEmptyStackYieldsNull() {
        var app = app();
        var a = new Stub("a", new java.util.ArrayList<>());

        app.openOverlay(a);
        app.dismissOverlay(a);

        assertThat(app.currentOverlay()).isNull();
        assertThat(app.overlayDepth()).isZero();
    }
}
