# 52 - 包 A4：prompt sections（构建、替换、差分）

> **状态：已闭环（2026-09-26）** —— R1–R7 全按建议实施；A4a/A4b/A4c 三步全落，实施记录见 §12。
> 队列来源：`docs/48 §5` 批次 A 行「prompt sections 构建、替换和差分」（P1，依赖 A1）。
> pi 参照锚点：`3390bd93630965a12a0a1a5c36ce890ec22f7e1d`（`git status` 空，工作树就在锚点上）。
> 本包闭环时必须回填的地方：本文 §12、`docs/48 §5` 的 A 行、`docs/48 §10.3` 的 L3 行
> （「`sections` 表达不了删除」）、`docs/32` 的 B87（③ 那一半）、`docs/51` 的 F11 更正、
> `docs/41` 的 `SystemMessage` 行与 `A-12` 行，以及本包 §10 新登记的条目（编号从 B94 起）。

---

## 1. 范围

### 1.1 这一包解决什么

pi 的**系统提示词不是一段字符串，而是一张「具名有序段」表**。这一包把三件事落地：

1. **构建** —— `buildSystemPromptSections`：从「自定义提示／工具集／工具片段／工具准则／追加提示／
   项目上下文／技能／cwd／自定义段」算出 `preamble`/`tools`/`rules`/`docs`/`project_context`/
   `skills`/`cwd`/`addendum` 这些具名段，每段自带 XML 标签；
2. **替换** —— 转录里的系统消息用 `sections` **按名覆盖**，值 `null` 表示**删除**该段；
3. **差分** —— `diffSystemPromptSections`：拿模型**当前**拥有的段（从转录重放）与**想要的**段比，
   产出最小补丁；补丁变成一条系统消息，在下次请求前注入。

没有这一包，pi 的「会话中途改系统提示」在 pi-java 上结构性不存在：`SystemPromptBuilder`
只吐一整段字符串，`sections` 没有生产者，删除态表达不了。

### 1.2 本包不做什么

- **`before_agent_start` 的 `forceSystemPrompt` 投影**（`_installAgentForcedPromptProjection`，
  `agent-session.ts:1173-1193`）。java **没有扩展系统**，`forceSystemPrompt` 的写入面不可达
  （F8）。随之不做的还有 `agent-session.ts:1410-1421` 里「handler 改了 selectedTools 还是
  `setActiveTools` 说了算」那套仲裁 —— 那是扩展 API 的语义，不是 sections 的。
- **`_restoreToolsFromTranscript`（`agent-session.ts:1195-1207`）**：它从转录的前导消息恢复
  `activeTools`，属于**会话恢复**（A-11 工具状态线的另一半），java 已有对应物
  （`PiLaneEngine.transcriptMessages` / `HarnessConfig.activeTools`），本包不动。
- **段级摘要／`cache_control`**：`sections` 的分段是 Anthropic prompt cache 断点的天然落点，
  但断点标记是 A-01（`cache_control`）的事，本包只保证段的**边界**稳定、可被它引用。
- **TUI／web 的 sections 渲染**（B87④ 的另一半）：本包只保证**转录**里有 sections；
  宿主面怎么显示归宿主面那一包。

### 1.3 与 A1／A2／A3 的关系

| 包 | 与本包的接口 |
|---|---|
| **A1** | `Message.SystemMessage` 第四变体 + `sections` 字段是 A1 落的。A1 **故意**只落 `Map<String,String>`，把「值 `null` ＝ 删除」留给本包（`docs/48 §10.3 L3`）。 |
| **A2** | `StreamRequest` 换成 `TranscriptContext`、五条车道改从转录取提示 —— 本包的补丁消息因此**自动**被所有车道看到，不需要再碰车道。 |
| **A3** | `NextTurnUpdate.messages`（A3b 的 R3）**就是本包的生产通道**，其 javadoc 已点名 `_preparePromptAndToolLoadout` 是生产者。`ToolChangeDeclaration` 会把 `toolsAdded` **合并进**本包产出的那条消息（锚点就是它），而不是另起一条。 |

---

## 2. 命题表（pi 侧，均已实读；引用自锚点 `3390bd936`）

### P1 —— `SystemMessage.sections` 的形状（`packages/ai/src/types.ts:491-509`）

```ts
export interface SystemMessage {
	role: "system";
	content: string | TextContent[];
	/**
	 * Named, ordered prompt sections rendered verbatim after `content`. The leading message
	 * declares them; later messages replace sections by name, and `null` removes one. Keep
	 * each section self-delimiting (a tag, a heading) so the model can relate an update to
	 * the original. Avoid integer-like names; JSON objects reorder those.
	 */
	sections?: Record<string, string | null>;
	toolsAdded?: Tool[];
	toolsRemoved?: ToolReference[];
	timestamp: number;
}
```

三条要点：① 值是 `string | null`，`null` ＝ **删除**；② 顺序是**对象插入序**（pi 用
`Object.entries`/`Object.values` 遍历）；③ pi 自己提醒「段名别用整数样字符串，JSON 对象会重排」
—— 这是给**外部**写入者（扩展、手写 entry）的告示，不是本包的义务。

### P2 —— `getSystemMessageText`（`packages/ai/src/utils/text.ts:15-21`）

```ts
export function getSystemMessageText(message: SystemMessage): string {
	const parts = [contentText(message.content)];
	for (const text of Object.values(message.sections ?? {})) {
		if (text !== null) parts.push(text);
	}
	return parts.filter((part) => part.length > 0).join("\n\n");
}
```

**跳过 `null`、滤掉空串、以 `\n\n` 连**。这是「完整提示」的渲染 —— 前导消息与重放后的
合并消息都走它。

### P3 —— `renderSystemMessageUpdate`（`text.ts:23-40`）

```ts
export function renderSystemMessageUpdate(message: SystemMessage): string {
	const parts: string[] = [];
	const text = contentText(message.content);
	if (text.length > 0) parts.push(text);
	for (const [name, value] of Object.entries(message.sections ?? {})) {
		parts.push(
			value === null
				? `Removed system prompt section "${name}".`
				: `Updated system prompt section "${name}":\n\n${value}`,
		);
	}
	return parts.join("\n\n");
}
```

与 P2 的**两处关键差别**：① 段值**不过空串滤**（帧照发）；② `null` 渲染成
`Removed system prompt section "x".`。pi 自述这是请求期文本、版本间可改（`text.ts:25-27`）。

### P4 —— 重放（`packages/ai/src/utils/transcript.ts:57-80`）

```ts
export function getCurrentSystemMessage(messages: TranscriptMessages): SystemMessage | undefined {
	const content: string[] = [];
	const sections = new Map<string, string>();
	let timestamp: number | undefined;
	for (const message of messages) {
		if (!isSystemMessage(message)) continue;
		timestamp ??= message.timestamp;
		const text = contentText(message.content);
		if (text.length > 0) content.push(text);
		for (const [name, value] of Object.entries(message.sections ?? {})) {
			if (value === null) sections.delete(name);
			else sections.set(name, value);
		}
	}
	const tools = getCurrentTools(messages);
	if (timestamp === undefined && tools.length === 0) return undefined;
	return {
		role: "system",
		content: content.join("\n\n"),
		...(sections.size > 0 ? { sections: Object.fromEntries(sections) } : {}),
		...(tools.length > 0 ? { toolsAdded: tools } : {}),
		timestamp: timestamp ?? 0,
	};
}
```

`Map.set` 保插入序、**已存在的键被覆盖时不改变位置**（与 JS `Set`/`Map` 同语义）；
删除后再设会排到**末尾**。`getCurrentSystemPrompt` = `getSystemMessageText(重放结果)`，
无系统消息时空串。

