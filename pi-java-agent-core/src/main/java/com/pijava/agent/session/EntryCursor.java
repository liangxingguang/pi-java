package com.pijava.agent.session;

/** Sequence-based cursor (aligned with pi {@code EntryCursor}). */
public record EntryCursor(long afterSeq) {}
