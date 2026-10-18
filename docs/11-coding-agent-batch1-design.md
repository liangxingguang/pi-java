# 11 coding-agent 批 1 设计 —— B236 工具选择模式面（`--tools` pattern/modifier/`mcp__` ＋ `defaultTools` 设置）

> **状态：✅ 已闭环（2026-10-18）**——6 commits：`48053f0f` ToolNameMatcher+ToolSelection、`5e603584` defaultTools 设置键、`3acd01f6` --tools 解析期校验＋help 文本、`b1cefdc1` 装配接线（注册面门＋默认 7→4＋createCodingTools 删除）、`ca0e1177` DefaultToolsSelectionTest、闭环回填 commit。账面：coding-agent 217.5/496（43.9%）→ **221.0/496 = 44.6%**（未加权 92 → 95/290 = 32.8%）；合计 882.5/1381（63.9%）→ **886.0/1381 ≈ 64.2%**。全量 `mvn clean verify` 绿（exit 0）。
> 实施偏差注记：① `Resolved.isActiveOnLoad` 加了 `isAllowed` 门（§3.6 对账表行 7 自洽——设计稿 Resolved 代码与其自身对账表的潜在不一致，测试按对账表写）；② `PowerShellToolTest` 七件形断言**删除**而非 §3.8 预想的改断（agent-core 看不见 coding-agent 的 `ToolSelection`，语义覆盖在 ToolSelectionTest/DefaultToolsSelectionTest）；③ §6 回填的全仓数字按批 5 后真实基线重算（882.5/1381，非本稿 0 批次卡的 869.0/1383）。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（agent-core 批 4 闭环后，`c5f0f06c`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`09-agent-core-batch4-design.md`（已闭环）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围（用户 2026-10-18 选定） | **B236** 全量：`--tools`/`-t` 的通配 pattern（`*`）、`+name`/`-name` 修饰符、`mcp__` 前缀语义；`--exclude-tools`/`-xt` 通配 denylist；settings `defaultTools` 键（含 `mergeDefaultTools`/`resolveDefaultTools` 分层语义）；默认激活集与注册面过滤的连带对齐 |
| 地图单元 | map/04 **A-20**（`--tools`，w2 存疑→对齐）、**A-21**（`--exclude-tools`，w1 存疑→对齐）、**E2-35**（`defaultTools`，w2 缺失→对齐）；A-18/A-19 判词补注（注册面语义，无账面变动） |
| 基线账面 | coding-agent 217.5/496 = **43.9%**（未加权 92/290 = 31.7%）；全仓 869.0/1383 ≈ 62.8% |
| 目标账面 | coding-agent **221.0/496 = 44.6%**（+3.5）；未加权 **95/290 = 32.8%**；全仓 **872.5/1383 ≈ 63.1%** |
| 重件 | 会话装配接选择策略：注册面过滤＋默认激活集 **7→4**（§3.5/§3.6，裁决①）；`ToolSelection.resolve` 全分支（§3.2） |
| 中件 | `defaultTools` 设置键＋merge 特判（§3.3）；`ToolNameMatcher`（§3.1）；移植 pi `default-tools-setting.test.ts` 可达子集（§4） |
| 小件 | ArgsParser 解析期校验＋`null ≙ 缺席`（§3.4）；HelpText（§3.7）；ToolSetFactory 清理（§3.8，裁决②） |
| 裁决 | ① 默认激活集 7→4（pi 锚点实证，**最大用户可见变化**）；② `createCodingTools`（七件形）删除——无 pi 对位；③ `mcp__` 保留语义策略先行（可观察效果随 MCP 批 B221 ⑫b）；④ reload 臂（`usesDefaultTools`/`defaultToolModifiers` on reload）登记 **B246** 不做 |
| 新登记 | B246（工具选择的 reload 臂＋SDK `tools` 选项抛错面） |
| 不碰 | codemode/`tool_search`（R5）；MCP 工具进会话注册面/激活门（B221 ⑫b）；`--no-mcp`（map/07）；扩展系统 D 域；SDK 面；TUI |

**一句话**：pi 的 `--tools` 不是「逗号裸名过滤」，而是一套**三层语义**——名单条目（裸名/`*` 模式/`mcp__` 前缀）→ 修饰符解析（`+name`/`-name` 叠在默认选择上）→ 注册面门（`_isAllowedTool`：不匹配的内置工具**连注册都不注册**，MCP 工具有保留条款）。本批把这三层连同 `defaultTools` 设置键整体搬过来。

---

## 1. pi 参照（逐字）

### 1.1 CLI 解析与校验（`cli/args.ts:146-166`）

```ts
		} else if (arg === "--no-tools" || arg === "-nt") {
			result.noTools = true;
		} else if (arg === "--no-builtin-tools" || arg === "-nbt") {
			result.noBuiltinTools = true;
		} else if ((arg === "--tools" || arg === "-t") && i + 1 < args.length) {
			const tools = args[++i]
				.split(",")
				.map((s) => s.trim())
				.filter((name) => name.length > 0);
			const error = getToolListError(tools);
			if (error) {
				result.diagnostics.push({ type: "error", message: `${arg}: ${error}` });
			} else {
				result.tools = tools;
			}
		} else if ((arg === "--exclude-tools" || arg === "-xt") && i + 1 < args.length) {
			result.excludeTools = args[++i]
				.split(",")
				.map((s) => s.trim())
				.filter((name) => name.length > 0);
		} else if (arg === "--thinking" && i + 1 < args.length) {
```

要点：`--tools` 解析期跑 `getToolListError`，失败 push **error** 诊断（`${arg}: ${error}`，arg 是实敲的旗标）且**不写** `result.tools`；`--exclude-tools` 无校验。帮助文本（`args.ts:318-324`）：

```
  --no-tools, -nt                Disable all tools by default (built-in and extension)
  --no-builtin-tools, -nbt       Disable built-in tools by default but keep extension/custom tools enabled
  --tools, -t <tools>            Comma-separated allowlist of tool names or patterns (*) to enable
                                 Keeps MCP tools unless an entry starts with mcp__
                                 Only +name/-name entries add to or remove from the defaults
  --exclude-tools, -xt <tools>   Comma-separated denylist of tool names or patterns (*) to disable
                                 Applies to all tools, MCP tools included
```

### 1.2 修饰符与默认选择（`core/settings-manager.ts:83-137`）