### P5 —— 构建入参与归一（`packages/coding-agent/src/core/system-prompt.ts:9-70`）

```ts
export interface BuildSystemPromptOptions {
	customPrompt?: string;          // 替换默认 preamble
	forceSystemPrompt?: string;     // before_agent_start 的整份替换
	selectedTools?: string[];       // 默认 ["read","bash","edit","write"]
	toolSnippets?: Record<string, string>;
	toolGuidelines?: Record<string, string[]>;
	promptGuidelines?: string[];
	appendSystemPrompt?: string;
	sections?: Record<string, string>;
	cwd: string;
	contextFiles?: Array<{ path: string; content: string }>;
	skills?: Skill[];
}
```

`normalizeBuildSystemPromptOptions` 把每一项归一成**非可选、已拷副本**的形状
（`selectedTools` 默认四件套；`appendSystemPrompt` 默认 `""`；集合类逐层浅拷）。
它存在的理由是「暴露给扩展的可变形状」—— java 无扩展，但归一仍然是**差分稳定性**的前提：
默认值必须在两侧一致，否则每次差分都会吐出常量段。

### P6 —— `buildSystemPromptSections`（`system-prompt.ts:120-180`）

顺序即语义，逐条：

1. **段名校验**（`:132-136`）：每个自定义段名必须匹配 `^[a-z][a-z0-9_-]*$`，且**不得**叫
   `preamble` ⇒ 否则 `throw new Error(\`Invalid system prompt section name: ${name}\`)`；
2. `preamble` ＝ `customPrompt ??`（默认正文，见下）；
3. 无 `customPrompt` 时才产出 `tools`（`:151-155`）：

   ```
   ${selectedTools.filter(n => !!toolSnippets[n]).map(n => `- ${n}: ${toolSnippets[n]}`).join("\n") || "(none)"}

   In addition to the tools above, you may have access to other custom tools depending on the project.
   ```

   注意两个细节：**只有带 snippet 的工具进表**；过滤后为空写 `(none)`；
4. 无 `customPrompt` 时才产出 `rules` ＝ `buildRules(...)`（P7）；
5. 无 `customPrompt` 时才产出 `docs`（默认正文，路径来自 `getReadmePath()/getDocsPath()/getExamplesPath()`）；
6. `addendum` ＝ 非空 `appendSystemPrompt` 时才产出；
7. `project_context` ＝ `contextFiles` 非空时，`renderProjectContext`（`:72-79`）：

   ```
   Project-specific instructions and guidelines:

   <project_instructions path="${path}">
   ${content}
   </project_instructions>
   ```

   多份之间以 `\n\n` 连（`["…", ...files].join("\n\n")`）；
8. `skills` ＝ 当 `selectedTools` 含 `read` 或 `bash`（取 `["read","bash"].find(...)`，
   **read 优先**）且技能非空时，`formatSkillsForPrompt(skills, tool).trim()` 非空才产出（P15）；
9. `cwd` ＝ `cwd.replace(/\\/g, "/")`（**反斜杠一律换正斜杠**）；
10. **自定义段**（`:175-177`）：按 `Object.entries(customSections)` 顺序，**只收非空值**，
    写入 `promptSections[name]` —— 可覆盖前面任何同名段（除 `preamble`，被校验挡住）；
11. **收尾**（`:179-183`）：出参是**新对象**，`preamble` 排第一且**不加标签**；其余每个
    `name → \`<${name}>\n${content}\n</${name}>\``，按 `promptSections` 的插入序。

`preamble` 的默认正文（`:144`）：

```
You are an expert coding assistant operating inside pi, a coding-agent harness. You help users by reading files, executing commands, editing code, and writing new files.
```

### P7 —— `buildRules`（`system-prompt.ts:81-119`）

去重按 **trim 后的字面值**（`seen` 集合），空串丢弃；顺序：
① 文件操作规则（仅当 `bash|powershell` 在且 `grep/find/ls` **都不在**时，按
bash+PowerShell／只有 PowerShell／只有 bash 三选一）→ ② 按 `selectedTools` 顺序逐工具取
`toolGuidelines[name]` → ③ `promptGuidelines` → ④ 两条固定兜底
（`Be concise in your responses`、`Show file paths clearly when working with files`）。
出参每行前缀 `- `。

### P8 —— `buildSystemPromptState` / `buildSystemPrompt`（`system-prompt.ts:186-197`）

```ts
export function buildSystemPromptState(input) {
	if (input.forceSystemPrompt !== undefined) return { content: input.forceSystemPrompt };
	return { content: "", sections: buildSystemPromptSections(input) };
}
export function buildSystemPrompt(input): string {
	return getSystemMessageText({ role: "system", ...buildSystemPromptState(input), timestamp: 0 });
}
```

`forceSystemPrompt` 存在时**没有 sections**（整份文本走 `content`）—— 本包不做这一支（§1.2）。

### P9 —— `diffSystemPromptSections`（`system-prompt.ts:199-215`）

```ts
export function diffSystemPromptSections(
	previous: Record<string, string | null>,
	current: SystemPromptSections,
): Record<string, string | null> | undefined {
	const patch: Record<string, string | null> = {};
	for (const [name, text] of Object.entries(current)) {
		if (previous[name] !== text) patch[name] = text;
	}
	for (const name of Object.keys(previous)) {
		if (current[name] === undefined) patch[name] = null;
	}
	return Object.keys(patch).length > 0 ? patch : undefined;
}
```

**补丁的插入序 ＝「先全部变更/新增段（按 `current` 序），后全部删除段（按 `previous` 序）」**。
出参 `undefined` ＝ 无事发生。⚠️ `previous` 的形参类型含 `null`，但 javadoc 写明它来自转录重放
⇒ **实际上不会是 `null`**（重放已把删除消化掉）。

### P10 —— 生产者 `_preparePromptAndToolLoadout`（`agent-session.ts:1143-1170`）

```ts
private _preparePromptAndToolLoadout(
	options: NormalizedBuildSystemPromptOptions,
	messages: AgentMessage[] = this.agent.state.messages,
): SystemMessage | undefined {
	options.selectedTools = [...new Set(options.selectedTools)].filter((name) => this._toolRegistry.has(name));
	this.agent.state.tools = options.selectedTools.flatMap((name) => {
		const tool = this._toolRegistry.get(name);
		return tool ? [tool] : [];
	});
	const sections = diffSystemPromptSections(
		getCurrentSystemMessage(messages)?.sections ?? {},
		buildSystemPromptSections(options),
	);
	return sections ? { role: "system", content: "", sections, timestamp: Date.now() } : undefined;
}
```

四条纪律：① 工具名先**去重**再**过滤到注册表内**，然后**无条件**写回 `agent.state.tools`；
② 差分基准是「转录重放出的段」（不是内存里的上一份 options）；③ 有变化才建消息，
`content` 恒为 `""`、`timestamp` 是 `Date.now()`；④ **它自己不下发消息**，只返回。

### P11 —— 两个调用点

| 位置 | 动作 |
|---|---|
| `agent-session.ts:1431-1432`（`prompt()` 内，`before_agent_start` 之后） | `if (updateMessage) messages.unshift(updateMessage);` —— **前置进本次请求的 pending** |
| `agent-session.ts:599-611`（`prepareNextTurnWithContext` 内） | 回到 `{...previousSnapshot, context:{...,tools}, messages:[...(previousSnapshot?.messages ?? []), updateMessage], model, thinkingLevel}` —— **追加进下一轮的 prepared 消息** |

两条都会顺手 `this._runSystemPromptOptions = options`（让 `session.systemPrompt` 与
provider 看到的一致）。

