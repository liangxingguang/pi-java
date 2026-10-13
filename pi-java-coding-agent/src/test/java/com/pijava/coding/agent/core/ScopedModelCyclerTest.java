package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.model.ScopedModel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** {@link ScopedModelCycler} 夹具（pi agent-session.ts:2519-2560）。 */
class ScopedModelCyclerTest {

    private static ModelInfo info(String provider, String id) {
        return new ModelInfo(ModelId.of(provider, id), id, Set.of(ModelCapability.TEXT),
            100_000, 4096, false, PricingInfo.UNKNOWN);
    }

    private static ModelCatalog catalog(List<ModelInfo> models) {
        return new ModelCatalog() {
            @Override public List<ModelInfo> listModels() { return models; }
            @Override public Optional<ModelInfo> find(ModelId<?> id) {
                return models.stream().filter(m -> m.id().equals(id)).findFirst();
            }
            @Override public List<ModelInfo> search(String query) { return List.of(); }
        };
    }

    private static final ModelInfo A = info("p", "a");
    private static final ModelInfo B = info("p", "b");
    private static final ModelInfo C = info("p", "c");

    @Test
    void cyclesForwardWithinScopeAndWraps() {
        var scope = List.of(ScopedModel.of(A), ScopedModel.of(B, "high"), ScopedModel.of(C));
        var catalog = catalog(List.of(A, B, C));

        assertThat(ScopedModelCycler.next(A.id(), scope, catalog)).isEqualTo(B.id());
        assertThat(ScopedModelCycler.next(B.id(), scope, catalog)).isEqualTo(C.id());
        assertThat(ScopedModelCycler.next(C.id(), scope, catalog)).isEqualTo(A.id());
    }

    @Test
    void currentOutsideScopeLandsOnSecondEntry() {
        // pi 逐字：currentIndex===-1 先归 0，再前进一格 ⇒ 落到 pool[1]。
        var scope = List.of(ScopedModel.of(A), ScopedModel.of(B));
        var catalog = catalog(List.of(A, B, C));

        assertThat(ScopedModelCycler.next(C.id(), scope, catalog)).isEqualTo(B.id());
    }

    @Test
    void singleEntryScopeDoesNotMove() {
        var scope = List.of(ScopedModel.of(A, "max"));
        var catalog = catalog(List.of(A, B));

        assertThat(ScopedModelCycler.next(A.id(), scope, catalog)).isEqualTo(A.id());
        // ≤1 直接返回 undefined：当前模型不在 scope 也保持不动。
        assertThat(ScopedModelCycler.next(B.id(), scope, catalog)).isEqualTo(B.id());
    }

    @Test
    void vanishedScopeEntriesAreFiltered() {
        var scope = List.of(ScopedModel.of(A), ScopedModel.of(B), ScopedModel.of(C));
        var catalog = catalog(List.of(A, C));   // b disappeared after catalog refresh

        assertThat(ScopedModelCycler.next(A.id(), scope, catalog)).isEqualTo(C.id());
    }

    @Test
    void emptyScopeCyclesFullCatalog() {
        var catalog = catalog(List.of(A, B, C));

        assertThat(ScopedModelCycler.next(A.id(), List.of(), catalog)).isEqualTo(B.id());
        assertThat(ScopedModelCycler.next(C.id(), List.of(), catalog)).isEqualTo(A.id());
    }

    @Test
    void explicitLevelFollowsSwitchedModel() {
        var scope = List.of(ScopedModel.of(A), ScopedModel.of(B, "high"));

        assertThat(ScopedModelCycler.levelFor(B.id(), scope)).contains("high");
        assertThat(ScopedModelCycler.levelFor(A.id(), scope)).isEmpty();
        assertThat(ScopedModelCycler.levelFor(B.id(), List.of())).isEmpty();
    }
}