```ts
/** Tools enabled at startup when `defaultTools` does not change them. */
export const DEFAULT_TOOL_NAMES: readonly string[] = ["read", "bash", "edit", "write"];

/** Whether a tool selection entry is a `+name` or `-name` modifier. */
export function isToolModifier(entry: unknown): boolean {
	return typeof entry === "string" && (entry.startsWith("+") || entry.startsWith("-"));
}

/**
 * Validate a tool list from `--tools` or the SDK `tools` option. It is either an allowlist of plain
 * names and patterns or a list of only `+name`/`-name` entries with exact names. Returns the
 * problem, or undefined when the list is valid.
 */
export function getToolListError(entries: readonly string[]): string | undefined {
	const modifiers = entries.filter(isToolModifier);
	if (modifiers.length === 0) return undefined;
	if (modifiers.length < entries.length) return "tool names cannot be mixed with +name or -name entries";
	const pattern = modifiers.find((entry) => entry.includes("*"));
	if (pattern) return `+name and -name entries take exact tool names, not patterns: ${pattern}`;
	return undefined;
}

/**
 * Apply the `+name` and `-name` entries of `entries` to `base` in order: `+name` adds a tool and
 * `-name` removes one. Other entries are ignored.
 */
export function applyToolModifiers(base: readonly string[], entries: readonly string[]): string[] {
	const tools = [...base];
	for (const entry of entries) {
		if (!isToolModifier(entry)) continue;
		const name = entry.slice(1);
		const index = tools.indexOf(name);
		if (entry.startsWith("+") && index === -1 && name) tools.push(name);
		else if (entry.startsWith("-") && index !== -1) tools.splice(index, 1);
	}
	return tools;
}

/**
 * Merge `defaultTools` of two settings layers. A list with plain tool names replaces the inherited
 * one; a list of only `+name`/`-name` entries is appended, so it modifies the inherited selection.
 */
function mergeDefaultTools(base: string[] | undefined, overrides: string[] | undefined): string[] | undefined {
	if (overrides === undefined) return base;
	// Settings files are not validated; a malformed value replaces instead of throwing here.
	if (!Array.isArray(base) || !Array.isArray(overrides) || !overrides.every(isToolModifier)) return overrides;
	return [...base, ...overrides];
}

/**
 * Resolve a merged `defaultTools` list: plain names replace `DEFAULT_TOOL_NAMES`, then `+name` adds
 * and `-name` removes a tool, in list order.
 */
function resolveDefaultTools(entries: string[]): string[] {
	const plain = entries.filter((entry) => !isToolModifier(entry));
	return applyToolModifiers(plain.length > 0 || entries.length === 0 ? plain : DEFAULT_TOOL_NAMES, entries);
}
```

`getDefaultTools`（`settings-manager.ts:1328-1333`）：

```ts
	/** The resolved `defaultTools` selection, or undefined when no settings layer sets it. */
	getDefaultTools(): string[] | undefined {
		const tools = this.settings.defaultTools;
		if (tools === undefined) return undefined;
		return resolveDefaultTools(Array.isArray(tools) ? tools.filter((tool) => typeof tool === "string") : []);
	}
```

设置 schema（`settings-schema.ts:326-331`）：

```ts
		defaultTools: Type.Optional(
			Type.Array(Type.String(), {
				description:
					"Initial tool selection. Plain names replace the inherited selection; +name and -name entries add or remove tools.",
			}),
		),
```

`deepMergeSettings` 的特判（`settings-manager.ts:139-145`）：JSON 深合并里数组是整体替换，唯独 `defaultTools` 走 `mergeDefaultTools`（覆盖层全修饰符 ⇒ 追加）。

### 1.3 名称匹配器与 `mcp__`（`core/mcp-servers.ts:11-42`）

```ts
/**
 * Regular expression for a tool name pattern where `*` matches any characters, as `toolExposure`,
 * `--tools`, and `--exclude-tools` accept them.
 */
function toolPatternRegExp(pattern: string): RegExp {
	const source = pattern
		.split("*")
		.map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
		.join(".*");
	return new RegExp(`^${source}$`);
}

/** Whether a tool name matches any of the entries, each an exact name or a pattern. */
export function createToolNameMatcher(entries: readonly string[]): (name: string) => boolean {
	const names = new Set(entries.filter((entry) => !entry.includes("*")));
	const patterns = entries.filter((entry) => entry.includes("*")).map(toolPatternRegExp);
	return (name) => names.has(name) || patterns.some((pattern) => pattern.test(name));
}

/** MCP resource tools, which reach every server with resources. */
export const LIST_MCP_RESOURCES_TOOL = "list_mcp_resources";
export const LIST_MCP_RESOURCE_TEMPLATES_TOOL = "list_mcp_resource_templates";
export const READ_MCP_RESOURCE_TOOL = "read_mcp_resource";

const MCP_RESOURCE_TOOLS: ReadonlySet<string> = new Set([
	LIST_MCP_RESOURCES_TOOL,
	LIST_MCP_RESOURCE_TEMPLATES_TOOL,
	READ_MCP_RESOURCE_TOOL,
]);

/** Whether a tool comes from MCP: a server tool (`mcp__<server>__<tool>`) or a resource tool. */
export function isMcpToolName(name: string): boolean {
	return name.startsWith("mcp__") || MCP_RESOURCE_TOOLS.has(name);
}
```

注意：这不是 minimatch——只有 `*`→`.*` 一条规则，其余字符全部字面化，全串锚定。

### 1.4 会话装配解析（`core/sdk.ts:280-295`）

```ts
	const toolListError = options.tools ? getToolListError(options.tools) : undefined;
	if (toolListError) throw new Error(`Invalid tools option: ${toolListError}`);
	const defaultToolNames = options.noTools ? [] : (settingsManager.getDefaultTools() ?? DEFAULT_TOOL_NAMES);
	// A `tools` list of only `+name`/`-name` entries changes the default selection instead of
	// replacing it, like the `defaultTools` setting.
	const toolModifiers = options.tools?.some(isToolModifier) ? options.tools : undefined;
	const selectedToolNames = toolModifiers ? applyToolModifiers(defaultToolNames, toolModifiers) : options.tools;
	const allowedToolNames = toolModifiers
		? options.noTools === "all"
			? selectedToolNames
			: undefined
		: (options.tools ?? (options.noTools === "all" ? [] : undefined));
	const excludedToolNames = options.excludeTools;
	const isExcludedTool = excludedToolNames ? createToolNameMatcher(excludedToolNames) : undefined;
	const initialActiveToolNames = (selectedToolNames ?? defaultToolNames).filter((name) => !isExcludedTool?.(name));
```

