package com.pijava.coding.agent.codemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.extension.ExtensionContext;

/** Tests for the B6 minimal {@code models.list} catalog. */
class CodemodeModelsTest {

    @Test
    void listsRegisteredModelsAndDropsNothingSecret() throws Exception {
        var provider = provider("demo", List.of(ModelInfo.minimal(ModelId.of("demo", "m1"))));
        var registry = ProviderRegistry.create();
        registry.register(provider);

        var global = CodemodeModels.globals(context(registry)).get("models.list");
        assertThat(global.execute(new Object[0])).isEqualTo(List.of(Map.of(
            "provider", "demo",
            "id", "m1",
            "name", "m1",
            "contextWindow", 0,
            "maxTokens", 0,
            "deprecated", false,
            "capabilities", List.of())));
    }

    @Test
    void filtersByProviderWhenGiven() throws Exception {
        var registry = ProviderRegistry.create();
        registry.register(provider("one", List.of(ModelInfo.minimal(ModelId.of("one", "a")))));
        registry.register(provider("two", List.of(ModelInfo.minimal(ModelId.of("two", "b")))));

        var global = CodemodeModels.globals(context(registry)).get("models.list");
        var filtered = (List<?>) global.execute(new Object[] {"two"});
        assertThat(filtered).hasSize(1);
        assertThat(((Map<?, ?>) filtered.get(0)).get("id")).isEqualTo("b");
    }

    private static Provider provider(String name, List<ModelInfo> models) {
        return (Provider) Proxy.newProxyInstance(
            CodemodeModelsTest.class.getClassLoader(), new Class<?>[] {Provider.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "name" -> name;
                case "getModels" -> models;
                case "toString" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static ExtensionContext context(ProviderRegistry registry) {
        return (ExtensionContext) Proxy.newProxyInstance(
            CodemodeModelsTest.class.getClassLoader(), new Class<?>[] {ExtensionContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "providers" -> registry;
                case "toString" -> "test-context";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
