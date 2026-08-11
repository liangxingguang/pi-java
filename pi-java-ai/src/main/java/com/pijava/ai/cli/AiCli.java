package com.pijava.ai.cli;

import java.util.Comparator;

import com.pijava.ai.auth.EnvApiKeyResolver;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.provider.AnthropicProvider;
import com.pijava.ai.provider.DeepSeekProvider;
import com.pijava.ai.provider.GoogleProvider;
import com.pijava.ai.provider.MistralProvider;
import com.pijava.ai.provider.OpenAIProvider;
import com.pijava.ai.provider.ProviderRegistry;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * pi-ai CLI — standalone command-line tool for model listing, auth, and ping.
 *
 * <p>Entry point: {@code pi-ai list-models}, {@code pi-ai auth <provider>},
 * {@code pi-ai ping <provider> [model]}.</p>
 */
@Command(name = "pi-ai", description = "pi-java AI model management CLI",
         subcommands = {AiCli.ListModels.class, AiCli.AuthCmd.class, AiCli.PingCmd.class})
public final class AiCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** Main entry point for the CLI. */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new AiCli()).execute(args);
        System.exit(exitCode);
    }

    // ── list-models ───────────────────────────────────────────

    @Command(name = "list-models", description = "List available models")
    static final class ListModels implements Runnable {

        @Parameters(index = "0", arity = "0..1",
                    description = "Provider filter (anthropic|openai|google|deepseek|mistral)")
        String provider;

        @Override
        public void run() {
            var catalogs = new java.util.LinkedHashMap<String, java.util.List<ModelInfo>>();

            if (provider == null || provider.equals("anthropic")) {
                catalogs.put("anthropic", BuiltinCatalog.anthropicModels().listModels());
            }
            if (provider == null || provider.equals("openai")) {
                catalogs.put("openai", BuiltinCatalog.openaiModels().listModels());
            }
            if (provider == null || provider.equals("google")) {
                catalogs.put("google", BuiltinCatalog.googleModels().listModels());
            }
            if (provider == null || provider.equals("deepseek")) {
                catalogs.put("deepseek", BuiltinCatalog.deepseekModels().listModels());
            }
            if (provider == null || provider.equals("mistral")) {
                catalogs.put("mistral", BuiltinCatalog.mistralModels().listModels());
            }

            // Header
            System.out.printf("%-12s %-30s %-10s %-10s %-10s%n",
                    "Provider", "Model ID", "Context", "Input/$", "Output/$");
            System.out.println("──────────  ─────────────────────────────  ──────────  ──────────  ──────────");

            for (var entry : catalogs.entrySet()) {
                for (var model : entry.getValue()) {
                    var p = model.pricing();
                    System.out.printf("%-12s %-30s %-10s %-10s %-10s%n",
                            entry.getKey(),
                            model.id().modelName(),
                            formatTokens(model.maxInputTokens()),
                            p.isKnown() ? String.format("$%.2f", p.inputPrice()) : "N/A",
                            p.isKnown() ? String.format("$%.2f", p.outputPrice()) : "N/A");
                }
            }
        }

        private String formatTokens(int tokens) {
            if (tokens >= 1_000_000) return (tokens / 1_000_000) + "M";
            if (tokens >= 1_000) return (tokens / 1_000) + "K";
            return String.valueOf(tokens);
        }
    }

    // ── auth ──────────────────────────────────────────────────

    @Command(name = "auth", description = "Configure API key for a provider")
    static final class AuthCmd implements Runnable {

        @Parameters(index = "0", description = "Provider name (anthropic|openai|google|deepseek|mistral)")
        String provider;

        @Override
        public void run() {
            System.out.print("Enter API key for " + provider + ": ");
            var console = System.console();
            if (console == null) {
                System.err.println("No console available. Set the environment variable instead.");
                System.exit(1);
            }
            var key = new String(console.readPassword());
            if (key.isBlank()) {
                System.out.println("Aborted.");
                return;
            }
            var store = new FileCredentialStore();
            store.storeApiKey(provider, key);
            System.out.println("API key saved for " + provider + ".");
        }
    }

    // ── ping ──────────────────────────────────────────────────

    @Command(name = "ping", description = "Test connectivity to a provider")
    static final class PingCmd implements Runnable {

        @Parameters(index = "0", description = "Provider name")
        String provider;

        @Option(names = {"-m", "--model"}, description = "Model to test with")
        String model;

        @Override
        public void run() {
            System.out.println("Pinging " + provider + "...");
            try {
                var resolver = new EnvApiKeyResolver();
                var key = resolver.resolveApiKey(provider);
                if (key.isEmpty()) {
                    var store = new FileCredentialStore();
                    key = store.resolveApiKey(provider);
                }
                if (key.isEmpty()) {
                    System.out.println("No API key found. Run 'pi-ai auth " + provider + "' first.");
                    return;
                }

                var options = com.pijava.ai.api.ApiOptions.defaults();
                // Use resolved key
                options = new com.pijava.ai.api.ApiOptions(
                        options.baseUrl(), key.get(), options.timeout(),
                        options.maxRetries(), options.extra());

                var registry = ProviderRegistry.global();
                registry.register(new AnthropicProvider());
                registry.register(new OpenAIProvider());
                registry.register(new GoogleProvider());
                registry.register(new DeepSeekProvider());
                registry.register(new MistralProvider());

                var prov = registry.get(provider).orElseThrow();
                var api = prov.createApi(
                        com.pijava.ai.api.ChatApi.class, options);
                var models = prov.builtinModels().listModels();
                var testModel = model != null
                        ? model
                        : models.isEmpty() ? "unknown" : models.get(0).id().modelName();

                var request = com.pijava.ai.api.StreamRequest.of(
                        com.pijava.ai.model.ModelId.of(provider, testModel),
                        java.util.List.of(new com.pijava.ai.message.Message.UserMessage(
                                java.util.List.of(new com.pijava.ai.message.ContentBlock.TextContent("ping")))));

                var response = api.send(request, options);
                System.out.println("OK — connected to " + provider + " using " + testModel);
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getMessage());
            }
        }
    }
}