### P12 —— 前导声明在生产上**不**由「种子」产生

两处会折叠「提示 ＋ 工具」成前导系统消息的地方，在**主线上都不触发**：

```ts
// packages/agent/src/agent.ts:84-85
const initialMessage = createInitialSystemMessage(initialState?.systemPrompt, tools.map(toToolDeclaration));
if (messages[0]?.role !== "system" && initialMessage) messages.unshift(initialMessage);
// createInitialSystemMessage: 提示与工具**都**空 ⇒ undefined（transcript.ts:12-22）
```

```ts
// packages/coding-agent/src/core/sdk.ts:366-372
agent = new Agent({ initialState: { systemPrompt: "", model, thinkingLevel, tools: [] }, ... });
```

⇒ 构造期 `createInitialSystemMessage("", [])` ＝ `undefined`，**没有种子**。
且 `createContextSnapshot()`（`agent.ts:450-456`）**只交 `{messages, tools}`**，
`runAgentLoop` 的 `declareToolChanges` 也**不读** `context.systemPrompt`；
请求期唯一的折叠点 `agent-loop.ts:357` 调的是 `normalizeContext({ messages: llmMessages })`
—— **只传 messages**。⇒ **`Context.systemPrompt` 在 pi 生产路径上是死字段。**

pi 生产的前导系统消息因此只有一个来源：**P10 的 sections 补丁**，其 `toolsAdded` 由
`declareToolChanges`（`agent-loop.ts:281-333`）的「有 pending 系统消息」那一支**合并进去**
（`withToolChanges(pending, changes)` 保留 `content`/`sections`，只换工具字段）。

**实测佐证**：pi 的 `system-prompt-updates.test.ts` 第一条断言前导消息
`content === ""`、`sections` 五个键、`toolsAdded` 四件套、且**整个会话只有一条**系统 entry。

### P13 —— skills 段（`packages/coding-agent/src/core/skills.ts:355-392`）

```
\n\nThe following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>…</name>
    <description>…</description>
    <location>…</location>
  </skill>
</available_skills>
```

（`Use bash to load…` 是 `fileReadTool === "bash"` 那一支。）五个 XML 文本位
（`&<>"'`）经 `escapeXml` 转义；不可见技能（`disableModelInvocation`）过滤掉；
过滤后为空 ⇒ 返回 `""`（调用方 `.trim()` 后仍为空则不产出段）。
`<location>` 是 `skill.filePath`。

### P14 —— 默认 `docs` 段（`system-prompt.ts:157-168`）

指向 `getReadmePath()/getDocsPath()/getExamplesPath()`（`config.ts:440-452`，
＝ pi 包目录下的 `README.md` / `docs/` / `examples/`），正文里逐条列出
`docs/extensions.md`、`docs/themes.md`、`docs/skills.md`、`docs/prompt-templates.md`、
`docs/tui.md`、`docs/keybindings.md`、`docs/sdk.md`、`docs/custom-provider.md`、
`docs/models.md`、`docs/packages.md`、`docs/environment-variables.md`。

---

## 3. 顺带发现（Java 侧，全部为今天可核实的状态）

**F1 —— `sections` 的删除态在类型上不存在。**
`Message.SystemMessage.sections()` 是 `Map<String,String>`，`orderedSections`
（`Message.java:79-89`）对值与键都 `Objects.requireNonNull`。⇒ pi 的
`sections: { b: null }` 在 java 上**没有表示**。

**F2 —— 读侧对删除**响亮抛错**。
`MessageJsonCodec.decodeSections`（`:83-101`）遇到非字符串值抛
`DecodeError.schema("has non-string section " + key)` —— B87a 的**刻意**选择（静默丢键会静默
改 prompt）。⇒ 今天任何带 `null` 值的系统消息 entry **读不回来**。

**F3 —— 渲染侧只写一支。**
`MessageTexts.renderSystemMessageUpdate`（`:75-86`）只有 `Updated …` 分支，javadoc 明写
「`value === null` 分支在 java 上表达不了」，`MessageTextsTest:19` 也照此声明
「`'Removed system prompt section "b".'` 因此在本类中不存在」。

**F4 —— 重放侧只做覆盖。**
`Transcripts.getCurrentSystemMessage`（`:140-163`）是 `sections.putAll(system.sections())`
—— 只覆盖、不删除。

**F5 —— `SystemPromptBuilder` 不是 pi 的移植。**
`pi-java-agent-core/.../prompt/SystemPromptBuilder.java`（67 行）是 pi-java 自己发明的链式
builder：`base()` 追加模板、`tools()` 输出 `## Available Tools` + `- **name**: snippet`、
`skills()` 输出 `## Active Skills` + 逐技能 `systemPrompt()`、`instructions()`。段落结构、
标题文字、工具行格式**与 pi 全不相同**，也**完全不产出 sections**。它唯一的调用者是
`ContextAssembler.buildSystemPrompt:43-51`。

**F6 —— 前导声明的折入时机与 pi 不同。**
`PiLaneEngine.ensureInitialDeclaration`（`:277-292`）在**每个 pass 的起手**用**当时的**
`systemPrompt`/`activeTools` 折一条。pi 的同名动作在 `createMutableAgentState` 里是
**构造期一次**、用**构造期**入参；且主线传 `""`/`[]` ⇒ 生产上从不折（P12）。
⇒ java 今天在 `context.messages` 里**始终多一条 pi 没有的种子消息**（不落盘、不发射，
但会进请求），且它把**提示正文放在 `content`**，而 pi 放在 `sections`。

**F7 —— `Context.systemPrompt` 在两边都是死字段，但 java 靠它承载提示。**
pi 生产不读它（P12）。java 侧自 A2 起五条车道也不再读它（`grep 'systemPrompt()'`
在 `pi-java-ai/src/main` 只命中 javadoc）——它今天只被 `PiLaneEngine` 用来**折种子**、
被 `PiLaneEngine.rebuiltContext` 复制。⇒ A4 落地后它连这个用途也没有了。

**F8 —— 没有 `before_agent_start`。**
`grep -rn "before_agent_start" --include=*.java .` **零命中**。⇒ `forceSystemPrompt`、
`event.systemPromptOptions`、`pi.setActiveTools` 这些写入面在 java 不存在；
`sections` 的**外部**写入者（扩展）也不存在。

**F9 —— `AgentHarness.setActiveTools` 零生产调用者**（A3 的 F6）。
`AgentHarness.java:470-478` 是 pi `setActiveToolsByName` 的对应物（改名 + 收对象集）。
本包让它第一次有生产意义。

**F10 —— 通道已就位。**
`PiLoop.NextTurnUpdate.messages`（A3b 的 R3）的 javadoc 已写明「**没有这条通道，
『合并』那一支在 Java 上结构性不可达**」，并点名 `_preparePromptAndToolLoadout` 是生产者。
⇒ 本包**不需要**新的管道。

**F11 —— 段名保序已是既定纪律。**
`Message.SystemMessage.orderedSections` 用 `LinkedHashMap` + `Collections.unmodifiableMap`
（A2 的 F3）；`MessageJsonCodec.decodeSections` 读侧同样 `LinkedHashMap`；
`SessionJson` 写侧 `!sections().isEmpty()` 才落键（A1 的 M4 探针钉过）。
本包只需在这三处**加入 null 值**，不动顺序纪律。

