package com.pijava.agent.hook;

import com.pijava.ai.message.Message;
import java.util.List;

/** Hook triggered when building the LLM message list. Can inject/remove messages. */
@FunctionalInterface
public interface TransformContextHook {
    /** Invoked when building the LLM message list; may inject or remove messages. */
    List<Message> transformContext(List<Message> messages);
}
