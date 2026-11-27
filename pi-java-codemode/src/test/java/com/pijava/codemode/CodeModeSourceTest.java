package com.pijava.codemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pijava.codemode.api.CodeModeSource;
import com.pijava.codemode.api.CodeModeSourceException;
import org.junit.jupiter.api.Test;

/** Tests for {@link CodeModeSource}. */
class CodeModeSourceTest {
    @Test
    void returnsPlainCodeUnchanged() throws Exception {
        CodeModeSource.Parsed first = CodeModeSource.parse("text('hi')");
        assertThat(first.code()).isEqualTo("text('hi')");
        assertThat(first.options().maxOutputTokens()).isNull();
        assertThat(first.options().timeoutMs()).isNull();

        String input = "// just a comment\nreturn 1";
        CodeModeSource.Parsed second = CodeModeSource.parse(input);
        assertThat(second.code()).isEqualTo(input);
        assertThat(second.options().maxOutputTokens()).isNull();
        assertThat(second.options().timeoutMs()).isNull();
    }

    @Test
    void parsesOptionsLineAndKeepsLineNumbers() throws Exception {
        CodeModeSource.Parsed first =
                CodeModeSource.parse("// @options: {\"timeout_ms\": 10}\nconst a = 1;\ntext(a)");
        assertThat(first.code()).isEqualTo("\nconst a = 1;\ntext(a)");
        assertThat(first.options().timeoutMs()).isEqualTo(10L);

        CodeModeSource.Parsed second =
                CodeModeSource.parse("  // @options:{\"max_output_tokens\":0,\"timeout_ms\":1500}\r\ntext(1)");
        assertThat(second.options().maxOutputTokens()).isZero();
        assertThat(second.options().timeoutMs()).isEqualTo(1500L);

        CodeModeSource.Parsed third = CodeModeSource.parse("// @options: {}\ntext(1)");
        assertThat(third.code()).isEqualTo("\ntext(1)");
        assertThat(third.options().maxOutputTokens()).isNull();
        assertThat(third.options().timeoutMs()).isNull();
    }

    @Test
    void onlyTreatsFirstLineAsOptionsLine() throws Exception {
        assertThat(CodeModeSource.parse("// @optionsx {}\ntext(1)").options().timeoutMs()).isNull();
        assertThat(CodeModeSource.parse("text(\"// @options: {}\")").options().timeoutMs()).isNull();
    }

    @Test
    void rejectsMisplacedOptionsLine() {
        String[] inputs = {
            "// count things\n// @options: {\"timeout_ms\": 1000}\nwhile (true) {}",
            "\n  // @options: {\"timeout_ms\": 1000}\nwhile (true) {}",
            "text(1)\r\n// @options: {\"timeout_ms\": 1}",
            "// @options: {\"timeout_ms\": 1}\ntext(1)\n// @options: {\"timeout_ms\": 2}",
        };
        int[] lines = {2, 2, 2, 3};
        for (int i = 0; i < inputs.length; i++) {
            String input = inputs[i];
            String expected = "The // @options: line must be the first line of the input; "
                    + "found one on line " + lines[i];
            assertThatThrownBy(() -> CodeModeSource.parse(input))
                    .isInstanceOf(CodeModeSourceException.class)
                    .hasMessage(expected);
        }
    }

    @Test
    void rejectsEmptyInputAndInvalidOptions() {
        assertMessage("", "Expected JavaScript source text (non-empty). Provide JS only, optionally with a first "
                + "line `// @options: {\"max_output_tokens\": 1000}`.");
        assertMessage("  \n", "Expected JavaScript source text (non-empty). Provide JS only, optionally with a "
                + "first line `// @options: {\"max_output_tokens\": 1000}`.");
        assertMessage("// @options:\ntext(1)",
                "@options must be a JSON object with supported fields `max_output_tokens` and `timeout_ms`");
        assertMessageContains("// @options: {timeout_ms: 1}\ntext(1)",
                "@options must be valid JSON with supported fields `max_output_tokens` and `timeout_ms`: ");
        assertMessage("// @options: [1]\ntext(1)",
                "@options must be a JSON object with supported fields `max_output_tokens` and `timeout_ms`");
        assertMessage("// @options: {\"yield\": 1}\ntext(1)",
                "@options only supports `max_output_tokens` and `timeout_ms`; got `yield`");
        assertMessage("// @options: {\"max_output_tokens\": 1.5}\ntext(1)",
                "@options field `max_output_tokens` must be a non-negative safe integer");
        assertMessageContains("// @options: {\"timeout_ms\": 0}\ntext(1)",
                "@options field `timeout_ms` must be a positive integer up to 2147483647");
        assertMessage("// @options: {\"timeout_ms\": 1}",
                "The @options line must be followed by JavaScript source on subsequent lines");
        assertMessage("// @options: {\"timeout_ms\": 1}\n  \n",
                "The @options line must be followed by JavaScript source on subsequent lines");
    }

    private static void assertMessage(String input, String expected) {
        assertThatThrownBy(() -> CodeModeSource.parse(input))
                .isInstanceOf(CodeModeSourceException.class)
                .hasMessage(expected);
    }

    private static void assertMessageContains(String input, String expected) {
        assertThatThrownBy(() -> CodeModeSource.parse(input))
                .isInstanceOf(CodeModeSourceException.class)
                .hasMessageContaining(expected);
    }
}
