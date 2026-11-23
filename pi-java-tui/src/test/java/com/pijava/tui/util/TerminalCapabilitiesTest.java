package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.pijava.tui.util.TerminalCapabilities.ImageProtocol;

/** docs/26 §4.1（K35）：终端能力探测逐臂断言。 */
class TerminalCapabilitiesTest {

    @AfterEach
    void resetCache() {
        TerminalCapabilities.resetForTest();
    }

    private static Map<String, String> env(String... pairs) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void tmuxDisablesImages() {
        var caps = TerminalCapabilities.detect(
            env("TMUX", "1", "TERM", "xterm-256color", "COLORTERM", "truecolor"), "Linux");
        assertThat(caps.images()).isNull();
        assertThat(caps.trueColor()).isTrue();
    }

    @Test
    void screenDisablesHyperlinks() {
        var caps = TerminalCapabilities.detect(env("TERM", "screen-256color"), "Linux");
        assertThat(caps.images()).isNull();
        assertThat(caps.hyperlinks()).isFalse();
    }

    @Test
    void herdrUsesKittyAndTrueColorHint() {
        var caps = TerminalCapabilities.detect(env("TERM_PROGRAM", "Herdr", "TERM", "xterm"), "Linux");
        assertThat(caps.images()).isEqualTo(ImageProtocol.KITTY);
        assertThat(caps.trueColor()).isFalse();
        assertThat(caps.hyperlinks()).isTrue();
    }

    @Test
    void rexForcesTrueColor() {
        var caps = TerminalCapabilities.detect(env("TERM_PROGRAM", "rex"), "Linux");
        assertThat(caps.images()).isEqualTo(ImageProtocol.KITTY);
        assertThat(caps.trueColor()).isTrue();
        assertThat(caps.hyperlinks()).isTrue();
    }

    @Test
    void orcaRequiresImageProtocolAdvertisement() {
        var caps = TerminalCapabilities.detect(
            env("TERM_PROGRAM", "Orca", "ORCA_IMAGE_PROTOCOL", "kitty"), "Linux");
        assertThat(caps.images()).isEqualTo(ImageProtocol.KITTY);
        assertThat(caps.hyperlinks()).isTrue();
    }

    @Test
    void kittyGhosttyWeztermWarpArm() {
        assertThat(TerminalCapabilities.detect(env("KITTY_WINDOW_ID", "1"), "Linux").images())
            .isEqualTo(ImageProtocol.KITTY);
        assertThat(TerminalCapabilities.detect(env("TERM_PROGRAM", "ghostty"), "Linux").images())
            .isEqualTo(ImageProtocol.KITTY);
        assertThat(TerminalCapabilities.detect(env("WEZTERM_PANE", "0"), "Linux").images())
            .isEqualTo(ImageProtocol.KITTY);
        assertThat(TerminalCapabilities.detect(env("WARP_SESSION_ID", "s"), "Linux").images())
            .isEqualTo(ImageProtocol.KITTY);
    }

    @Test
    void itermUsesIterm2AndWindowsTerminalStaysText() {
        var iterm = TerminalCapabilities.detect(env("TERM_PROGRAM", "iTerm.app"), "macOS");
        assertThat(iterm.images()).isEqualTo(ImageProtocol.ITERM2);
        assertThat(iterm.trueColor()).isTrue();

        var wt = TerminalCapabilities.detect(env("WT_SESSION", "x"), "Windows 11");
        assertThat(wt.images()).isNull();
        assertThat(wt.trueColor()).isTrue();
        assertThat(wt.hyperlinks()).isTrue();
    }

    @Test
    void alacrittyThreeArms() {
        var byWindowId = TerminalCapabilities.detect(
            env("TERM", "xterm-256color", "ALACRITTY_WINDOW_ID", "1"), "Linux");
        assertThat(byWindowId.images()).isNull();
        assertThat(byWindowId.trueColor()).isTrue();
        assertThat(byWindowId.hyperlinks()).isTrue();

        var byTerm = TerminalCapabilities.detect(env("TERM", "alacritty"), "Linux");
        assertThat(byTerm.hyperlinks()).isTrue();

        var byProgram = TerminalCapabilities.detect(env("TERM_PROGRAM", "alacritty"), "Linux");
        assertThat(byProgram.hyperlinks()).isTrue();
    }

    @Test
    void vscodeZedJetbrainsAndWindowsConsole() {
        var vscode = TerminalCapabilities.detect(env("TERM_PROGRAM", "vscode"), "Linux");
        assertThat(vscode.images()).isNull();
        assertThat(vscode.hyperlinks()).isTrue();

        var zed = TerminalCapabilities.detect(env("TERM_PROGRAM", "zed"), "Linux");
        assertThat(zed.hyperlinks()).isTrue();

        var jetbrains = TerminalCapabilities.detect(
            env("TERMINAL_EMULATOR", "JetBrains-JediTerm"), "Linux");
        assertThat(jetbrains.trueColor()).isTrue();
        assertThat(jetbrains.hyperlinks()).isFalse();

        var winConsole = TerminalCapabilities.detect(env(), "Windows 11");
        assertThat(winConsole.images()).isNull();
        assertThat(winConsole.trueColor()).isTrue();
        assertThat(winConsole.hyperlinks()).isFalse();
    }

    @Test
    void unknownTerminalUsesTrueColorHintOnly() {
        var hinted = TerminalCapabilities.detect(env("TERM", "xterm-256color", "COLORTERM", "24bit"), "Linux");
        assertThat(hinted.trueColor()).isTrue();
        assertThat(hinted.hyperlinks()).isFalse();

        var direct = TerminalCapabilities.detect(env("TERM", "xterm-direct"), "Linux");
        assertThat(direct.trueColor()).isTrue();

        var plain = TerminalCapabilities.detect(env("TERM", "xterm"), "Linux");
        assertThat(plain.trueColor()).isFalse();
        assertThat(plain.hyperlinks()).isFalse();
    }

    @Test
    void piOverridesWinOverDetection() {
        var noTrueColor = TerminalCapabilities.detect(
            env("TERM_PROGRAM", "rex", "PI_TRUE_COLOR", "0"), "Linux");
        assertThat(noTrueColor.trueColor()).isFalse();

        var hyperlinksOn = TerminalCapabilities.detect(
            env("TERM", "xterm", "PI_HYPERLINKS", "1"), "Linux");
        assertThat(hyperlinksOn.hyperlinks()).isTrue();

        var noImages = TerminalCapabilities.detect(
            env("KITTY_WINDOW_ID", "1", "PI_IMAGE_PROTOCOL", "none"), "Linux");
        assertThat(noImages.images()).isNull();

        var itermOverride = TerminalCapabilities.detect(
            env("TERM", "xterm", "PI_IMAGE_PROTOCOL", "iterm2"), "Linux");
        assertThat(itermOverride.images()).isEqualTo(ImageProtocol.ITERM2);
    }

    @Test
    void getCachesAndResets() {
        TerminalCapabilities.resetForTest();
        assertThat(TerminalCapabilities.get()).isSameAs(TerminalCapabilities.get());
        TerminalCapabilities.resetForTest();
        assertThat(TerminalCapabilities.get()).isNotNull();
    }
}
