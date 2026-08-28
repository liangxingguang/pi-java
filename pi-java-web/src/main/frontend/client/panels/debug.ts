// Stage B/C：代码调试面板（文件浏览器 / git status·diff·history / 终端 / skills）。
import { html, nothing } from "lit";
import { icon } from "@mariozechner/mini-lit";
import {
  X, File, Folder, FolderOpen, GitBranch, RefreshCw,
  Terminal as TerminalIcon, PanelLeftClose, History,
} from "lucide";

import type { DirEntry, StatusEntry, CommitInfo, SkillInfo } from "../../shared/protocol.js";
import { send, requestRender } from "../runtime.js";
import { truncateText } from "../utils.js";

let debugOpen = false;
let debugTab: "files" | "git" | "terminal" | "skills" = "files";
let dirPath = "";
let dirEntries: DirEntry[] = [];
let openFilePath = "";
let openFileContent = "";
let gitStatus: StatusEntry[] = [];
let gitDiffFile = "";
let gitDiffText = "";
let gitHistoryCommits: CommitInfo[] = [];
let bashOutput = "";
let bashRunning = false;
let skills: SkillInfo[] = [];

export function isDebugOpen(): boolean {
  return debugOpen;
}

export function toggleDebug() {
  debugOpen = !debugOpen;
  requestRender();
  if (debugOpen && !dirPath && dirEntries.length === 0) {
    send({ type: "listDir", path: "" });
  }
  if (debugOpen && gitStatus.length === 0) {
    send({ type: "gitStatus" });
  }
}

function switchDebugTab(tab: "files" | "git" | "terminal" | "skills") {
  debugTab = tab;
  if (tab === "files" && dirEntries.length === 0) {
    send({ type: "listDir", path: "" });
  }
  if (tab === "git" && gitStatus.length === 0) {
    send({ type: "gitStatus" });
  }
  if (tab === "skills" && skills.length === 0) {
    send({ type: "listSkills" });
  }
  requestRender();
}

function openDir(path: string) {
  send({ type: "listDir", path });
}

function handleOpenFile(path: string) {
  openFilePath = path;
  send({ type: "readFile", path });
}

function refreshGit() {
  send({ type: "gitStatus" });
}

function handleGitDiff(file?: string) {
  gitDiffFile = file || "";
  gitDiffText = "";
  send({ type: "gitDiff", file });
}

function loadGitHistory() {
  send({ type: "gitHistory", limit: 50 });
}

function handleBashSend(command: string) {
  if (!command.trim() || bashRunning) return;
  bashRunning = true;
  bashOutput = "";
  send({ type: "bash", command });
  requestRender();
}

function handleAbortBash() {
  send({ type: "abortBash" });
}

function loadSkills() {
  send({ type: "listSkills" });
}

function upDir(path: string): string {
  const idx = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  return idx < 0 ? "" : path.substring(0, idx);
}

function joinPath(dir: string, name: string): string {
  if (!dir) return name;
  const sep = dir.includes("\\") ? "\\" : "/";
  return dir + sep + name;
}

function renderFileTree() {
  return html`
    <div class="debug-section">
      <div class="debug-breadcrumb">
        ${dirPath
          ? html`<button @click=${() => openDir(upDir(dirPath))} title="Up">${icon(FolderOpen, "xs")}</button>`
          : icon(FolderOpen, "xs")}
        <span class="truncate flex-1">${dirPath || "/"}</span>
        <button @click=${() => openDir("")} title="Refresh">${icon(RefreshCw, "xs")}</button>
      </div>
      <div class="debug-tree">
        ${dirEntries.map((e) => html`
          <button
            class="debug-tree-item"
            @click=${() => e.kind === "dir" ? openDir(joinPath(dirPath, e.name)) : handleOpenFile(joinPath(dirPath, e.name))}
            title=${e.kind === "file" ? joinPath(dirPath, e.name) : ""}
          >
            ${icon(e.kind === "dir" ? Folder : File, "xs")}
            <span class="truncate flex-1">${e.name}</span>
            ${e.kind === "file" ? html`<span class="debug-size">${e.size}</span>` : nothing}
          </button>
        `)}
        ${dirEntries.length === 0 ? html`<div class="debug-empty">Empty directory</div>` : nothing}
      </div>
    </div>
  `;
}

function renderCodeViewer() {
  if (!openFilePath) return nothing;
  return html`
    <div class="debug-section">
      <div class="debug-breadcrumb">
        <span class="truncate flex-1" title=${openFilePath}>${openFilePath}</span>
        <button @click=${() => { openFilePath = ""; openFileContent = ""; requestRender(); }} title="Close">${icon(X, "xs")}</button>
      </div>
      <pre class="debug-code"><code>${openFileContent}</code></pre>
    </div>
  `;
}

function statusLabel(s: StatusEntry): string {
  return (s.indexStatus || ".") + (s.workTreeStatus || ".");
}

function statusClass(s: StatusEntry): string {
  const code = statusLabel(s);
  if (code.includes("U") || code.includes("D")) return "debug-status-del";
  if (code.includes("A")) return "debug-status-add";
  return "debug-status-mod";
}

