package com.pijava.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 客户端命令（对齐 pi {@code CommandSchema}，9 个）。
 *
 * <p>变体字段不同 → sealed interface + record；{@code type} 判别字段经
 * {@code @JsonTypeInfo(EXISTING_PROPERTY)} 读取各 record 的 {@link #type()}。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Command.List.class),
    @JsonSubTypes.Type(value = Command.Create.class),
    @JsonSubTypes.Type(value = Command.Attach.class),
    @JsonSubTypes.Type(value = Command.Detach.class),
    @JsonSubTypes.Type(value = Command.Prompt.class),
    @JsonSubTypes.Type(value = Command.Steer.class),
    @JsonSubTypes.Type(value = Command.Abort.class),
    @JsonSubTypes.Type(value = Command.SetModel.class),
    @JsonSubTypes.Type(value = Command.SetThinking.class)
})
public sealed interface Command {

    /** 线格式 type 值。 */
    @JsonProperty("type")
    String type();

    @JsonTypeName("list")
    record List() implements Command {
        @Override public String type() { return "list"; }
    }

    @JsonTypeName("create")
    record Create(String cwd, String name, ModelRef model,
                  ProtocolThinkingLevel thinkingLevel) implements Command {
        @Override public String type() { return "create"; }
    }

    @JsonTypeName("attach")
    record Attach(String sessionId) implements Command {
        @Override public String type() { return "attach"; }
    }

    @JsonTypeName("detach")
    record Detach(String sessionId) implements Command {
        @Override public String type() { return "detach"; }
    }

    @JsonTypeName("prompt")
    record Prompt(String sessionId, String text) implements Command {
        @Override public String type() { return "prompt"; }
    }

    @JsonTypeName("steer")
    record Steer(String sessionId, String text) implements Command {
        @Override public String type() { return "steer"; }
    }

    @JsonTypeName("abort")
    record Abort(String sessionId) implements Command {
        @Override public String type() { return "abort"; }
    }

    @JsonTypeName("set_model")
    record SetModel(String sessionId, ModelRef model) implements Command {
        @Override public String type() { return "set_model"; }
    }

    @JsonTypeName("set_thinking")
    record SetThinking(String sessionId, ProtocolThinkingLevel thinkingLevel)
        implements Command {
        @Override public String type() { return "set_thinking"; }
    }
}
