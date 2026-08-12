package com.pijava.agent.hook;

import com.pijava.ai.message.Message;
import java.util.List;

/** Context passed to {@code before_run} and {@code before_resume} hooks. */
public record RunContext(String lane, String runId, List<Message> prompt) {}
