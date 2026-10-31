package com.pijava.evals.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.evals.api.EvalSuite;

/**
 * 批 32（docs/44 §6/D185）：eval suite 的静态注册表。
 *
 * <p>Java 没有 pi 的 {@code *.eval.ts} 文件发现机制，因此由 contents 类在静态
 * 初始化时 {@link #register(EvalSuite)}；{@code -t}/{@code evalSet/case} 过滤在
 * CLI 内完成。登记 D185。</p>
 */
public final class EvalCatalog {

    private static final List<EvalSuite> REGISTERED = new CopyOnWriteArrayList<>();

    private EvalCatalog() {
    }

    /**
     * Register a suite (idempotent by suite name).
     *
     * @param suite suite to register
     */
    public static void register(EvalSuite suite) {
        Objects.requireNonNull(suite, "suite");
        if (REGISTERED.stream().noneMatch(existing -> existing.name().equals(suite.name()))) {
            REGISTERED.add(suite);
        }
    }

    /**
     * Remove all registrations (tests).
     */
    public static void reset() {
        REGISTERED.clear();
    }

    /**
     * Registered suites in registration order.
     *
     * @return immutable suite list
     */
    public static List<EvalSuite> suites() {
        return List.copyOf(REGISTERED);
    }

    /**
     * Select suites by name, simple class name, or leave all when no selector is
     * a suite-level selector.
     *
     * @param suites    candidate suites
     * @param selectors CLI selectors
     * @return selected suites in input order
     */
    public static List<EvalSuite> select(List<EvalSuite> suites, List<String> selectors) {
        Objects.requireNonNull(suites, "suites");
        var wanted = selectors == null ? List.<String>of() : selectors;
        if (wanted.isEmpty()) {
            return List.copyOf(suites);
        }
        var matched = new LinkedHashSet<EvalSuite>();
        for (var suite : suites) {
            if (wanted.stream().anyMatch(selector -> matchesSuite(suite, selector))) {
                matched.add(suite);
            }
        }
        return new ArrayList<>(matched);
    }

    /**
     * {@code true} when the selector names this suite (any case selector counts).
     *
     * @param suite    suite
     * @param selector CLI selector
     * @return whether the suite is selected
     */
    public static boolean matchesSuite(EvalSuite suite, String selector) {
        if (suite.name().equals(selector) || suite.getClass().getSimpleName().equals(selector)) {
            return true;
        }
        return selector.startsWith(suite.name() + "/");
    }

    /**
     * Case-level selectors ({@code evalSet/case}) that apply to this suite.
     *
     * @param suite    suite
     * @param selectors CLI selectors
     * @return case ids, empty when the suite was selected as a whole
     */
    public static List<String> caseSelectors(EvalSuite suite, List<String> selectors) {
        var ids = new ArrayList<String>();
        for (var selector : selectors) {
            if (selector.startsWith(suite.name() + "/")) {
                ids.add(selector);
            }
        }
        return Collections.unmodifiableList(ids);
    }
}
