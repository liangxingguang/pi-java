// ── Client → Server messages ──

export type ClientMessage =
  | { type: "prompt"; text: string }
  | { type: "steer"; text: string }
  | { type: "followUp"; text: string }
  | { type: "abort" }
  | { type: "getModels" }
  | { type: "setModel"; provider: string; modelId: string }
  | { type: "setThinkingLevel"; level: string }
  | { type: "getState" }
  | { type: "newSession" }
  | { type: "getSessions" }
  | { type: "loadSession"; sessionPath: string }
  // ── Stage B：文件浏览器 / git（代码调试）──
  | { type: "listDir"; path: string }
  | { type: "readFile"; path: string }
  | { type: "gitStatus" }
  | { type: "gitDiff"; file?: string; staged?: boolean }
  | { type: "gitHistory"; file?: string; limit?: number }
  // ── Stage C：终端 / fork / 导出 / skills ──
  | { type: "bash"; command: string }
  | { type: "abortBash" }
  | { type: "getTree" }
  | { type: "fork"; entryId: string }
  | { type: "clone" }
  | { type: "exportHtml" }
  | { type: "listSkills" }
  // ── Stage D：会话控制 ──
  | { type: "setSessionName"; name: string }
  | { type: "getSessionStats" }
  // ── Phase 3：设置 ──
  | { type: "getSettings" }
  | { type: "setSetting"; key: string; value: string };

// ── Server → Client messages ──

export interface ModelInfo {
  provider: string;
  id: string;
  name: string;
}

export interface SessionListItem {
  id: string;
  path: string;
  name?: string;
  cwd: string;
  created: string;
  modified: string;
  messageCount: number;
  firstMessage: string;
}

export interface DirEntry {
  name: string;
  kind: "file" | "dir";
  size: number;
  modifiedMs: number;
}

export interface StatusEntry {
  path: string;
  indexStatus: string;
  workTreeStatus: string;
}

export interface CommitInfo {
  id: string;
  message: string;
  author: string;
  date: string;
}

export interface SkillInfo {
  name: string;
  description: string;
}

export type ServerMessage =
  | { type: "agentEvent"; event: any }
  | { type: "stateSync"; state: SerializedAgentState }
  | { type: "models"; models: ModelInfo[]; current?: ModelInfo; thinkingLevel?: string }
  | { type: "modelChanged"; model: ModelInfo; thinkingLevel: string }
  | { type: "error"; message: string }
  | { type: "ready" }
  | { type: "sessions"; sessions: SessionListItem[]; currentSessionId: string }
  | { type: "sessionChanged"; sessionId: string }
  // ── Stage B ──
  | { type: "dirListing"; path: string; entries: DirEntry[] }
  | { type: "fileContent"; path: string; content: string; truncated: boolean }
  | { type: "gitStatusResult"; cwd: string; status: StatusEntry[] }
  | { type: "gitDiffResult"; file?: string; staged: boolean; diff: string }
  | { type: "gitHistoryResult"; file?: string; commits: CommitInfo[] }
  // ── Stage C ──
  | { type: "bashResult"; command: string; exitCode: number; output: string; truncated: boolean; aborted: boolean }
  | { type: "tree"; tree: any[]; leafId?: string }
  | { type: "exportPath"; path: string }
  | { type: "skills"; skills: SkillInfo[] }
  // ── Stage D：会话控制 ──
  | { type: "sessionNameChanged"; sessionId: string; name: string }
  | { type: "sessionStats"; sessionId: string; name: string; messageCount: number; entryCount: number; model?: ModelInfo; thinkingLevel: string }
  // ── Phase 3：设置 ──
  | { type: "settingsState"; settings: Record<string, string> };

export interface SerializedAgentState {
  messages: any[];
  model?: ModelInfo;
  thinkingLevel: string;
  systemPrompt: string;
  isStreaming: boolean;
  streamingMessage?: any;
  errorMessage?: string;
  tools: string[];
  sessionId: string;
  sessionName?: string;
}
