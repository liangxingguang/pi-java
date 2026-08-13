package com.pijava.coding.agent.cli;

import java.util.Comparator;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelInfo;

/**
 * {@code --list-models} command: prints the built-in model catalog, optionally
 * filtered by a search term (Phase 3 design §9.4).
 */
public final class ListModelsCommand {

    private ListModelsCommand() {}

    /**
     * Print matching models and return the exit code.
     *
     * @param search null/"" = all models, non-empty = fuzzy search term
     */
    public static int run(String search) {
        var catalog = BuiltinCatalog.all();
        var models = search == null || search.isBlank()
            ? catalog.listModels() : catalog.search(search);
        var sorted = models.stream()
            .sorted(Comparator
                .comparing((ModelInfo m) -> m.id().provider())
                .thenComparing(m -> m.id().modelName()))
            .toList();

        if (sorted.isEmpty()) {
            System.out.println("No models found"
                + (search == null || search.isBlank() ? "." : " for: " + search));
            return 0;
        }
        System.out.printf("%-12s %-30s %-10s %-10s %-10s%n",
            "Provider", "Model ID", "Context", "Input/$", "Output/$");
        for (var model : sorted) {
            var pricing = model.pricing();
            System.out.printf("%-12s %-30s %-10s %-10s %-10s%n",
                model.id().provider(),
                model.id().modelName(),
                formatTokens(model.maxInputTokens()),
                pricing.isKnown()
                    ? String.format("$%.2f", pricing.inputPrice()) : "N/A",
                pricing.isKnown()
                    ? String.format("$%.2f", pricing.outputPrice()) : "N/A");
        }
        return 0;
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) {
            return (tokens / 1_000_000) + "M";
        }
        if (tokens >= 1_000) {
            return (tokens / 1_000) + "K";
        }
        return String.valueOf(tokens);
    }
}
