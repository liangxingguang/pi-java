package com.pijava.coding.agent.rpc;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import com.pijava.ai.message.ContentBlock;

/**
 * RPC 命令 —— stdin 上的 type-tagged JSONL 命令（对齐 pi {@code rpc-types.ts}，
 * 非 JSON-RPC 2.0）。
 *
 * <p>P6-5b 首批 8 个命令覆盖基本对话回路。命令字段不同 → sealed interface +
 * record；{@code type} 判别字段经 {@code @JsonTypeInfo(EXISTING_PROPERTY)} 读取
 * 各 record 的 {@link #type()}。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
    include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RpcCommand.Prompt.class),
    @JsonSubTypes.Type(value = RpcCommand.Steer.class),
    @JsonSubTypes.Type(value = RpcCommand.FollowUp.class),
    @JsonSubTypes.Type(value = RpcCommand.Abort.class),
    @JsonSubTypes.Type(value = RpcCommand.GetState.class),
    @JsonSubTypes.Type(value = RpcCommand.NewSession.class),
    @JsonSubTypes.Type(value = RpcCommand.GetMessages.class),
    @JsonSubTypes.Type(value = RpcCommand.GetLastAssistantText.class)
})
public sealed interface RpcCommand {

    /** 可选关联 ID，回显在响应里（pi: {@code id?: string}）。 */
    String id();

    /** 线格式的 type 值，如 "prompt"、"get_state"。 */
    @JsonProperty("type")
    String type();

    @JsonTypeName("prompt")
    record Prompt(String id, String message, List<ContentBlock.ImageContent> images,
                  StreamingBehavior streamingBehavior) implements RpcCommand {
        @Override public String type() { return "prompt"; }
    }

    @JsonTypeName("steer")
    record Steer(String id, String message, List<ContentBlock.ImageContent> images)
        implements RpcCommand {
        @Override public String type() { return "steer"; }
    }

    @JsonTypeName("follow_up")
    record FollowUp(String id, String message) implements RpcCommand {
        @Override public String type() { return "follow_up"; }
    }

    @JsonTypeName("abort")
    record Abort(String id) implements RpcCommand {
        @Override public String type() { return "abort"; }
    }

    @JsonTypeName("get_state")
    record GetState(String id) implements RpcCommand {
        @Override public String type() { return "get_state"; }
    }

    @JsonTypeName("new_session")
    record NewSession(String id) implements RpcCommand {
        @Override public String type() { return "new_session"; }
    }

    @JsonTypeName("get_messages")
    record GetMessages(String id) implements RpcCommand {
        @Override public String type() { return "get_messages"; }
    }

    @JsonTypeName("get_last_assistant_text")
    record GetLastAssistantText(String id) implements RpcCommand {
        @Override public String type() { return "get_last_assistant_text"; }
    }

    /** pi: streamingBehavior?: "steer" | "followUp" —— 纯常量闭集 → enum。 */
    enum StreamingBehavior {
        STEER, FOLLOW_UP;

        /** pi: "steer" | "followUp"。 */
        @JsonValue
        public String wireName() {
            return this == STEER ? "steer" : "followUp";
        }
    }
}
