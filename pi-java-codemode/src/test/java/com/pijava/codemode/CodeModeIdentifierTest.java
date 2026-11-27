package com.pijava.codemode;

import static com.pijava.codemode.api.CodeModeIdentifier.toIdentifier;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.codemode.api.CodeModeIdentifier;
import org.junit.jupiter.api.Test;

/** Tests for {@link CodeModeIdentifier}. */
class CodeModeIdentifierTest {
    @Test
    void keepsValidIdentifierCharacters() {
        assertThat(toIdentifier("mcp__docs__search")).isEqualTo("mcp__docs__search");
        assertThat(toIdentifier("a1$_Z")).isEqualTo("a1$_Z");
    }

    @Test
    void replacesInvalidCharacters() {
        assertThat(toIdentifier("my-tool")).isEqualTo("my_tool");
        assertThat(toIdentifier("a.b c")).isEqualTo("a_b_c");
    }

    @Test
    void replacesInvalidFirstCharacter() {
        assertThat(toIdentifier("1tool")).isEqualTo("_tool");
        assertThat(toIdentifier("-tool")).isEqualTo("_tool");
    }

    @Test
    void returnsUnderscoreForEmptyInput() {
        assertThat(toIdentifier("")).isEqualTo("_");
    }
}
