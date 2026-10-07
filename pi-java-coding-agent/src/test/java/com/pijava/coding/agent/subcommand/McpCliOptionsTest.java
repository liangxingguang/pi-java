package com.pijava.coding.agent.subcommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code parseOptions} 与 {@code parsePairs}（{@code cli.ts:129-182,262-275}）。
 */
class McpCliOptionsTest {

    private final List<String> errors = new ArrayList<>();

    private static final Map<String, McpCliOptions.Kind> KNOWN = Map.of(
            "local", McpCliOptions.Kind.FLAG,
            "url", McpCliOptions.Kind.VALUE,
            "header", McpCliOptions.Kind.LIST);

    private McpCliOptions.Parsed parse(String... args) {
        return McpCliOptions.parse(args, KNOWN, errors::add, Integer.MAX_VALUE);
    }

    @Test
    void readsFlagsValuesAndLists() {
        var parsed = parse("--local", "--url", "http://x", "--header", "A=1", "--header", "B=2");
        assertThat(parsed.has("local")).isTrue();
        assertThat(parsed.value("url")).isEqualTo("http://x");
        assertThat(parsed.lists().get("header")).containsExactly("A=1", "B=2");
        assertThat(errors).isEmpty();
    }

    @Test
    void theLastValueOfARepeatedOptionWins() {
        var parsed = parse("--url", "a", "--url", "b");
        assertThat(parsed.value("url")).isEqualTo("b");
    }

    @Test
    void theShortLocalAliasIsRewritten() {
        assertThat(parse("-l").has("local")).isTrue();
    }

    @Test
    void everythingAfterADoubleDashIsPositional() {
        var parsed = parse("--url", "http://x", "--", "--flag", "rest");
        assertThat(parsed.positional()).containsExactly("--flag", "rest");
        assertThat(parsed.value("url")).isEqualTo("http://x");
    }

    @Test
    void reachingThePositionalLimitEndsTheOptions() {
        // `mcp add <server> <command> --flag` passes `--flag` through to the command.
        var parsed = McpCliOptions.parse(
                new String[] {"files", "npx", "--flag", "-y"}, KNOWN, errors::add, 2);
        assertThat(parsed.positional()).containsExactly("files", "npx", "--flag", "-y");
        assertThat(errors).isEmpty();
    }

    @Test
    void anUnknownOptionIsReportedWithTheHint() {
        assertThat(parse("--nope")).isNull();
        assertThat(errors).containsExactly("Unknown option --nope.\n" + McpCliHelp.HINT);
    }

    @Test
    void anOptionWithoutItsValueIsReported() {
        assertThat(parse("--url")).isNull();
        assertThat(errors).containsExactly("--url needs a value.");
    }

    @Test
    void aBareArgumentIsPositional() {
        assertThat(parse("files", "echo").positional()).containsExactly("files", "echo");
    }

    @Test
    void pairsSplitOnTheFirstEquals() {
        var pairs = McpCliOptions.parsePairs("header", List.of("A=1=2", "B="), errors::add);
        assertThat(pairs).containsEntry("A", "1=2").containsEntry("B", "");
    }

    @Test
    void aPairWithoutAKeyIsReported() {
        assertThat(McpCliOptions.parsePairs("env", List.of("=value"), errors::add)).isNull();
        assertThat(McpCliOptions.parsePairs("env", List.of("novalue"), errors::add)).isNull();
        assertThat(errors).containsExactly(
                "--env expects KEY=VALUE, got \"=value\".",
                "--env expects KEY=VALUE, got \"novalue\".");
    }

    @Test
    void anAbsentListIsEmptyRatherThanAnError() {
        assertThat(McpCliOptions.parsePairs("header", null, errors::add)).isEmpty();
        assertThat(errors).isEmpty();
    }
}