会话配置接线（`sdk.ts:471-476`）：

```ts
		initialActiveToolNames,
		usesDefaultTools: (options.tools === undefined || toolModifiers !== undefined) && !options.noTools,
		defaultToolModifiers: toolModifiers,
		allowedToolNames,
		excludedToolNames,
```

`main.ts:540-549` 的 CLI→SDK 映射：

```ts
	// Tools
	if (parsed.noTools) {
		options.noTools = "all";
	} else if (parsed.noBuiltinTools) {
		options.noTools = "builtin";
	}
	if (parsed.tools) {
		options.tools = [...parsed.tools];
	}
	if (parsed.excludeTools) {
		options.excludeTools = [...parsed.excludeTools];
	}
```

### 1.5 会话内消费（`core/agent-session.ts`）

构造器装匹配器（`:502-508`）：

```ts
		if (config.allowedToolNames) {
			this._allowedTools = createToolNameMatcher(config.allowedToolNames);
			this._allowlistFiltersMcp =
				config.allowedToolNames.length === 0 || config.allowedToolNames.some((entry) => entry.startsWith("mcp__"));
		}
		this._excludedTools = config.excludedToolNames ? createToolNameMatcher(config.excludedToolNames) : undefined;
```

注册面门（`:1534-1543`）：

```ts
	/**
	 * Whether `--tools` and `--exclude-tools` keep the tool registered. MCP tools stay registered
	 * unless the allowlist filters them (see `_allowlistFiltersMcp`).
	 */
	private _isAllowedTool(name: string): boolean {
		if (this._excludedTools?.(name)) return false;
		if (!this._allowedTools || this._allowedTools(name)) return true;
		return !this._allowlistFiltersMcp && isMcpToolName(name);
	}
```

激活面（`_refreshToolRegistry`，`:3566-3587`）：

```ts
		const nextActiveToolNames = (
			options?.activeToolNames ? [...options.activeToolNames] : [...previousActiveToolNames]
		).filter((name) => this._isAllowedTool(name));

		if (allowedTools) {
			for (const toolName of this._toolRegistry.keys()) {
				// Naming or matching a tool activates it even when it is not active by default. MCP tools
				// kept registered without being named stay inactive.
				if (allowedTools(toolName) && this._isDeclarable(toolName)) {
					nextActiveToolNames.push(toolName);
				}
			}
		} else if (options?.includeAllExtensionTools) {
			// …扩展工具默认激活臂（java 无扩展工具面，略）…
		}
```

默认激活四件（`_buildRuntime`，`:3650-3653`）：

```ts
		const defaultActiveToolNames = this._baseToolsOverride
			? Object.keys(this._baseToolsOverride)
			: ["read", "bash", "edit", "write"];
		const baseActiveToolNames = options.activeToolNames ?? defaultActiveToolNames;
```

注册面过滤（`_refreshToolRegistry`，`:3511-3514,3550`）：`_baseToolDefinitions` 与扩展工具都经 `_isAllowedTool` 过滤后才进 `_toolDefinitions`/`_toolRegistry`——**allowlist 不匹配的内置工具连注册都不注册**；`_pendingToolNames`（`:444-449,1814`）兜住「点名了但尚未注册」（MCP 晚连接）的工具，注册后激活。

### 1.6 行为规格（`test/default-tools-setting.test.ts`，310 行）

关键断言（逐字摘）：

```ts
	it("uses the configured list as the initial built-in selection", async () => {
		const session = await createSession(["grep", "find"]);
		expect(session.getAllTools().map((tool) => tool.name).sort())
			.toEqual(["bash", "edit", "find", "grep", "ls", "powershell", "read", "write"]);
		expect(session.getActiveToolNames()).toEqual(["grep", "find"]);
		expect(session.systemPrompt).toContain("- grep:");
		expect(session.systemPrompt).not.toContain("- read:");
	});

	it("can select powershell instead of bash", async () => {
		const session = await createSession(["read", "powershell", "edit", "write"]);
		expect(session.getActiveToolNames()).toEqual(["read", "powershell", "edit", "write"]);
		expect(session.systemPrompt).not.toContain("- bash:");
	});

	it("preserves explicit tool option precedence", async () => {
		const allowlistedSession = await createSession(["grep"], { tools: ["read"] });
		expect(allowlistedSession.getActiveToolNames()).toEqual(["read"]);

		const excludedSession = await createSession(["read", "grep"], { excludeTools: ["read"] });
		expect(excludedSession.getActiveToolNames()).toEqual(["grep"]);

		const toolLessSession = await createSession(["read"], { noTools: "all" });
		expect(toolLessSession.getAllTools()).toEqual([]);
		expect(toolLessSession.getActiveToolNames()).toEqual([]);
	});

	it("applies +name and -name tool options to the default selection", async () => {
		const session = await createSession(["+grep"], { tools: ["+inactive_tool", "-write"] }, [inactiveTool]);
		expect(session.getActiveToolNames().sort()).toEqual([
			"active_tool", "bash", "edit", "grep", "inactive_tool", "read",
		]);

		const toolLess = await createSession(["read"], { noTools: "all", tools: ["+inactive_tool"] }, [inactiveTool]);
		expect(toolLess.getActiveToolNames()).toEqual(["inactive_tool"]);
	});

	it("rejects invalid tool modifier options", async () => {
		await expect(createSession([], { tools: ["read", "+grep"] })).rejects.toThrow(
			"Invalid tools option: tool names cannot be mixed with +name or -name entries",
		);
		await expect(createSession([], { tools: ["-gr*"] })).rejects.toThrow(
			"Invalid tools option: +name and -name entries take exact tool names, not patterns: -gr*",
		);
	});
```

默认集实证（reload 测试首断言，`:241`）：`expect(session.getActiveToolNames()).toEqual(["read", "bash", "edit", "write"]);`——**pi 裸跑只激活四件**，grep/find/ls/powershell 注册但不声明给模型。

---

## 2. 差距清单（java 现状 → pi）

