package com.pijava.agent.hook;

import com.pijava.agent.entry.Entry;
import java.util.List;

/** Context passed to {@code before_compaction} hook. */
public record CompactionContext(String lane, List<Entry> transcript, int currentTokens) {}