**F12 —— `docs` 段的三个路径在 pi-java 上只有一个有靶子。**
pi 的 `docs` 段引用 `getReadmePath()` / `getDocsPath()` / `getExamplesPath()`
（包目录下的 `README.md` / `docs/` / `examples/`）。pi-java 根目录有 `README.md` ✓
与 `docs/` ✓（但内容是**编号的设计文档** —— `01-requirements-analysis.md` …
`52-a4-prompt-sections-design.md` —— 不是 pi 那种 `extensions.md`/`tui.md`/`skills.md`
主题文档），**没有 `examples/` 目录**。⇒ R2 落地时 `examples` 那一行**没有对应物**，
`docs` 段的正文必须重写（R2-B），且「pi 的文档主题清单在 pi-java 上不存在」这件事本身
需要登记（§10 L-H）。

---

## 4. 设计

### 4.1 ai 侧：`sections` 的删除态（F1–F4）

**R1 的推荐＝① `Map<String,String>` 允许 `null` 值**（见 §9）。落点五处：

| 文件 | 改动 |
|---|---|
| `Message.java:79-89` | `orderedSections` 只对**键**要求非空；值允许 `null`；仍用 `LinkedHashMap` + `Collections.unmodifiableMap`（**不能**用 `Map.copyOf`/`Map.of`，二者在 null 值上 NPE —— 仓里已被 `Settings.unknown()` 咬过一次） |
| `MessageTexts.java:54-61` | `getSystemMessageText` 跳过 `null` 值（pi 的 `if (text !== null)`），空串仍滤 |
| `MessageTexts.java:75-86` | `renderSystemMessageUpdate` 加 `Removed system prompt section "x".` 一支 |
| `Transcripts.java:140-163` | 重放改成 `if (value == null) sections.remove(name); else sections.put(name, value);` |
| `MessageJsonCodec.java:83-101` | `decodeSections` 收下 JSON `null`（`put(key, null)`），文本值照旧，其它类型（数字／对象／数组）仍抛 |
| `SessionJson.java:134-136` | 无需改结构；`valueToTree` 会把 `null` 写成 JSON `null`（pi 的线格） |

**为什么不删 `requireNonNull` 而是改成「只拒键」**：pi 的 `Record` 键来自 `Object.keys`，
不可能是 null；值是 `string | null`，`null` 有语义。两侧的约束本来就不同。

### 4.2 agent-core 侧：`system-prompt.ts` 的移植（F5）

新增 `com.pijava.agent.prompt` 下的三件（**替换**现有 `SystemPromptBuilder`）：

- `SystemPromptOptions`（pi `BuildSystemPromptOptions` + `Normalized…`）—— record ＋ 静态
  `normalize(...)`，字段与 P5 一一对应；
- `SystemPromptSections`（pi 的段表别名）—— **直接用有序 `LinkedHashMap<String,String>`**，
  不新开类型：pi 的 `Record<string,string>` 在 java 上的对应物就是它，而插入序 ＝ 渲染序
  已经是既定纪律（F11）。类型别名写在 javadoc 里，读的人不必再跳一层。
- `SystemPrompts`（pi `system-prompt.ts` 的移植）：`buildSections(options)` /
  `buildState(options)` / `build(options)` / `diff(previous, current)`，
  私有 `buildRules` / `renderProjectContext` / `formatSkillsForPrompt` / 段名校验常量。

**`preamble` 的归属**：pi 的 `preamble` 在出参里**没有标签**（它是段表的第一项但不是
`<preamble>…</preamble>`）—— 移植时必须逐字保留这个不对称，否则 `getSystemMessageText`
渲染出的提示会多一层标签，且 `diff` 出的 `preamble` 补丁值也会不同（P9 的 oracle 就断言
`diffSystemPromptSections(previous, current)` 产出 `{ preamble: "You are B." }`，
**无标签**，而 `plan_mode` 那条**带** `<plan_mode>` 标签）。

**文件行数**：pi 的 `system-prompt.ts` 是 215 行（含注释），Java 版加上 record 与技能格式化
会超 500 行 ⇒ 拆三个文件（`SystemPromptOptions` / `SystemPrompts` / `SkillsPromptSection`）。

**旧 `SystemPromptBuilder` 与 `SystemPromptBuilderTest` 一并删除**（R3）。

### 4.3 agent-core 侧：生产接线（F6、F7、F9、F10）

- `ContextAssembler.buildSystemPrompt(lane)` 换成
  `ContextAssembler.promptLoadout(lane, messages)`：
  ```
  var options = <按 lane 的 options 状态归一，selectedTools = 生效工具名>;
  var patch = SystemPrompts.diff(
      orEmpty(Transcripts.getCurrentSystemMessage(messages)).sections(),
      SystemPrompts.buildSections(options));
  return patch == null ? null
      : new Message.SystemMessage("", Instant.now(), patch, List.of(), List.of());
  ```
  与 P10 逐条同形（去重、过滤到注册表、无条件写回 `lane.activeTools`）。
- `PiLaneEngine.startPass`：算出 loadout 消息后 **前置进本 pass 的 prompts**
  （pi 的 `messages.unshift(updateMessage)`），不再把它放进 `lane.messages`。
- `PiLaneEngine.prepareNextTurn`：把 loadout 消息填进 `NextTurnUpdate.messages`
  （pi 的 `[...previousSnapshot?.messages, updateMessage]`）。
- `ensureInitialDeclaration` **收窄**（R4 推荐①）：折入移进 `AgentHarness` 构造器，
  用**构造期**的 `systemPrompt`/`activeTools`，判据照 `createMutableAgentState`
  （工作副本不以系统消息开头 **且** 提示与工具**都**非空才折）。

### 4.4 提示内容的归属（R2、R3）

`AgentSession` / `SessionSetup` 从「拼一个字符串」改成「装配一份 `SystemPromptOptions`」：

- `--system-prompt` → `customPrompt`（`preamble` ＝ 用户给的整段）；
- `--append-system-prompt` → `appendSystemPrompt`（`addendum` 段）；
- 生效工具集 → `selectedTools`；各工具的 `promptSnippet()`/`promptGuidelines()` →
  `toolSnippets`/`toolGuidelines`；
- 技能 → `skills`（`<location>` 取技能文件路径）；
- `cwd` → 当前工作目录（反斜杠换正斜杠）；
- 项目上下文 → `contextFiles`（`AGENTS.md` 一类）。

**`DEFAULT_SYSTEM_PROMPT` 与「`docs` 段正文指向哪里」是 R2/R3 的裁决点**（§9）。

---

## 5. 三步拆分

照 A3 的形状（A3a/A3b/A3c 已被证明能切开）：

| 步 | 模块 | 内容 | 可独立验收 |
|---|---|---|---|
| **A4a** | `pi-java-ai` | §4.1 的六处改动 ＋ 新夹具 `SystemMessageSectionsTest`（移植 pi `system-message-replay.test.ts` 的 sections 四条，含 `{b: null}` 删除与 `Removed …` 帧） | 是：纯数据形状，`ai` 模块内自洽，不碰任何生产者 |
| **A4b** | `pi-java-agent-core` | §4.2 的移植 ＋ 删旧 builder ＋ 新夹具 `SystemPromptsTest`（移植 pi `system-prompt-updates.test.ts` 的三条纯函数用例） | 是：纯函数，无调用者也能验 |
| **A4c** | `agent-core` ＋ `coding-agent` | §4.3 的接线 ＋ §4.4 的 options 装配 ＋ `AgentHarness.setActiveTools` 的生产意义 | 是：端到端 —— 一条「起手声明 → 工具变更 → 下一轮提示段补丁」的会话夹具 |

⚠️ **顺序是硬依赖**：A4c 的差分基准来自 A4a 的重放（`getCurrentSystemMessage(...).sections()`），
而 A4b 的 `diff` 又是差分的一半。A4a 单独可跑（ai 模块），A4b 只在 A4a 之后才可能被 A4c 用到
（但 A4b 自己是纯函数，可与 A4a 并行）。

