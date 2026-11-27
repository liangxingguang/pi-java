package com.pijava.coding.agent.codemode;

import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.extension.ToolRendererResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Text-level renderer tests aligned with {@code renderer.ts}. */
class CodemodeRendererTest {
    private static final ToolRendererResolver RESOLVER = new CodemodeToolRenderer();

    @Test
    void callLinePreviewsCodeAndMarksInvalidArg() {
        var renderer = RESOLVER.resolve("codemode", () -> null);
        assertThat(renderer.renderCall("codemode", Map.of("code", "line1\nline2"), false))
            .containsExactly("codemode", "line1", "line2");
        assertThat(renderer.renderCall("codemode", Map.of(), false))
            .containsExactly("codemode", "[invalid arg]");
        assertThat(RESOLVER.resolve("read", () -> null)).isNull();
    }

    @Test
    void resultDropsScriptHeaderAndFoldsLongOutput() {
        var renderer = RESOLVER.resolve("codemode", () -> null);
        String text = "Script completed\nWall time 1.2 seconds\nOutput:\nline1\nline2\nline3\nline4\nline5\nline6";
        assertThat(renderer.renderResult("codemode", text, false, false))
            .containsExactly("line1", "line2", "line3", "line4", "line5",
                "... (1 more lines, to expand)");
    }

    @Test
    void nestedCallsFoldEarlierRowsAndShowStatusIcons() {
        List<CodemodeDetails.NestedCall> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            calls.add(new CodemodeDetails.NestedCall("id/" + i, "tool" + i, "{}", "ok"));
        }
        List<String> lines = CodemodeToolRenderer.renderNestedCalls(calls, false);
        assertThat(lines).hasSize(9);
        assertThat(lines.get(0)).startsWith("... (1 earlier calls, to expand)");
        assertThat(lines.get(1)).startsWith("OK tool1");
        assertThat(CodemodeToolRenderer.timerLabel(2500, true)).isEqualTo("Took 2.5s");
    }

    @Test
    void nestedCallErrorsAppearOnlyWhenExpanded() {
        CodemodeDetails.NestedCall call = new CodemodeDetails.NestedCall("id", "read", "{}",
            "error", 12.0, "boom\nsecond", null);
        assertThat(CodemodeToolRenderer.renderNestedCalls(List.of(call), false).get(0)).doesNotContain("boom");
        assertThat(CodemodeToolRenderer.renderNestedCalls(List.of(call), true))
            .anyMatch(line -> line.contains("boom\n    second"));
    }
}
