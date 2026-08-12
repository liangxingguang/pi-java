package com.pijava.agent.hook;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;

/** Context passed to {@code after_response} hook. */
public record ResponseContext(String lane, String runId, AssistantMessage response,
                               StreamEvent.UsageInfo usage) {}
