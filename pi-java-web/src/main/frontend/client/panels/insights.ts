// 跨会话洞察页 —— 对齐 dsh-context 的 Context Insights：
// KPI、按日用量、工具账本与会话卡片。数据由后端扫描 JSONL 会话文件得到。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { BarChart3, RefreshCw, X } from "lucide";

import type {
  SessionInsightCard,
  SessionInsightDay,
  SessionInsightsReport,
  SessionInsightTool,
} from "../../shared/protocol.js";
import { send, requestRender } from "../runtime.js";

const COMPOSITION_COLORS: Record<string, string> = {
  user: "#10b981",
  injected: "#8b5cf6",
  answers: "#f59e0b",
  reasoning: "#ec4899",
  toolCalls: "#14b8a6",
  toolResults: "#64748b",
};

const RANGES: { key: string; label: string }[] = [
  { key: "today", label: "Today" },
  { key: "7d", label: "7 days" },
  { key: "30d", label: "30 days" },
  { key: "all", label: "All" },
];

let insightsOpen = false;
let report: SessionInsightsReport | null = null;
let loading = false;
let range = "7d";
let search = "";

// ── 对外钩子 ──

export function isInsightsOpen(): boolean {
  return insightsOpen;
}

export function toggleInsights() {
  if (!insightsOpen) {
    requestRange(range);
  }
  insightsOpen = !insightsOpen;
  requestRender();
}

function closeInsights() {
  insightsOpen = false;
  requestRender();
}

function requestRange(next: string) {
  range = next;
  loading = true;
  send({ type: "getSessionInsights", range: next });
  requestRender();
}

/** 处理 sessionInsights 服务端消息。 */
export function onSessionInsights(next: SessionInsightsReport): void {
  report = next;
  loading = false;
  requestRender();
}

// ── 页面 ──

export function renderInsightsPanel() {
  if (!insightsOpen) return nothing;
  return html`
    <div class="insights-overlay" @click=${closeInsights}></div>
    <section class="insights-panel" role="dialog" aria-label="Context insights">
      <div class="insights-header">
        <div class="insights-title">
          ${icon(BarChart3, "sm")}
          <span>Context Insights</span>
          ${report ? html`<span class="insights-backend">${report.backend}</span>` : nothing}
        </div>
        <div class="insights-actions">
          <div class="insights-ranges">
            ${RANGES.map((item) => html`
              <button
                class="ins-range ${range === item.key ? "active" : ""}"
                @click=${() => requestRange(item.key)}
              >${item.label}</button>
            `)}
          </div>
          <button class="history-header-btn" title="Refresh" @click=${() => requestRange(range)}>
            ${icon(RefreshCw, "sm")}
          </button>
          <button class="history-header-btn" title="Close" @click=${closeInsights}>
            ${icon(X, "sm")}
          </button>
        </div>
      </div>
      <div class="insights-body">
        ${loading && !report
          ? html`<div class="debug-empty">Scanning sessions...</div>`
          : !report
            ? html`<div class="debug-empty">No insights data</div>`
            : !report.supported
              ? html`<div class="debug-empty">
                  Insights currently support the JSONL session backend only
                  (active: ${report.backend || "unknown"}).
                </div>`
              : renderReport(report)}
      </div>
    </section>
  `;
}

function renderReport(r: SessionInsightsReport) {
  return html`
    ${renderKpis(r)}
    ${renderDays(r)}
    <div class="insights-split">
      ${renderTools(r.tools)}
      ${renderSessions(r.sessions)}
    </div>
    <div class="insights-note">
      Scanned ${r.sessionCount} session${r.sessionCount === 1 ? "" : "s"} with activity in this range.
      Token and cost figures are provider-reported usage; system prompt/tool composition is not
      available for historical sessions.
    </div>
  `;
}

// ── KPI ──

