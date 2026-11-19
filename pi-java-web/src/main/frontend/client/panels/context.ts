// 上下文洞察面板 —— 对齐 dsh-context 的 Context 页签：
// 当前窗口组成、逐请求趋势、上下文事件与会话 KPI。数据由后端
// getContextInsights 计算（与 ContextUsageEstimator 同口径估算 + provider 实报 usage）。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { ChevronDown, ChevronRight, Gauge, RefreshCw, X } from "lucide";

import type {
  ContextCategory,
  ContextItem,
  ContextReport,
  ContextStep,
} from "../../shared/protocol.js";
import { send, requestRender } from "../runtime.js";

const CATEGORY_COLORS: Record<string, string> = {
  system: "#6366f1",
  tools: "#0ea5e9",
  user: "#10b981",
  injected: "#8b5cf6",
  answers: "#f59e0b",
  reasoning: "#ec4899",
  toolCalls: "#14b8a6",
  toolResults: "#64748b",
};

let contextOpen = false;
let report: ContextReport | null = null;
let loading = false;
/** 趋势图上钉住的请求序号；null ⇒ 显示 live 组成。 */
let pinnedStep: number | null = null;
let expanded = new Set<string>();

// ── 对外钩子 ──

export function isContextOpen(): boolean {
  return contextOpen;
}

export function toggleContext() {
  if (!contextOpen) {
    requestReport();
  }
  contextOpen = !contextOpen;
  requestRender();
}

function closeContext() {
  contextOpen = false;
  requestRender();
}

function requestReport() {
  loading = true;
  send({ type: "getContextInsights" });
  requestRender();
}

/** agent_end / stateSync 后刷新（header 上的占用指示始终跟随最新一次请求）。 */
export function onAgentEnd(): void {
  requestReport();
}

/** 会话切换：清掉旧报告再取新会话的。 */
export function onSessionChanged(): void {
  report = null;
  pinnedStep = null;
  expanded = new Set();
  requestReport();
}

/** 处理 contextInsights 服务端消息。 */
export function onReport(next: ContextReport): void {
  report = next;
  loading = false;
  if (pinnedStep !== null && !next.steps.some((step) => step.index === pinnedStep)) {
    pinnedStep = null;
  }
  requestRender();
}

// ── header 入口 ──

export function renderContextMeter() {
  const percent = report && report.contextWindow > 0 ? Math.min(100, report.percent) : 0;
  const label = report ? `${Math.round(report.percent)}%` : "—";
  const title = report
    ? `Context ${fmtTokens(report.contextTokens)} / ${fmtTokens(report.contextWindow)} (${pct(report.percent)})`
    : "Context usage";
  return html`
    <button class="ctx-meter" title=${title} @click=${toggleContext}>
      <span class="ctx-meter-bar">
        <span style="width:${percent}%;background:${meterColor(percent)}"></span>
      </span>
      <span class="ctx-meter-label">${label}</span>
    </button>
  `;
}

// ── 面板 ──

export function renderContextPanel() {
  if (!contextOpen) return nothing;
  return html`
    <div class="context-overlay" @click=${closeContext}></div>
    <aside class="context-panel" role="dialog" aria-label="Context insights">
      <div class="history-header">
        <div class="context-title">
          ${icon(Gauge, "sm")}
          <span>Context</span>
          ${report?.model ? html`<span class="context-model" title=${report.model}>${report.model}</span>` : nothing}
        </div>
        <div style="display:flex;align-items:center;gap:4px">
          <button class="history-header-btn" title="Refresh context" @click=${requestReport}>
            ${icon(RefreshCw, "sm")}
          </button>
          <button class="history-header-btn" title="Close" @click=${closeContext}>
            ${icon(X, "sm")}
          </button>
        </div>
      </div>
      <div class="context-body">
        ${loading && !report
          ? html`<div class="debug-empty">Loading context...</div>`
          : !report
            ? html`<div class="debug-empty">No context data</div>`
            : renderReport(report)}
      </div>
    </aside>
  `;
}

function renderReport(r: ContextReport) {
  const step = pinnedStep === null ? null : r.steps.find((s) => s.index === pinnedStep) ?? null;
  const categories = step ? stepCategories(r, step) : r.categories;
  const used = step ? step.promptTokens : r.contextTokens;
  return html`
    ${renderKpis(r)}
    ${renderCurrent(r, categories, used, step)}
    ${renderTrend(r)}
    ${renderEvents(r)}
  `;
}

// ── KPI ──

