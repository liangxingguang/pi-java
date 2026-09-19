import "@mariozechner/mini-lit/dist/ThemeToggle.js";
import "./app.css";

import { html, render, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { ChevronDown, Plus, History, PanelLeftClose, Menu, Code2, GitFork, Download, Pencil, Info, Send, Compass, Settings } from "lucide";

import type { AgentMessage } from "@mariozechner/pi-agent-core";
import type { ToolResultMessage } from "@mariozechner/pi-ai";

import {
  MessageList,
  MessageEditor,
  StreamingMessageContainer,
} from "@mariozechner/pi-web-ui";

void MessageList;
void MessageEditor;
void StreamingMessageContainer;
import type {
  ClientMessage,
  ServerMessage,
  ModelInfo,
  SerializedAgentState,
  SessionListItem,
} from "../shared/protocol.js";

import * as debug from "./panels/debug.js";
import * as treePanel from "./panels/tree.js";
import * as statsPanel from "./panels/stats.js";
import * as settingsPanel from "./panels/settings.js";
import { initRuntime } from "./runtime.js";
import { truncateText } from "./utils.js";

// ── State ──

let ws: WebSocket | null = null;
let connected = false;
let messages: AgentMessage[] = [];
let isStreaming = false;
let streamingMessage: AgentMessage | null = null;
let currentModel: ModelInfo | undefined;
let thinkingLevel = "off";
let availableModels: ModelInfo[] = [];
let errorMessage: string | undefined;
let showModelDropdown = false;
let modelFilter = "";
let toolNames: string[] = [];
let currentSessionId = "";
let currentSessionName: string | undefined;
let sidebarOpen = false;
let sessionList: SessionListItem[] = [];
let sessionsLoading = false;
let sessionFilter = "";
let notice = "";
let exportPath = "";

// ── WebSocket ──

let wsPort: number | null = null;
let requiresAuth = false;
let token = localStorage.getItem("pi-java-web-token") || "";

// pi-java-web: static and WS run on different ports; discover the WS port via /api/config.
async function getWsUrl(): Promise<string> {
  if (wsPort === null) {
    try {
      const res = await fetch("/api/config");
      const cfg = await res.json();
      wsPort = cfg.wsPort ?? 0;
      requiresAuth = cfg.requiresAuth === true;
    } catch {
      wsPort = 0;
    }
  }
  if (requiresAuth && !token) {
    token = window.prompt("Enter gateway token (printed in the server terminal):") || "";
    if (token) localStorage.setItem("pi-java-web-token", token);
  }
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  const host = wsPort ? `${location.hostname}:${wsPort}` : location.host;
  const qs = token ? `?token=${encodeURIComponent(token)}` : "";
  return `${proto}//${host}/api/ws${qs}`;
}

function connectWs() {
  void getWsUrl().then((url) => {
    ws = new WebSocket(url);
    ws.onopen = () => {
      connected = true;
      send({ type: "getModels" });
      send({ type: "getState" });
      send({ type: "listDir", path: "" });
      send({ type: "gitStatus" });
      renderApp();
    };

    ws.onclose = () => {
      connected = false;
      ws = null;
      renderApp();
      setTimeout(connectWs, 2000);
    };

    ws.onerror = () => {
      ws?.close();
    };

    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data) as ServerMessage;
      if (msg.type === "agentEvent") {
        console.log("[ws<-server]", msg.event?.type, JSON.stringify(msg.event || {}).slice(0, 160));
      } else {
        console.log("[ws<-server]", msg.type);
      }
      handleServerMessage(msg);
    };
  });
}

