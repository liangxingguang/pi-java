package com.pijava.agent.session;

/** A lane and its current leaf entry (aligned with pi {@code LanePointer}). */
public record LanePointer(String lane, String leafId) {}
