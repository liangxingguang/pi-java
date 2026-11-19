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
  | { type: "cancelSessions" }
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
  // ── Context 面板：上下文洞察 ──
  | { type: "getContextInsights" }
  // ── Insights 页：跨会话洞察 ──
  | { type: "getSessionInsights"; range: string }
  // ── Phase 3：设置 ──
  | { type: "getSettings" }
  | { type: "setSetting"; key: string; value: string }
  // ── 扩展面板：已加载 / 已安装扩展清单 ──
  | { type: "getExtensions" };

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

// ── 扩展面板：已加载 / 已安装扩展清单（web 独有面）──

export interface ExtensionInfo {
  name: string;
  path: string;
  source?: string;
  scope?: string;
  origin?: string;
  builtin: boolean;
  loaded: boolean;
  version: string;
  description: string;
  tools: string[];
  commands: string[];
  providers: string[];
  skills: string[];
}

// ── Context 面板：上下文洞察（对齐 dsh-context 的 Context 页签）──

export interface ContextTotals {
  turns: number;
  requests: number;
  toolCalls: number;
  cacheHitRate: number;
  costUsd: number;
  promptTokens: number;
  outputTokens: number;
}

export interface ContextItem {
  label: string;
  tokens: number;
  preview: string;
}

export interface ContextCategory {
  key: string;
  label: string;
  tokens: number;
  items: ContextItem[];
}

export interface ContextStep {
  index: number;
  promptTokens: number;
  outputTokens: number;
  contextTokens: number;
  reported: boolean;
  categories: Record<string, number>;
}

export interface ContextEvent {
  type: string;
  label: string;
  timestamp?: string;
  tokensBefore?: number;
  detail?: string;
}

export interface ContextReport {
  sessionId: string;
  model?: string;
  contextWindow: number;
  maxOutputTokens: number;
  reserveTokens: number;
  contextTokens: number;
  estimatedTokens: number;
  percent: number;
  freeTokens: number;
  totals: ContextTotals;
  categories: ContextCategory[];
  steps: ContextStep[];
  events: ContextEvent[];
}

// ── Insights 页：跨会话洞察 ──

export interface SessionInsightDay {
  date: string;
  promptTokens: number;
  outputTokens: number;
  costUsd: number;
}

export interface SessionInsightTool {
  name: string;
  count: number;
}

export interface SessionInsightSlice {
  key: string;
  label: string;
  tokens: number;
}

export interface SessionInsightCard {
  id: string;
  path: string;
  name: string;
  cwd: string;
  created?: string;
  modifiedMs: number;
  messages: number;
  turns: number;
  toolCalls: number;
  promptTokens: number;
  outputTokens: number;
  costUsd: number;
  cacheHitRate: number;
  model?: string;
  parentSessionId?: string;
  firstMessage: string;
  composition: SessionInsightSlice[];
}

export interface SessionInsightsReport {
  range: string;
  supported: boolean;
  backend: string;
  sessionCount: number;
  promptTokens: number;
  outputTokens: number;
  totalTokens: number;
  costUsd: number;
  cacheHitRate: number;
  turns: number;
  toolCalls: number;
  days: SessionInsightDay[];
  tools: SessionInsightTool[];
  sessions: SessionInsightCard[];
}

export type ServerMessage =
  | { type: "agentEvent"; event: any }
  | { type: "stateSync"; state: SerializedAgentState }
  | { type: "models"; models: ModelInfo[]; current?: ModelInfo; thinkingLevel?: string }
  | { type: "modelChanged"; model: ModelInfo; thinkingLevel: string }
  | { type: "error"; message: string }
  | { type: "ready" }
  | { type: "sessions"; sessions: SessionListItem[]; currentSessionId: string }
  // 批 22（W15）：渐进发布帧——周期发布点的部分表（pi partialSessions 对位）
  | { type: "sessionsProgress"; loaded: number; total: number; sessions: SessionListItem[] }
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
  // ── Context 面板：上下文洞察 ──
  | { type: "contextInsights"; report: ContextReport }
  // ── Insights 页：跨会话洞察 ──
  | { type: "sessionInsights"; report: SessionInsightsReport }
  // ── Phase 3：设置 ──
  | { type: "settingsState"; settings: Record<string, string> }
  // ── 扩展面板：已加载 / 已安装扩展清单 ──
  | { type: "extensions"; extensions: ExtensionInfo[] };

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
