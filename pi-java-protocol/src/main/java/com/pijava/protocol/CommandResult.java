package com.pijava.protocol;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 命令结果（对齐 pi {@code CommandResultSchema}）。
 *
 * <p>除 list/detach 外，所有命令结果都回完整 {@link SessionSnapshot}（而非增量），
 * 客户端整体替换。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = CommandResult.ListResult.class),
    @JsonSubTypes.Type(value = CommandResult.DetachResult.class),
    @JsonSubTypes.Type(value = CommandResult.CreateResult.class),
    @JsonSubTypes.Type(value = CommandResult.AttachResult.class),
    @JsonSubTypes.Type(value = CommandResult.PromptResult.class),
    @JsonSubTypes.Type(value = CommandResult.SteerResult.class),
    @JsonSubTypes.Type(value = CommandResult.AbortResult.class),
    @JsonSubTypes.Type(value = CommandResult.SetModelResult.class),
    @JsonSubTypes.Type(value = CommandResult.SetThinkingResult.class)
})
public sealed interface CommandResult {

    /** 线格式 type 值。 */
    @JsonProperty("type")
    String type();

    @JsonTypeName("list")
    record ListResult(List<SessionMetadata> sessions) implements CommandResult {
        @Override public String type() { return "list"; }
    }

    @JsonTypeName("detach")
    record DetachResult(String sessionId) implements CommandResult {
        @Override public String type() { return "detach"; }
    }

    @JsonTypeName("create")
    record CreateResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "create"; }
    }

    @JsonTypeName("attach")
    record AttachResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "attach"; }
    }

    @JsonTypeName("prompt")
    record PromptResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "prompt"; }
    }

    @JsonTypeName("steer")
    record SteerResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "steer"; }
    }

    @JsonTypeName("abort")
    record AbortResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "abort"; }
    }

    @JsonTypeName("set_model")
    record SetModelResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "set_model"; }
    }

    @JsonTypeName("set_thinking")
    record SetThinkingResult(SessionSnapshot session) implements CommandResult {
        @Override public String type() { return "set_thinking"; }
    }
}
