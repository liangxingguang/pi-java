// Phase 2：会话统计弹层（messageCount / entryCount / model / thinking）。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { X } from "lucide";

import type { ModelInfo } from "../../shared/protocol.js";
import { send, requestRender } from "../runtime.js";

let statsOpen = false;
let sessionStats: {
  sessionId: string;
  name: string;
  messageCount: number;
  entryCount: number;
  model?: ModelInfo;
  thinkingLevel: string;
} | null = null;

export function toggleStats() {
  if (!statsOpen) {
    sessionStats = null;
    send({ type: "getSessionStats" });
  }
  statsOpen = !statsOpen;
  requestRender();
}

export function renderStatsModal() {
  if (!statsOpen || !sessionStats) return nothing;
  const s = sessionStats;
  return html`
    <div class="history-overlay open" @click=${toggleStats}></div>
    <div class="stats-panel open">
      <div class="history-header">
        <span style="font-size:13px;font-weight:600;color:var(--foreground)">Session info</span>
        <button class="history-header-btn" @click=${toggleStats} title="Close">${icon(X, "sm")}</button>
      </div>
      <div class="stats-body">
        <div class="stats-row"><span>Name</span><span>${s.name || "—"}</span></div>
        <div class="stats-row"><span>Session id</span><span class="mono">${s.sessionId.slice(0, 8)}</span></div>
        <div class="stats-row"><span>Messages</span><span>${s.messageCount}</span></div>
        <div class="stats-row"><span>Entries</span><span>${s.entryCount}</span></div>
        <div class="stats-row"><span>Model</span><span>${s.model ? s.model.id : "—"}</span></div>
        <div class="stats-row"><span>Thinking</span><span>${s.thinkingLevel}</span></div>
      </div>
    </div>
  `;
}

/** 处理 sessionStats 服务端消息。 */
export function onSessionStats(msg: {
  sessionId: string;
  name: string;
  messageCount: number;
  entryCount: number;
  model?: ModelInfo;
  thinkingLevel: string;
}): void {
  sessionStats = {
    sessionId: msg.sessionId,
    name: msg.name,
    messageCount: msg.messageCount,
    entryCount: msg.entryCount,
    model: msg.model,
    thinkingLevel: msg.thinkingLevel,
  };
  requestRender();
}