function renderKpis(r: SessionInsightsReport) {
  return html`
    <div class="ins-kpis">
      ${kpi("Sessions", String(r.sessionCount), `${r.turns} turns`)}
      ${kpi("Prompt", fmtTokens(r.promptTokens), `${fmtTokens(r.totalTokens)} total`)}
      ${kpi("Output", fmtTokens(r.outputTokens), `${r.toolCalls} tool calls`)}
      ${kpi("Cost", formatCost(r.costUsd), "provider usage")}
      ${kpi("Cache hit", r.promptTokens > 0 ? pct(r.cacheHitRate * 100) : "—", "prompt cache")}
      ${kpi("Avg / session", r.sessionCount > 0 ? fmtTokens(r.totalTokens / r.sessionCount) : "—", "tokens")}
    </div>
  `;
}

function kpi(label: string, value: string, sub: string) {
  return html`
    <div class="ins-kpi">
      <span class="ins-kpi-label">${label}</span>
      <span class="ins-kpi-value">${value}</span>
      <span class="ins-kpi-sub">${sub}</span>
    </div>
  `;
}

// ── 按日用量 ──

function renderDays(r: SessionInsightsReport) {
  const days = r.days;
  if (days.length === 0) {
    return html`
      <section class="ins-card">
        <div class="ins-card-head"><span class="ins-card-title">Daily usage</span></div>
        <div class="ins-empty">No usage recorded in this range.</div>
      </section>
    `;
  }
  const max = Math.max(...days.map(dayTokens), 1);
  const step = days.length > 20 ? Math.ceil(days.length / 10) : 1;
  return html`
    <section class="ins-card">
      <div class="ins-card-head">
        <span class="ins-card-title">Daily usage</span>
        <span class="ins-card-sub">prompt + output · ${r.range}</span>
      </div>
      <div class="ins-chart">
        <div class="ins-chart-y">
          <span>${fmtTokens(max)}</span>
          <span>${fmtTokens(max / 2)}</span>
          <span>0</span>
        </div>
        <div class="ins-chart-main">
          <div class="ins-bars">
            ${days.map((day) => renderDay(day, max))}
          </div>
          <div class="ins-chart-x">
            ${days.map((day, index) => index % step === 0
              ? html`<span>${dayLabel(day.date)}</span>`
              : nothing)}
          </div>
        </div>
      </div>
    </section>
  `;
}

function renderDay(day: SessionInsightDay, max: number) {
  const prompt = day.promptTokens / max * 100;
  const output = day.outputTokens / max * 100;
  return html`
    <div class="ins-bar" title=${`${day.date}\nprompt ${fmtTokens(day.promptTokens)} · output ${fmtTokens(day.outputTokens)}\n${formatCost(day.costUsd)}`}>
      <div class="ins-bar-stack">
        <span class="ins-bar-output" style="height:${output}%"></span>
        <span class="ins-bar-prompt" style="height:${prompt}%"></span>
      </div>
    </div>
  `;
}

// ── 工具账本 ──

function renderTools(tools: SessionInsightTool[]) {
  const max = tools.length > 0 ? Math.max(...tools.map((tool) => tool.count)) : 1;
  return html`
    <section class="ins-card ins-tools-card">
      <div class="ins-card-head">
        <span class="ins-card-title">Tool calls</span>
        <span class="ins-card-sub">${tools.reduce((sum, tool) => sum + tool.count, 0)} total</span>
      </div>
      ${tools.length === 0
        ? html`<div class="ins-empty">No tool calls in this range.</div>`
        : html`<div class="ins-tools">
            ${tools.map((tool) => html`
              <div class="ins-tool">
                <span class="ins-tool-name" title=${tool.name}>${tool.name}</span>
                <span class="ins-tool-bar"><span style="width:${tool.count / max * 100}%"></span></span>
                <span class="ins-tool-count">${tool.count}</span>
              </div>
            `)}
          </div>`}
    </section>
  `;
}

// ── 会话卡片 ──

