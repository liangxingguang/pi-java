package com.pijava.telemetry;

/** Optional descriptive metadata shared by metric instruments. */
public record InstrumentOptions(String description, String unit) {
}
