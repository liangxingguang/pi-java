// 扩展面板：展示本次会话已加载的扩展，以及包里 resolve 出但没加载的已安装扩展。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { X, RefreshCw } from "lucide";

import type { ExtensionInfo } from "../../shared/protocol.js";
import { send, requestRender } from "../runtime.js";

let extensionsOpen = false;
let loading = false;
let rows: ExtensionInfo[] | null = null;

export function isExtensionsOpen(): boolean {
  return extensionsOpen;
}

export function toggleExtensions() {
  if (!extensionsOpen) {
    rows = null;
    loading = true;
    send({ type: "getExtensions" });
  } else {
    loading = false;
  }
  extensionsOpen = !extensionsOpen;
  requestRender();
}

/** 会话换实例（new/fork/load）后清单会变，重新拉一遍。 */
export function onSessionChanged() {
  if (!extensionsOpen) return;
  rows = null;
  loading = true;
  send({ type: "getExtensions" });
  requestRender();
}

/** 处理 extensions 服务端消息。 */
export function onExtensions(extensions: ExtensionInfo[]): void {
  rows = extensions;
  loading = false;
  requestRender();
}

export function renderExtensionsPanel() {
  if (!extensionsOpen) return nothing;
  const list = rows || [];
  const loadedCount = list.filter((e) => e.loaded).length;
  return html`
    <div class="history-overlay open" @click=${toggleExtensions}></div>
    <div class="stats-panel open" style="width:480px">
      <div class="history-header">
        <span style="font-size:13px;font-weight:600;color:var(--foreground)">
          Extensions
          ${rows
            ? html`<span class="ext-summary">${loadedCount} loaded / ${list.length} installed</span>`
            : nothing}
        </span>
        <div style="display:flex;align-items:center;gap:4px">
          <button class="history-header-btn" title="Reload list" @click=${refresh}>${icon(RefreshCw, "sm")}</button>
          <button class="history-header-btn" @click=${toggleExtensions} title="Close">${icon(X, "sm")}</button>
        </div>
      </div>
      <div class="stats-body ext-body">
        ${loading && !rows
          ? html`<div class="debug-empty">Loading extensions...</div>`
          : !rows
            ? html`<div class="debug-empty">No extension data</div>`
            : list.length === 0
              ? html`<div class="debug-empty">
                  No extensions loaded. Add one with <code>-e &lt;path&gt;</code>, keep a JAR on
                  the classpath, or run <code>pi-java install &lt;source&gt;</code>.
                </div>`
              : list.map(renderRow)}
      </div>
    </div>
  `;
}

function refresh() {
  rows = null;
  loading = true;
  send({ type: "getExtensions" });
  requestRender();
}

function renderRow(e: ExtensionInfo) {
  return html`
    <div class="ext-row ${e.loaded ? "" : "ext-row-idle"}">
      <div class="ext-row-head">
        <span class="ext-name">${e.name}</span>
        ${e.version ? html`<span class="ext-badge">v${e.version}</span>` : nothing}
        ${e.builtin ? html`<span class="ext-badge ext-badge-builtin">builtin</span>` : nothing}
        ${e.scope ? html`<span class="ext-badge">${e.scope}</span>` : nothing}
        ${e.source ? html`<span class="ext-badge">${e.source}</span>` : nothing}
        ${e.loaded ? nothing : html`<span class="ext-badge ext-badge-idle">not loaded</span>`}
      </div>
      ${e.description ? html`<div class="ext-desc">${e.description}</div>` : nothing}
      <div class="ext-path mono" title=${e.path}>${e.path || "—"}</div>
      ${renderChips(e)}
    </div>
  `;
}

function renderChips(e: ExtensionInfo) {
  const groups = [
    { label: "tools", items: e.tools },
    { label: "commands", items: e.commands },
    { label: "providers", items: e.providers },
    { label: "skills", items: e.skills },
  ].filter((g) => g.items && g.items.length > 0);
  if (groups.length === 0) return nothing;
  return html`
    <div class="ext-chips">
      ${groups.map(
        (g) => html`<span class="ext-chip" title=${g.items.join(", ")}>${g.label} ${g.items.length}</span>`,
      )}
    </div>
  `;
}