function renderGitPanel() {
  return html`
    <div class="debug-section">
      <div class="debug-breadcrumb">
        <span class="flex-1">${gitStatus.length} changed</span>
        <button @click=${loadGitHistory} title="Git history">${icon(History, "xs")}</button>
        <button @click=${refreshGit} title="Refresh">${icon(RefreshCw, "xs")}</button>
      </div>
      <div class="debug-tree">
        ${gitStatus.map((s) => html`
          <button class="debug-tree-item" @click=${() => handleGitDiff(s.path)}>
            <span class="debug-status ${statusClass(s)}">${statusLabel(s)}</span>
            <span class="truncate flex-1">${s.path}</span>
          </button>
        `)}
        ${gitStatus.length === 0 ? html`<div class="debug-empty">Working tree clean</div>` : nothing}
      </div>
      ${gitHistoryCommits.length > 0 ? html`
        <div class="debug-breadcrumb" style="margin-top:8px">
          <span class="flex-1">Recent commits (${gitHistoryCommits.length})</span>
          <button @click=${() => { gitHistoryCommits = []; requestRender(); }} title="Close history">${icon(X, "xs")}</button>
        </div>
        <div class="debug-tree">
          ${gitHistoryCommits.map((c) => html`
            <div class="debug-tree-item" title=${c.message}>
              <span class="git-commit-id">${c.id.slice(0, 7)}</span>
              <span class="truncate flex-1">${truncateText(c.message, 60)}</span>
              <span class="debug-size">${c.author}</span>
            </div>
          `)}
        </div>
      ` : nothing}
      ${gitDiffText ? html`
        <div class="debug-breadcrumb" style="margin-top:8px">
          <span class="truncate flex-1">${gitDiffFile || "all files"}</span>
          <button @click=${() => { gitDiffText = ""; requestRender(); }} title="Close">${icon(X, "xs")}</button>
        </div>
        <pre class="debug-code debug-diff"><code>${gitDiffText}</code></pre>
      ` : nothing}
    </div>
  `;
}

function renderTerminalPanel() {
  return html`
    <div class="debug-section">
      <form
        class="debug-term-form"
        @submit=${(e: Event) => {
          e.preventDefault();
          const input = (e.target as HTMLFormElement).querySelector("input") as HTMLInputElement;
          const cmd = input.value;
          input.value = "";
          handleBashSend(cmd);
        }}
      >
        <input
          class="debug-term-input"
          placeholder="$ command..."
          autocomplete="off"
          spellcheck="false"
        />
        <button type="submit" ?disabled=${bashRunning}>Run</button>
        <button type="button" @click=${handleAbortBash} ?disabled=${!bashRunning}>Abort</button>
      </form>
      <pre class="debug-code debug-term-output">${bashOutput || (bashRunning ? "Running..." : "")}</pre>
    </div>
  `;
}

function renderSkillsPanel() {
  return html`
    <div class="debug-section">
      <div class="debug-breadcrumb">
        <span class="flex-1">${skills.length} skills</span>
        <button @click=${loadSkills} title="Refresh">${icon(RefreshCw, "xs")}</button>
      </div>
      <div class="debug-tree">
        ${skills.map((s) => html`
          <div class="debug-tree-item" title=${s.description}>
            <span class="truncate flex-1">${s.name}</span>
          </div>
        `)}
        ${skills.length === 0 ? html`<div class="debug-empty">No skills</div>` : nothing}
      </div>
    </div>
  `;
}

export function renderDebugPanel() {
  if (!debugOpen) return nothing;
  return html`
    <div class="debug-panel">
      <div class="debug-tabs">
        <button class="${debugTab === "files" ? "active" : ""}" @click=${() => switchDebugTab("files")}>
          ${icon(File, "xs")}
        </button>
        <button class="${debugTab === "git" ? "active" : ""}" @click=${() => switchDebugTab("git")}>
          ${icon(GitBranch, "xs")}
        </button>
        <button class="${debugTab === "terminal" ? "active" : ""}" @click=${() => switchDebugTab("terminal")}>
          ${icon(TerminalIcon, "xs")}
        </button>
        <button class="${debugTab === "skills" ? "active" : ""}" @click=${() => switchDebugTab("skills")}>
          ${icon(File, "xs")} S
        </button>
        <button @click=${toggleDebug} title="Close panel">${icon(PanelLeftClose, "xs")}</button>
      </div>
      <div class="debug-body">
        ${debugTab === "files" ? html`${renderFileTree()}${renderCodeViewer()}`
          : debugTab === "git" ? renderGitPanel()
          : debugTab === "terminal" ? renderTerminalPanel()
          : renderSkillsPanel()}
      </div>
    </div>
  `;
}

// ── 服务端消息 ──

export function onDirListing(path: string, entries: DirEntry[]): void {
  dirPath = path;
  dirEntries = entries;
  requestRender();
}

export function onFileContent(path: string, content: string): void {
  openFilePath = path;
  openFileContent = content;
  requestRender();
}

export function onGitStatusResult(status: StatusEntry[]): void {
  gitStatus = status;
  requestRender();
}

export function onGitDiffResult(file: string | undefined, diff: string): void {
  gitDiffFile = file || "";
  gitDiffText = diff;
  requestRender();
}

export function onGitHistoryResult(commits: CommitInfo[]): void {
  gitHistoryCommits = commits;
  requestRender();
}

export function onBashResult(result: { exitCode: number; output: string; aborted: boolean }): void {
  bashRunning = false;
  bashOutput = (result.aborted ? "[aborted]\n" : "") + result.output
    + (result.exitCode !== 0 ? `\n[exit ${result.exitCode}]` : "");
  requestRender();
}

export function onSkills(skillList: SkillInfo[]): void {
  skills = skillList;
  requestRender();
}

export function onBashOutput(delta: string): void {
  bashOutput += delta;
  requestRender();
}
