package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：custom-header 验收——吉祥物行集、副标题、仅 tui 挂槽、回内置臂。 */
class CustomHeaderExtensionTest {

    private static ExtensionUiHostEnv env() {
        var renders = new AtomicInteger();
        return new ExtensionUiHostEnv(() -> 80, () -> 24, renders::incrementAndGet,
            (slot, text) -> "[" + slot + "]" + text + "[/]");
    }

    @Test
    void mascotLinesMatchPiAssembly() {
        var lines = CustomHeaderExtension.piMascot((slot, text) -> "[" + slot + "]" + text + "[/]");

        assertThat(lines).hasSize(8);
        assertThat(lines.get(0)).isEmpty();
        assertThat(lines.get(1)).isEqualTo("     █[dim]▌[/]  █[dim]▌[/]");
        assertThat(lines.get(2)).isEqualTo("  [accent]" + "█".repeat(14) + "[/]");
        assertThat(lines.get(3)).isEqualTo(
            "     [accent]██[/]    [accent]██[/]");
        assertThat(lines.get(7)).isEmpty();
    }

    @Test
    void subtitleCarriesVersion() {
        var subtitle = CustomHeaderExtension.subtitle(
            (slot, text) -> "[" + slot + "]" + text + "[/]", Version.VERSION);

        assertThat(subtitle).isEqualTo(
            "[muted]   shitty coding agent[/][dim] v" + Version.VERSION + "[/]");
    }

    @Test
    void tuiSessionMountsHeaderWithNineLines() {
        var w = new W3Context();
        new CustomHeaderExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.headers()).hasSize(1);
        var component = w.ui.headers().get(0).create(env());
        var lines = component.render(80);
        assertThat(lines).hasSize(9);
        assertThat(lines.get(8)).contains("shitty coding agent")
            .contains(" v" + Version.VERSION);
        assertThat(component.render(80)).isEqualTo(lines);
    }

    @Test
    void nonTuiSessionDoesNotMount() {
        var w = new W3Context();
        w.mode = "rpc";
        new CustomHeaderExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.headers()).isEmpty();
    }

    @Test
    void commandRestoresBuiltinHeader() {
        var w = new W3Context();
        new CustomHeaderExtension().register(w.ctx);

        w.command("builtin-header", "");

        assertThat(w.ui.headers()).hasSize(1);
        assertThat(w.ui.headers().get(0)).isNull();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Built-in header restored");
    }

    @Test
    void blockGlyphs() {
        assertThat(CustomHeaderExtension.BLOCK).isEqualTo("█");
        assertThat(CustomHeaderExtension.PUPIL).isEqualTo("▌");
        assertThat(CustomHeaderExtension.SUBTITLE).isEqualTo("   shitty coding agent");
    }
}