function renderKpis(r: ContextReport) {
  const t = r.totals;
  return html`
    <div class="ctx-kpis">
      <div class="ctx-kpi">
        <span class="ctx-kpi-label">Context</span>
        <span class="ctx-kpi-value">${fmtTokens(r.contextTokens)}</span>
        <span class="ctx-kpi-sub">
          ${r.contextWindow > 0 ? `of ${fmtTokens(r.contextWindow)} · ${pct(r.percent)}` : "window unknown"}
        </span>
      </div>
      <div class="ctx-kpi">
        <span class="ctx-kpi-label">Requests</span>
        <span class="ctx-kpi-value">${t.requests}</span>
        <span class="ctx-kpi-sub">${t.turns} turns · ${fmtTokens(t.promptTokens)} in</span>
      </div>
      <div class="ctx-kpi">
        <span class="ctx-kpi-label">Tool calls</span>
        <span class="ctx-kpi-value">${t.toolCalls}</span>
        <span class="ctx-kpi-sub">${fmtTokens(t.outputTokens)} out</span>
      </div>
      <div class="ctx-kpi">
        <span class="ctx-kpi-label">Cache hit</span>
        <span class="ctx-kpi-value">${t.promptTokens > 0 ? pct(t.cacheHitRate * 100) : "—"}</span>
        <span class="ctx-kpi-sub">${formatCost(t.costUsd)} cost</span>
      </div>
    </div>
  `;
}

// ── 当前窗口组成 ──