---

## 6. 先红与变异矩阵（设计期预测；实施时如实改写）

**先红手法**（照 A2b/A3c 的升级版）：夹具写在实现之后时，用
`git stash push -- <实现文件>` ＋ **旧代码复跑新夹具** 拿真红。

| 步 | 预期红集 |
|---|---|
| A4a | `sections: {b: null}` 夹具：旧代码抛 `has non-string section b`（F2）；`Removed …` 夹具：旧代码渲染出 `Updated system prompt section "b":\n\nnull`（F3）；重放夹具：旧代码保留 `b`（F4） |
| A4b | 新类型全部「找不到符号」（编译失败）；`SystemPromptsTest` 的 `preamble` 无标签／`plan_mode` 带标签两条各钉一处 |
| A4c | stash 实现 ＋ 旧代码复跑 ⇒ 期望「请求里没有那条段补丁消息」与「首轮宣告了全部工具」两类红 |

**变异探针**（M1–M?，每条必须落到「恰 N 红」并在实施记录里对账）：

- M1 `orderedSections` 恢复 `requireNonNull(value)` ⇒ 应恰 1 红（删除态夹具）
- M2 `getSystemMessageText` 去掉 `null` 跳过 ⇒ 应恰 1 红
- M3 `renderSystemMessageUpdate` 去掉 `Removed` 支（回落到 `Updated`）⇒ 应恰 1 红
- M4 重放去掉 `sections.remove` ⇒ 应恰 1 红
- M5 `diff` 的删除循环删掉 ⇒ 应恰 1 红（`plan_mode: null` 那条）
- M6 `diff` 的补丁顺序反转（删除在前）⇒ 若有夹具钉顺序则应红；**预判可能零红**（P9 的两轮
  循环决定了顺序，且渲染时删除帧的位置在生产输入下总是末尾）⇒ 若零红，**补一条两段同现的夹具**
- M7 `preamble` 加上 `<preamble>` 标签 ⇒ 应恰 2 红（`buildSections` 与 `diff` 各一）
- M8 段名校验去掉 ⇒ 应恰 1 红
- M9 `tools` 段去掉「只有带 snippet 的进表」过滤 ⇒ 应恰 1 红
- M10 `cwd` 不换反斜杠 ⇒ 应恰 1 红
- M11 `_preparePromptAndToolLoadout` 的差分基准换成「内存里上一份 options」⇒ 应恰 1 红
  （转录被外部改写后差分不收敛的那条）

⚠️ **两条方法论提醒**（A3 已付过学费，本包继续用）：
① 探针脚本可能**静默无效**（CRLF 行尾、正则漏 `;`）—— 凡报「零红」先 `grep` 复核变异是否
真的落到文件里；② 变异落到了也可能**夹具没牙**，判据是「这个夹具在什么情况下会红」。

---

## 7. 实测记录

### 7.1 pi 自己的 oracle：`packages/coding-agent/test/system-prompt-updates.test.ts`

**8 条，2026-09-26 在锚点上实跑 —— 8/8 绿**：

```
$ cd packages/ai && node scripts/generate-models.ts --strict   # providers/data/ 被 gitignore
$ ./node_modules/.bin/vitest --run --config packages/coding-agent/vitest.config.ts \
    packages/coding-agent/test/system-prompt-updates.test.ts
 Test Files  1 passed (1)
      Tests  8 passed (8)
   Duration  22.03s
```

其中三条是**纯函数**（`diffs sections into a patch`、`keeps the preamble untagged…`、以及
`buildSystemPromptState` 的两条断言）⇒ 可直接作 A4b 的 Java 夹具 oracle，逐字移植。
另外五条是会话级（需要 `createHarness` ＋ 扩展系统），逐条列出供 A4c 挑选可移植的断言。

跑完 `git status` 仍为空、`HEAD` 仍是 `3390bd936…`（工作树在锚点上）。

### 7.2 pi 的 `packages/ai/test/system-message-replay.test.ts`

A3a 已把工具部分移植进 `ToolStateChangesTest`。**本包要用的是它的 sections 部分**，逐字期望值：

```
getCurrentSystemMessage → sections: { a: "<a>2</a>", c: "<c>1</c>" }     // b 被 null 删掉
getCurrentSystemPrompt  → "base\n\nalso do this\n\n<a>2</a>\n\n<c>1</c>"
getSystemMessageText(leading) → "base\n\n<a>1</a>\n\n<b>1</b>"
renderSystemMessageUpdate(update) →
  ['Updated system prompt section "a":\n\n<a>2</a>',
   'Removed system prompt section "b".',
   'Updated system prompt section "c":\n\n<c>1</c>'].join("\n\n")
```

### 7.3 Java 侧现状的实测记录

- `grep 'systemPrompt()' pi-java-ai/src/main` ⇒ 只命中两处 javadoc，**零生产读者**（F7）；
- `grep -rn "before_agent_start" --include=*.java .` ⇒ **零命中**（F8）；
- `AgentSession.DEFAULT_SYSTEM_PROMPT` 只有 `SessionSetup.systemPromptFor:72` 一个读者；
  `SystemPromptBuilder` 只有 `ContextAssembler:46` 一个读者；
- 现有夹具对删除态的**书面声明**：`MessageTextsTest:19`（「`'Removed …'` 因此在本类中不存在」）、
  `TranscriptsTest:24`（「与 pi 夹具的唯一差异：pi 的第 5 条系统消息用 `sections: { b: null }`」）
  —— A4a 落地的同时**改写这两处声明**，它们就是先红的见证。

---

## 8. 验收 grep（实施后必须全过）

1. `grep -rn "requireNonNull(entry.getValue()" pi-java/src/main` ⇒ 零命中；
2. `MessageJsonCodec.decodeSections` 仍拒**非文本非 null** 的值（数字／对象／数组）——
   ⚠️ **文案不变**（仍是 `has non-string section <name>`），变的是条件：实施后
   `grep -rn "has non-string section" --include=*.java .` **恰 1 命中**（`MessageJsonCodec:103`），
   而「值为 JSON `null`」的那一支走的是 `put(key, null)`。**别按「零命中」复核。**
3. `grep -rn "Removed system prompt section" pi-java-ai/src/main` ⇒ 恰 1 命中；
4. `grep -rn "class SystemPromptBuilder" .` ⇒ 零命中（已删）；
5. `grep -rn "## Available Tools\|## Active Skills" --include=*.java .` ⇒ 零命中；
6. `grep -rn "diffSystemPromptSections\|SystemPrompts.diff" --include=*.java pi-java-agent-core/src/main` ⇒ ≥1；
7. `grep -rn "ensureInitialDeclaration" --include=*.java .` ⇒ 只剩构造期那一处（或零命中，视 R4 结论）；
8. `grep -rn "DEFAULT_SYSTEM_PROMPT" --include=*.java .` ⇒ 视 R3 结论（删则零命中）；
9. 全 reactor `mvn -o clean verify` SUCCESS、无 `System.out.println`、改动文件均 ≤500 行。

---

## 9. 裁决点（请审核时给结论）

### R1 —— `sections` 的「值 `null` ＝ 删除」在 Java 上用什么承载？

- **① `Map<String,String>`，值允许 `null`**（**推荐**）。改动最小（六处）、线格逐字对齐 pi
  （Jackson `valueToTree` 把 `null` 值写成 JSON `null`）、顺序纪律原样保留。
  代价：`Map.copyOf`/`Map.of`/`Map.entry` 在这个 Map 上会 NPE —— 靠 javadoc ＋ 一个断言型夹具钉住。
