package com.pijava.evals.cli;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 批 32（docs/44 §6）：CLI 参数默认、env 回退与成对 provider/model 校验。 */
class EvalCliOptionsTest {

    @Test
    void defaultsToOneRunPerVariant() {
        var options = EvalCliOptions.parse(new String[0], Map.of());
        assertThat(options.runsPerVariant()).isEqualTo(1);
        assertThat(options.selectors()).isEmpty();
        assertThat(options.host()).isFalse();
        assertThat(options.hasModelSelection()).isFalse();
    }

    @Test
    void readsRunsFromEnvironment() {
        var options = EvalCliOptions.parse(new String[0],
            Map.of(EvalCliOptions.RUNS_ENV, "3"));
        assertThat(options.runsPerVariant()).isEqualTo(3);
    }

    @Test
    void explicitRunsWinOverEnvironment() {
        var options = EvalCliOptions.parse(
            new String[] {"--runs-per-variant", "2", "--host"},
            Map.of(EvalCliOptions.RUNS_ENV, "5"));
        assertThat(options.runsPerVariant()).isEqualTo(2);
        assertThat(options.host()).isTrue();
    }

    @Test
    void readsProviderAndModelFromEnvironment() {
        var options = EvalCliOptions.parse(new String[0],
            Map.of("PI_PROVIDER", "openai", "PI_MODEL", "gpt-4o"));
        assertThat(options.provider()).isEqualTo("openai");
        assertThat(options.model()).isEqualTo("gpt-4o");
        assertThat(options.hasModelSelection()).isTrue();
    }

    @Test
    void rejectsUnpairedProviderAndModel() {
        assertThatThrownBy(() -> EvalCliOptions.parse(
            new String[] {"--provider", "openai"}, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("together");
    }

    @Test
    void parsesSelectorsAndPattern() {
        var options = EvalCliOptions.parse(
            new String[] {"-t", "smoke.*", "set/case"}, Map.of());
        assertThat(options.selectors()).containsExactly("set/case");
        assertThat(options.testNamePattern()).isEqualTo("smoke.*");
    }

    @Test
    void rejectsUnknownOption() {
        assertThatThrownBy(() -> EvalCliOptions.parse(new String[] {"--bogus"}, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--bogus");
    }

    @Test
    void rejectsInvalidRunCount() {
        assertThatThrownBy(() -> EvalCliOptions.parse(
            new String[] {"--runs-per-variant", "0"}, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvalCliOptions.parse(
            new String[] {"--runs-per-variant", "many"}, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptySelectorsBehaveLikeNoFilter() {
        assertThat(EvalCliOptions.parse(new String[] {"--host"}, Map.of()).selectors())
            .isEqualTo(List.of());
    }
}
