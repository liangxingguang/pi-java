package com.pijava.coding.agent.theme;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.core.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ThemeJsonValidator} 测试（pi theme-schema.ts 对位，批 21/docs/32 §7）。
 */
class ThemeJsonValidatorTest {

    private static JsonNode parse(String json) {
        try {
            return Json.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String validColors() {
        // 51 必填令牌全给 ""（终端默认是合法值）。
        var colors = new StringBuilder();
        for (var token : ThemeToken.values()) {
            if (token.fallback() == null) {
                if (colors.length() > 0) {
                    colors.append(',');
                }
                colors.append('"').append(token.piName()).append("\":\"\"");
            }
        }
        return colors.toString();
    }

    @Test
    void acceptsMinimalValidTheme() {
        var theme = ThemeJsonValidator.validate("t",
            parse("{\"name\":\"t\",\"colors\":{" + validColors() + "}}"));
        assertThat(theme.name()).isEqualTo("t");
        assertThat(theme.appearance()).isNull();
        assertThat(theme.export()).isNull();
        assertThat(theme.colors()).hasSize(51);
    }

    @Test
    void rejectsNameWithSlashBeforeSchemaCheck() {
        assertThatThrownBy(() -> ThemeJsonValidator.validate("t",
                parse("{\"name\":\"a/b\",\"colors\":{}}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid theme name \"a/b\": theme names cannot contain \"/\""
                + " because it is reserved for automatic light/dark theme settings.");
    }

    @Test
    void listsMissingRequiredTokensSorted() {
        var error = org.assertj.core.api.Assertions.catchThrowable(
            () -> ThemeJsonValidator.validate("my-theme",
                parse("{\"name\":\"my-theme\",\"colors\":{\"accent\":\"#fff\"}}")));
        assertThat(error).isInstanceOf(IllegalArgumentException.class);
        assertThat(error.getMessage())
            .startsWith("Invalid theme \"my-theme\":\n\nMissing required color tokens:\n  - ")
            .contains("\n\nPlease add these colors to your theme's \"colors\" object."
                + "\nSee the built-in themes (dark.json, light.json) for reference values.");
        // 缺失清单排序（TreeSet，ASCII 序）：accent 已给 ⇒ 首条 bashMode（"ba"<"bo"）。
        assertThat(error.getMessage()).contains("Missing required color tokens:\n  - bashMode\n  - border\n");
        // 5 个可选令牌不出现在缺失清单。
        assertThat(error.getMessage()).doesNotContain("scrollbarTrack")
            .doesNotContain("scrollbarThumb").doesNotContain("searchMatchBg")
            .doesNotContain("searchMatchText").doesNotContain("thinkingMax");
    }

    @Test
    void rejectsUnknownTopLevelAndTokenKeys() {
        var error = org.assertj.core.api.Assertions.catchThrowable(
            () -> ThemeJsonValidator.validate("t",
                parse("{\"name\":\"t\",\"bogus\":1,\"colors\":{" + validColors()
                    + ",\"notAToken\":\"#fff\"}}")));
        assertThat(error.getMessage()).contains("unexpected property \"bogus\"")
            .contains("unexpected token \"notAToken\"")
            .contains("Other errors:");
    }

    @Test
    void rejectsBadColorValueTypes() {
        var error = org.assertj.core.api.Assertions.catchThrowable(
            () -> ThemeJsonValidator.validate("t",
                parse("{\"name\":\"t\",\"colors\":{\"accent\":300}}")));
        assertThat(error.getMessage()).contains("/colors/accent");
    }

    @Test
    void validatesExportSectionKeys() {
        var theme = ThemeJsonValidator.validate("t",
            parse("{\"name\":\"t\",\"colors\":{" + validColors()
                + "},\"export\":{\"pageBg\":\"#101018\"}}"));
        assertThat(theme.export().pageBg()).isEqualTo("#101018");
        assertThat(theme.export().cardBg()).isNull();

        assertThatThrownBy(() -> ThemeJsonValidator.validate("t",
                parse("{\"name\":\"t\",\"colors\":{" + validColors()
                    + "},\"export\":{\"rogue\":\"#fff\"}}")))
            .hasMessageContaining("unexpected property \"rogue\"");
    }

    @Test
    void acceptsVarsAndIntegerColorValues() {
        var theme = ThemeJsonValidator.validate("t",
            parse("{\"name\":\"t\",\"vars\":{\"brand\":\"#123456\"},\"colors\":{"
                + validColors().replace("\"accent\":\"\"", "\"accent\":\"brand\"")
                + ",\"scrollbarTrack\":12}}"));
        assertThat(theme.vars()).containsEntry("brand", "#123456");
        assertThat(theme.colors()).containsEntry("scrollbarTrack", 12);
    }

    @Test
    void rejectsBadAppearanceLiteral() {
        assertThatThrownBy(() -> ThemeJsonValidator.validate("t",
                parse("{\"name\":\"t\",\"appearance\":\"sepia\",\"colors\":{"
                    + validColors() + "}}")))
            .hasMessageContaining("/appearance");
    }
}
