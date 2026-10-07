package com.pijava.mcp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Resolve configuration values that may be shell commands, environment variables, or literals
 * (pi {@code core/resolve-config-value.ts}).
 *
 * <p>{@code !cmd} runs a shell command and uses its trimmed stdout; otherwise {@code $NAME} and
 * {@code ${NAME}} interpolate environment variables, with {@code $$} and {@code $!} escaping a
 * literal {@code $} and {@code !}. Anything else is a literal. Command results are cached for
 * the lifetime of the instance.</p>
 *
 * <p>pi keeps the command cache in module scope; the shared {@link #DEFAULT} instance is the
 * equivalent here.</p>
 */
public final class ConfigValueResolver {

    /** The instance used by the MCP runtime (pi's module-level cache and default shell). */
    public static final ConfigValueResolver DEFAULT = new ConfigValueResolver();

    private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final CommandRunner runner;
    private final Map<String, Optional<String>> commandResults = new ConcurrentHashMap<>();

    /** Resolve commands with the JDK shell. */
    public ConfigValueResolver() {
        this(new JdkShellRunner());
    }

    /**
     * Resolve commands with the given runner.
     *
     * @param runner runs {@code !cmd} values
     */
    public ConfigValueResolver(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * Resolve a config value ({@code resolve-config-value.ts:145-151}).
     *
     * @param config the configured value
     * @param env environment variables that take precedence over the process environment
     * @return the resolved value, or {@code null} when it cannot be resolved
     */
    public @Nullable String resolve(String config, @Nullable Map<String, String> env) {
        return switch (parseReference(config)) {
            case Reference.Command command -> cached(command.config());
            case Reference.Template template -> resolveTemplate(template.parts(), env);
        };
    }

    /**
     * Resolve like {@link #resolve} but bypass the command cache
     * ({@code resolve-config-value.ts:221-227}).
     *
     * @param config the configured value
     * @param env environment variables that take precedence over the process environment
     * @return the resolved value, or {@code null} when it cannot be resolved
     */
    public @Nullable String resolveUncached(String config, @Nullable Map<String, String> env) {
        return switch (parseReference(config)) {
            case Reference.Command command -> runCommand(command.config());
            case Reference.Template template -> resolveTemplate(template.parts(), env);
        };
    }

    /**
     * Resolve a config value or describe why it could not be resolved
     * ({@code resolve-config-value.ts:229-251}).
     *
     * @param config the configured value
     * @param description what the value is for, used in the error message
     * @param env environment variables that take precedence over the process environment
     * @return the resolved value
     * @throws IllegalStateException when the value cannot be resolved
     */
    public String resolveOrThrow(String config, String description, @Nullable Map<String, String> env) {
        var resolved = resolveUncached(config, env);
        if (resolved != null) {
            return resolved;
        }
        throw switch (parseReference(config)) {
            case Reference.Command command -> new IllegalStateException("Failed to resolve " + description
                    + " from shell command: " + command.config().substring(1));
            case Reference.Template ignored -> unresolvedTemplate(config, description, env);
        };
    }

    /**
     * Only reachable when the template references a variable that is not set: a value with no
     * references resolves to itself, so pi's bare {@code Failed to resolve <description>} fallback
     * ({@code resolve-config-value.ts:250}) cannot be reached. It is kept for fidelity.
     */
    private IllegalStateException unresolvedTemplate(
            String config, String description, @Nullable Map<String, String> env) {
        var missing = missingEnvVarNames(config, env);
        if (missing.size() == 1) {
            return new IllegalStateException("Failed to resolve " + description
                    + " from environment variable: " + missing.get(0));
        }
        if (missing.size() > 1) {
            return new IllegalStateException("Failed to resolve " + description
                    + " from environment variables: " + String.join(", ", missing));
        }
        return new IllegalStateException("Failed to resolve " + description);
    }

    /**
     * Resolve every header value, dropping the ones that resolve to nothing
     * ({@code resolve-config-value.ts:256-269}).
     *
     * @param headers the configured headers
     * @param env environment variables that take precedence over the process environment
     * @return the resolved headers, or {@code null} when there is nothing left
     */
    public @Nullable Map<String, String> resolveHeaders(
            @Nullable Map<String, String> headers, @Nullable Map<String, String> env) {
        if (headers == null) {
            return null;
        }
        var resolved = new LinkedHashMap<String, String>();
        for (var entry : headers.entrySet()) {
            var value = resolve(entry.getValue(), env);
            if (value != null && !value.isEmpty()) {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Resolve every header value, failing on the first one that cannot be resolved
     * ({@code resolve-config-value.ts:271-282}).
     *
     * @param headers the configured headers
     * @param description what the headers belong to, used in the error message
     * @param env environment variables that take precedence over the process environment
     * @return the resolved headers, or {@code null} when there are none
     */
    public @Nullable Map<String, String> resolveHeadersOrThrow(
            @Nullable Map<String, String> headers, String description, @Nullable Map<String, String> env) {
        if (headers == null) {
            return null;
        }
        var resolved = new LinkedHashMap<String, String>();
        for (var entry : headers.entrySet()) {
            resolved.put(entry.getKey(),
                    resolveOrThrow(entry.getValue(), description + " header \"" + entry.getKey() + "\"", env));
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Whether the value is a shell command ({@code resolve-config-value.ts:130-132}).
     *
     * @param config the configured value
     * @return {@code true} for {@code !…}
     */
    public static boolean isCommand(String config) {
        return parseReference(config) instanceof Reference.Command;
    }

    /**
     * The environment variable of a value that is exactly one reference
     * ({@code resolve-config-value.ts:115-119}).
     *
     * @param config the configured value
     * @return the name, or {@code null} when the value is not a single {@code $NAME}
     */
    public static @Nullable String envVarName(String config) {
        return switch (parseReference(config)) {
            case Reference.Command ignored -> null;
            case Reference.Template template -> template.parts().size() == 1
                    && template.parts().get(0) instanceof Part.Env env ? env.name() : null;
        };
    }

    /**
     * Every environment variable the value references, each once, in order
     * ({@code resolve-config-value.ts:121-124,92-99}).
     *
     * @param config the configured value
     * @return the names, empty for a command or a literal
     */
    public static List<String> envVarNames(String config) {
        return switch (parseReference(config)) {
            case Reference.Command ignored -> List.of();
            case Reference.Template template -> {
                var names = new ArrayList<String>();
                for (var part : template.parts()) {
                    if (part instanceof Part.Env env && !names.contains(env.name())) {
                        names.add(env.name());
                    }
                }
                yield names;
            }
        };
    }

    /**
     * The referenced environment variables that are not set
     * ({@code resolve-config-value.ts:126-128}).
     *
     * @param config the configured value
     * @param env environment variables that take precedence over the process environment
     * @return the missing names, in order
     */
    public List<String> missingEnvVarNames(String config, @Nullable Map<String, String> env) {
        var missing = new ArrayList<String>();
        for (var name : envVarNames(config)) {
            if (resolveEnv(name, env) == null) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * Whether every referenced environment variable is set
     * ({@code resolve-config-value.ts:134-136}).
     *
     * @param config the configured value
     * @param env environment variables that take precedence over the process environment
     * @return {@code true} when nothing is missing
     */
    public boolean isConfigured(String config, @Nullable Map<String, String> env) {
        return missingEnvVarNames(config, env).isEmpty();
    }

    /** Drop the cached command results ({@code resolve-config-value.ts:285-287}). */
    public void clearCache() {
        commandResults.clear();
    }

    // ------------------------------------------------------------------ template

    private @Nullable String cached(String commandConfig) {
        var cached = commandResults.get(commandConfig);
        if (cached != null) {
            return cached.orElse(null);
        }
        var result = Optional.ofNullable(runCommand(commandConfig));
        commandResults.put(commandConfig, result);
        return result.orElse(null);
    }

    /** pi trims the stdout and treats blank as unresolved ({@code resolve-config-value.ts:178-179}). */
    private @Nullable String runCommand(String commandConfig) {
        var value = runner.run(commandConfig.substring(1));
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** {@code resolveEnvConfigValue}: the map first, then the process environment, blanks skipping. */
    private static @Nullable String resolveEnv(String name, @Nullable Map<String, String> env) {
        if (env != null) {
            var fromMap = env.get(name);
            if (fromMap != null && !fromMap.isEmpty()) {
                return fromMap;
            }
        }
        var fromProcess = System.getenv(name);
        return fromProcess == null || fromProcess.isEmpty() ? null : fromProcess;
    }

    private static @Nullable String resolveTemplate(List<Part> parts, @Nullable Map<String, String> env) {
        var resolved = new StringBuilder();
        for (var part : parts) {
            switch (part) {
                case Part.Literal literal -> resolved.append(literal.value());
                case Part.Env reference -> {
                    var value = resolveEnv(reference.name(), env);
                    if (value == null) {
                        return null;
                    }
                    resolved.append(value);
                }
            }
        }
        return resolved.toString();
    }

    private static Reference parseReference(String config) {
        if (config.startsWith("!")) {
            return new Reference.Command(config);
        }
        return new Reference.Template(parseTemplate(config));
    }

    private static List<Part> parseTemplate(String config) {
        var parts = new ArrayList<Part>();
        var index = 0;
        while (index < config.length()) {
            var dollar = config.indexOf('$', index);
            if (dollar < 0) {
                appendLiteral(parts, config.substring(index));
                break;
            }
            appendLiteral(parts, config.substring(index, dollar));
            var next = dollar + 1 < config.length() ? config.charAt(dollar + 1) : '\0';
            if (next == '$' || next == '!') {
                appendLiteral(parts, String.valueOf(next));
                index = dollar + 2;
                continue;
            }
            if (next == '{') {
                var end = config.indexOf('}', dollar + 2);
                if (end < 0) {
                    appendLiteral(parts, "$");
                    index = dollar + 1;
                    continue;
                }
                var name = config.substring(dollar + 2, end);
                if (ENV_VAR_NAME.matcher(name).matches()) {
                    parts.add(new Part.Env(name));
                } else {
                    appendLiteral(parts, config.substring(dollar, end + 1));
                }
                index = end + 1;
                continue;
            }
            var name = envPrefix(config, dollar + 1);
            if (name != null) {
                parts.add(new Part.Env(name));
                index = dollar + 1 + name.length();
                continue;
            }
            appendLiteral(parts, "$");
            index = dollar + 1;
        }
        return parts;
    }

    private static void appendLiteral(List<Part> parts, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof Part.Literal previous) {
            parts.set(parts.size() - 1, new Part.Literal(previous.value() + value));
            return;
        }
        parts.add(new Part.Literal(value));
    }

    /** The longest {@code [A-Za-z_][A-Za-z0-9_]*} at {@code from}, or {@code null}. */
    private static @Nullable String envPrefix(String value, int from) {
        if (from >= value.length() || !isNameStart(value.charAt(from))) {
            return null;
        }
        var end = from + 1;
        while (end < value.length() && isNamePart(value.charAt(end))) {
            end++;
        }
        return value.substring(from, end);
    }

    private static boolean isNameStart(char value) {
        return value == '_' || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    private static boolean isNamePart(char value) {
        return isNameStart(value) || (value >= '0' && value <= '9');
    }

    /** One parsed config value. */
    private sealed interface Reference {

        /** A {@code !cmd} value. */
        record Command(String config) implements Reference {
        }

        /** A template of literals and environment references. */
        record Template(List<Part> parts) implements Reference {
        }
    }

    /** One piece of a template. */
    private sealed interface Part {

        /** Text copied verbatim. */
        record Literal(String value) implements Part {
        }

        /** A {@code $NAME} reference. */
        record Env(String name) implements Part {
        }
    }
}
