/**
 * L5 conformance runner (pi side) — see pi-java `docs/23c` §2.
 *
 * Drives pi's `agentLoop` with a fully scripted stream function and writes the
 * emitted `AgentEvent` sequence to `<CONFORMANCE_OUT>/<id>.pi.jsonl`, one
 * normalized frame per line. The pi-java side replays the *same* script through
 * `PiLoop` and diffs the two files.
 *
 * Run:
 *   CONFORMANCE_SCRIPTS=<pi-java>/conformance/scripts \
 *   CONFORMANCE_OUT=<pi-java>/conformance/pi-out \
 *   npx vitest --run --config vitest.conformance.config.ts test/conformance/run.test.ts
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
	type AssistantMessage,
	type AssistantMessageEvent,
	type Context,
	EventStream,
	type Message,
	type Model,
	type ToolResultMessage,
} from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { describe, expect, it } from "vitest";
import { agentLoop } from "../../src/agent-loop.ts";
import type { AgentContext, AgentEvent, AgentLoopConfig, AgentMessage, AgentTool } from "../../src/types.ts";

const SCRIPTS_DIR = process.env.CONFORMANCE_SCRIPTS;
const OUT_DIR = process.env.CONFORMANCE_OUT;
if (!SCRIPTS_DIR || !OUT_DIR) {
	throw new Error("CONFORMANCE_SCRIPTS and CONFORMANCE_OUT must be set");
}

// ── script model ───────────────────────────────────────────────────────────

interface ScriptTool {
	name: string;
	executionMode?: "sequential" | "parallel";
	isError?: boolean;
	terminate?: boolean;
	/** Blocked by `beforeToolCall` before it ever executes. */
	reject?: boolean;
	details?: unknown;
	/** Partial results streamed through `onUpdate` before the call resolves. */
	updates?: number;
}

interface ScriptContent {
	type: "text" | "thinking" | "toolCall";
	text?: string;
	thinking?: string;
	name?: string;
	arguments?: Record<string, unknown>;
	/**
	 * Explicit streaming chunks for a text block. Absent = the whole text in one
	 * `text_start`/`text_end` pair with no delta. The splitting rule is spelled
	 * out in the script rather than inferred, so the Java side cannot drift from
	 * a heuristic.
	 */
	chunks?: string[];
}

interface ScriptResponse {
	content: ScriptContent[];
	stopReason: "stop" | "length" | "toolUse" | "aborted" | "error";
	/**
	 * Prepend an echo of what this request actually carried (message count /
	 * model / systemPrompt) to the first text block.
	 *
	 * Why: normalized frames are agent events only — the request itself never
	 * reaches a frame (the scripted stream ignores its arguments). A
	 * `nextTurn` context replacement is therefore invisible to the diff, and
	 * the scenario would pass even with the hook never wired up. The echo
	 * folds the request's shape into the assistant message, which *is* framed.
	 */
	echoRequest?: boolean;
}

/** What `prepareNextTurn` returns after a given turn (pi's `AgentLoopTurnUpdate`). */
interface ScriptNextTurn {
	/** Fires once, after the turn at this index (0-based). */
	afterTurn: number;
	/** Replaces the whole context: these become the user messages. */
	messages?: string[];
	systemPrompt?: string;
	/** Model id to switch to (provider stays "openai"). */
	model?: string;
}

interface Script {
	id: string;
	name: string;
	systemPrompt?: string;
	prompt?: string;
	toolExecution?: "sequential" | "parallel";
	tools?: ScriptTool[];
	responses: ScriptResponse[];
	/** Steering messages injected after the turn at `afterTurn` (0-based). */
	steer?: { afterTurn: number; text: string }[];
	/** Follow-up messages injected once the inner loop drains after `afterTurn`. */
	followUp?: { afterTurn: number; text: string }[];
	/** `prepareNextTurn`'s return value; absent = no hook configured. */
	nextTurn?: ScriptNextTurn;
	/** Abort the run once this many tool calls have completed. */
	abortAfterToolCalls?: number;
}

// ── message construction ───────────────────────────────────────────────────