- **② `Map<String,Optional<String>>`**。类型上消灭 null，但涟漪到全部读写点与 JSON 编解码
  （Jackson 的 `Optional` 支持需要 `Jdk8Module`，本仓未装），且 `Optional` 作 Map 值本身是反模式。
- **③ 值对象列表 `List<SectionPatch>`**。最显式，但丢掉 pi 的「键插入序 ＝ 渲染序」语义
  （`Map.set` 覆盖不改位、删除后再设排末尾这些细节都要自己实现），且 `getSystemMessageText`
  的 `Object.values` 语义要重写。

### R2 —— pi 的默认提示**文本**移植到什么程度？

- **A 逐字照抄**（含 `docs` 段里 `docs/extensions.md`、`docs/tui.md`… 那份**文件清单**）。
  ❌ 那批文件在 pi-java 仓库里**不存在** ⇒ 会主动误导模型去读不存在的路径。
- **B 结构、段名、校验、差分、渲染逐字对齐 pi；默认**文本**用 pi-java 的内容**（**推荐**）：
  `preamble` 用 pi-java 现有正文、`docs` 段指向 pi-java 的 `README.md` 与 `docs/`
  （⚠️ **没有 `examples/`**，F12）并列出 pi-java 真实存在的文档、`rules` 的两条兜底与工具准则
  照 pi 的形状（可用 pi-java 的措辞）。
  理由：判据是**行为**对齐，而这一段是**内容**；pi 的 docs 段正文是「指向本仓文件」的索引，
  它的正确取值本来就随仓库而变。
- **C 只落机制**：`buildSections` 完整移植，但 `AgentSession` 继续以 `customPrompt` 传现有文本
  ⇒ 只产出 `preamble` 一段，`tools`/`rules`/`docs`/`cwd` 全不产出。代价：工具集变化时
  `toolsAdded` 会变而 `tools` 段不变 ⇒ **差分看不到提示里的工具清单变化**，A4c 的价值大打折扣。

### R3 —— 旧 `SystemPromptBuilder` 与 `DEFAULT_SYSTEM_PROMPT` 的处置？

- **① 删除两者**（**推荐**）：pi 没有对应物，其输出（`## Available Tools` / `## Active Skills`）
  是 pi-java 的发明；留着就是「两套提示构建器」，其中一套永远不在 pi 的信路上。
  改动的用户可见后果：默认系统提示词**整份变化**（R2 选定文本后）。
- **② 保留 `SystemPromptBuilder` 不动、新代码另起**：会在 `ContextAssembler` 里留下
  「用哪个」的分支，且旧类的测试继续为已死的文本背书。

### R4 —— 前导声明的折入时机

- **① 收窄到构造期**（**推荐**，逐字对齐 `createMutableAgentState` ＋ `sdk.ts:368-372`）：
  折入移进 `AgentHarness` 构造器，用构造期入参；`AgentSession` 构造时传
  `systemPrompt = ""`（提示走 sections）与**空工具集**，随后 `setActiveTools` 补上。
  代价：`AgentSession` 的装配顺序要动，且「构造后立刻 setActiveTools」成为一个隐式契约。
- **② 保留每 pass 折入，只把提示串传空**：改动小，但 `context.messages` 里始终多一条 pi 没有的
  系统消息（不落盘、不发射，但在**支持中途系统消息**的模型上会**多上一根线**，
  且重放的 `timestamp` 由它决定而不是补丁消息的 `Date.now()`）。
- ③ 完全不动（种子继续承载提示正文）：**与 A4 直接冲突** —— 提示会经 `content` 与 `sections`
  各出现一次，**不可选**。

### R5 —— 谁调 loadout？

- **① 起手 ＋ 每轮**（**推荐**，逐字对齐 pi 的两个调用点）：
  `PiLaneEngine.startPass`（前置进本次 pending）与 `PiLaneEngine.prepareNextTurn`
  （填 `NextTurnUpdate.messages`）。
- ② 只在起手：会话中途的工具/提示变化不会以补丁形式下发 ⇒ 差分那一半白做。

### R6 —— `AgentHarness.setActiveTools` 是否成为生产者？

- **① 是**（**推荐**）：它改了 `lane.activeTools`，下一次 `startPass` 的 options 自然带上新
  `selectedTools` ⇒ 差分自动产出 `tools`/`rules` 段补丁。A3 的 F6（「零生产调用者」）就此消解。
  本包**不需要**给它加额外代码，只需要**一条端到端夹具**证明它真的产出补丁。
- ② 否：F6 留到下一个人发现。

### R7 —— 三步拆分

- **① A4a/A4b/A4c**（**推荐**，§5）。A3 已证明这种切法能独立验收。
- ② 一步到位：跨 `ai` 与两个模块、约 900 行，先红只能靠 stash，且四类改动的红会互相掩盖。

---

## 10. 遗留登记（本包预计产出，实施后落 `docs/32`）

| 编号 | 事项 | 处置 |
|---|---|---|
| L-A | `Context.systemPrompt` 在 A4 后**两侧都无读者**（F7）。pi 保留了该字段（`Context` 类型仍声明它、`normalizeContext` 仍读它）⇒ java 保留字段、删掉生产写入 | 登记，不改 ☑ 与 pi 同 |
| L-B | `ensureInitialDeclaration` 的 `content` 承载提示文本（F6）→ 随 R4 消除 | 登记 ＋ `docs/51` 更正 F11 |
| L-C | `forceSystemPrompt` / `before_agent_start` 的段写入面不可达（F8） | 登记，不在本包 |
| L-D | 段级 prompt cache 断点（A-01 的落点） | 登记，指向 A-01 |
| L-E | TUI/web 的 sections 渲染（B87④ 的另一半） | 登记，指向宿主面 |
| L-F | pi 的 `sections` 是 `Record<string,string\|null>`，键序即渲染序；java 若将来引入「外部写入段」的入口，须重申 pi 的告示「避免整数样段名」 | 登记，今天无外部写入者 |
| L-G | 会话中途的系统提示变化在**不支持中途系统消息**的模型上会被折叠成新前导消息（`collapseSystemMessages`），A2 已落，本包不重复 | 已闭环，不出表 |
| L-H | pi 的 `docs` 段引用 `examples/` 与一批**主题式**文档（`extensions.md`/`tui.md`/`skills.md`…），pi-java **两样都没有**（F12）：没有 `examples/` 目录，`docs/` 下是编号设计文档 | 登记，随 R2 落地时按 pi-java 实际重写 `docs` 段正文 |
| L-I | `buildSystemPromptState` 的 `forceSystemPrompt` 分支（§1.2 不做）在 java 侧没有表示 ⇒ 将来若接扩展系统，`SystemPrompts.buildState` 的签名要跟着加 | 登记，不在本包 |

---

## 11. 本次设计的取证方式与已知不足

**取证方式**：全部 pi 侧引用走 `git show 3390bd936:<path>`（**不读工作树**，见
`[[pi-anchor-and-drift]]`）；pi 的 oracle 夹具**实跑**（§7.1）；Java 侧结论全部由 `grep`/读文件
得到，并在 §3 逐条给出可复核的落点。

**已知不足**：
1. **P10 的调用点顺序**（起手 unshift 与 `before_agent_start` 的先后）只读了 `:1410-1432`
   那一段；扩展系统本包不做（F8），故不影响设计，但实施 A4c 时若要对照 `prompt()` 的全序，
   需再读一遍 `:1380-1440`。
2. **pi 的 harness（`packages/agent`）路径**没有 sections 生产者 —— 只有 coding-agent 有。
   java 的 conformance 夹具（`ConformanceRunner` 直驱 `PiLoop`）因此**不会**经过本包的代码，
   S1–S15 金标不受本包影响。⇒ A4c 需要**新写**会话级夹具，而不是复用 conformance。
