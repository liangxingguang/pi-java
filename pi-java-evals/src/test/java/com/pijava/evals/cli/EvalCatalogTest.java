package com.pijava.evals.cli;

import java.util.List;

import com.pijava.evals.api.EvalCase;
import com.pijava.evals.api.EvalSuite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 批 32（docs/44 §6/D185）：静态注册表与 suite/case 选择。 */
class EvalCatalogTest {

    @AfterEach
    void resetCatalog() {
        EvalCatalog.reset();
    }

    @Test
    void registrationIsIdempotentByName() {
        EvalCatalog.register(new NamedSuite("alpha"));
        EvalCatalog.register(new NamedSuite("alpha"));
        assertThat(EvalCatalog.suites()).hasSize(1);
    }

    @Test
    void selectsSuiteByNameClassOrCase() {
        var alpha = new NamedSuite("alpha");
        var beta = new NamedSuite("beta");
        assertThat(EvalCatalog.select(List.of(alpha, beta), List.of("alpha")))
            .containsExactly(alpha);
        assertThat(EvalCatalog.select(List.of(alpha, beta), List.of("NamedSuite")))
            .containsExactly(alpha, beta);
        assertThat(EvalCatalog.select(List.of(alpha, beta), List.of("alpha/case")))
            .containsExactly(alpha);
        assertThat(EvalCatalog.select(List.of(alpha, beta), List.of()))
            .containsExactly(alpha, beta);
        assertThat(EvalCatalog.caseSelectors(alpha, List.of("alpha/case", "beta")))
            .containsExactly("alpha/case");
    }

    private static final class NamedSuite implements EvalSuite {

        private final String name;

        NamedSuite(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<EvalCase> cases() {
            return List.of();
        }
    }
}