| # | 面 | java 现状 | pi | 出处 |
|---|---|---|---|---|
| G1 | `--tools` 条目语义 | 逗号切分裸名，精确集合过滤（`SessionSetup.java:33-38`） | 裸名∨`*` 模式∨`+/-name` 修饰符∨`mcp__` 前缀 | §1.1/§1.3 |
| G2 | `--tools` 校验 | 无（任何值照收） | 解析期 `getToolListError`，失败 error 诊断且不写 tools | §1.1 |
| G3 | `--exclude-tools` | 逗号切分裸名（`SessionSetup.java:39-43`） | `*` 模式 denylist，注册面剔除 | §1.3/§1.5 |
| G4 | 修饰符基座 | 无修饰符概念 | `defaultToolNames = noTools ? [] : (settings.defaultTools ?? DEFAULT_TOOL_NAMES)` | §1.4 |
| G5 | 默认激活集 | `createCodingTools` **七件全激活**（`AgentSession.java:292`＋`SessionSetup.java:45`） | **四件**（read/bash/edit/write），grep/find/ls/powershell 注册不激活 | §1.5/§1.6 |
| G6 | powershell 注册 | **不注册**（assemble 走 `createCodingTools` 七件） | `createAllTools` 八件全注册 | §1.6（`getAllTools` 八件） |
| G7 | 注册面过滤 | 不过滤（注册七件，过滤只发生在激活面） | `_isAllowedTool` 门：allowlist 不匹配/excluded 的内置工具**不注册**；空名单连 MCP 保留都没有 | §1.5 |
| G8 | `mcp__` 保留 | 无概念 | 非空 allowlist 无 `mcp__` 条目时 MCP 工具保留注册（给 codemode/tool_search） | §1.3/§1.5 |
| G9 | `defaultTools` 设置 | 无字段（map/04 E2-35 缺失） | 键＋`mergeDefaultTools` 分层追加＋`resolveDefaultTools` | §1.2 |
| G10 | `--no-tools` | active 空，**注册面保留七件** | `noTools:"all"` ⇒ allowlist `[]` ⇒ **注册面空**（`getAllTools()==[]`） | §1.4/§1.6 |
| G11 | `--no-builtin-tools` | active 空，注册面保留 | `noTools:"builtin"` ⇒ 默认选择空、注册面保留（extension/custom 激活——java 无此面） | §1.4 |
| G12 | `--tools ""` | 与缺席无别（`split()` 空串→`List.of()`） | 空 allowlist ⇒ 零工具（`tools=[]` 非 undefined） | §1.1/§1.4 |
| G13 | help 文本 | 单行裸名措辞（`HelpText.java:45-48`） | 三行：pattern、`mcp__`、修饰符 | §1.1 |
| G14 | `ToolSetFactory.createCodingTools` | 七件（read/bash/edit/write/grep/find/ls），注释称对位 pi `createAllTools` | pi 现锚点 `createCodingTools` = **四件**（`tools/index.ts:193-200`）；`createAllTools` = **八件**含 powershell（`:213-224`）——java 七件形**无 pi 对位** | 裁决② |

---

## 3. java 待写代码

落点全在 **pi-java-coding-agent**（`com.pijava.coding.agent.core` 两个新文件＋四个既有文件），agent-core 只动 `ToolSetFactory` 清理（裁决②）。

### 3.1 新文件 `core/ToolNameMatcher.java`（§1.3 对位）

```java
package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * pi {@code toolPatternRegExp}/{@code createToolNameMatcher}/{@code isMcpToolName}
 * （core/mcp-servers.ts:11-42，B236/docs/11 §1.3）。
 *
 * <p>模式规则只有一条：{@code *} → 任意字符；其余字符全部字面化，全串锚定
 * （<b>不是</b> minimatch——ai 层 ModelGlob 那套不适用于工具名）。</p>
 */
public final class ToolNameMatcher {

    /** pi {@code MCP_RESOURCE_TOOLS}（mcp-servers.ts:33-37）——资源三件套也算 MCP 工具名。 */
    private static final Set<String> MCP_RESOURCE_TOOLS = Set.of(
        "list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource");

    private ToolNameMatcher() {}

    /** pi {@code toolPatternRegExp}（:11-18）。{@code Pattern.quote} 全字面化 ≙ pi 的正则元字符转义。 */
    static Pattern toolPattern(String pattern) {
        var parts = pattern.split("\\*", -1);
        var source = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                source.append(".*");
            }
            if (!parts[i].isEmpty()) {
                source.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(source.append("$").toString());
    }

    /** pi {@code createToolNameMatcher}（:21-26）：精确名集合 + 模式列表，任一命中即真。 */
    public static Predicate<String> create(List<String> entries) {
        Set<String> names = new HashSet<>();
        List<Pattern> patterns = new ArrayList<>();
        for (var entry : entries) {
            if (entry.contains("*")) {
                patterns.add(toolPattern(entry));
            } else {
                names.add(entry);
            }
        }
        return name -> names.contains(name)
            || patterns.stream().anyMatch(p -> p.matcher(name).matches());
    }

    /** pi {@code isMcpToolName}（:40-42）。 */
    public static boolean isMcpToolName(String name) {
        return name.startsWith("mcp__") || MCP_RESOURCE_TOOLS.contains(name);
    }
}
```

### 3.2 新文件 `core/ToolSelection.java`（§1.2/§1.4 对位）

