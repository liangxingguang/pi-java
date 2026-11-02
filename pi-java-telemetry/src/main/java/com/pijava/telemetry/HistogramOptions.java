package com.pijava.telemetry;

import java.util.List;

/** Histogram metadata and caller-supplied bucket boundary hints. */
public record HistogramOptions(String description, String unit, List<Double> boundaries) {

    /** Defensively copy optional boundaries. */
    public HistogramOptions {
        boundaries = boundaries == null ? null : List.copyOf(boundaries);
    }
}
