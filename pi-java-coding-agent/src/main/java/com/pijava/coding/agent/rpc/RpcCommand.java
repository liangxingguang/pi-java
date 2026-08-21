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
    @JsonSubTypes.Type(value = RpcCommand.GetLastAssistantText.class),
    @JsonSubTypes.Type(value = RpcCommand.SetModel.class),
    @JsonSubTypes.Type(value = RpcCommand.CycleModel.class),
    @JsonSubTypes.Type(value = RpcCommand.GetAvailableModels.class),
    @JsonSubTypes.Type(value = RpcCommand.SetThinkingLevel.class),
    @JsonSubTypes.Type(value = RpcCommand.CycleThinkingLevel.class),
    @JsonSubTypes.Type(value = RpcCommand.GetAvailableThinkingLevels.class),
    @JsonSubTypes.Type(value = RpcCommand.Compact.class),
    @JsonSubTypes.Type(value = RpcCommand.SetAutoCompaction.class),
    @JsonSubTypes.Type(value = RpcCommand.GetSessionStats.class),
    @JsonSubTypes.Type(value = RpcCommand.SetSessionName.class),
    @JsonSubTypes.Type(value = RpcCommand.GetCommands.class),
    @JsonSubTypes.Type(value = RpcCommand.SetSteeringMode.class),
    @JsonSubTypes.Type(value = RpcCommand.SetFollowUpMode.class),
    @JsonSubTypes.Type(value = RpcCommand.SetAutoRetry.class),
    @JsonSubTypes.Type(value = RpcCommand.AbortRetry.class),
    @JsonSubTypes.Type(value = RpcCommand.Bash.class),
    @JsonSubTypes.Type(value = RpcCommand.AbortBash.class),
    @JsonSubTypes.Type(value = RpcCommand.ExportHtml.class),
    @JsonSubTypes.Type(value = RpcCommand.SwitchSession.class),
    @JsonSubTypes.Type(value = RpcCommand.Fork.class),
    @JsonSubTypes.Type(value = RpcCommand.Clone.class),
    @JsonSubTypes.Type(value = RpcCommand.GetForkMessages.class),
    @JsonSubTypes.Type(value = RpcCommand.GetEntries.class),
    @JsonSubTypes.Type(value = RpcCommand.GetTree.class)
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

    // ── 次批（P6-5c）：模型/思考等级/压缩控制面 ──────────────────────────

    @JsonTypeName("set_model")
    record SetModel(String id, String model) implements RpcCommand {
        @Override public String type() { return "set_model"; }
    }

    @JsonTypeName("cycle_model")
    record CycleModel(String id) implements RpcCommand {
        @Override public String type() { return "cycle_model"; }
    }

    @JsonTypeName("get_available_models")
    record GetAvailableModels(String id) implements RpcCommand {
        @Override public String type() { return "get_available_models"; }
    }

    @JsonTypeName("set_thinking_level")
    record SetThinkingLevel(String id, String level) implements RpcCommand {
        @Override public String type() { return "set_thinking_level"; }
    }

    @JsonTypeName("cycle_thinking_level")
    record CycleThinkingLevel(String id) implements RpcCommand {
        @Override public String type() { return "cycle_thinking_level"; }
    }

    @JsonTypeName("get_available_thinking_levels")
    record GetAvailableThinkingLevels(String id) implements RpcCommand {
        @Override public String type() { return "get_available_thinking_levels"; }
    }

    @JsonTypeName("compact")
    record Compact(String id) implements RpcCommand {
        @Override public String type() { return "compact"; }
    }

    @JsonTypeName("set_auto_compaction")
    record SetAutoCompaction(String id, boolean enabled) implements RpcCommand {
        @Override public String type() { return "set_auto_compaction"; }
    }

    @JsonTypeName("get_session_stats")
    record GetSessionStats(String id) implements RpcCommand {
        @Override public String type() { return "get_session_stats"; }
    }

    @JsonTypeName("set_session_name")
    record SetSessionName(String id, String name) implements RpcCommand {
        @Override public String type() { return "set_session_name"; }
    }

    @JsonTypeName("get_commands")
    record GetCommands(String id) implements RpcCommand {
        @Override public String type() { return "get_commands"; }
    }

    // ── 末批（P6-5d）：队列模式 / 重试 / bash / 会话 / 导出 ─────────────────

    @JsonTypeName("set_steering_mode")
    record SetSteeringMode(String id, String mode) implements RpcCommand {
        @Override public String type() { return "set_steering_mode"; }
    }

    @JsonTypeName("set_follow_up_mode")
    record SetFollowUpMode(String id, String mode) implements RpcCommand {
        @Override public String type() { return "set_follow_up_mode"; }
    }

    @JsonTypeName("set_auto_retry")
    record SetAutoRetry(String id, boolean enabled) implements RpcCommand {
        @Override public String type() { return "set_auto_retry"; }
    }

    @JsonTypeName("abort_retry")
    record AbortRetry(String id) implements RpcCommand {
        @Override public String type() { return "abort_retry"; }
    }

    @JsonTypeName("bash")
    record Bash(String id, String command, Boolean excludeFromContext)
        implements RpcCommand {
        @Override public String type() { return "bash"; }
    }

    @JsonTypeName("abort_bash")
    record AbortBash(String id) implements RpcCommand {
        @Override public String type() { return "abort_bash"; }
    }

    @JsonTypeName("export_html")
    record ExportHtml(String id, String outputPath) implements RpcCommand {
        @Override public String type() { return "export_html"; }
    }

    @JsonTypeName("switch_session")
    record SwitchSession(String id, String sessionPath) implements RpcCommand {
        @Override public String type() { return "switch_session"; }
    }

    @JsonTypeName("fork")
    record Fork(String id, String entryId) implements RpcCommand {
        @Override public String type() { return "fork"; }
    }

    @JsonTypeName("clone")
    record Clone(String id) implements RpcCommand {
        @Override public String type() { return "clone"; }
    }

    @JsonTypeName("get_fork_messages")
    record GetForkMessages(String id) implements RpcCommand {
        @Override public String type() { return "get_fork_messages"; }
    }

    @JsonTypeName("get_entries")
    record GetEntries(String id, String since) implements RpcCommand {
        @Override public String type() { return "get_entries"; }
    }

    @JsonTypeName("get_tree")
    record GetTree(String id) implements RpcCommand {
        @Override public String type() { return "get_tree"; }
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