```java
package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * pi 的工具选择语义（B236，docs/11）：
 * {@code DEFAULT_TOOL_NAMES}/{@code isToolModifier}/{@code getToolListError}/
 * {@code applyToolModifiers}/{@code resolveDefaultTools}/{@code mergeDefaultTools}
 * （core/settings-manager.ts:83-137）＋ {@code sdk.ts:280-294} 的装配解析 ＋
 * {@code agent-session.ts:1534-1543} 的注册面门。{@code null ≙ pi undefined}。
 */
public final class ToolSelection {

    /** pi {@code DEFAULT_TOOL_NAMES}（settings-manager.ts:83，逐字顺序）。 */
    public static final List<String> DEFAULT_TOOL_NAMES = List.of("read", "bash", "edit", "write");

    private ToolSelection() {}

    /** pi {@code isToolModifier}（:86-89）。 */
    public static boolean isToolModifier(String entry) {
        return entry.startsWith("+") || entry.startsWith("-");
    }

    /**
     * pi {@code getToolListError}（:95-105）——<b>逐字错误文案</b>。
     * @return 问题描述；合法时 {@code null}
     */
    public static String getToolListError(List<String> entries) {
        var modifiers = entries.stream().filter(ToolSelection::isToolModifier).toList();
        if (modifiers.isEmpty()) {
            return null;
        }
        if (modifiers.size() < entries.size()) {
            return "tool names cannot be mixed with +name or -name entries";
        }
        var pattern = modifiers.stream().filter(e -> e.contains("*")).findFirst().orElse(null);
        if (pattern != null) {
            return "+name and -name entries take exact tool names, not patterns: " + pattern;
        }
        return null;
    }

    /** pi {@code applyToolModifiers}（:108-118）：按序应用 {@code +name} 增 / {@code -name} 删。 */
    public static List<String> applyToolModifiers(List<String> base, List<String> entries) {
        var tools = new ArrayList<>(base);
        for (var entry : entries) {
            if (!isToolModifier(entry)) {
                continue;
            }
            var name = entry.substring(1);
            var index = tools.indexOf(name);
            if (entry.startsWith("+") && index == -1 && !name.isEmpty()) {
                tools.add(name);
            } else if (entry.startsWith("-") && index != -1) {
                tools.remove(index);
            }
        }
        return tools;
    }

    /** pi {@code resolveDefaultTools}（:135-138）：裸名替换默认集，修饰符再按序叠。 */
    public static List<String> resolveDefaultTools(List<String> entries) {
        var plain = entries.stream().filter(e -> !isToolModifier(e)).toList();
        return applyToolModifiers(!plain.isEmpty() || entries.isEmpty() ? plain : DEFAULT_TOOL_NAMES, entries);
    }

    /** pi {@code mergeDefaultTools}（:124-129）：覆盖层全修饰符 ⇒ 追加到继承选择之后。 */
    public static List<String> mergeDefaultTools(List<String> base, List<String> overrides) {
        if (overrides == null) {
            return base;
        }
        if (base == null || !overrides.stream().allMatch(ToolSelection::isToolModifier)) {
            return overrides;
        }
        var merged = new ArrayList<>(base);
        merged.addAll(overrides);
        return merged;
    }

    /**
     * pi {@code sdk.ts:280-294} 的解析结果（{@code Resolved} 对象即「选择策略」）。
     * 匹配器在构造时固化（pi 是同形闭包）。
     */
    public static final class Resolved {
        private final List<String> initialActiveToolNames;
        /** pi {@code _allowedTools}；{@code null ≙ undefined}（无 allowlist）。 */
        private final Predicate<String> allowed;
        /** pi {@code _excludedTools}；{@code null ≙ undefined}。 */
        private final Predicate<String> excluded;
        /** pi {@code _allowlistFiltersMcp}（agent-session.ts:504-506）。 */
        private final boolean allowlistFiltersMcp;

        private Resolved(List<String> initialActiveToolNames, Predicate<String> allowed,
                         Predicate<String> excluded, boolean allowlistFiltersMcp) {
            this.initialActiveToolNames = initialActiveToolNames;
            this.allowed = allowed;
            this.excluded = excluded;
            this.allowlistFiltersMcp = allowlistFiltersMcp;
        }

        /** pi {@code _isAllowedTool}（agent-session.ts:1537-1541）——<b>注册面</b>门。 */
        public boolean isAllowed(String name) {
            if (excluded != null && excluded.test(name)) {
                return false;
            }
            if (allowed == null || allowed.test(name)) {
                return true;
            }
            return !allowlistFiltersMcp && ToolNameMatcher.isMcpToolName(name);
        }

        /**
         * pi 的<b>激活面</b>：allowlist 在册时「点名/匹配即激活」
         * （agent-session.ts:3571-3577，含默认不激活的 powershell）；否则看初始激活名表
         * （sdk.ts:294 → _buildRuntime activeToolNames）。
         */
        public boolean isActiveOnLoad(String name) {
            return allowed != null ? allowed.test(name) : initialActiveToolNames.contains(name);
        }
    }

    /**
     * pi {@code sdk.ts:280-294}（B236 核心解析）。前置：{@code tools} 已经
     * {@link #getToolListError} 校验（CLI 在解析期验，pi args.ts:155-159 同位）。
     *
     * @param tools                {@code --tools} 条目；null ≙ 未给，空表 ≙ 给了但全空（pi 的 []）
     * @param excludeTools         {@code --exclude-tools} 条目；null ≙ 未给
     * @param noToolsAll           {@code --no-tools}（pi {@code noTools === "all"}）
     * @param noBuiltinTools       {@code --no-builtin-tools}（pi {@code noTools === "builtin"}）
     * @param settingsDefaultTools {@code SettingsAccessors.getDefaultTools()}；null ≙ undefined
     */
    public static Resolved resolve(List<String> tools, List<String> excludeTools,
                                   boolean noToolsAll, boolean noBuiltinTools,
                                   List<String> settingsDefaultTools) {
        // pi :282 —— "all" 与 "builtin" 都把默认选择清空；差别只在 allowlist（下）。
        var defaultToolNames = (noToolsAll || noBuiltinTools)
            ? List.<String>of()
            : (settingsDefaultTools != null ? settingsDefaultTools : DEFAULT_TOOL_NAMES);
        // pi :285-286 —— 全修饰符名单不是 allowlist，是改默认选择。
        var toolModifiers = tools != null && tools.stream().anyMatch(ToolSelection::isToolModifier)
            ? tools : null;
        var selectedToolNames = toolModifiers != null
            ? applyToolModifiers(defaultToolNames, toolModifiers) : tools;
        // pi :287-291 —— 修饰符名单一般不成 allowlist；只有叠加在 noTools:"all" 上时
        // 例外（pi 注释：让 +name 在零基础上点名激活）。
        List<String> allowedToolNames = toolModifiers != null
            ? (noToolsAll ? selectedToolNames : null)
            : (tools != null ? tools : (noToolsAll ? List.of() : null));
        // pi :292-294
        Predicate<String> excluded =
            excludeTools != null ? ToolNameMatcher.create(excludeTools) : null;
        var initialActiveToolNames = (selectedToolNames != null ? selectedToolNames : defaultToolNames)
            .stream().filter(name -> excluded == null || !excluded.test(name)).toList();
        // pi agent-session.ts:504-506
        var allowlistFiltersMcp = allowedToolNames != null
            && (allowedToolNames.isEmpty()
                || allowedToolNames.stream().anyMatch(e -> e.startsWith("mcp__")));
        return new Resolved(initialActiveToolNames,
            allowedToolNames != null ? ToolNameMatcher.create(allowedToolNames) : null,
            excluded, allowlistFiltersMcp);
    }
}
```

