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

    // ── Stage B DTO：文件 / git ─────────────────────────────────────────

    /** 目录项（文件浏览器）。 */
    public record DirEntry(String name, String kind, long size, long modifiedMs) {
    }

    /** git status 项（{@code indexStatus}/{@code workTreeStatus}：A/M/D/R/C/U，空 = 无）。 */
    public record StatusEntry(String path, String indexStatus, String workTreeStatus) {
    }

    /** git 提交项。 */
    public record CommitInfo(String id, String message, String author, String date) {
    }

    /** skill 项（Stage C）。 */
    public record SkillInfo(String name, String description) {
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
        @JsonSubTypes.Type(value = WebClientMessage.LoadSession.class),
        @JsonSubTypes.Type(value = WebClientMessage.ListDir.class),
        @JsonSubTypes.Type(value = WebClientMessage.ReadFile.class),
        @JsonSubTypes.Type(value = WebClientMessage.GitStatus.class),
        @JsonSubTypes.Type(value = WebClientMessage.GitDiff.class),
        @JsonSubTypes.Type(value = WebClientMessage.GitHistory.class),
        @JsonSubTypes.Type(value = WebClientMessage.Bash.class),
        @JsonSubTypes.Type(value = WebClientMessage.AbortBash.class),
        @JsonSubTypes.Type(value = WebClientMessage.GetTree.class),
        @JsonSubTypes.Type(value = WebClientMessage.Fork.class),
        @JsonSubTypes.Type(value = WebClientMessage.Clone.class),
        @JsonSubTypes.Type(value = WebClientMessage.ExportHtml.class),
        @JsonSubTypes.Type(value = WebClientMessage.ListSkills.class)
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

        // ── Stage B 扩展：文件浏览器 / git（代码调试）──────────────────────

        @JsonTypeName("listDir")
        record ListDir(String path) implements WebClientMessage {
            @Override public String type() { return "listDir"; }
        }

        @JsonTypeName("readFile")
        record ReadFile(String path) implements WebClientMessage {
            @Override public String type() { return "readFile"; }
        }

        @JsonTypeName("gitStatus")
        record GitStatus() implements WebClientMessage {
            @Override public String type() { return "gitStatus"; }
        }

        @JsonTypeName("gitDiff")
        record GitDiff(String file, Boolean staged) implements WebClientMessage {
            @Override public String type() { return "gitDiff"; }
        }

        @JsonTypeName("gitHistory")
        record GitHistory(String file, Integer limit) implements WebClientMessage {
            @Override public String type() { return "gitHistory"; }
        }

        // ── Stage C 扩展：终端 / fork / 导出 / skills ──────────────────────

        @JsonTypeName("bash")
        record Bash(String command) implements WebClientMessage {
            @Override public String type() { return "bash"; }
        }

        @JsonTypeName("abortBash")
        record AbortBash() implements WebClientMessage {
            @Override public String type() { return "abortBash"; }
        }

        @JsonTypeName("getTree")
        record GetTree() implements WebClientMessage {
            @Override public String type() { return "getTree"; }
        }

        @JsonTypeName("fork")
        record Fork(String entryId) implements WebClientMessage {
            @Override public String type() { return "fork"; }
        }

        @JsonTypeName("clone")
        record Clone() implements WebClientMessage {
            @Override public String type() { return "clone"; }
        }

        @JsonTypeName("exportHtml")
        record ExportHtml() implements WebClientMessage {
            @Override public String type() { return "exportHtml"; }
        }

        @JsonTypeName("listSkills")
        record ListSkills() implements WebClientMessage {
            @Override public String type() { return "listSkills"; }
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
        @JsonSubTypes.Type(value = WebServerMessage.SessionChanged.class),
        @JsonSubTypes.Type(value = WebServerMessage.DirListing.class),
        @JsonSubTypes.Type(value = WebServerMessage.FileContent.class),
        @JsonSubTypes.Type(value = WebServerMessage.GitStatusResult.class),
        @JsonSubTypes.Type(value = WebServerMessage.GitDiffResult.class),
        @JsonSubTypes.Type(value = WebServerMessage.GitHistoryResult.class),
        @JsonSubTypes.Type(value = WebServerMessage.BashResult.class),
        @JsonSubTypes.Type(value = WebServerMessage.Tree.class),
        @JsonSubTypes.Type(value = WebServerMessage.ExportPath.class),
        @JsonSubTypes.Type(value = WebServerMessage.Skills.class)
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

        // ── Stage B 扩展：文件浏览器 / git ────────────────────────────────

        /** 目录列表响应（{@code dirListing}）。 */
        @JsonTypeName("dirListing")
        record DirListing(String path, List<DirEntry> entries) implements WebServerMessage {
            @Override public String type() { return "dirListing"; }
        }

        /** 文件内容响应（{@code fileContent}）。 */
        @JsonTypeName("fileContent")
        record FileContent(String path, String content, boolean truncated)
                implements WebServerMessage {
            @Override public String type() { return "fileContent"; }
        }

        /** git status 响应（{@code gitStatusResult}）。 */
        @JsonTypeName("gitStatusResult")
        record GitStatusResult(String cwd, List<StatusEntry> status)
                implements WebServerMessage {
            @Override public String type() { return "gitStatusResult"; }
        }

        /** git diff 响应（{@code gitDiffResult}，unified diff 文本）。 */
        @JsonTypeName("gitDiffResult")
        record GitDiffResult(String file, boolean staged, String diff)
                implements WebServerMessage {
            @Override public String type() { return "gitDiffResult"; }
        }

        /** git 历史响应（{@code gitHistoryResult}）。 */
        @JsonTypeName("gitHistoryResult")
        record GitHistoryResult(String file, List<CommitInfo> commits)
                implements WebServerMessage {
            @Override public String type() { return "gitHistoryResult"; }
        }

        // ── Stage C 扩展：终端 / fork / 导出 / skills ─────────────────────

        /** bash 执行完成（{@code bashResult}）。 */
        @JsonTypeName("bashResult")
        record BashResult(String command, int exitCode, String output,
                          boolean truncated, boolean aborted)
                implements WebServerMessage {
            @Override public String type() { return "bashResult"; }
        }

        /** 会话树（{@code tree}，节点 {@code {entry, children}}）。 */
        @JsonTypeName("tree")
        record Tree(List<ObjectNode> tree, String leafId) implements WebServerMessage {
            @Override public String type() { return "tree"; }
        }

        /** 导出 HTML 路径（{@code exportPath}）。 */
        @JsonTypeName("exportPath")
        record ExportPath(String path) implements WebServerMessage {
            @Override public String type() { return "exportPath"; }
        }

        /** skill 列表（{@code skills}）。 */
        @JsonTypeName("skills")
        record Skills(List<SkillInfo> skills) implements WebServerMessage {
            @Override public String type() { return "skills"; }
        }
    }
}
