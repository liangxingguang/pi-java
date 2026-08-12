package com.pijava.agent.hook;

import com.pijava.ai.message.Message;
import java.util.List;

/** Context passed to {@code before_request} hook. */
public record RequestContext(String lane, String runId, List<Message> messages) {}
