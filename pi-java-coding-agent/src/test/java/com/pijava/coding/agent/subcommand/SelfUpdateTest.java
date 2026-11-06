package com.pijava.coding.agent.subcommand;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W4（B277b）：{@code pi-java update} 自更新面——pi
 * {@code getConfiguredSelfUpdateArgv}（config.ts:341-346）、
 * {@code makeConfiguredSelfUpdateCommand}（:349-359）。
 */
class SelfUpdateTest {

    @Test
    void envValueSplitsOnWhitespaceAndBeatsSetting() {
        var argv = SelfUpdate.configuredSelfUpdateArgv(
            List.of("from-setting", "x"), "  mise run update  ");

        // pi 不解析引号，纯空白切分（config.ts:341-346）。
        assertThat(argv).containsExactly("mise", "run", "update");
    }

    @Test
    void settingIsUsedWhenEnvIsAbsent() {
        assertThat(SelfUpdate.configuredSelfUpdateArgv(List.of("install.sh", "--force"), null))
            .containsExactly("install.sh", "--force");
        assertThat(SelfUpdate.configuredSelfUpdateArgv(List.of("install.sh"), "   "))
            .containsExactly("install.sh");
    }

    @Test
    void neitherSourceYieldsNull() {
        assertThat(SelfUpdate.configuredSelfUpdateArgv(null, null)).isNull();
        assertThat(SelfUpdate.configuredSelfUpdateArgv(List.of(), "")).isNull();
    }

    @Test
    void placeholdersAreSubstituted() {
        var argv = SelfUpdate.substitutePlaceholders(
            List.of("deploy", "{packageName}", "{version}", "{installSpec}"), "1.4.2");

        assertThat(argv).containsExactly("deploy", "pi-java", "1.4.2", "pi-java@1.4.2");
    }
}