### 3.3 `Settings.java` ＋ `SettingsManager` ＋ `SettingsAccessors`（E2-35，§1.2 对位）

`Settings.java` 加字段（位置紧随 `enabledModels`，与 pi schema 顺序同位）：

```java
    /**
     * pi {@code defaultTools}（settings-schema.ts:326-331）：初始工具选择——裸名替换
     * 继承选择，{@code +name}/{@code -name} 增删。分层 merge 特判与解析见
     * {@link ToolSelection#mergeDefaultTools}/{@link ToolSelection#resolveDefaultTools}。
     */
    public List<String> defaultTools;
```

`SettingsManager.merge` 加 pi `deepMergeSettings` 的特判（settings-manager.ts:139-145）：

```java
    private Settings merge(Settings base, Settings override) {
        var baseNode = (ObjectNode) Json.mapper().valueToTree(base);
        var overrideNode = (ObjectNode) Json.mapper().valueToTree(override);
        deepMerge(baseNode, overrideNode);
        var merged = Json.mapper().convertValue(baseNode, Settings.class);
        // pi mergeDefaultTools（settings-manager.ts:124-129）经 deepMergeSettings 特判（:139-145）：
        // JSON 深合并里数组整体替换；pi 对 defaultTools 另立语义——覆盖层全是 +name/-name
        // 修饰符时追加到继承选择之后（逐层修饰），否则整体替换。
        merged.defaultTools = ToolSelection.mergeDefaultTools(base.defaultTools, override.defaultTools);
        return merged;
    }
```

`SettingsAccessors` 加（pi `getDefaultTools`，settings-manager.ts:1328-1333）：

```java
    /**
     * pi {@code getDefaultTools}（settings-manager.ts:1328-1333）：解析后的
     * {@code defaultTools} 选择；无任何层设置 ⇒ {@code null}（≙ pi undefined ⇒ 消费点
     * 回落 {@link ToolSelection#DEFAULT_TOOL_NAMES}）。
     */
    public List<String> getDefaultTools() {
        var tools = manager.effective().defaultTools;
        if (tools == null) {
            return null;
        }
        return ToolSelection.resolveDefaultTools(tools);
    }
```

窄差 D3：pi 容忍 `defaultTools` 非数组/含非字符串元素（:1332 的 `filter`）；java `List<String>` 类型化字段在读入期即抛（与既有类型化键行为一致，登记不另做）。

### 3.4 `ArgsParser` ＋ `Args`（G2/G12，§1.1 对位）

`ArgsParser` 加出席旗标字段与校验：

```java
    // 字段区
    /** --tools/-t 是否实给（pi result.tools 的 undefined vs [] 之分，args.ts:151-161）。 */
    boolean toolsFlagPresent;
    boolean excludeToolsFlagPresent;

    // parse() 内，parseArgs 成功后、toArgs 前：
        var parser = (ArgsParser) result.commandSpec().userObject();
        parser.toolsFlagPresent = result.hasMatchedOption("--tools");
        parser.excludeToolsFlagPresent = result.hasMatchedOption("--exclude-tools");
        return parser.toArgs(diagnostics);
```

`toArgs` 内（替换现在 `split(tools), split(excludeTools)` 两个实参的求值）：

```java
        // pi args.ts:151-161：--tools 解析期校验；失败 error 诊断且不写 tools（≙ null）。
        var toolEntries = splitOrNull(tools, toolsFlagPresent);
        if (toolEntries != null) {
            var error = ToolSelection.getToolListError(toolEntries);
            if (error != null) {
                // pi `${arg}: ${error}`；窄差 D1：picocli 不暴露实敲别名，前缀恒 --tools。
                diagnostics.add(new Args.ArgDiagnostic("error", "--tools: " + error));
                toolEntries = null;
            }
        }
```

```java
    /**
     * tools/excludeTools 专用 split：旗标缺席 ⇒ {@code null}（≙ pi undefined）；
     * 给了但全空白 ⇒ 空表（pi 的 [] —— `--tools ""` 是空 allowlist，零工具）。
     */
    private static List<String> splitOrNull(String commaSeparated, boolean present) {
        return present ? split(commaSeparated) : null;
    }
```

`Args` 紧凑构造器把 `tools`/`excludeTools` 移出 `copy(...)` 归一化（null 直传 ≙ pi undefined），record 组件 javadoc 改：

```java
     * @param tools                 tool allowlist 条目（裸名/`*` 模式/`+name`/`-name`）；
     *                              null = 旗标缺席（≙ pi undefined），空表 = 给了但全空
     * @param excludeTools          tool denylist 条目（裸名/`*` 模式）；null = 旗标缺席
```

紧凑构造器改动：

```java
        models = copy(models);
        // tools/excludeTools 不归一化：null ≙ pi 的 undefined（旗标缺席），与空表语义不同
        //（docs/11 §3.4）。
        extensions = copy(extensions);
```

### 3.5 `SessionSetup.activeTools` 重写（G4/G5/G10/G11，§1.4/§1.5 对位）

```java
    /** pi {@code sdk.ts:280-294} 的装配解析（B236/docs/11 §3.2）。 */
    static ToolSelection.Resolved resolveToolSelection(Args args, SettingsManager settings) {
        return ToolSelection.resolve(
            args.tools(), args.excludeTools(),
            args.noTools(), args.noBuiltinTools(),
            settings.accessors().getDefaultTools());
    }

    /**
     * pi 的激活面（agent-session.ts:3566-3585）：allowlist 在册 ⇒ 注册工具中匹配者
     * 全激活（「点名/匹配即激活」，含默认不激活的 powershell）；否则 ⇒ 初始激活名表
     * ∩ 注册面。{@code _pendingToolNames} 的晚注册臂（MCP）随 B221 ⑫b（窄差 D4）。
     */
    static Set<AgentTool<?, ?>> activeTools(ToolSelection.Resolved selection,
                                            List<AgentTool<?, ?>> registered) {
        return registered.stream()
            .filter(t -> selection.isActiveOnLoad(t.name()))
            .collect(Collectors.toSet());
    }
```

旧 `activeTools(Args, List)` 方法删除（唯一调用点 `AgentSession.java:487` 改道）。

### 3.6 `AgentSession.assemble` 注册面（G6/G7/G10，§1.5 对位）

