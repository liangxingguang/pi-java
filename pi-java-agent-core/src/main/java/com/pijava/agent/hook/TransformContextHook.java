package com.pijava.agent.hook;

import com.pijava.ai.message.Message;
import java.util.List;

/** Hook triggered when building the LLM message list. Can inject/remove messages. */
@FunctionalInterface
public interface TransformContextHook {
    List<Message> transformContext(List<Message> messages);
}
