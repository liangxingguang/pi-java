package com.pijava.evals.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.pijava.evals.report.EvalObservation;
import com.pijava.evals.report.ObservationJson;

/**
 * Receives evaluation results as they complete.
 */
public interface EvalReporter {

    /**
     * Called after each case finishes.
     *
     * @param result the case result
     */
    void onResult(EvalResult result);

    /**
     * Called after a suite finishes.
     *
     * @param suiteName suite name
     * @param results   all case results
     */
    default void onSuite(String suiteName, List<EvalResult> results) {
        // optional
    }

    /**
     * Persist one arm observation (批 32，docs/44 §5.3): copy the session snapshot
     * when available, then append the observation to {@code observations.jsonl}.
     *
     * <p>{@code sessionFile} blank/null ⇒ 只写 observation，不写伪 artifact。</p>
     *
     * @param observation observation to persist
     * @param sessionFile session JSONL path (nullable)
     * @param artifactDir artifact root
     * @return the observation with {@code sessionArtifact} recorded
     * @throws IOException if writing fails
     */
    static EvalObservation write(EvalObservation observation, String sessionFile,
                                 Path artifactDir) throws IOException {
        return ObservationJson.persist(observation, sessionFile, artifactDir);
    }
}
