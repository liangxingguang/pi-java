package com.pijava.coding.agent.core.session;

import java.time.Instant;

/**
 * Process-local session descriptor shown by {@code /session} and the resume
 * selector (Phase 3 design §11.5; cross-process listing arrives Phase 4).
 */
public record SessionInfo(
    String id,
    String name,
    Instant createdAt,
    long entryCount
) {}
