// Phase 3：设置面板（web 相关子集，对齐 TUI /settings）。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import { X } from "lucide";

import { send, requestRender } from "../runtime.js";

let settingsOpen = false;
let settingsState: Record<string, string> | undefined;

const SETTING_DEFS: { key: string; label: string; options: string[] }[] = [
  { key: "theme", label: "Theme", options: ["dark", "light"] },
  { key: "defaultThinkingLevel", label: "Default thinking", options: ["off", "minimal", "low", "medium", "high", "xhigh"] },
  { key: "steeringMode", label: "Steering mode", options: ["one-at-a-time", "all"] },
  { key: "followUpMode", label: "Follow-up mode", options: ["one-at-a-time", "all"] },
  { key: "defaultProjectTrust", label: "Project trust", options: ["prompt", "yes", "never"] },
  { key: "hideThinkingBlock", label: "Hide thinking block", options: ["false", "true"] },
];

export function isSettingsOpen(): boolean {
  return settingsOpen;
}

export function toggleSettings() {
  if (!settingsOpen) {
    settingsState = undefined;
    send({ type: "getSettings" });
  }
  settingsOpen = !settingsOpen;
  requestRender();
}

function setSetting(key: string, value: string) {
  settingsState = { ...(settingsState || {}), [key]: value };
  send({ type: "setSetting", key, value });
  requestRender();
}

export function renderSettingsPanel() {
  if (!settingsOpen) return nothing;
  return html`
    <div class="history-overlay open" @click=${toggleSettings}></div>
    <div class="stats-panel open" style="width:380px">
      <div class="history-header">
        <span style="font-size:13px;font-weight:600;color:var(--foreground)">Settings</span>
        <button class="history-header-btn" @click=${toggleSettings} title="Close">${icon(X, "sm")}</button>
      </div>
      <div class="stats-body">
        ${!settingsState ? html`
          <div class="debug-empty">Loading...</div>
        ` : SETTING_DEFS.map((d) => html`
          <div class="setting-row">
            <label>${d.label}</label>
            <select
              class="setting-select"
              .value=${settingsState?.[d.key] ?? ""}
              @change=${(e: Event) => setSetting(d.key, (e.target as HTMLSelectElement).value)}
            >
              ${d.options.map((o) => html`<option value=${o}>${o}</option>`)}
            </select>
          </div>
        `)}
        <div class="history-hint">Settings persist globally (shared across sessions).</div>
      </div>
    </div>
  `;
}

/** 处理 settingsState 服务端消息。 */
export function onSettingsState(settings: Record<string, string>): void {
  settingsState = settings;
  requestRender();
}