3. **`docs` 段的 pi-java 取值**（R2-B）在本文中只给了原则，没有逐字定稿 —— 定稿放在实施步
   A4b，并需要在夹具里逐字钉住。

---

## 12. 实施记录

**六次提交，2026-09-26**：

| 步 | commit | 内容 |
|---|---|---|
| **A4a** | `92a4b99` | `sections` 的删除态（ai ＋ agent-core 六处落点） |
| **A4b-1** | `fc1404e` | `SystemPromptOptions` / `SystemPrompts` / `SkillsPrompt` ＋ `Skill.filePath()`（机制） |
| **A4b-2** | `032de6d` | 删 `SystemPromptBuilder` ＋ `DEFAULT_SYSTEM_PROMPT`，迁 `ContextAssembler` / `HarnessConfig` / `SessionSetup` / `AgentSession` |
| **A4c-1** | `40bb3ea` | 生产者 `promptLoadout` ＋ 两个调用点 ＋ 判 `ensureInitialDeclaration` 出局 ＋ `PromptLoadoutTest` |
| **A4c-2** | `e7c68cc` | 每轮无条件交回 context（pi 的 `tools` 刷新）＋ 同 run 内切工具的夹具 |

**回归**（全 reactor `mvn -o test`，见 §12.6）：`ai` 886⇒**893**、`agent-core` 504⇒**519**、
`coding-agent` **272**、`tui` / `sqlite` 等其余模块不变；checkstyle 零新违规、`git diff --check`
干净、改动文件无 `System.out.println`。

### 12.1 A4a —— `sections` 的删除态（`92a4b99`）

§4.1 的六处落点全部落地。**先红：9 跑 8 红**（`SystemMessageSectionsTest`），全部是
`NullPointerException: section value`（`Message.java` 的 `orderedSections`）—— 形状门在上游，
所以红集**不分叉**；渲染与重放两支由变异探针守（§12.5 的 M2/M3/M4）。

⚠️ **实施中撞出的真陷阱（设计稿没预见）**：`SessionJson` 的 mapper 带
`setSerializationInclusion(NON_NULL)` ⇒ `valueToTree(sections)` 会把**值为 `null` 的键整个丢掉**，
「删掉 `obsolete` 段」静默变成「没提过 `obsolete`」—— 而两者的重放结果**不同**（前者删段、
后者保留此前设过的值）。先红拿到了 **2 红**（`SessionJsonSystemMessageTest` 与
`MessageJsonCodecSystemReadbackTest` 各一），改成逐项 `putNull` 后转绿。
`PiMessagesApi` 的 mapper 今天没有这个策略，但同样改成逐项写 —— **不把语义绑在映射器的默认值上**。

`TranscriptsTest` 与 `MessageTextsTest` 恢复成 pi `system-message-replay.test.ts` 的**逐字**
输入与期望（此前为绕开缺失的删除态做过删改，见两个类的旧 banner）。

### 12.2 A4b —— 移植 `system-prompt.ts` 并替换旧 builder（`fc1404e`、`032de6d`）

19 条新夹具（`SystemPromptsTest`）全绿，其中两条是 pi oracle
（`diffs sections into a patch`、`keeps the preamble untagged and replaces it like any section`）
的逐字移植。pi 那条 `buildSystemPromptState({forceSystemPrompt})` 的断言**没有移植** —— 见 §1.2／L-I。

**A4b-2 的连带面**（比设计稿预估的大，但都是「删除的必然结果」）：

- `HarnessConfig` ＋ `appendSystemPrompt` / `promptGuidelines` 两个组件（18 参兼容构造器 ＋
  Builder 同步）；`ExecutionContext` ＋ 两个 supplier；`LaneState` ＋ 两个字段；
  `AgentHarness` ＋ 两个 setter ＋ 两个 getter。
- `ContextAssembler.buildSystemPrompt` → `promptOptions` / `renderPrompt` / `promptLoadout` 三件。
  其中 `renderPrompt` 是**派生值**（`Context.systemPrompt` 仍是它，pi 生产上那个字段是死的 ——
  §2 P12；java 留着它只为让请求录制与诊断看得见模型实际收到的提示）。
- **空配置判据**：什么都没配（无提示、无工具、无准则、无技能、无追加）⇒ **空提示**，不是默认提示。
  这是 pi `createInitialSystemMessage` 的空判据在 harness 层的对应物；不加它，`AgentHarness` 的
  SDK 用户会在毫无配置时凭白多出一条系统消息（3 个既有夹具当场变红，见 §12.4-3）。
- `SessionSetup`：`systemPromptFor` 拆成 `customPromptFor` ＋ `appendSystemPromptFor` ＋
  新增 `DEFAULT_PROMPT_GUIDELINES`（pi-java 原有的 7 条表达风格条目里，**去掉**了 pi 兜底已有的
  两条「简洁」「写清路径」—— 逐字去重不会命中带句号的版本，留着就是重复项）。
- `AgentSession.DEFAULT_SYSTEM_PROMPT` 删除；`AgentSessionTest` 的两条断言按新映射改写
  （`--system-prompt` 与 `--append-system-prompt` 现在落在**两个槽**里）。

### 12.3 A4c —— 生产者与接线（`40bb3ea`、`e7c68cc`）

`ContextAssembler.promptLoadout` 按 §4.3 落地；`PiLaneEngine.startPass` 前置进 pending、
`prepareNextTurn` 填 `NextTurnUpdate.messages`；`ensureInitialDeclaration` 与其移植件
`ToolChangeDeclaration.initialDeclaration`（＋3 条夹具）**删除** —— R4 的结论。

**四处实施中才定下来的细节**：

1. **落盘序**：pi 的补丁与用户消息**都**经 `message_end` 落盘 ⇒ 日志序 `[system, user]`。java 的
   用户 entry 由 `RunLifecycle.startRun` **先行**写（`docs/31 §4.2` 的既定设计，且那条 entry
   因此**不算** `WriteDeferred`）⇒ 补丁也必须由 `startRun` 先写。
2. **抑制**：补丁进循环后会被 `declareToolChanges` **复制**一次（工具字段写回），引用对不上
   `alreadyPresent` ⇒ `PiLaneSink` 增加**按时间戳的一次性抑制**（`suppressSystemMessageAt`）。
   ⚠️ 试过「把补丁也放进 `lane.messages` 让锚点分支返回原对象」——不行，`onMessageEnd` 无条件
   `lane.messages.add`，会重复。
3. **工具序**：`tools`/`rules` 两段按 `selectedTools` 顺序生成，而 `activeTools` 是 `Set`
   （`Set.copyOf` 的迭代序**跨 JVM run 可变**）⇒ 走 `ToolRegistry.activeOf`（注册表序），
   与 `PiLaneEngine.activeTools` 同一稳定源。这不是洁癖：用 Set 的序会让**每次启动的提示都不一样**，
   续跑时吐出无意义的段补丁。
4. **`Context.tools` 的刷新**（`e7c68cc`）：pi 的 `prepareNextTurnWithContext` 返回值里
   `context: {...nextContext, tools: state.tools.slice()}` 是**每次都重建**的
   （`agent-session.ts:605-608`），java 此前只在压缩时交回 ⇒ **同一 run 内**的 `setActiveTools`
   对 `declareToolChanges` 不可见（段补丁说换了工具、工具声明一声不吭）。由探针 C4 撞出（§12.5）。

**端到端夹具** `PromptLoadoutTest`（4 条）：起手补丁的形状与工具合并、跨 prompt 切工具
（pi oracle `setActiveTools emits prompt sections and tool changes before the next request` 的镜像）、
**同 run 内**切工具（走 `NextTurnUpdate` 通道）、段表没变则不产出补丁。

