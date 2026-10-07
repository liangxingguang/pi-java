package com.pijava.coding.agent.subcommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Option parsing for {@code pi-java mcp} (pi {@code parseOptions}, {@code cli.ts:129-182}).
 *
 * <p>Hand-rolled rather than picocli: pi's parser stops at {@code --} <em>and</em> once
 * {@code maxPositionals} arguments are in hand, so a command's own options survive
 * ({@code pi mcp add <server> <command> --flag}). picocli has no "stop after N positionals"
 * mode.</p>
 */
final class McpCliOptions {

    /** How an option takes its value ({@code cli.ts:145}). */
    enum Kind {
        /** No value. */
        FLAG,
        /** One value. */
        VALUE,
        /** A repeatable value. */
        LIST
    }

    /** Short spellings. {@code -l}/{@code --local} match {@code pi install} ({@code cli.ts:130}). */
    private static final Map<String, String> ALIASES = Map.of("-l", "--local");

    /**
     * What one parse produced ({@code cli.ts:132-137}).
     *
     * @param positional the arguments that are not options
     * @param values the value of each {@code VALUE} option
     * @param flags the {@code FLAG} options that were given
     * @param lists the values of each {@code LIST} option, in order
     */
    record Parsed(List<String> positional, Map<String, String> values, Set<String> flags,
                  Map<String, List<String>> lists) {

        /** Whether a flag was given. */
        boolean has(String name) {
            return flags.contains(name);
        }

        /** The value of a {@code VALUE} option, or {@code null}. */
        @Nullable String value(String name) {
            return values.get(name);
        }
    }

    private McpCliOptions() {
    }

    /**
     * Parse {@code --name value} options (pi {@code parseOptions}, {@code cli.ts:144-182}).
     *
     * @param args the arguments after the subcommand
     * @param known the options this command accepts
     * @param error reports an unknown option or a missing value
     * @param maxPositionals how many positional arguments end the options; the rest are
     *                       positional, so a command's own options pass through
     * @return the parse, or {@code null} after {@code error} reported a problem
     */
    static @Nullable Parsed parse(String[] args, Map<String, Kind> known, Consumer<String> error,
                                  int maxPositionals) {
        var positional = new ArrayList<String>();
        var values = new LinkedHashMap<String, String>();
        var flags = new LinkedHashSet<String>();
        var lists = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < args.length; index++) {
            var arg = ALIASES.getOrDefault(args[index], args[index]);
            if ("--".equals(arg) || positional.size() >= maxPositionals) {
                for (var rest = "--".equals(arg) ? index + 1 : index; rest < args.length; rest++) {
                    positional.add(args[rest]);
                }
                break;
            }
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            var name = arg.substring(2);
            var kind = known.get(name);
            if (kind == null) {
                error.accept("Unknown option " + arg + ".\n" + McpCliHelp.HINT);
                return null;
            }
            if (kind == Kind.FLAG) {
                flags.add(name);
                continue;
            }
            if (++index >= args.length) {
                error.accept(arg + " needs a value.");
                return null;
            }
            if (kind == Kind.LIST) {
                lists.computeIfAbsent(name, key -> new ArrayList<>()).add(args[index]);
            } else {
                values.put(name, args[index]);
            }
        }
        return new Parsed(List.copyOf(positional), Map.copyOf(values), Set.copyOf(flags),
                Map.copyOf(lists));
    }

    /**
     * Parse {@code KEY=VALUE} pairs of a repeatable option (pi {@code parsePairs},
     * {@code cli.ts:262-275}).
     *
     * @param option the option name, for the error message
     * @param pairs the values, or {@code null} when the option was not given
     * @param error reports a pair without a non-empty key
     * @return the pairs, or {@code null} after {@code error} reported a problem
     */
    static @Nullable Map<String, String> parsePairs(String option, @Nullable List<String> pairs,
                                                    Consumer<String> error) {
        if (pairs == null) {
            return Map.of();
        }
        var record = new LinkedHashMap<String, String>();
        for (var pair : pairs) {
            var separator = pair.indexOf('=');
            if (separator <= 0) {
                error.accept("--" + option + " expects KEY=VALUE, got \"" + pair + "\".");
                return null;
            }
            record.put(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return Map.copyOf(record);
    }
}
