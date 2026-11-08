package com.pijava.evals.contents;

import com.pijava.evals.cli.EvalCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §6/D185）：contents 注册表把所有 suite 装进 {@link EvalCatalog}。
 */
class ContentsCatalogTest {

    @AfterEach
    void reset() {
        ContentsCatalog.resetForTests();
        EvalCatalog.reset();
    }

    @Test
    void installRegistersEverySuiteIdempotently() {
        EvalCatalog.reset();
        ContentsCatalog.install();
        ContentsCatalog.install();
        assertThat(EvalCatalog.suites()).extracting(suite -> suite.name())
            .containsExactlyInAnyOrder(
                DocumentationAuditEval.SUITE_NAME,
                CustomProviderDocsEval.SUITE_NAME,
                OpenAiProviderDocsEval.SUITE_NAME,
                ModelsDocsEval.SUITE_NAME,
                ExtensionsDocsEval.SUITE_NAME,
                PromptTemplatesDocsEval.SUITE_NAME,
                TuiDocsEval.SUITE_NAME,
                SmokeEval.SUITE_NAME);
    }

    @Test
    void onlyTheDocumentationAuditIsExpensive() {
        EvalCatalog.reset();
        ContentsCatalog.install();
        assertThat(EvalCatalog.suites())
            .filteredOn(suite -> suite.expensive())
            .extracting(suite -> suite.name())
            .containsExactly(DocumentationAuditEval.SUITE_NAME);
    }
}