`AgentSession.java:289-293` 段改写：

```java
        var tools = new ToolRegistry(null);
        var commandPrefix = effective.shellCommandPrefix == null
            ? "" : effective.shellCommandPrefix;
        // B236（docs/11 §3.6）：注册面 = pi createAllTools 八件（tools/index.ts:213-224，
        // 含 powershell——批 4 只落了本体，注册面在本批）经 pi _isAllowedTool 门过滤
        //（agent-session.ts:1537-1541 → _refreshToolRegistry :3514）：allowlist 不匹配或
        // excluded 的内置工具**不注册**；空名单（--no-tools / --tools ""）注册面空
        //（pi getAllTools()==[]，default-tools-setting.test.ts:155）。
        var selection = SessionSetup.resolveToolSelection(args, settings);
        var toolList = ToolSetFactory.createAllTools(commandPrefix).stream()
            .filter(t -> selection.isAllowed(t.name()))
            .toList();
        tools.registerAll(toolList);
```

`AgentSession.java:487` 改：

```java
            .activeTools(SessionSetup.activeTools(selection, toolList))
```

行为对账（全部有 pi 测试背书，§1.6）：

| 输入 | 注册面 | 激活面 |
|---|---|---|
| 裸跑（无旗标无设置） | 8 件 | **read, bash, edit, write**（裁决①） |
| `defaultTools: ["grep","find"]` | 8 件 | grep, find |
| `defaultTools: ["+powershell"]` | 8 件 | 5 件（4+powershell） |
| `--tools +powershell` | 8 件 | 5 件 |
| `--tools read,grep` | read, grep 两件 | read, grep |
| `--tools 'gr*'` | grep | grep（模式点名激活） |
| `--tools read --exclude-tools read` | 无 | 无 |
| `--exclude-tools bash` | 7 件（无 bash） | read, edit, write |
| `--no-tools` | **空** | 空 |
| `--no-builtin-tools` | 8 件 | 空 |
| `--tools ""` | **空** | 空 |
| `--no-tools --tools +grep` | grep | grep（pi sdk.ts:287-290 例外臂） |

### 3.7 `HelpText`（G13，§1.1 对位）

```
              --no-tools, -nt                Disable all tools by default (built-in and extension)
              --no-builtin-tools, -nbt       Disable built-in tools by default but keep extension/custom tools enabled
              --tools, -t <tools>            Comma-separated allowlist of tool names or patterns (*) to enable
                                             Keeps MCP tools unless an entry starts with mcp__
                                             Only +name/-name entries add to or remove from the defaults
              --exclude-tools, -xt <tools>   Comma-separated denylist of tool names or patterns (*) to disable
                                             Applies to all tools, MCP tools included
```

（pi `args.ts:318-324` 逐字；java 原有行序不动，仅替换这四行的措辞。）

### 3.8 `ToolSetFactory` 清理（G14，裁决②）

- `createCodingTools(String)` **删除**：pi 现锚点 `createCodingTools` = 四件（`tools/index.ts:193-200`），java 七件形无对位（批 3/4 注释引的「createAllTools 七件序」在现锚点是八件含 powershell）。四件默认集的语义已由 `ToolSelection.DEFAULT_TOOL_NAMES` 表达。
- `createAllTools` javadoc 修正：对位 pi `createAllTools`（`tools/index.ts:213-224`）**八件**注册面；「注册≠激活」段保留，激活通道的「随 B236」改「docs/11 已落」。
- `createReadOnlyTools` 不动（对位 pi `createReadOnlyTools`，`tools/index.ts:205-211`）。
- 测试连带：`ToolSetFactoryTest.createCodingToolsFollowsPiCreateAllToolsOrder` 删除（`PowerShellToolTest:79` 的八件序断言已覆盖 `createAllTools`）；`PowerShellToolTest:88-90` 引用 `createCodingTools` 的断言改断 `ToolSelection.DEFAULT_TOOL_NAMES` 四件。

---

## 4. 测试计划

### 4.1 新夹具

| 夹具 | 内容 |
|---|---|
| `ToolNameMatcherTest` | 精确名／`*` 模式（`gr*`、`*sh`、`mcp__radius__*`、`**`、孤立 `*`）／全串锚定（`gre` 不中 `grep`）／正则元字符字面化（`a.c` 只中字面 `a.c`）／`isMcpToolName`（`mcp__` 前缀＋资源三件套＋反例） |
| `ToolSelectionTest` | `getToolListError` 两条逐字文案＋合法三支（全裸名/全修饰符/空表）；`applyToolModifiers`（按序、加缺删有、空名 `+` 不加、重复 `+` 不双）；`resolveDefaultTools`（裸名替换/纯修饰符叠默认/空表⇒默认）；`mergeDefaultTools`（override null/全修饰符追加/含裸名替换）；`resolve` 全分支矩阵（§3.6 对账表的每一行）＋`isAllowed`/`isActiveOnLoad` 语义（含 `mcp__` 保留正反对：allowlist 无 `mcp__` 条目时 `mcp__x__y` 保留注册但不激活；有 `mcp__` 条目时按匹配） |
| `DefaultToolsSelectionTest`（session 级，移植 pi `default-tools-setting.test.ts` 可达子集） | §3.6 对账表全 12 行走真装配（`AgentSession.create`＋内存 SettingsManager）；系统提示侧只断「激活工具进 prompt、未激活不进」（java prompt 段走 ContextAssembler，不断 pi 的 `- grep:` 字面） |

不可移植（登记，不进夹具）：reload 两臂（裁决④/B246）；扩展工具/SDK customTools 行（无扩展面）；`_isActivatable` 的 tool_search 臂（R5）。

### 4.2 既有测试连锁（实现期逐个点名，本稿先挂账）

- `ArgsParserTest`：`tools()`/`excludeTools()` 缺席断言从 `isEmpty()` 改 `isNull()`（§3.4 语义改）；`:45-46/:129` 的值断言不变。
- **默认激活 7→4**（裁决①）波及：凡经 `AgentSession.create` 默认装配又断言工具面/系统提示的测试（`AgentSessionTest`、`AgentSessionToolIntegrationTest`、`SessionFailurePathTest`、RPC/web 两侧的工具清单断言等）——实现期跑 `mvn test -pl pi-java-coding-agent,pi-java-tui,pi-java-server,pi-java-client -am` 全盘点，预期改断言或显式 `--tools +grep,+find,+ls` 保旧面。
- `PowerShellToolTest`/`ToolSetFactoryTest`：§3.8 清理连带。

