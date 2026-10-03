package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.InMemoryModelsStore;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.provider.Provider;
import com.pijava.coding.agent.cli.Args;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/70 §1.5／§7.1-9/13：两阶段编排（先全部离线恢复、再全部联网）、
 * 逐 provider 的错误记账、以及 {@code --offline}／{@code PI_OFFLINE} 的判定。
 */
class ModelCatalogRefreshTest {

    /** 记录自己被调用的相位；可选在联网相抛错。 */
    private static final class RecordingProvider implements Provider {
        private final String id;
        private final List<String> order;
        private final boolean failOnline;

        RecordingProvider(String id, List<String> order, boolean failOnline) {
            this.id = id;
            this.order = order;
            this.failOnline = failOnline;
        }

        @Override public String name() { return id; }
        @Override public String displayName() { return id; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() { return Set.of(); }
        @Override public <T extends ProviderApi> T createApi(
                Class<T> apiType, ApiOptions options) {
            throw new UnsupportedOperationException();
        }
        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        @Override
        public void refreshModels(RefreshModelsContext context) {
            order.add(id + ":" + (context.allowNetwork() ? "online" : "offline"));
            if (failOnline && context.allowNetwork()) {
                throw new IllegalStateException(id + " exploded");
            }
        }
    }

    @Test
    void everyProviderRestoresOfflineBeforeAnyGoesOnline() {
        var order = Collections.synchronizedList(new ArrayList<String>());
        var a = new RecordingProvider("a", order, false);
        var b = new RecordingProvider("b", order, false);

        new ModelCatalogRefresh(new InMemoryModelsStore())
            .refresh(List.of(a, b), true, false);

        assertThat(order).hasSize(4);
        assertThat(order.subList(0, 2)).containsExactlyInAnyOrder("a:offline", "b:offline");
        assertThat(order.subList(2, 4)).containsExactlyInAnyOrder("a:online", "b:online");
    }

    @Test
    void offlineOnlyRefreshNeverGoesOnline() {
        var order = Collections.synchronizedList(new ArrayList<String>());
        var a = new RecordingProvider("a", order, false);

        new ModelCatalogRefresh(new InMemoryModelsStore())
            .refresh(List.of(a), false, false);

        assertThat(order).containsExactly("a:offline");
    }

    @Test
    void oneProviderFailureIsRecordedWithoutStoppingTheOthers() {
        var order = new CopyOnWriteArrayList<String>();
        var boom = new RecordingProvider("boom", order, true);
        var fine = new RecordingProvider("fine", order, false);

        var result = new ModelCatalogRefresh(new InMemoryModelsStore())
            .refresh(List.of(boom, fine), true, false);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).containsOnlyKeys("boom");
        assertThat(result.errors().get("boom")).hasMessage("boom exploded");
        assertThat(order).contains("fine:online");
    }

    @Test
    void offlineFlagAndTruthyEnvVarBothDisableNetwork() {
        assertThat(ModelCatalogRefresh.offlineMode(args(false), "1")).isTrue();
        assertThat(ModelCatalogRefresh.offlineMode(args(false), "true")).isTrue();
        assertThat(ModelCatalogRefresh.offlineMode(args(false), "YES")).isTrue();
        assertThat(ModelCatalogRefresh.offlineMode(args(false), "0")).isFalse();
        assertThat(ModelCatalogRefresh.offlineMode(args(false), "")).isFalse();
        assertThat(ModelCatalogRefresh.offlineMode(args(false), null)).isFalse();
        assertThat(ModelCatalogRefresh.offlineMode(args(true), null)).isTrue();
    }

    private static Args args(boolean offline) {
        return com.pijava.coding.agent.cli.ArgsParser.parse(
            offline ? new String[] {"--offline"} : new String[0]);
    }
}
