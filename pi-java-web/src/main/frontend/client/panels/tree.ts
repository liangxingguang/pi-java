// Phase 1：会话历史 / 树面板（对齐 TUI /tree + /fork）。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { ChevronDown, ChevronRight, GitFork, RefreshCw, PanelLeftClose } from "lucide";

import { send, requestRender } from "../runtime.js";

let historyOpen = false;
let treeData: any[] | null = null;
let treeLeafId: string | undefined;
let treeLoading = false;
let treeExpanded = new Set<string>();

export function isHistoryOpen(): boolean {
  return historyOpen;
}

export function toggleHistory() {
  historyOpen = !historyOpen;
  if (historyOpen && !treeData) {
    treeLoading = true;
    send({ type: "getTree" });
  }
  requestRender();
}

function closeHistory() {
  historyOpen = false;
  requestRender();
}

function reloadTree() {
  treeLoading = true;
  treeData = null;
  send({ type: "getTree" });
  requestRender();
}

function handleFork(entryId: string) {
  send({ type: "fork", entryId });
  historyOpen = false;
  requestRender();
}

function toggleTreeExpand(id: string) {
  if (treeExpanded.has(id)) treeExpanded.delete(id);
  else treeExpanded.add(id);
  requestRender();
}

/** 递归收集所有带子节点的 entry id，默认全部展开。 */
function collectExpandable(nodes: any[], set: Set<string>) {
  for (const n of nodes) {
    if (n.children && n.children.length > 0) {
      set.add(n.entry.id);
      collectExpandable(n.children, set);
    }
  }
}

/** 计算树节点的显示标签与摘要文本（对齐 SessionJson 的 {role, content}）。 */
function treeEntryLabel(node: any): { label: string; text: string } {
  const e = node.entry;
  if (e.type === "message" && e.message) {
    const text = (e.message.content || [])
      .map((b: any) => (typeof b === "string" ? b : b.text || ""))
      .filter(Boolean)
      .join(" ")
      .trim();
    return { label: e.message.role || "message", text };
  }
  return { label: e.type, text: "" };
}

function renderTreeNode(node: any, depth: number) {
  const id = node.entry.id;
  const hasChildren = node.children && node.children.length > 0;
  const expanded = treeExpanded.has(id);
  const { label, text } = treeEntryLabel(node);
  return html`
    <div>
      <div class="history-node" style="padding-left:${depth * 14 + 6}px">
        ${hasChildren ? html`
          <button class="history-chevron" @click=${() => toggleTreeExpand(id)}>${icon(expanded ? ChevronDown : ChevronRight, "xs")}</button>
        ` : html`<span class="history-chevron"></span>`}
        <span class="history-badge history-badge-${label}">${label}</span>
        <span class="history-text truncate flex-1" title=${text || id}>${text || id.slice(0, 8)}</span>
        <button class="history-fork" title="Fork conversation here" @click=${() => handleFork(id)}>${icon(GitFork, "xs")}</button>
      </div>
      ${hasChildren && expanded ? html`${node.children.map((c: any) => renderTreeNode(c, depth + 1))}` : nothing}
    </div>
  `;
}

export function renderHistoryPanel() {
  if (!historyOpen) return nothing;
  return html`
    <div class="history-overlay open" @click=${closeHistory}></div>
    <div class="history-panel open">
      <div class="history-header">
        <span style="font-size:13px;font-weight:600;color:var(--foreground)">Session history</span>
        <div style="display:flex;align-items:center;gap:4px">
          <button
            class="history-header-btn"
            title="Refresh tree"
            @click=${reloadTree}
          >${icon(RefreshCw, "sm")}</button>
          <button
            class="history-header-btn"
            title="Close"
            @click=${closeHistory}
          >${icon(PanelLeftClose, "sm")}</button>
        </div>
      </div>
      <div class="flex-1 overflow-y-auto p-1">
        ${treeLoading ? html`
          <div class="debug-empty">Loading tree...</div>
        ` : !treeData || treeData.length === 0 ? html`
          <div class="debug-empty">No history entries yet</div>
        ` : html`
          ${treeData.map((n: any) => renderTreeNode(n, 0))}
          <div class="history-hint">Click ${icon(GitFork, "xs")} on a node to fork the conversation from that point.</div>
        `}
      </div>
    </div>
  `;
}

/** 处理 tree 服务端消息。 */
export function onTree(msg: { tree: any[]; leafId?: string }): void {
  treeData = msg.tree;
  treeLeafId = msg.leafId;
  treeLoading = false;
  treeExpanded = new Set<string>();
  if (treeData) collectExpandable(treeData, treeExpanded);
  requestRender();
}