function createUsage() {
	return {
		input: 0,
		output: 0,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: 0,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
}

function createModel(): Model<"openai-responses"> {
	return {
		id: "mock",
		name: "mock",
		api: "openai-responses",
		provider: "openai",
		baseUrl: "https://example.invalid",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 8192,
		maxTokens: 2048,
	};
}

function buildContent(blocks: ScriptContent[]): AssistantMessage["content"] {
	return blocks.map((b) => {
		if (b.type === "text") return { type: "text", text: b.text ?? "" };
		if (b.type === "thinking") return { type: "thinking", thinking: b.thinking ?? "" };
		return {
			type: "toolCall" as const,
			id: `call_${b.name}_${Math.random().toString(36).slice(2, 8)}`,
			name: b.name ?? "tool",
			arguments: b.arguments ?? {},
		};
	});
}

function createAssistantMessage(
	content: AssistantMessage["content"],
	stopReason: AssistantMessage["stopReason"],
): AssistantMessage {
	return {
		role: "assistant",
		content,
		api: "openai-responses",
		provider: "openai",
		model: "mock",
		usage: createUsage(),
		stopReason,
		timestamp: Date.now(),
	};
}

function userMessage(text: string): AgentMessage {
	return { role: "user", content: text, timestamp: Date.now() };
}

function identityConverter(messages: AgentMessage[]): Message[] {
	return messages.filter(
		(m) => m.role === "user" || m.role === "assistant" || m.role === "toolResult",
	) as Message[];
}

// ── scripted stream ────────────────────────────────────────────────────────

/** A stream double that replays a scripted response as real lifecycle events. */
class ScriptedStream extends EventStream<AssistantMessageEvent, AssistantMessage> {
	constructor(response: ScriptResponse) {
		super(
			(event) => event.type === "done" || event.type === "error",
			(event) => {
				if (event.type === "done") return event.message;
				if (event.type === "error") return event.error;
				throw new Error("Unexpected event type");
			},
		);

		const stop = response.stopReason;
		const final = createAssistantMessage(buildContent(response.content), stop);
		// Partial before each delta: pi's loop reads `event.partial` on every update.
		const blank = createAssistantMessage([], "stop");
		// Content-index-aligned view of the finalized blocks, so `toolcall_end`
		// carries the tool call it actually belongs to.
		const finalized = final.content;

		queueMicrotask(() => {
			this.push({ type: "start", partial: blank });
			response.content.forEach((block, index) => {
				if (block.type === "text") {
					const chunks = block.chunks ?? [block.text ?? ""];
					this.push({ type: "text_start", contentIndex: index, partial: blank });
					chunks.slice(1).forEach((chunk) => {
						this.push({ type: "text_delta", contentIndex: index, delta: chunk, partial: blank });
					});
					this.push({
						type: "text_end",
						contentIndex: index,
						content: block.text ?? "",
						partial: blank,
					});
				} else if (block.type === "toolCall") {
					this.push({ type: "toolcall_start", contentIndex: index, partial: blank });
					this.push({
						type: "toolcall_end",
						contentIndex: index,
						toolCall: finalized[index] as never,
						partial: blank,
					});
				}
			});
			if (stop === "aborted" || stop === "error") {
				this.push({ type: "error", reason: stop, error: final });
			} else {
				this.push({
					type: "done",
					reason: stop === "length" ? "length" : stop === "toolUse" ? "toolUse" : "stop",
					message: final,
				});
			}
		});
	}
}

// ── framing ────────────────────────────────────────────────────────────────

/** Renames tool-call ids to `tc1, tc2, …` by first appearance (docs/23c §2.3). */
class Normalizer {
	private readonly ids = new Map<string, string>();

	toolCallId(id: string): string {
		let name = this.ids.get(id);
		if (!name) {
			name = `tc${this.ids.size + 1}`;
			this.ids.set(id, name);
		}
		return name;
	}

	message(m: AgentMessage): unknown {
		if (m.role === "user") return { role: "user", content: m.content };
		if (m.role === "assistant") {
			// 3a parity (docs/31 §8.19): provider identity + usage now render —
			// twin of the Java FrameNormalizer assistant branch. The mock sets all
			// of them via createAssistantMessage (never faux's withUsageEstimate),
			// so they are deterministic. `timestamp` does NOT ride (Date.now() on
			// both sides — same exclusion logic as toolCallId); `deferred` neither
			// (random handle id, no script sets it). Optional keys drop when
			// undefined — identical omission rules as the toolResult branch.
			const out: Record<string, unknown> = {
				role: "assistant",
				content: m.content.map((c) => this.block(c)),
				stopReason: m.stopReason,
			};
			if (m.api !== undefined) out.api = m.api;
			if (m.provider !== undefined) out.provider = m.provider;
			if (m.model !== undefined) out.model = m.model;
			if (m.usage !== undefined) out.usage = m.usage;
			if (m.errorMessage !== undefined) out.errorMessage = m.errorMessage;
			return out;
		}
		// toolResult carries the whole message payload now — twin of the Java
		// FrameNormalizer.messageOf (createToolResultMessage puts details/usage/
		// addedToolNames on the message, agent-loop.ts:784-797). Omission rules
		// match `result()`: undefined/empty keys are dropped (JS stringify already
		// drops undefined; addedToolNames only rides when non-empty). No
		// `terminate` on a message (result-tree-only), and toolCallId stays off
		// the frame (unstable id; order locates the call).
		const tr = m as ToolResultMessage;
		const out: Record<string, unknown> = {
			role: "toolResult",
			toolName: tr.toolName,
			content: tr.content.map((c) => this.block(c as AssistantMessage["content"][number])),
		};
		if (tr.details !== undefined) out.details = tr.details;
		if (tr.usage !== undefined) out.usage = tr.usage;
		if (tr.addedToolNames?.length) out.addedToolNames = tr.addedToolNames;
		out.isError = tr.isError;
		return out;
	}

	block(c: AssistantMessage["content"][number]): unknown {
		if (c.type === "text") return { type: "text", text: c.text };
		if (c.type === "thinking") return { type: "thinking", thinking: c.thinking };
		return { type: "toolCall", name: c.name, arguments: c.arguments };
	}

	/**
	 * `tool_execution_end.result` is the *whole* finalized result object
	 * (agent-loop.ts:774-782). Twin of the Java `FrameNormalizer.resultOf`:
	 * `terminate` keeps `true` only (pi's undefined/absent and an explicit false both
	 * mean "no termination" at the batch gate, `:590`); `addedToolNames` keeps
	 * non-empty only; null/undefined fields drop from the wire. `usage` passes through
	 * raw — no script sets it today; if one ever does, the field-name difference
	 * between pi's `Usage` and the Java record turns the diff red *on purpose*:
	 * negotiating a shared shape is then required, not silently skipping the field.
	 */
	result(r: unknown): unknown {
		const res = (r ?? {}) as {
			content?: AssistantMessage["content"];
			details?: unknown;
			usage?: unknown;
			addedToolNames?: string[];
			terminate?: boolean;
		};
		const out: Record<string, unknown> = {
			content: (res.content ?? []).map((c) => this.block(c)),
			terminate: res.terminate === true ? true : undefined,
			addedToolNames: res.addedToolNames?.length ? res.addedToolNames : undefined,
		};
		if (res.details !== undefined && res.details !== null) out["details"] = res.details;
		if (res.usage !== undefined && res.usage !== null) out["usage"] = res.usage;
		return out;
	}

	frame(event: AgentEvent): unknown {
		switch (event.type) {
			case "agent_start":
			case "turn_start":
				return { type: event.type };
			case "message_start":
			case "message_end":
				return { type: event.type, message: this.message(event.message) };
			case "message_update": {
				const e = event.assistantMessageEvent;
				const detail =
					"delta" in e ? e.delta : "content" in e ? e.content : "toolCall" in e ? e.toolCall.name : null;
				return { type: "message_update", evt: e.type, detail };
			}
			case "tool_execution_start":
				return { type: event.type, id: this.toolCallId(event.toolCallId), name: event.toolName };
			case "tool_execution_update":
				return {
					type: event.type,
					id: this.toolCallId(event.toolCallId),
					name: event.toolName,
					partialResult: event.partialResult,
				};
			case "tool_execution_end":
				return {
					type: event.type,
					id: this.toolCallId(event.toolCallId),
					name: event.toolName,
					result: this.result(event.result),
					isError: event.isError,
				};
			case "turn_end":
				return {
					type: "turn_end",
					stopReason: (event.message as AssistantMessage).stopReason,
					toolResults: event.toolResults.map((r) => r.toolName),
				};
			case "agent_end":
				return { type: "agent_end", messages: event.messages.map((m) => this.message(m)) };
			default:
				return { type: "unknown" };
		}
	}
}

// ── driver ─────────────────────────────────────────────────────────────────

async function runScript(script: Script): Promise<string[]> {
	const tools: AgentTool[] = (script.tools ?? []).map((t) => ({
		name: t.name,
		label: t.name,
		description: `scripted tool ${t.name}`,
		parameters: Type.Object({}, { additionalProperties: true }),
		executionMode: t.executionMode,
		execute: async (_toolCallId, _params, _signal, onUpdate) => {
			// Streamed updates mirror the Java conformance driver: each partial is a
			// whole AgentToolResult (types.ts:361-377 — `details` is a required field),
			// pushed before the call resolves → `tool_execution_update` frames (agent-loop.ts:690-704).
			for (let i = 1; i <= (t.updates ?? 0); i++) {
				onUpdate?.({
					content: [{ type: "text" as const, text: `partial ${i}` }],
					details: {},
				});
			}
			return {
				content: [{ type: "text" as const, text: t.isError ? "failed" : "ok" }],
				details: t.details ?? {},
				terminate: t.terminate ?? false,
			};
		},
	}));

	const context: AgentContext = {
		systemPrompt: script.systemPrompt ?? "You are helpful.",
		messages: [],
		tools,
	};

	// Queue drain points are polled *after* turn N's `turn_end`, i.e. once the
	// (N+1)-th assistant stream has already started. Deriving the completed-turn
	// count from the stream call counter keeps this independent of how quickly
	// the event consumer drains the stream.
	let streamCalls = 0;
	const completedTurns = () => streamCalls - 1;
	// Byte-for-byte the same echo as the Java runner's `Driver.withEcho` —
	// changing one without the other turns a wording difference into a
	// reported behavior difference.
	const withEcho = (response: ScriptResponse, context: Context, model: Model<any>): ScriptResponse => {
		const echo = `[n=${context.messages.length} model=${model.provider}/${model.id} sys=${context.systemPrompt ?? "-"}]`;
		const content = response.content.map((block, index) =>
			index === 0 && block.type === "text"
				? { ...block, text: `${echo} ${block.text ?? ""}` }
				: block,
		);
		return { ...response, content };
	};
	const streamFn = (model: Model<any>, context: Context) => {
		const response = script.responses[streamCalls++];
		return new ScriptedStream(response.echoRequest ? withEcho(response, context, model) : response);
	};
	const modelFor = (id: string): Model<any> => ({ ...createModel(), id });

	let nextTurnFired = false;
	const prepareNextTurn = async () => {
		const spec = script.nextTurn;
		if (!spec || nextTurnFired || spec.afterTurn !== completedTurns()) return undefined;
		nextTurnFired = true;
		return {
			model: spec.model ? modelFor(spec.model) : undefined,
			// pi: `currentContext = nextTurnSnapshot.context ?? currentContext` — whole replacement.
			context: spec.messages
				? {
						systemPrompt: spec.systemPrompt,
						messages: spec.messages.map(userMessage),
						tools,
					}
				: undefined,
		};
	};

	const rejected = new Set((script.tools ?? []).filter((t) => t.reject).map((t) => t.name));
	const steering = [...(script.steer ?? [])];
	const followUp = [...(script.followUp ?? [])];

	const config: AgentLoopConfig = {
		model: createModel(),
		convertToLlm: identityConverter,
		toolExecution: script.toolExecution,
		beforeToolCall: async ({ toolCall }) =>
			rejected.has(toolCall.name) ? { block: true, reason: "denied by policy" } : undefined,
		getSteeringMessages: async () => {
			const turn = completedTurns();
			const now = steering.filter((s) => s.afterTurn === turn);
			steering.splice(0, steering.length, ...steering.filter((s) => s.afterTurn !== turn));
			return now.map((s) => userMessage(s.text));
		},
		getFollowUpMessages: async () => {
			const turn = completedTurns();
			const now = followUp.filter((f) => f.afterTurn === turn);
			followUp.splice(0, followUp.length, ...followUp.filter((f) => f.afterTurn !== turn));
			return now.map((f) => userMessage(f.text));
		},
		prepareNextTurn,
	};

	const norm = new Normalizer();
	const frames: unknown[] = [];
	const stream = agentLoop([userMessage(script.prompt ?? "Hello")], context, config, undefined, streamFn);
	for await (const event of stream) {
		frames.push(norm.frame(event));
	}
	await stream.result();
	return frames.map((f) => JSON.stringify(canonical(f)));
}

/** Key-sorted JSON so key order never registers as a difference. */
function canonical(value: unknown): unknown {
	if (Array.isArray(value)) return value.map(canonical);
	if (value && typeof value === "object") {
		const out: Record<string, unknown> = {};
		for (const key of Object.keys(value as Record<string, unknown>).sort()) {
			out[key] = canonical((value as Record<string, unknown>)[key]);
		}
		return out;
	}
	return value;
}

describe("L5 conformance (pi side)", () => {
	const ids = ["S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12"];
	for (const id of ids) {
		const scriptPath = join(SCRIPTS_DIR, `${id}.json`);
		it(`runs ${id}`, async (ctx) => {
			if (!existsSync(scriptPath)) {
				ctx.skip();
				return;
			}
			const script = JSON.parse(readFileSync(scriptPath, "utf8")) as Script;
			const frames = await runScript(script);
			writeFileSync(join(OUT_DIR, `${id}.pi.jsonl`), `${frames.join("\n")}\n`);
			expect(frames.length).toBeGreaterThan(0);
		});
	}
});