**顺带修**：`PayloadRecordingStreamFn` 的消息投影只写 `role`/`content` ⇒ A4c 之后录制里那条系统消息
看起来是**空的**（提示在 `sections` 里）。补上三个 delta 字段与一条夹具断言。

### 12.4 设计偏离与实测更正

1. **§4.2 的「三个文件」实际是两个**：`SystemPromptOptions`（record ＋ Builder）与
   `SystemPrompts`（含 `buildRules`/`renderProjectContext`/`tag`），技能格式化独立成
   `SkillsPrompt`。三件合计 208 ＋ 314 ＋ 86 ＝ 608 行，各自都在 500 行以内。
2. **`docs` 段的取值**（R2-B 的落地）：正文按 pi-java 重写（`README.md` ＋ `docs/` ＋ 一段说明
   `docs/` 是编号设计文档），**没有 `examples/` 那一行**（F12）；且整段只在
   `documentation != null` 时产出 —— 而 `ContextAssembler` 今天**不传**它
   （`SystemPromptOptions.DocumentationPaths` 无生产者）。⇒ **`docs` 段在今天只有测试可达**，
   登记为 L-J。
3. **空配置判据**（§12.2 的第五条）是**设计稿没写**的：`CrossTurnContextTest`（2 条）与
   `HarnessTelemetrySpansTest`（1 条）当场变红，因为「渲染出的提示恒非空」让
   `ensureInitialDeclaration` 对无工具无提示的 harness 也落了种子。判决：**补空判据**（而不是改夹具）
   —— pi 的 `createMutableAgentState` 在「提示与工具都空」时不建前导消息。
4. **`ToolChangeDeclaration.initialDeclaration` 的删除顺带减掉 3 条夹具**（agent-core 518⇒515）。
5. **`AgentHarness.java` 514⇒523、`PiLaneSink.java` 533⇒556**：两条**存量超限**文件（`docs/32`
   已登记）本包各增 9 / 23 行，未拆分。

### 12.5 变异红集（全部实测；⚠️ 两条探针自己出过假零红）

**A4a**（`ai` 三夹具 ＋ `agent-core` 两夹具）：

| 探针 | 变异 | 红 |
|---|---|---|
| M1 | `orderedSections` 恢复拒 null 值 | **10 error**（3 个类） |
| M2 | 完整提示不跳过 null 段 | **2 error** |
| M3 | 更新渲染的条件取反（去掉 `Removed` 支） | **4 failure** |
| M4 | 重放把 `remove` 换成 `put(key, null)` | **3 failure** |
| M5 | `SessionJson` 落线回退 `valueToTree` | **2 failure** |
| M6 | 读侧重新拒 null | **2 error** |

**A4b**（`SystemPromptsTest`）：B1 `preamble` 也包标签 ⇒ **4 红** · B2 段名校验去掉 ⇒ **1 红** ·
B3 空工具表写空串而非 `(none)` ⇒ **1 红** · B4 cwd 不做反斜杠归一 ⇒ **1 红** ·
B5 准则去重去掉 ⇒ **1 红**。

**A4c**（`PromptLoadoutTest`（＋`PiLaneEngineTest`））：C1 生产者恒不产出补丁 ⇒ **2 红** ·
C2 差分两侧对调 ⇒ **3 红** · C3 起手不把补丁前置进 pending ⇒ **3 红** ·
C4 下一轮不交回补丁 ⇒ **1 红**（⚠️ 首次实测**零红**，见下） · C5 sink 不做抑制 ⇒ **5 红**。

⚠️ **本包的两条探针方法论教训**（接 A3 的两条，`docs/51 §12.4.2`）：

1. **「变异落地」的检查必须确认原串也消失了**。B4 的 perl 因反斜杠转义失败（根本没改），
   而落地检查用的是**子串**`options.cwd()` —— 它恰好是原串 `options.cwd().replace('\\','/')` 的
   子串 ⇒ 检查通过、实跑零红，**假红报绿**。改成「原串计数必须下降」后拿到 1 红。
2. **`perl -0pi` 不带 `/g` 只替换第一处**。C1 的目标行 `var options = promptOptions(lane);` 在
   `ContextAssembler` 里出现**两次**（`renderPrompt` 与 `promptLoadout`），探针只改了前者 ⇒ 零红。
   改成按行号定位后拿到 2 红。
3. ⚠️ **「零红」也可能是夹具的盲区，而不是探针的问题**。C4（把 `NextTurnUpdate.messages` 改成恒
   `null`）**真落地了**却是零红：当时**没有任何夹具**走那条通道（java 每次 `prompt()` 都是新 run，
   起手那条路把跨 prompt 的场景全包了）。补上「同 run 内切工具」的夹具之后 C4 恰 1 红。
   ⇒ **一个探针零红的三种成因**：变异没落地（1、2）、夹具没牙、**通道没夹具**。
4. **`git checkout -- <file>` 会连未提交的实现改动一起还原**（本包踩了**两次**：A4a 的探针把
   `Message.java`/`MessageTexts.java` 打回 HEAD；A4c 的 C4 把 tools-refresh 修复打回 HEAD）。
   ⇒ 探针一律在**已提交**的工作树上跑，或先 `cp` 备份。

### 12.6 遗留登记（落 `docs/32`，编号 B94 起）

| 编号 | 事项 | 处置 |
|---|---|---|
| **B94** | **pi-java 的 8 个内置工具一个都没声明 `promptSnippet`/`promptGuidelines`**，而 pi 的 8 个全声明了（`read.ts:74`、`bash.ts:388`…，都是 `*ToolSystemPromptContribution`）。⇔ pi 的 `tools` 段**只收声明了片段的**工具（`system-prompt.ts:151`）—— 照该稀疏规则，pi-java 的段会是 `(none)`。本包因此用「有片段用片段、否则用描述」的回落保住工具清单，并把这份 `*ToolSystemPromptContribution` 的移植登记为缺口 | 新登记 |
| **B95** | `docs` 段**没有生产者**：`SystemPromptOptions.DocumentationPaths` 只在夹具里构造过，`ContextAssembler` 不传。（pi 的三个路径是 `getReadmePath()/getDocsPath()/getExamplesPath()` 的包目录解析；pi-java 无 `examples/`，且需要一个「发行根」的解析策略） | 新登记 |
| **B96** | `PayloadRecordingStreamFn` 的消息投影从「只写 role/content」扩到含三个 delta 字段 —— 记的是 **pi-java 自有**的录制格式（不是 pi 的线格），改动会改变既有 trace 文件的形状 | 新登记 |
| L-A | `Context.systemPrompt` 在两侧都无行为读者（pi 生产是死字段）；java 保留它作**派生值**（请求录制/诊断） | 登记，与 pi 同 |
| L-C | `forceSystemPrompt` / `before_agent_start` 的段写入面不可达（F8） | 登记，不在本包 |
| L-D | 段级 prompt cache 断点（A-01 的落点）—— 本包已保证段边界稳定 | 指向 A-01 |
| L-E | TUI/web 的 sections 渲染（B87④ 的另一半）—— ⚠️ A4c 之后系统消息**有生产可见的 sections**，这条从「静默误渲染」升级为**用户可见** | 指向宿主面，**优先级上调** |
| L-F | pi 的告示「段名避开整数样的字符串」（JSON 对象会重排它们）—— java 今天无外部段写入者 | 登记 |
| L-I | `buildSystemPromptState` 的 `forceSystemPrompt` 分支在图型上缺席 | 登记 |
| L-J | `docs` 段（B95）与 `docs/52 §11` 的第三条已知不足同源 | 见 B95 |