### 4.3 门禁

每 commit `mvn test -pl pi-java-coding-agent -am`（底层改动时）；**收口前全量 `mvn clean verify`**——spotbugs 绑在 verify 阶段，批 4 的 NP_BOOLEAN_RETURN_NULL 就是每-commit 门禁漏网、全量才逮到（教训见 memory `agent-core-batch4-closed`）。

---

## 5. 裁决与登记

### 裁决①：默认激活集 7→4（**本批最大用户可见变化**）

pi 锚点 `DEFAULT_TOOL_NAMES` 恒四件（`settings-manager.ts:83`，无平台分支），其自有测试实证裸跑激活面 `["read","bash","edit","write"]`（`default-tools-setting.test.ts:241`）；grep/find/ls/powershell 注册但不声明，激活通道 = `--tools`/`defaultTools`/`setActiveTools`（pi 的帮助示例 `--tools read,grep,find,ls` 正是这个默认集的旁证）。java 现状七件全激活是 Phase 2b 时代的近似，本批随 B236 一起对齐（修饰符语义的正确性依赖基座正确）。

**后果**：裸跑的 pi-java 不再向模型声明 grep/find/ls；恢复途径 = `--tools +grep,+find,+ls` 或 settings `defaultTools: ["+grep","+find","+ls"]`。模型可见面收缩是本批的**意图**，不是回归。

### 裁决②：`createCodingTools`（七件形）删除

pi 现锚点 `createCodingTools` = 四件（`tools/index.ts:193-200`）；java 的七件形没有任何 pi 函数对位（批 3 注释引的「createAllTools 七件」在现锚点是八件含 powershell）。唯一主码消费点（`AgentSession.assemble`）本批改走 `createAllTools`＋选择策略 ⇒ 删除方法，防后续误用。

### 裁决③：`mcp__` 保留语义策略先行

`isMcpToolName`/`allowlistFiltersMcp`/`_isAllowedTool` 的 MCP 保留臂**全量落地**（策略层纯函数，测试驱动）；但 java 的 MCP 工具尚未进会话注册面（`McpToolDefinition` 只在 mcp 包内流转，激活门属 B221 ⑫b）⇒ 本批无可观察的 MCP 行为变化，策略先就位，MCP 工具进注册面时自动获得 pi 语义。map/07 的 `mcp__` 细则行不动。

### 裁决④：reload 臂登记 **B246** 不做

pi 的 `usesDefaultTools`/`defaultToolModifiers`（`sdk.ts:472-473`）只在 `reload()` 里有语义（`agent-session.ts:3660-3685`：设置重载后激活**新增**的默认工具、移除的保持激活、`-name` 移除的保持移除）——依附 pi 的扩展运行时重载面（`session.reload()` → 资源/扩展全量重载）。java 无此面（设置改动走 accessors 即效，无 reload 入口），连同 SDK `tools` 选项的抛错面（`sdk.ts:280-281` `Invalid tools option: ...`，java 无 SDK）一并登记 **B246**，触发条件 = java 出现 session reload/扩展运行时或 SDK 面。

### 窄差登记

- **D1**：校验诊断前缀恒 `--tools`（pi 按实敲别名 `${arg}`；picocli 不暴露命中的别名）。
- **D2**：pi SDK 面的 `Invalid tools option` 抛错（`sdk.ts:280-281`）无对位；CLI 面等价物 = 解析期 error 诊断（`Main` 打印 `error: ...` 并退出）。
- **D3**：settings `defaultTools` 形状异常（非数组/非字符串元素）：pi 容忍（`:1332` filter），java 类型化字段读入即抛（与既有类型化键一致）。
- **D4**：`_pendingToolNames` 晚注册激活臂（`agent-session.ts:444-449,1814`）随 MCP 批（B221 ⑫b）；本批点名的未注册名静默丢弃。

---

## 6. 闭环回填清单（commit 6）

| 文档 | 动作 |
|---|---|
| `docs/map/04-coding-agent.md` | A-20 翻对齐（w2，+1.0）、A-21 翻对齐（w1，+0.5）、E2-35 翻对齐（w2，+2.0）；A-18/A-19 判词补注册面证据（无账面变动）；A 域汇总 48.5→**50.0/62 = 80.6%**（对齐 30/缺失 4/存疑 9）；E2 汇总 49.0→**51.0/86 = 59.3%**（对齐 21/缺失 22/存疑 12）；未加权表（对齐 92→95）与模块合计 217.5→**221.0/496 = 44.6%**；文末汇总行同步 |
| `docs/04-open-items-register.md` | §3.3 **B236 销号**；新增 **B246**（reload 臂＋SDK tools 抛错面，裁决④）；§4 收录决①②③④ |
| `docs/05-module-alignment-map.md` | coding-agent 行 217.5/496/43.9% → **221.0/496/44.6%**（未加权 92/290→95/290 = 32.8%）；合计 869.0/1383 → **872.5/1383 ≈ 63.1%**；批 1 bullet |
| 本稿 | 头部翻「✅ 已闭环」横幅＋落地 commit 清单 |

## 7. commit 划分（6 个）

| # | commit | 内容 |
|---|---|---|
| 1 | `feat(coding-agent): ToolNameMatcher + ToolSelection — pi tool name patterns and modifiers (B236)` | §3.1/§3.2 纯函数层＋`ToolNameMatcherTest`/`ToolSelectionTest` |
| 2 | `feat(coding-agent): settings defaultTools key with merge/resolve semantics (map E2-35)` | §3.3＋merge 特判夹具 |
| 3 | `feat(coding-agent): --tools parse-time validation + null-means-absent + help text (map A-20)` | §3.4/§3.7＋ArgsParserTest 增补/修正 |
| 4 | `feat(coding-agent): session assembly wires tool selection — registry gate, default four, createAllTools (B236)` | §3.5/§3.6/§3.8＋连锁测试修正（4.2） |
| 5 | `test(coding-agent): DefaultToolsSelectionTest — port pi default-tools-setting reachable subset` | §4.1 session 级夹具 |
| 6 | `docs(coding-agent): batch-1 closure — weighted 43.9->44.6%` | §6 回填 |

依赖序：1→2→3→4→5→6（4 依赖 1/2/3；5 依赖 4）。
