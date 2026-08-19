package com.pijava.ai.cli;


import com.pijava.ai.auth.EnvApiKeyResolver;
import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.provider.builtin.ProviderCatalog;

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
                    description = "Provider filter (anthropic, openai, moonshotai-cn, ...)")
        String provider;

        @Override
        public void run() {
            var providers = ProviderCatalog.all().stream()
                .filter(p -> provider == null || provider.equals(p.name()))
                .toList();

            // Header
            System.out.printf("%-22s %-30s %-10s %-10s %-10s%n",
                    "Provider", "Model ID", "Context", "Input/$", "Output/$");
            System.out.println(separator());

            for (var p : providers) {
                for (var model : p.builtinModels().listModels()) {
                    printModel(p, model);
                }
            }
        }

        private static String separator() {
            return "─".repeat(22) + " " + "─".repeat(30) + " " + "─".repeat(10)
                + " " + "─".repeat(10) + " " + "─".repeat(10);
        }

        private void printModel(Provider provider, ModelInfo model) {
            var pricing = model.pricing();
            System.out.printf("%-22s %-30s %-10s %-10s %-10s%n",
                    provider.name(),
                    model.id().modelName(),
                    formatTokens(model.maxInputTokens()),
                    pricing.isKnown() ? String.format("$%.2f", pricing.inputPrice()) : "N/A",
                    pricing.isKnown() ? String.format("$%.2f", pricing.outputPrice()) : "N/A");
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

        @Parameters(index = "0", description = "Provider name (anthropic, openai, moonshotai-cn, ...)")
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
                if (key.isEmpty() && !"ollama".equals(provider)) {
                    System.out.println("No API key found. Run 'pi-ai auth " + provider + "' first.");
                    return;
                }
                var options = com.pijava.ai.api.ApiOptions.defaults();
                options = new com.pijava.ai.api.ApiOptions(
                        options.baseUrl(), key.orElse(""), options.timeout(),
                        options.maxRetries(), options.extra());

                var registry = ProviderRegistry.create();
                registry.loadBuiltinProviders();
                registry.discoverFromServiceLoader();

                var prov = registry.get(provider).orElseThrow(
                    () -> new IllegalArgumentException("Unknown provider: " + provider));
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

                api.send(request, options);
                System.out.println("OK — connected to " + provider + " using " + testModel);
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getMessage());
            }
        }
    }
}