function send(msg: ClientMessage) {
  if (ws?.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function handleServerMessage(msg: ServerMessage) {
  switch (msg.type) {
    case "ready":
      break;

    case "stateSync":
      applyStateSync(msg.state);
      break;

    case "agentEvent":
      handleAgentEvent(msg.event);
      break;

    case "models":
      availableModels = msg.models;
      if (msg.current) currentModel = msg.current;
      if (msg.thinkingLevel) thinkingLevel = msg.thinkingLevel;
      renderApp();
      break;

    case "modelChanged":
      currentModel = msg.model;
      thinkingLevel = msg.thinkingLevel;
      renderApp();
      break;

    case "error":
      errorMessage = msg.message;
      renderApp();
      setTimeout(() => {
        errorMessage = undefined;
        renderApp();
      }, 5000);
      break;

    case "sessions":
      sessionList = msg.sessions;
      currentSessionId = msg.currentSessionId;
      sessionsLoading = false;
      renderApp();
      break;

    case "sessionChanged":
      currentSessionId = msg.sessionId;
      renderApp();
      break;

    // ── Stage D：会话控制 ──
    case "sessionNameChanged":
      currentSessionId = msg.sessionId;
      currentSessionName = msg.name;
      renderApp();
      break;

    // ── Stage B：文件 / git（debug 面板）──
    case "dirListing":
      debug.onDirListing(msg.path, msg.entries);
      break;

    case "fileContent":
      debug.onFileContent(msg.path, msg.content);
      break;

    case "gitStatusResult":
      debug.onGitStatusResult(msg.status);
      break;

    case "gitDiffResult":
      debug.onGitDiffResult(msg.file, msg.diff);
      break;

    case "gitHistoryResult":
      debug.onGitHistoryResult(msg.commits);
      break;

    // ── Stage C：终端 / 导出 / skills ──
    case "bashResult":
      debug.onBashResult(msg);
      break;

    case "skills":
      debug.onSkills(msg.skills);
      break;

    case "exportPath":
      exportPath = msg.path;
      notice = `Exported to ${msg.path}`;
      renderApp();
      setTimeout(() => { notice = ""; renderApp(); }, 8000);
      break;

    // ── Phase 1：会话历史 / 树 ──
    case "tree":
      treePanel.onTree(msg);
      break;

    // ── Phase 2：会话统计 ──
    case "sessionStats":
      statsPanel.onSessionStats(msg);
      break;

    // ── Phase 3：设置 ──
    case "settingsState":
      settingsPanel.onSettingsState(msg.settings);
      break;
  }
}

function applyStateSync(state: SerializedAgentState) {
  console.log("[stateSync] messages=", state.messages.length, "| sessionId=", state.sessionId);
  messages = state.messages;
  isStreaming = state.isStreaming;
  streamingMessage = state.streamingMessage || null;
  thinkingLevel = state.thinkingLevel;
  toolNames = state.tools;
  if (state.model) currentModel = state.model;
  if (state.errorMessage) errorMessage = state.errorMessage;
  currentSessionId = state.sessionId;
  currentSessionName = state.sessionName;
  renderApp();
}

function handleAgentEvent(event: any) {
  switch (event.type) {
    case "agent_start":
      isStreaming = true;
      renderApp();
      break;

    case "agent_end":
      isStreaming = false;
      streamingMessage = null;
      if (event.messages) {
        // agent_end 携带完整累计 transcript（后端 AgentEnd 权威收口）——整表替换，
        // 而非按 (role,timestamp) 去重追加：序列化消息不含 timestamp，去重会把
        // 新回合的 user/assistant 误判为重复而丢弃（最后一组不回显，需刷新才显示）。
        console.log("[agent_end] REPLACING messages: incoming=",
          event.messages.length, "| prev=", messages.length, "| willRetry=", event.willRetry);
        messages = event.messages;
      } else {
        // 关键诊断：agent_end 没有 messages 字段 → 前端保留旧列表，
        // streaming 容器被清空 → 本回合输出消失（症状 2）。
        console.warn("[agent_end] NO messages field in payload! prev=", messages.length);
      }
      updateStreamingContainer(null, false);
      renderApp();
      break;

    case "message_start":
      renderApp();
      break;

    case "message_update":
      streamingMessage = event.message;
      updateStreamingContainer(event.message, true);
      break;

    case "message_end":
      // 即时回显 user 消息（后端 run 启动即发 message_end，对齐 pi）。
      // 直接追加而非按 timestamp 去重：wire 序列化不含 timestamp（都为
      // undefined），去重会把后续轮次的新 user 消息误判为已存在而丢弃；
      // 且 message_end 必先于 agent_end（整表赋值）到达，不会重复。
      if (event.message && event.message.role === "user") {
        console.log("[message_end] user echo ->", event.message.content);
        messages = [...messages, event.message];
      }
      streamingMessage = null;
      updateStreamingContainer(null, true);
      renderApp();
      break;

    case "turn_start":
      renderApp();
      break;

    case "turn_end":
      if (event.toolResults) {
        for (const tr of event.toolResults) {
          const existing = messages.findIndex(
            (m: any) => m.role === "toolResult" && m.toolCallId === (tr as ToolResultMessage).toolCallId
          );
          if (existing === -1) {
            messages = [...messages, tr];
          }
        }
      }
      renderApp();
      break;

    case "tool_execution_start":
    case "tool_execution_update":
    case "tool_execution_end":
      renderApp();
      break;

    case "bashOutput":
      debug.onBashOutput(event.delta || "");
      break;
  }
}

function updateStreamingContainer(message: AgentMessage | null, streaming: boolean) {
  const container = document.querySelector("streaming-message-container") as StreamingMessageContainer | null;
  if (container) {
    container.isStreaming = streaming;
    container.setMessage(message, !streaming);
  }
}

// ── User actions ──

function handleSend(input: string) {
  if (!input.trim() || isStreaming) return;
  // DEBUG: 前端本地不追加 user 消息；可见性完全依赖 agent_end 携带全量 messages。
  // 若 agent_end 无 messages 字段，本回合 user+assistant 两帧都会从列表消失。
  console.log("[send] prompt=", input, "| messages.len=", messages.length, "| isStreaming=", isStreaming);
  send({ type: "prompt", text: input });

  const editor = document.querySelector("message-editor") as MessageEditor | null;
  if (editor) {
    editor.value = "";
    editor.attachments = [];
  }
}

function handleAbort() {
  send({ type: "abort" });
}

function handleModelSelect(model: ModelInfo) {
  send({ type: "setModel", provider: model.provider, modelId: model.id });
  showModelDropdown = false;
  renderApp();
}

function handleThinkingChange(level: string) {
  send({ type: "setThinkingLevel", level });
}

// ── Phase 2：followUp / steer ──

function editorValue(): string {
  const editor = document.querySelector("message-editor") as MessageEditor | null;
  return editor ? (editor as any).value || "" : "";
}

function clearEditor() {
  const editor = document.querySelector("message-editor") as MessageEditor | null;
  if (editor) {
    editor.value = "";
    editor.attachments = [];
  }
}

function handleFollowUp() {
  if (isStreaming) return;
  send({ type: "followUp", text: editorValue() });
  clearEditor();
}

function handleSteer() {
  if (isStreaming) return;
  send({ type: "steer", text: editorValue() });
  clearEditor();
}

// ── Stage C：导出 / 克隆（会话级，留在 main）──

function handleCloneSession() {
  send({ type: "clone" });
}

function handleExportHtml() {
  exportPath = "";
  send({ type: "exportHtml" });
}

// ── 会话侧栏 ──

function toggleModelDropdown() {
  showModelDropdown = !showModelDropdown;
  modelFilter = "";
  renderApp();
  if (showModelDropdown) {
    requestAnimationFrame(() => {
      document.getElementById("model-filter")?.focus();
    });
  }
}

function handleNewSession() {
  send({ type: "newSession" });
  closeSidebar();
}

function handleRenameSession() {
  const newName = window.prompt("Rename current session", currentSessionName || "");
  if (newName !== null && newName.trim()) {
    send({ type: "setSessionName", name: newName.trim() });
  }
}

function toggleSidebar() {
  sidebarOpen = !sidebarOpen;
  if (sidebarOpen) {
    sessionsLoading = true;
    send({ type: "getSessions" });
  }
  renderApp();
}

function closeSidebar() {
  sidebarOpen = false;
  renderApp();
}

function handleLoadSession(sessionPath: string) {
  send({ type: "loadSession", sessionPath });
  closeSidebar();
}

document.addEventListener("click", (e) => {
  if (showModelDropdown) {
    const dropdown = document.getElementById("model-dropdown");
    const trigger = document.getElementById("model-trigger");
    if (dropdown && !dropdown.contains(e.target as Node) && !trigger?.contains(e.target as Node)) {
      showModelDropdown = false;
      renderApp();
    }
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && sidebarOpen) {
    closeSidebar();
  }
  if ((e.ctrlKey || e.metaKey) && e.key === "Enter" && !isStreaming) {
    e.preventDefault();
    handleFollowUp();
  }
});

// ── Render ──

function buildToolResultsMap(): Map<string, ToolResultMessage> {
  const map = new Map<string, ToolResultMessage>();
  for (const msg of messages) {
    if ((msg as any).role === "toolResult") {
      map.set((msg as ToolResultMessage).toolCallId, msg as ToolResultMessage);
    }
  }
  return map;
}

function formatRelativeTime(isoDate: string): string {
  const date = new Date(isoDate);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  if (diffMins < 1) return "just now";
  if (diffMins < 60) return `${diffMins}m ago`;
  const diffHours = Math.floor(diffMins / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 30) return `${diffDays}d ago`;
  return date.toLocaleDateString();
}

function sessionTitle(s: SessionListItem): string {
  if (s.name) return s.name;
  if (s.firstMessage) return truncateText(s.firstMessage, 60);
  return `Session ${s.id.slice(0, 8)}`;
}

function renderSidebar() {
  return html`
    <div class="sidebar-inner h-full flex flex-col">
      <div style="display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid var(--border);flex-shrink:0">
        <span style="font-size:14px;font-weight:600;color:var(--foreground)">Sessions</span>
        <div style="display:flex;align-items:center;gap:4px">
          <button
            style="padding:6px;border-radius:6px;border:none;background:transparent;cursor:pointer;color:var(--foreground);display:flex"
            title="New session"
            @click=${handleNewSession}
            @mouseenter=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--accent)'}
            @mouseleave=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
          >${icon(Plus, "sm")}</button>
          <button
            style="padding:6px;border-radius:6px;border:none;background:transparent;cursor:pointer;color:var(--foreground);display:flex"
            title="Rename session"
            @click=${handleRenameSession}
            @mouseenter=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--accent)'}
            @mouseleave=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
          >${icon(Pencil, "sm")}</button>
          <button
            style="padding:6px;border-radius:6px;border:none;background:transparent;cursor:pointer;color:var(--foreground);display:flex"
            title="Clone session (fork)"
            @click=${handleCloneSession}
            @mouseenter=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--accent)'}
            @mouseleave=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
          >${icon(GitFork, "sm")}</button>
          <button
            style="padding:6px;border-radius:6px;border:none;background:transparent;cursor:pointer;color:var(--foreground);display:flex"
            title="Export session as HTML"
            @click=${handleExportHtml}
            @mouseenter=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--accent)'}
            @mouseleave=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
          >${icon(Download, "sm")}</button>
          <button
            style="padding:6px;border-radius:6px;border:none;background:transparent;cursor:pointer;color:var(--foreground);display:flex"
            title="Session info"
            @click=${statsPanel.toggleStats}
            @mouseenter=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--accent)'}
            @mouseleave=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
          >${icon(Info, "sm")}</button>
          <button
            style="padding:6px;border-radius:6px;border:none;background:transparent;cursor:pointer;color:var(--foreground);display:flex"
            title="Close sidebar"
            @click=${closeSidebar}
            @mouseenter=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--accent)'}
            @mouseleave=${(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'}
          >${icon(PanelLeftClose, "sm")}</button>
        </div>
      </div>

      <div class="px-3 py-2 border-b border-border">
        <input
          type="text"
          placeholder="Filter sessions..."
          class="w-full px-2 py-1 text-xs rounded border border-border bg-background text-foreground outline-none"
          .value=${sessionFilter}
          @input=${(e: Event) => { sessionFilter = (e.target as HTMLInputElement).value; renderApp(); }}
        />
      </div>

      <div class="flex-1 overflow-y-auto">
        ${sessionsLoading ? html`
          <div style="display:flex;align-items:center;justify-content:center;padding:48px 0;color:var(--muted-foreground);font-size:13px">
            Loading sessions...
          </div>
        ` : sessionList.length === 0 ? html`
          <div style="display:flex;align-items:center;justify-content:center;padding:48px 0;color:var(--muted-foreground);font-size:13px">
            No sessions yet
          </div>
        ` : (() => {
          const q = sessionFilter.trim().toLowerCase();
          const filtered = q
            ? sessionList.filter((s) =>
                sessionTitle(s).toLowerCase().includes(q)
                || (s.firstMessage || "").toLowerCase().includes(q)
                || s.id.toLowerCase().includes(q))
            : sessionList;
          if (filtered.length === 0) {
            return html`
              <div style="display:flex;align-items:center;justify-content:center;padding:48px 0;color:var(--muted-foreground);font-size:13px">
                No matching sessions
              </div>
            `;
          }
          return filtered.map((s) => html`
            <button
              class="session-item ${s.id === currentSessionId ? 'active' : ''}"
              @click=${() => handleLoadSession(s.path)}
            >
              <div class="session-item-title">${sessionTitle(s)}</div>
              ${s.name && s.firstMessage ? html`
                <div class="session-item-preview">${truncateText(s.firstMessage, 80)}</div>
              ` : nothing}
              <div class="session-item-meta">
                <span>${s.messageCount} messages</span>
                <span>${formatRelativeTime(s.modified)}</span>
              </div>
            </button>
          `);
        })()}
      </div>
    </div>
  `;
}

function renderApp() {
  const app = document.getElementById("app");
  if (!app) return;

  const toolResultsById = buildToolResultsMap();
  // 已提交在跑、但还没有任何增量到达 ⇒ 「等 agent 结果」的那一段。
  const waiting = isStreaming && !streamingMessage;

  const appHtml = html`
    <!-- Mobile sidebar overlay -->
    <div
      class="sidebar-overlay ${sidebarOpen ? 'open' : ''}"
      @click=${closeSidebar}
    ></div>

    <!-- Sidebar panel -->
    <div class="sidebar-panel ${sidebarOpen ? 'open' : ''}">
      ${renderSidebar()}
    </div>

    <!-- Main content -->
    <div class="main-content flex flex-col h-full bg-background text-foreground min-w-0 flex-1">
      <!-- Header -->
      <div class="flex items-center gap-2 px-3 py-2 border-b border-border shrink-0 overflow-visible">
        <button
          class="p-1.5 rounded hover:bg-accent transition-colors shrink-0"
          title="${sidebarOpen ? 'Close sidebar' : 'Open sessions'}"
          @click=${toggleSidebar}
        >
          ${sidebarOpen ? icon(PanelLeftClose, "sm") : icon(Menu, "sm")}
        </button>

        <span class="font-semibold text-sm shrink-0 hidden sm:inline">Pi Web UI</span>

        <span class="text-[10px] px-1.5 py-0.5 rounded-full shrink-0 ${connected ? 'bg-green-500/20 text-green-400' : 'bg-red-500/20 text-red-400'}">
          ${connected ? "Connected" : "Disconnected"}
        </span>
        ${isStreaming ? html`
          <span class="text-[10px] px-1.5 py-0.5 rounded-full shrink-0 bg-amber-500/20 text-amber-400 animate-pulse">Running</span>
        ` : ""}

        <div class="flex-1"></div>

        <!-- Model selector -->
        <div class="relative shrink-0">
          <button
            id="model-trigger"
            class="flex items-center gap-1 px-2 py-1 text-xs rounded border border-border hover:bg-accent transition-colors"
            @click=${toggleModelDropdown}
          >
            <span class="max-w-[120px] sm:max-w-[200px] truncate">${currentModel?.id || "No model"}</span>
            ${icon(ChevronDown, "xs")}
          </button>
          ${showModelDropdown ? (() => {
            const sorted = [...availableModels].sort((a, b) => a.id.localeCompare(b.id));
            const filtered = modelFilter
              ? sorted.filter((m) => m.id.toLowerCase().includes(modelFilter.toLowerCase()))
              : sorted;
            return html`
            <div
              id="model-dropdown"
              class="fixed right-4 mt-1 z-[200] w-64 sm:w-72 max-h-96 flex flex-col rounded-md border border-border bg-popover shadow-lg"
            >
              <div class="p-1.5 border-b border-border shrink-0">
                <input
                  id="model-filter"
                  type="text"
                  placeholder="Filter models..."
                  class="w-full px-2 py-1 text-xs rounded border border-border bg-background text-foreground outline-none"
                  .value=${modelFilter}
                  @input=${(e: Event) => { modelFilter = (e.target as HTMLInputElement).value; renderApp(); requestAnimationFrame(() => document.getElementById("model-filter")?.focus()); }}
                  @keydown=${(e: KeyboardEvent) => { if (e.key === "Escape") { showModelDropdown = false; renderApp(); }}}
                />
              </div>
              <div class="overflow-y-auto flex-1">
                ${filtered.map(
                  (m) => html`
                    <button
                      class="w-full text-left px-3 py-2 text-xs hover:bg-accent transition-colors ${m.id === currentModel?.id ? 'bg-accent/50' : ''}"
                      @click=${() => handleModelSelect(m)}
                    >
                      ${m.id}
                    </button>
                  `
                )}
                ${filtered.length === 0 ? html`<div class="px-3 py-2 text-xs text-muted-foreground">No matches</div>` : ""}
              </div>
            </div>
          `; })() : ""}
        </div>

        <!-- Thinking level -->
        <select
          class="text-xs px-1.5 py-1 rounded border border-border bg-background shrink-0"
          .value=${thinkingLevel}
          @change=${(e: Event) => handleThinkingChange((e.target as HTMLSelectElement).value)}
        >
          <option value="off">No thinking</option>
          <option value="minimal">Minimal</option>
          <option value="low">Low</option>
          <option value="medium">Medium</option>
          <option value="high">High</option>
        </select>

        ${toolNames.length > 0 ? html`
          <span class="text-[10px] text-muted-foreground shrink-0 hidden sm:inline" title=${toolNames.join(", ")}>
            ${toolNames.length} tools
          </span>
        ` : ""}

        <button
          class="p-1.5 rounded hover:bg-accent transition-colors shrink-0"
          title="Code debugging (files / git)"
          @click=${debug.toggleDebug}
        >
          ${debug.isDebugOpen() ? icon(PanelLeftClose, "sm") : icon(Code2, "sm")}
        </button>

        <button
          class="p-1.5 rounded hover:bg-accent transition-colors shrink-0"
          title="Session history (tree / fork)"
          @click=${treePanel.toggleHistory}
        >
          ${treePanel.isHistoryOpen() ? icon(PanelLeftClose, "sm") : icon(History, "sm")}
        </button>

        <button
          class="p-1.5 rounded hover:bg-accent transition-colors shrink-0"
          title="Settings"
          @click=${settingsPanel.toggleSettings}
        >
          ${settingsPanel.isSettingsOpen() ? icon(PanelLeftClose, "sm") : icon(Settings, "sm")}
        </button>

        <theme-toggle class="shrink-0"></theme-toggle>
      </div>

      <!-- Notice banner -->
      ${notice ? html`
        <div class="px-4 py-2 text-sm border-b bg-green-500/10 text-green-400 border-green-500/20">
          ${notice}
        </div>
      ` : ""}

      <!-- Error banner -->
      ${errorMessage ? html`
        <div class="px-4 py-2 bg-destructive/10 text-destructive text-sm border-b border-destructive/20">
          ${errorMessage}
        </div>
      ` : ""}

      <!-- Messages area -->
      <div class="flex-1 overflow-y-auto px-3 sm:px-4 py-4" id="messages-scroll">
        ${messages.length === 0 && !isStreaming ? html`
          <div class="flex items-center justify-center h-full text-muted-foreground text-sm">
            Send a message to start a conversation
          </div>
        ` : html`
          <div class="max-w-4xl mx-auto flex flex-col gap-3">
            <message-list
              .messages=${messages}
              .tools=${[]}
              .pendingToolCalls=${new Set()}
              .isStreaming=${isStreaming}
            ></message-list>

            <!-- 等待首个增量时 pi-web-ui 自己会渲染一个闪烁的 2×4 方块；用外层
                 容器把它藏掉。⚠️ 不能直接给组件加 hidden 类 —— 它在
                 connectedCallback 里设了内联 display:block，会盖过类规则。 -->
            <div class="${waiting ? 'hidden' : ''}">
              <streaming-message-container
                class="${isStreaming ? '' : 'hidden'}"
                .tools=${[]}
                .isStreaming=${isStreaming}
                .pendingToolCalls=${new Set()}
                .toolResultsById=${toolResultsById}
              ></streaming-message-container>
            </div>

            ${waiting ? html`
              <div class="typing-dots mx-4 mb-3" role="status" aria-label="Assistant is working">
                <span></span><span></span><span></span>
              </div>
            ` : ""}
          </div>
        `}
      </div>

      <!-- Input area -->
      <div class="border-t border-border px-3 sm:px-4 py-2 sm:py-3 shrink-0">
        <div class="max-w-4xl mx-auto">
          <message-editor
            .isStreaming=${isStreaming}
            .currentModel=${currentModel ? { provider: currentModel.provider, id: currentModel.id, name: currentModel.name } as any : undefined}
            .thinkingLevel=${thinkingLevel}
            .showAttachmentButton=${false}
            .showModelSelector=${false}
            .showThinkingSelector=${false}
            .onSend=${(input: string) => handleSend(input)}
            .onAbort=${() => handleAbort()}
          ></message-editor>
          <div class="flex items-center gap-2 mt-1.5">
            <button
              class="flex items-center gap-1 px-2 py-1 text-xs rounded border border-border hover:bg-accent transition-colors text-muted-foreground shrink-0"
              @click=${handleFollowUp}
              ?disabled=${isStreaming}
              title="Continue / follow up from the last user turn"
            >${icon(Send, "xs")} Continue</button>
            <button
              class="flex items-center gap-1 px-2 py-1 text-xs rounded border border-border hover:bg-accent transition-colors text-muted-foreground shrink-0"
              @click=${handleSteer}
              ?disabled=${isStreaming}
              title="Steer the current turn"
            >${icon(Compass, "xs")} Steer</button>
            <span class="text-[10px] text-muted-foreground hidden sm:inline">Ctrl+Enter to continue</span>
          </div>
        </div>
      </div>
    </div>

    ${debug.renderDebugPanel()}

    ${treePanel.renderHistoryPanel()}

    ${statsPanel.renderStatsModal()}

    ${settingsPanel.renderSettingsPanel()}
  `;

  render(appHtml, app);

  if (isStreaming) {
    const scrollEl = document.getElementById("messages-scroll");
    if (scrollEl) {
      const isNearBottom = scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight < 150;
      if (isNearBottom) {
        scrollEl.scrollTop = scrollEl.scrollHeight;
      }
    }
  }
}

// ── Init ──

initRuntime(send, renderApp);
renderApp();
connectWs();
