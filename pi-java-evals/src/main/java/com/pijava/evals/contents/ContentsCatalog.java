package com.pijava.evals.contents;

import com.pijava.evals.cli.EvalCatalog;

/**
 * 批 32（docs/44 §6/D185）：contents 包的 eval suite 注册入口。
 *
 * <p>Java 没有 pi 的 {@code *.eval.ts} 文件发现机制，因此由本类把 contents
 * 下的 suite 注册进 {@link EvalCatalog}；{@code EvalCli.main} 启动时调用
 * {@link #install()}。注册按 suite name 幂等，可重复调用。</p>
 */
public final class ContentsCatalog {

    private static volatile boolean installed;

    private ContentsCatalog() {
    }

    /**
     * Register every contents suite (idempotent).
     */
    public static void install() {
        if (installed) {
            return;
        }
        synchronized (ContentsCatalog.class) {
            if (installed) {
                return;
            }
            EvalCatalog.register(new DocumentationAuditEval());
            EvalCatalog.register(new CustomProviderDocsEval());
            EvalCatalog.register(new OpenAiProviderDocsEval());
            EvalCatalog.register(new ModelsDocsEval());
            EvalCatalog.register(new ExtensionsDocsEval());
            EvalCatalog.register(new TuiDocsEval());
            EvalCatalog.register(new SmokeEval());
            installed = true;
        }
    }

    /** Reset the installed flag (tests). */
    static void resetForTests() {
        installed = false;
    }
}