function renderCurrent(
  r: ContextReport,
  categories: ContextCategory[],
  used: number,
  step: ContextStep | null,
) {
  const window = r.contextWindow > 0 ? r.contextWindow : Math.max(used, 1);
  const usedPct = clamp(used / window * 100, 0, 100);
  const reservePct = clamp(r.reserveTokens / window * 100, 0, 100 - usedPct);
  const outputPct = clamp(r.maxOutputTokens / window * 100, 0, 100 - usedPct - reservePct);
  const freePct = Math.max(0, 100 - usedPct - reservePct - outputPct);
  const freeTokens = Math.max(0, window - used - r.reserveTokens - r.maxOutputTokens);
  const composition = categories.reduce((sum, c) => sum + c.tokens, 0);
  const expandable = step === null;
  return html`
    <section class="ctx-card">
      <div class="ctx-card-head">
        <span class="ctx-card-title">${step ? `Request #${step.index + 1}` : "Current context"}</span>
        <span class="ctx-card-sub">${step ? (step.reported ? "provider reported" : "estimated") : "live window"}</span>
        ${step ? html`<button class="ctx-link" @click=${clearPin}>Back to live</button>` : nothing}
      </div>
      <div class="ctx-window-line">
        <span>~${fmtTokens(used)}</span>
        <span class="ctx-muted">
          / ${fmtTokens(window)}${r.contextWindow > 0 ? ` · ${pct(used / window * 100)}` : ""}
        </span>
      </div>
      <div class="ctx-stack" role="img" aria-label="Context composition">
        ${categories
          .filter((c) => c.tokens > 0)
          .map((c) => html`
            <span
              class="ctx-seg"
              style="width:${(c.tokens / Math.max(composition, 1)) * usedPct}%;background:${colorOf(c.key)}"
              title="${c.label}: ~${fmtTokens(c.tokens)}"
            ></span>
          `)}
        ${reservePct > 0
          ? html`<span class="ctx-seg ctx-seg-reserved" style="width:${reservePct}%" title="Auto-compaction reserve: ~${fmtTokens(r.reserveTokens)}"></span>`
          : nothing}
        ${outputPct > 0
          ? html`<span class="ctx-seg ctx-seg-output" style="width:${outputPct}%" title="Max output reserve: ~${fmtTokens(r.maxOutputTokens)}"></span>`
          : nothing}
        ${freePct > 0
          ? html`<span class="ctx-seg ctx-seg-free" style="width:${freePct}%" title="Free: ~${fmtTokens(freeTokens)}"></span>`
          : nothing}
      </div>
      <div class="ctx-legend">
        ${categories.map((c) => html`
          <button
            class="ctx-legend-row ${expandable && c.items.length > 0 ? "expandable" : ""}"
            ?disabled=${!expandable || c.items.length === 0}
            @click=${() => toggleCategory(c.key)}
          >
            <span class="ctx-dot" style="background:${colorOf(c.key)}"></span>
            <span class="ctx-legend-label">${c.label}</span>
            <span class="ctx-legend-tokens">${fmtTokens(c.tokens)}</span>
            <span class="ctx-legend-pct">${composition > 0 ? pct(c.tokens / composition * 100) : "0.0%"}</span>
            ${expandable && c.items.length > 0
              ? icon(expanded.has(c.key) ? ChevronDown : ChevronRight, "xs")
              : html`<span style="width:14px"></span>`}
          </button>
          ${expandable && expanded.has(c.key) ? renderItems(c.items) : nothing}
        `)}
      </div>
      ${r.contextWindow > 0
        ? html`<div class="ctx-note">
            Reserved: compaction ~${fmtTokens(r.reserveTokens)} · max output ~${fmtTokens(r.maxOutputTokens)}
            (compaction triggers near ${fmtTokens(Math.max(0, window - r.reserveTokens))}).
          </div>`
        : nothing}
      ${step
        ? html`<div class="ctx-note">
            Historical request: message categories are from that request;
            system prompt/tool definitions use the current configuration.
          </div>`
        : nothing}
    </section>
  `;
}

function renderItems(items: ContextItem[]) {
  return html`
    <div class="ctx-items">
      ${items.map((item) => html`
        <div class="ctx-item">
          <span class="ctx-item-label" title=${item.label}>${item.label}</span>
          <span class="ctx-item-tokens">${fmtTokens(item.tokens)}</span>
          <span class="ctx-item-preview" title=${item.preview}>${item.preview || "—"}</span>
        </div>
      `)}
    </div>
  `;
}

// ── 逐请求趋势 ──

function renderTrend(r: ContextReport) {
  if (r.steps.length === 0) {
    return html`
      <section class="ctx-card">
        <div class="ctx-card-head"><span class="ctx-card-title">Context trend</span></div>
        <div class="ctx-empty">No model requests yet.</div>
      </section>
    `;
  }
  const max = Math.max(r.contextWindow, ...r.steps.map((s) => s.contextTokens), 1);
  return html`
    <section class="ctx-card">
      <div class="ctx-card-head">
        <span class="ctx-card-title">Context trend</span>
        <span class="ctx-card-sub">
          per model request · ${r.steps.some((s) => s.reported) ? "provider prompt" : "estimated"}
        </span>
        ${pinnedStep !== null ? html`<button class="ctx-link" @click=${clearPin}>Live</button>` : nothing}
      </div>
      <div class="ctx-trend">
        <div class="ctx-trend-y">
          <span>${fmtTokens(max)}</span>
          <span>${fmtTokens(max / 2)}</span>
          <span>0</span>
        </div>
        <div class="ctx-trend-scroll">
          <div class="ctx-trend-bars">
            ${r.steps.map((step) => {
              const height = clamp(step.contextTokens / max * 100, 0.5, 100);
              return html`
                <button
                  class="ctx-bar ${pinnedStep === step.index ? "pinned" : ""}"
                  title=${`#${step.index + 1} · ~${fmtTokens(step.contextTokens)}${step.reported ? " (reported)" : " (estimated)"}`}
                  @click=${() => pinStep(step.index)}
                >
                  <span class="ctx-bar-stack" style="height:${height}%">
                    ${r.categories.map((c) => {
                      const tokens = step.categories[c.key] ?? 0;
                      return tokens > 0
                        ? html`<span style="height:${tokens / Math.max(step.contextTokens, 1) * 100}%;background:${colorOf(c.key)}"></span>`
                        : nothing;
                    })}
                  </span>
                </button>
              `;
            })}
          </div>
        </div>
      </div>
      <div class="ctx-trend-axis">
        <span>#${r.steps[0].index + 1}</span>
        <span>${r.steps.length} requests</span>
        <span>#${r.steps[r.steps.length - 1].index + 1}</span>
      </div>
    </section>
  `;
}

// ── 上下文事件 ──

function renderEvents(r: ContextReport) {
  const events = [...r.events].reverse().slice(0, 40);
  return html`
    <section class="ctx-card">
      <div class="ctx-card-head">
        <span class="ctx-card-title">Context events</span>
        <span class="ctx-card-sub">${r.events.length} total</span>
      </div>
      ${events.length === 0
        ? html`<div class="ctx-empty">No compaction or model switches yet.</div>`
        : html`<div class="ctx-events">
            ${events.map((event) => html`
              <div class="ctx-event">
                <span class="ctx-event-dot ctx-event-${event.type}"></span>
                <span class="ctx-event-main">
                  <span class="ctx-event-label">${event.label}</span>
                  ${event.detail ? html`<span class="ctx-event-detail" title=${event.detail}>${event.detail}</span>` : nothing}
                </span>
                ${event.tokensBefore != null
                  ? html`<span class="ctx-event-tokens">before ${fmtTokens(event.tokensBefore)}</span>`
                  : nothing}
                <span class="ctx-event-time">${formatTime(event.timestamp)}</span>
              </div>
            `)}
          </div>`}
    </section>
  `;
}

// ── 交互 ──

function pinStep(index: number) {
  pinnedStep = index;
  requestRender();
}

function clearPin() {
  pinnedStep = null;
  requestRender();
}

function toggleCategory(key: string) {
  if (expanded.has(key)) {
    expanded.delete(key);
  } else {
    expanded.add(key);
  }
  requestRender();
}

function stepCategories(reportData: ContextReport, step: ContextStep): ContextCategory[] {
  return reportData.categories.map((category) => ({
    ...category,
    tokens: step.categories[category.key] ?? 0,
    items: [],
  }));
}

// ── 格式化 ──

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

function formatTime(value?: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function colorOf(key: string): string {
  return CATEGORY_COLORS[key] ?? "#64748b";
}

function meterColor(percent: number): string {
  if (percent >= 90) return "#ef4444";
  if (percent >= 70) return "#f59e0b";
  return "var(--accent-foreground)";
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
