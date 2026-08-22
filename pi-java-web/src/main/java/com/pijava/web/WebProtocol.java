package com.pijava.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * WebSocket JSON 协议（Phase 7）—— 基线对齐 pi-webui {@code shared/protocol.ts}，
 * 完整功能作协议超集。
 *
 * <p>客户端 → 服务端：{@link WebClientMessage}；服务端 → 客户端：{@link WebServerMessage}。
 * 事件/状态载荷（{@code agentEvent.event}、{@code stateSync.state}）为预序列化的
 * JSON 节点（经 {@code SessionJson.mapper()}），事件字段名对齐 pi-webui 前端
 * {@code handleAgentEvent} / {@code applyStateSync} 期望。</p>
 */
public final class WebProtocol {

    private WebProtocol() {}

    // ── DTO ─────────────────────────────────────────────────────────────

    /** 模型信息（pi-webui {@code ModelInfo}）。 */
    public record ModelInfo(String provider, String id, String name) {
    }

    /** 会话列表项（pi-webui {@code SessionListItem}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionListItem(
            String id, String path, String name, String cwd,
            String created, String modified, int messageCount, String firstMessage) {
    }

    /** 序列化 agent 状态（pi-webui {@code SerializedAgentState}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SerializedAgentState(
            List<ObjectNode> messages,
            ModelInfo model,
            String thinkingLevel,
            String systemPrompt,
            boolean isStreaming,
            ObjectNode streamingMessage,
            String errorMessage,
            List<String> tools,
            String sessionId,
            String sessionName) {
    }

    // ── 客户端 → 服务端 ─────────────────────────────────────────────────

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
        include = JsonTypeInfo.As.EXISTING_PROPERTY)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = WebClientMessage.Prompt.class),
        @JsonSubTypes.Type(value = WebClientMessage.Steer.class),
        @JsonSubTypes.Type(value = WebClientMessage.FollowUp.class),
        @JsonSubTypes.Type(value = WebClientMessage.Abort.class),
        @JsonSubTypes.Type(value = WebClientMessage.GetModels.class),
        @JsonSubTypes.Type(value = WebClientMessage.SetModel.class),
        @JsonSubTypes.Type(value = WebClientMessage.SetThinkingLevel.class),
        @JsonSubTypes.Type(value = WebClientMessage.GetState.class),
        @JsonSubTypes.Type(value = WebClientMessage.NewSession.class),
        @JsonSubTypes.Type(value = WebClientMessage.GetSessions.class),
        @JsonSubTypes.Type(value = WebClientMessage.LoadSession.class)
    })
    public sealed interface WebClientMessage {

        /** 线格式的 type 判别值（如 "prompt"、"getState"）。 */
        @JsonProperty("type")
        String type();

        @JsonTypeName("prompt")
        record Prompt(String text) implements WebClientMessage {
            @Override public String type() { return "prompt"; }
        }

        @JsonTypeName("steer")
        record Steer(String text) implements WebClientMessage {
            @Override public String type() { return "steer"; }
        }

        @JsonTypeName("followUp")
        record FollowUp(String text) implements WebClientMessage {
            @Override public String type() { return "followUp"; }
        }

        @JsonTypeName("abort")
        record Abort() implements WebClientMessage {
            @Override public String type() { return "abort"; }
        }

        @JsonTypeName("getModels")
        record GetModels() implements WebClientMessage {
            @Override public String type() { return "getModels"; }
        }

        @JsonTypeName("setModel")
        record SetModel(String provider, String modelId) implements WebClientMessage {
            @Override public String type() { return "setModel"; }
        }

        @JsonTypeName("setThinkingLevel")
        record SetThinkingLevel(String level) implements WebClientMessage {
            @Override public String type() { return "setThinkingLevel"; }
        }

        @JsonTypeName("getState")
        record GetState() implements WebClientMessage {
            @Override public String type() { return "getState"; }
        }

        @JsonTypeName("newSession")
        record NewSession() implements WebClientMessage {
            @Override public String type() { return "newSession"; }
        }

        @JsonTypeName("getSessions")
        record GetSessions() implements WebClientMessage {
            @Override public String type() { return "getSessions"; }
        }

        @JsonTypeName("loadSession")
        record LoadSession(String sessionPath) implements WebClientMessage {
            @Override public String type() { return "loadSession"; }
        }
    }

    // ── 服务端 → 客户端 ─────────────────────────────────────────────────

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
        include = JsonTypeInfo.As.EXISTING_PROPERTY)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = WebServerMessage.Ready.class),
        @JsonSubTypes.Type(value = WebServerMessage.StateSync.class),
        @JsonSubTypes.Type(value = WebServerMessage.AgentEvent.class),
        @JsonSubTypes.Type(value = WebServerMessage.Models.class),
        @JsonSubTypes.Type(value = WebServerMessage.ModelChanged.class),
        @JsonSubTypes.Type(value = WebServerMessage.Error.class),
        @JsonSubTypes.Type(value = WebServerMessage.Sessions.class),
        @JsonSubTypes.Type(value = WebServerMessage.SessionChanged.class)
    })
    public sealed interface WebServerMessage {

        /** 线格式的 type 判别值（如 "ready"、"stateSync"）。 */
        @JsonProperty("type")
        String type();

        /** 连接建立（pi-webui 前端 onopen 后忽略，随 {@code getModels}/{@code getState}）。 */
        @JsonTypeName("ready")
        record Ready() implements WebServerMessage {
            @Override public String type() { return "ready"; }
        }

        /** 状态快照（{@code stateSync}）。 */
        @JsonTypeName("stateSync")
        record StateSync(SerializedAgentState state) implements WebServerMessage {
            @Override public String type() { return "stateSync"; }
        }

        /** 事件（{@code agentEvent}，event 字段名为 pi-webui 前端词汇）。 */
        @JsonTypeName("agentEvent")
        record AgentEvent(ObjectNode event) implements WebServerMessage {
            @Override public String type() { return "agentEvent"; }
        }

        /** 模型列表（{@code models}）。 */
        @JsonTypeName("models")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Models(List<ModelInfo> models, ModelInfo current, String thinkingLevel)
                implements WebServerMessage {
            @Override public String type() { return "models"; }
        }

        /** 模型/思考等级变更广播（{@code modelChanged}）。 */
        @JsonTypeName("modelChanged")
        record ModelChanged(ModelInfo model, String thinkingLevel)
                implements WebServerMessage {
            @Override public String type() { return "modelChanged"; }
        }

        /** 错误（{@code error}）。 */
        @JsonTypeName("error")
        record Error(String message) implements WebServerMessage {
            @Override public String type() { return "error"; }
        }

        /** 会话列表（{@code sessions}）。 */
        @JsonTypeName("sessions")
        record Sessions(List<SessionListItem> sessions, String currentSessionId)
                implements WebServerMessage {
            @Override public String type() { return "sessions"; }
        }

        /** 会话切换广播（{@code sessionChanged}）。 */
        @JsonTypeName("sessionChanged")
        record SessionChanged(String sessionId) implements WebServerMessage {
            @Override public String type() { return "sessionChanged"; }
        }
    }
}
