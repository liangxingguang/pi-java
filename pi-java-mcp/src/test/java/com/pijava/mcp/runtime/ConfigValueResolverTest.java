package com.pijava.mcp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code resolve-config-value.ts} 的 15 条规则逐条对齐。
 */
class ConfigValueResolverTest {

    /** Records the commands it ran and answers from a table. */
    private static final class FakeRunner implements CommandRunner {

        private final Map<String, String> results = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        private FakeRunner answers(String command, String value) {
            results.put(command, value);
            return this;
        }

        @Override
        public String run(String command) {
            calls.add(command);
            return results.get(command);
        }
    }

    // -------------------------------------------------------------- templates

    @Test
    void resolvesBothDollarFormsAndBareNames() {
        var resolver = new ConfigValueResolver();
        var env = Map.of("A", "1", "BC", "2");
        assertThat(resolver.resolve("${A}-$BC", env)).isEqualTo("1-2");
    }

    @Test
    void escapesDollarAndBangWithDollar() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolve("$$A", Map.of("A", "x"))).isEqualTo("$A");
        assertThat(resolver.resolve("$!A", Map.of("A", "x"))).isEqualTo("!A");
    }

    @Test
    void keepsAnInvalidBraceReferenceVerbatim() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolve("${1BAD}", Map.of())).isEqualTo("${1BAD}");
    }

    @Test
    void leavesAnUnclosedBraceAloneAndResolvesWhatFollows() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolve("${A", Map.of("A", "x"))).isEqualTo("${A");
        assertThat(resolver.resolve("${A$x", Map.of("A", "a", "x", "b"))).isEqualTo("${Ab");
    }

    @Test
    void leavesAStrayDollarAlone() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolve("100$", Map.of())).isEqualTo("100$");
        assertThat(resolver.resolve("$ x", Map.of())).isEqualTo("$ x");
    }

    @Test
    void failsTheWholeTemplateWhenOneVariableIsMissing() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolve("a${MISSING_ONE}b", Map.of())).isNull();
    }

    @Test
    void treatsAnEmptyEnvironmentValueAsUnset() {
        var resolver = new ConfigValueResolver();
        var env = new LinkedHashMap<String, String>();
        env.put("MCP_TEST_BLANK_SLOT", "");
        assertThat(resolver.resolve("${MCP_TEST_BLANK_SLOT}", env)).isNull();
    }

    @Test
    void fallsBackToTheProcessEnvironmentAfterABlankMapValue() {
        var resolver = new ConfigValueResolver();
        var env = new LinkedHashMap<String, String>();
        env.put("PATH", "");
        assertThat(resolver.resolve("${PATH}", env)).isEqualTo(System.getenv("PATH"));
    }

    // --------------------------------------------------------------- commands

    @Test
    void runsCommandsAndTrimsTheirOutput() {
        var runner = new FakeRunner().answers("echo hi", "  hi\n");
        var resolver = new ConfigValueResolver(runner);
        assertThat(resolver.resolve("!echo hi", Map.of())).isEqualTo("hi");
    }

    @Test
    void treatsBlankCommandOutputAsUnresolved() {
        var runner = new FakeRunner().answers("nothing", "   \n");
        assertThat(new ConfigValueResolver(runner).resolve("!nothing", Map.of())).isNull();
    }

    @Test
    void cachesCommandResultsButNotTheUncachedEntryPoint() {
        var runner = new FakeRunner().answers("whoami", "me");
        var resolver = new ConfigValueResolver(runner);
        resolver.resolve("!whoami", Map.of());
        resolver.resolve("!whoami", Map.of());
        assertThat(runner.calls).hasSize(1);
        resolver.resolveUncached("!whoami", Map.of());
        assertThat(runner.calls).hasSize(2);
    }

    @Test
    void clearingTheCacheMakesTheCommandRunAgain() {
        var runner = new FakeRunner().answers("whoami", "me");
        var resolver = new ConfigValueResolver(runner);
        resolver.resolve("!whoami", Map.of());
        resolver.clearCache();
        resolver.resolve("!whoami", Map.of());
        assertThat(runner.calls).hasSize(2);
    }

    @Test
    void cachesAFailedCommandToo() {
        var runner = new FakeRunner();
        var resolver = new ConfigValueResolver(runner);
        assertThat(resolver.resolve("!missing", Map.of())).isNull();
        assertThat(resolver.resolve("!missing", Map.of())).isNull();
        assertThat(runner.calls).hasSize(1);
    }

    // --------------------------------------------------------------- orThrow

    @Test
    void namesTheShellCommandThatFailed() {
        var resolver = new ConfigValueResolver(new FakeRunner());
        assertThatThrownBy(() -> resolver.resolveOrThrow("!secret-tool lookup k", "API key", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to resolve API key from shell command: secret-tool lookup k");
    }

    @Test
    void namesTheSingleMissingVariable() {
        var resolver = new ConfigValueResolver();
        assertThatThrownBy(() -> resolver.resolveOrThrow("${NOPE_ONE}", "API key", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to resolve API key from environment variable: NOPE_ONE");
    }

    @Test
    void namesEveryMissingVariableInOrder() {
        var resolver = new ConfigValueResolver();
        assertThatThrownBy(() -> resolver.resolveOrThrow("${NOPE_B}${NOPE_A}", "API key", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to resolve API key from environment variables: NOPE_B, NOPE_A");
    }

    /**
     * {@code ${1BAD}} 不是引用、是一个字面量，所以 {@code resolveConfigValueOrThrow} 把它
     * **原样返回**而不是抛错——pi 的裸 {@code Failed to resolve <desc>} 兜底因此不可达
     * （{@code resolve-config-value.ts:250}）。
     */
    @Test
    void returnsAnInvalidBraceReferenceLiterally() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolveOrThrow("${1BAD}", "API key", Map.of())).isEqualTo("${1BAD}");
    }

    @Test
    void returnsALiteralUntouched() {
        var resolver = new ConfigValueResolver();
        assertThat(resolver.resolveOrThrow("sk-literal", "API key", Map.of())).isEqualTo("sk-literal");
    }

    // --------------------------------------------------------------- headers

    @Test
    void resolveHeadersDropsWhatResolvesToNothing() {
        var resolver = new ConfigValueResolver();
        var headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer ${TOKEN}");
        headers.put("Empty", "${MCP_TEST_BLANK_SLOT}");
        headers.put("Blank", "plain");
        var env = new LinkedHashMap<String, String>();
        env.put("TOKEN", "t");
        env.put("MCP_TEST_BLANK_SLOT", "");

        var resolved = resolver.resolveHeaders(headers, env);
        assertThat(resolved).containsEntry("Authorization", "Bearer t");
        assertThat(resolved).doesNotContainKey("Empty");
        assertThat(resolved).containsEntry("Blank", "plain");
    }

    @Test
    void resolveHeadersIsNullWithoutHeaders() {
        assertThat(new ConfigValueResolver().resolveHeaders(null, Map.of())).isNull();
        assertThat(new ConfigValueResolver().resolveHeaders(Map.of(), Map.of())).isNull();
    }

    @Test
    void resolveHeadersOrThrowStopsAtTheFirstUnresolvedHeader() {
        var resolver = new ConfigValueResolver();
        assertThatThrownBy(() -> resolver.resolveHeadersOrThrow(
                Map.of("X-Key", "${NOPE_ONE}"), "MCP server \"s\"", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to resolve MCP server \"s\" header \"X-Key\" from environment variable: NOPE_ONE");
    }

    @Test
    void resolveHeadersOrThrowReturnsNullWithoutHeaders() {
        assertThat(new ConfigValueResolver().resolveHeadersOrThrow(null, "d", Map.of())).isNull();
        assertThat(new ConfigValueResolver().resolveHeadersOrThrow(Map.of(), "d", Map.of())).isNull();
    }

    // ------------------------------------------------------------ inspection

    @Test
    void classifiesValues() {
        assertThat(ConfigValueResolver.isCommand("!x")).isTrue();
        assertThat(ConfigValueResolver.isCommand("x")).isFalse();
        assertThat(ConfigValueResolver.envVarName("${A}")).isEqualTo("A");
        assertThat(ConfigValueResolver.envVarName("$A")).isEqualTo("A");
        assertThat(ConfigValueResolver.envVarName("pre${A}")).isNull();
        assertThat(ConfigValueResolver.envVarName("${A}${B}")).isNull();
    }

    @Test
    void listsReferencedVariablesOnceInOrder() {
        assertThat(ConfigValueResolver.envVarNames("${B}${A}${B}")).containsExactly("B", "A");
        assertThat(ConfigValueResolver.envVarNames("!cmd ${A}")).isEmpty();
    }

    @Test
    void reportsWhichVariablesAreMissing() {
        var resolver = new ConfigValueResolver();
        var env = Map.of("HERE", "x");
        assertThat(resolver.missingEnvVarNames("${HERE}${GONE_A}${GONE_B}", env))
                .containsExactly("GONE_A", "GONE_B");
        assertThat(resolver.isConfigured("${HERE}", env)).isTrue();
        assertThat(resolver.isConfigured("${GONE_A}", env)).isFalse();
    }
}
