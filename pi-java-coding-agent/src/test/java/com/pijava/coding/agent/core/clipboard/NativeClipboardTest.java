package com.pijava.coding.agent.core.clipboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** docs/26 §4.2（K34）：平台候选链与回退语义。 */
class NativeClipboardTest {

    @AfterEach
    void reset() {
        NativeClipboard.overrideForTest(null);
    }

    private static Map<String, String> env(String... pairs) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static List<String> names(List<List<String>> candidates) {
        return candidates.stream()
            .map(command -> command.isEmpty() ? "awt" : command.get(0))
            .toList();
    }

    @Test
    void windowsPrefersPowerShellThenClipThenAwt() {
        assertThat(names(NativeClipboard.commandCandidates(env(), "Windows 11")))
            .containsExactly("powershell", "clip", "awt");
    }

    @Test
    void macUsesPbcopy() {
        assertThat(names(NativeClipboard.commandCandidates(env(), "Mac OS X")))
            .containsExactly("pbcopy", "awt");
    }

    @Test
    void linuxFallsBackThroughWlCopyXclipXsel() {
        assertThat(names(NativeClipboard.commandCandidates(env(), "Linux")))
            .containsExactly("wl-copy", "xclip", "xsel", "awt");
    }

    @Test
    void termuxTakesPriority() {
        var candidates = NativeClipboard.commandCandidates(
            env("TERMUX_VERSION", "0.118", "PREFIX", "/data/data/com.termux/files/usr"), "Linux");
        assertThat(names(candidates)).containsExactly("termux-clipboard-set", "awt");
    }

    @Test
    void xclipCommandCarriesSelectionArgument() {
        var candidates = NativeClipboard.commandCandidates(env(), "Linux");
        assertThat(candidates.get(1)).containsExactly("xclip", "-selection", "clipboard");
        assertThat(candidates.get(2)).containsExactly("xsel", "--clipboard", "--input");
    }

    @Test
    void copyStopsAtFirstSuccess() {
        var attempted = new ArrayList<List<String>>();
        NativeClipboard.overrideForTest((command, stdin) -> attempted.add(command));
        try {
            NativeClipboard.copy("text");
            assertThat(attempted).hasSize(1);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void copyFallsThroughToNextCandidate() {
        var attempted = new ArrayList<List<String>>();
        NativeClipboard.overrideForTest((command, stdin) -> {
            attempted.add(command);
            if (attempted.size() == 1) {
                throw new IOException("missing tool");
            }
        });
        try {
            NativeClipboard.copy("text");
            assertThat(attempted).hasSize(2);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void copyThrowsWhenEveryCandidateFails() {
        var attempted = new ArrayList<List<String>>();
        NativeClipboard.overrideForTest((command, stdin) -> {
            attempted.add(command);
            throw new IOException("boom");
        });
        int expected = NativeClipboard.commandCandidates(
            System.getenv(), System.getProperty("os.name", "")).size();
        assertThatThrownBy(() -> NativeClipboard.copy("text"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("boom");
        assertThat(attempted).hasSize(expected);
    }
}