function renderSessions(sessions: SessionInsightCard[]) {
  const q = search.trim().toLowerCase();
  const filtered = q
    ? sessions.filter((session) =>
        sessionTitle(session).toLowerCase().includes(q)
        || (session.firstMessage || "").toLowerCase().includes(q)
        || (session.cwd || "").toLowerCase().includes(q)
        || (session.model || "").toLowerCase().includes(q))
    : sessions;
  return html`
    <section class="ins-card ins-sessions-card">
      <div class="ins-card-head">
        <span class="ins-card-title">Sessions</span>
        <span class="ins-card-sub">${filtered.length} / ${sessions.length}</span>
        <input
          id="ins-search"
          class="ins-search"
          type="text"
          placeholder="Filter sessions..."
          .value=${search}
          @input=${(event: Event) => {
            search = (event.target as HTMLInputElement).value;
            requestRender();
            requestAnimationFrame(() => {
              const input = document.getElementById("ins-search") as HTMLInputElement | null;
              if (input) {
                input.focus();
                input.setSelectionRange(search.length, search.length);
              }
            });
          }}
        />
      </div>
      ${filtered.length === 0
        ? html`<div class="ins-empty">No matching sessions.</div>`
        : html`<div class="ins-sessions">
            ${filtered.map(renderSessionCard)}
          </div>`}
    </section>
  `;
}

function renderSessionCard(session: SessionInsightCard) {
  return html`
    <button class="ins-session" @click=${() => openSession(session)}>
      <div class="ins-session-head">
        <span class="ins-session-title" title=${sessionTitle(session)}>${sessionTitle(session)}</span>
        <span class="ins-session-time">${relativeTime(session.modifiedMs)}</span>
      </div>
      <div class="ins-session-meta">
        <span title=${session.cwd}>${shortPath(session.cwd)}</span>
        ${session.model ? html`<span class="ins-session-model">${session.model}</span>` : nothing}
        ${session.parentSessionId ? html`<span class="ins-session-parent">fork</span>` : nothing}
      </div>
      <div class="ins-session-stats">
        <span>${fmtTokens(session.promptTokens + session.outputTokens)} tokens</span>
        <span>${formatCost(session.costUsd)}</span>
        <span>${session.turns} turns</span>
        <span>${session.toolCalls} tools</span>
        <span>${session.promptTokens > 0 ? pct(session.cacheHitRate * 100) : "—"} cache</span>
      </div>
      ${renderComposition(session)}
    </button>
  `;
}

/** 历史会话的消息组成条（系统提示/工具快照不可得，故只画消息类别）。 */
function renderComposition(session: SessionInsightCard) {
  const slices = (session.composition || []).filter((slice) => slice.tokens > 0);
  const total = slices.reduce((sum, slice) => sum + slice.tokens, 0);
  if (total <= 0) return nothing;
  return html`
    <div
      class="ins-session-comp"
      title=${slices.map((slice) => `${slice.label}: ~${fmtTokens(slice.tokens)}`).join("\n")}
    >
      ${slices.map((slice) => html`
        <span
          style="width:${slice.tokens / total * 100}%;background:${COMPOSITION_COLORS[slice.key] ?? "#64748b"}"
        ></span>
      `)}
    </div>
  `;
}

function openSession(session: SessionInsightCard) {
  // switchTo 接受 id/前缀；用 id 比文件名可靠（自定义 session-dir 的文件名不带 id）。
  send({ type: "loadSession", sessionPath: session.id });
  closeInsights();
}

// ── 格式化 ──

function sessionTitle(session: SessionInsightCard): string {
  if (session.name) return session.name;
  if (session.firstMessage) return session.firstMessage;
  return `Session ${session.id.slice(0, 8)}`;
}

function shortPath(cwd: string): string {
  if (!cwd) return "unknown workspace";
  const normalized = cwd.replace(/\\/g, "/");
  const parts = normalized.split("/").filter(Boolean);
  return parts.length <= 2 ? normalized : `…/${parts.slice(-2).join("/")}`;
}

function dayTokens(day: SessionInsightDay): number {
  return day.promptTokens + day.outputTokens;
}

function dayLabel(date: string): string {
  const parsed = new Date(`${date}T00:00:00`);
  return Number.isNaN(parsed.getTime())
    ? date
    : parsed.toLocaleDateString([], { month: "short", day: "numeric" });
}

function relativeTime(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "";
  const diff = Date.now() - ms;
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function fmtTokens(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "0";
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 100_000) return `${Math.round(value / 1000)}k`;
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`;
  return `${Math.round(value)}`;
}

function pct(value: number): string {
  return `${value.toFixed(1)}%`;
}

function formatCost(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "$0";
  return `$${value < 0.01 ? value.toFixed(4) : value.toFixed(2)}`;
}
