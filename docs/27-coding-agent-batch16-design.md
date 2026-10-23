# coding-agent 批 16 设计稿：E5 扩展 API 面第一批——激活门＋提示分节＋registerToolRenderer＋扩展 CLI flag＋/reload

【背景摘要】本批是 coding-agent 对齐批次总账（docs/20）§3 序 15，对应模块对齐地图
（docs/map/04）的 E5 域（扩展 API 面）第一批。范围＝序 15 原定三项（activation 门暴露、
prompt 分节接线、registerToolRenderer/B204）＋批 14 重排并入的 A-43（扩展注册 CLI flag）
＋批 15 重排并入的 C-23 `/reload`＋B246（reload 的 defaultTools diff 臂），D 域 #36
`model_select` 随收、#11 `mcp_servers_change` 经裁决⑤随 E5-23 后续批。
工单挂靠：B221（收编 ②④⑤，余 ①③⑥）、B204、B246。

术语（首现释义）：

- **激活门（activation gate）**：决定哪些注册工具会被声明给模型的过滤层。pi 的激活态
  唯一落点是 `agent.state.tools`；java 的落点是 `lane.activeTools`。
- **提示分节（prompt sections）**：pi 系统提示词按命名段（preamble/tools/rules/…）构建，
  每轮与转录重放值 diff、只把变化段作为补丁消息插入。扩展经 `before_agent_start` 事件的
  可变 `systemPromptOptions.sections` 写入自定义段。
- **flag 面**：扩展用 `registerFlag` 声明 CLI flag、用户命令行传值、扩展用 `getFlag` 读回
  的机制。pi 解析期不识别扩展 flag，未知 `--flag` 全部收集，扩展加载后再绑定。
- **责任链 resolver**：`registerToolRenderer` 注册的 `(toolName, next) -> renderers?`
  函数；返回 null/undefined 表示不管，调 `next()` 表示委托后续 resolver。
- **B221②④⑤**：台账 docs/04 工单 B221 的缺口清单条目——② 激活门未暴露给扩展、
  ④ registerToolRenderer（＝B204）、⑤ 提示分节接线。
- **defaultTools diff**：pi `reload()` 的工具激活语义——新加入 `defaultTools` 设置的工具
  被激活；从中移除的保持激活；会话中经 `-name` 修饰符移除的保持移除（B246）。

---

## §0 基线与预期翻动

基线（批 15 闭环实算，2026-10-23）：模块 341.0/485 ≈ 70.3%；E5 域 16.0/58 ≈ 27.6%；
C 域 34.0/38 ≈ 89.5%；A 域 60.5/62 ≈ 97.6%；D 域 75.0/83 ≈ 90.4%；
全仓 1,020.5/1379 ≈ 74.0%。

| 行 | 前态 | 后态 | Δ（加权） | 手段 |
|---|---|---|---:|---|
| map/04 **E5-7** `registerFlag`/`getFlag`（w1） | 缺失 | 对齐 | +1.0 | T1 |
| **E5-30** 扩展注册 CLI flag（w1） | 缺失 | 对齐 | +1.0 | T1 |
| **A-43** 扩展注册的 CLI flag（w1） | 缺失 | 对齐 | +1.0 | T1 |
| **E5-18** `getActiveTools`/`getAllTools`/`setActiveTools`（w2） | 缺失 | 对齐 | +2.0 | T2 |
| **E8-7** 提示分节构建与差分（w1，**不计入分母**） | 缺失 | 对齐 | 注记 | T3 |
| **E5-11** `registerToolRenderer`（w1） | 缺失 | 对齐 | +1.0 | T4 |
| **E5-29** 热重载（w2） | 存疑 | 对齐 | +1.0 | T5（裁决③⑫，窄差 D90/D93） |
| **C-23** `/reload`（w1） | 存疑 | 对齐 | +0.5 | T5 |
| **D#36** `model_select`（w2） | 缺失 | 对齐 | +2.0 | T6 |
| **D#11** `mcp_servers_change`（w2） | 缺失 | **不动** | 0 | 裁决⑤（随 E5-23 批） |

**预期账面**：模块 341.0→**350.5/485 ≈ 72.3%**（+9.5）；E5 域 16.0→**22.0/58 ≈ 37.9%**；
D 域 75.0→**77.0/83 ≈ 92.8%**；C 域 34.0→**34.5/38 ≈ 90.7%**；A 域 60.5→**61.5/62 ≈ 99.2%**；
全仓 1,020.5→**1,030.0/1379 ≈ 74.7%**。

**工单**：**B204 销号**（registerToolRenderer＋MCP 渲染器）、**B246 销号**（reload 臂达成）；
B221 收编 ②④⑤（余 ① MCP 六事件／③ `ctx.ui.custom`→批 17／⑥ RPC `get_commands` 扩展臂）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

> 文件路径均相对 `packages/coding-agent/src/`。

### 1.1 扩展 CLI flag 面（A-43/E5-7/E5-30）

**解析期不识别扩展 flag**——`cli/args.ts:247-271` 主循环兜底分支：所有未匹配的 `--xxx`
进 `unknownFlags: Map<string, boolean | string>`（`:56-58` 声明）。三态逐字：

```ts
} else if (arg.startsWith("--")) {
    const eqIndex = arg.indexOf("=");
    if (eqIndex !== -1) {
        result.unknownFlags.set(arg.slice(2, eqIndex), arg.slice(eqIndex + 1));
    } else {
        const flagName = arg.slice(2);
        const next = args[i + 1];
        if (next !== undefined && !next.startsWith("-") && !next.startsWith("@")) {
            result.unknownFlags.set(flagName, next);
            i++;
        } else {
            result.unknownFlags.set(flagName, true);
        }
    }
} else if (arg.startsWith("-") && !arg.startsWith("--")) {
    result.diagnostics.push({ type: "error", message: `Unknown option: ${arg}` });
}
```

`--k=v` 取等号后字符串；`--k v`（下个 token 不以 `-`/`@` 起头）吞为值；裸 `--k` 记 `true`。
单横线未知选项是 error 诊断。

**绑定期（扩展加载后）**——`core/agent-session-services.ts:82-128`
`applyExtensionFlagValues`（main.ts:756 把 `parsed.unknownFlags` 作为
`extensionFlagValues` 传入服务创建；`:195` 处调用）逐字关键段：

```ts
for (const [name, value] of extensionFlagValues) {
    const flag = registeredFlags.get(name);
    if (!flag) { unknownFlags.push(name); continue; }
    if (flag.type === "boolean") {
        extensionsResult.runtime.flagValues.set(name, true);
        continue;
    }
    if (typeof value === "string") {
        extensionsResult.runtime.flagValues.set(name, value);
        continue;
    }
    diagnostics.push({ type: "error",
        message: `Extension flag "--${name}" requires a value` });
}
if (unknownFlags.length > 0) {
    diagnostics.push({ type: "error",
        message: `Unknown option${unknownFlags.length === 1 ? "" : "s"}: ${unknownFlags.map((name) => `--${name}`).join(", ")}` });
}
```

要点：boolean 型**恒写 true**（解析期吞下的字符串值被忽略）；string 型收到 `true`
（无值）报 `Extension flag "--<name>" requires a value`；未注册名汇总成一条
`Unknown options: --a, --b`（单数不加 s）。

**注册/读取**——`core/extensions/types.ts:1663-1680`（API 签名，判别联合）：

```ts
/** Register a CLI flag. */
registerFlag(
    name: string,
    options:
        | { description?: string; type: "boolean"; default?: boolean }
        | { description?: string; type: "string"; default?: string },
): void;

/** Get the value of a registered CLI flag. */
getFlag(name: string): boolean | string | undefined;
```

`core/extensions/loader.ts:331-349`（registerFlag）＋`:373-378`（getFlag）：

```ts
registerFlag(name, options): void {
    assertActive();
    if (options.default !== undefined && typeof options.default !== options.type) {
        throw new Error(
            `Invalid default for flag "${name}": expected ${options.type}, got ${typeof options.default}`);
    }
    extension.flags.set(name, { name, extensionPath: extension.path, ...options });
    if (options.default !== undefined && !runtime.flagValues.has(name)) {
        if (state === "loading") {
            if (!pendingFlagValues.has(name)) pendingFlagValues.set(name, options.default);
        } else {
            runtime.flagValues.set(name, options.default);
        }
    }
},

// Flag access - checks extension registered it, reads from runtime
getFlag(name: string): boolean | string | undefined {
    assertActive();
    if (!extension.flags.has(name)) return undefined;
    return runtime.flagValues.has(name) ? runtime.flagValues.get(name) : pendingFlagValues.get(name);
},
```

要点：default 类型不匹配即抛；**声明存本扩展**（`extension.flags`）、**值存共享
`runtime.flagValues`**；default 的 `!has` 门＝CLI 值/先到 default 不被覆盖；getFlag
有**所有权门**（只能读本扩展注册的名），runtime 优先、loading 期回落 pending。
commit（`:530-541`）把 pending 灌入 runtime（`!has` 门）。

**reload 保留**——`core/extensions/runner.ts:665-671`：`getFlagValues()` 返回拷贝；
`agent-session.ts:3661-3662` reload 先 `oldRunner.getFlagValues()` 快照，
`:3631-3635` `_buildRuntime({flagValues})` 灌回新 runtime。**flag 值跨 reload 存活**。

**--help 面**——`cli/args.ts:273-283` `printHelp(extensionFlags?)`：

```ts
const extensionFlagsText =
    extensionFlags && extensionFlags.length > 0
        ? `\n${chalk.bold("Extension CLI Flags:")}\n${extensionFlags
                .map((flag) => {
                    const value = flag.type === "string" ? " <value>" : "";
                    const description = flag.description ?? `Registered by ${flag.extensionPath}`;
                    return `  --${flag.name}${value}`.padEnd(30) + description;
                })
                .join("\n")}\n`
        : "";
```

`main.ts:877-884`：**--help 在完整运行时（含扩展加载）建好之后打印**；所有扩展的 flags
拍平传入。格式：`--name`（string 型补 ` <value>`）padEnd(30)＋description（缺省回落
`Registered by <path>`）；无扩展 flag 时段落整体消失。

**真实用例**（examples/extensions/plan-mode/index.ts:53-57, 340-343）：

```ts
pi.registerFlag("plan", {
    description: "Start in plan mode (read-only exploration)",
    type: "boolean",
    default: false,
});
// session_start 钩里：if (pi.getFlag("plan") === true) { planModeEnabled = true; }
```

### 1.2 激活门（E5-18；B221②）

**API 声明**——types.ts:1737-1750：

```ts
/** Get the names of the active tools, which are the tools declared to the model. */
getActiveTools(): string[];

/** Get all configured tools with parameter schema, prompt guidelines, exposure, and source metadata. */
getAllTools(): ToolInfo[];

/**
 * Set the active tools by name. Unknown and `hidden` tools are ignored. Tools with `codemode` or
 * `deferred` exposure stay callable from codemode scripts whether active or not.
 */
setActiveTools(toolNames: string[]): void;
```

`ToolInfo`（types.ts:2093-2099）＝`Pick<ToolDefinition, "name"|"description"|"parameters"|"promptGuidelines">`
＋`exposure`＋`namespace?`＋`annotations?`＋`sourceInfo`。

**接线**——loader 的 API 纯委托共享 runtime（loader.ts:416-433），runtime 真身由
runner.bindCore 从 session 拷入（agent-session.ts:3422-3426）：

```ts
getActiveTools: () => this.getActiveToolNames(),
getAllTools: () => this.getAllTools(),
setActiveTools: (toolNames) => this.setActiveToolsByName(toolNames),
```

**session 实现**——agent-session.ts:1483-1531：

```ts
getActiveToolNames(): string[] {
    return this.agent.state.tools.map((t) => t.name);
}

setActiveToolsByName(toolNames: string[]): void {
    const previous = this.getActiveToolNames();
    this._setActiveTools(toolNames);
    // A loadout that deactivates a tool replaces the restored one, whose pending tools are dropped.
    const active = new Set(this.getActiveToolNames());
    if (previous.some((name) => !active.has(name))) this._pendingToolNames.clear();
}

private _setActiveTools(toolNames: string[]): void {
    const tools = this._applyToolLoadout(toolNames);
    for (const tool of tools) this._pendingToolNames.delete(tool.name);
    this._rebuildSystemPrompt(tools.map((tool) => tool.name));
}
```

**过滤落点** `_applyToolLoadout`（:1575-1579）：去重后按注册表命中＋
`exposure !== "hidden"`＋`_isActivatable` 过滤，写 `agent.state.tools`。**「流进 LLM
请求」的过滤就是这里**——agent loop 直接把 `state.tools` 作为声明发给 provider，无额外
请求期过滤。`_isActivatable`（:1549-1552）：allowlist 未匹配的 MCP 工具仅当非 direct
且注册了 tool_search 才可激活。

**注册即激活**——agent-session.ts:3603：

```ts
return this._isDeclarable(name) && this._toolDefinitions.get(name)?.definition.defaultActive !== false;
```

pi 的 `ToolDefinition.defaultActive?: boolean`（types.ts:614-617）是工具级闸门：
`defaultActive: false` 的扩展工具注册后不自动激活。

### 1.3 提示分节（B221⑤/E8-7）

**sections 槽**——`core/system-prompt.ts:29-30`：`sections?: Record<string, string>`
（normalize 后恒非空，:72 `{ ...(input.sections ?? {}) }`）。段名校验
（:144-148）：必须匹配 `^[a-z][a-z0-9_-]*$` 且不能叫 `preamble`，否则抛
`Invalid system prompt section name: <name>`。

**构建序**——`buildSystemPromptSections`（:127-193）：内置节固定序 preamble →
（无 customPrompt 时）tools/rules/docs → addendum → project_context → skills → cwd →
**自定义节最后追加**（:184-186：`if (content) promptSections[name] = content;`——空内容
不进段）。除 preamble 外每节包 `<name>\n...\n</name>`（:188-191）。

**diff**——`diffSystemPromptSections`（:217-229）：与转录重放值逐段比对，变化段进补丁、
消失段置 `null`，无差返回 undefined。**无独立缓存**——「上一次状态」每次从转录重放
（`getCurrentSystemMessage(messages)?.sections ?? {}`）。

**关键结论：pi 基座构建同样从不填 sections**（`_rebuildSystemPrompt`
agent-session.ts:1715-1725 无此键）。自定义节唯一填充通道是 **`before_agent_start`
事件的可变 `systemPromptOptions`**（types.ts:918-929：`systemPromptOptions:
NormalizedBuildSystemPromptOptions`，"Mutable prompt sections. Later handlers observe
mutations made by earlier handlers."）。runner 编排（runner.ts:1425-1471）让**同一
options 对象贯穿所有处理器**。

**全仓唯一内置生产者**＝内置 MCP 扩展写 `mcp_servers` 节
（extensions/mcp/index.ts:1173-1181）：

```ts
pi.on("before_agent_start", async (event, ctx) => {
    await waitForDirectServers(ctx);
    const { sections } = event.systemPromptOptions;
    const section = renderServersSection(servers);
    if (section) sections[MCP_SERVERS_SECTION] = section;
    else delete sections[MCP_SERVERS_SECTION];
});
```

消费链：AgentSession :2105 `_preparePromptAndToolLoadout(result.systemPromptOptions)` →
`buildSystemPromptSections` 追加自定义节 → `diffSystemPromptSections` 与转录比对 →
有差才插入 system 补丁消息（:1745-1749）：

```ts
const sections = diffSystemPromptSections(
    getCurrentSystemMessage(messages)?.sections ?? {},
    buildSystemPromptSections(options),
);
return sections ? { role: "system", content: "", sections, timestamp: Date.now() } : undefined;
```

**对 java「promptOptions 从未调 .sections(...)」的修正认知**：缺的不是构建期传参，而是
「before_agent_start 事件对象暴露可变 sections → 写回装配输入 → diff 补丁进转录」这条链。
MCP `mcp_servers` 节生产者属 map/07（B221①族），不在本批。

### 1.4 registerToolRenderer（E5-11/B204）

**API**——types.ts:1695-1696＋:660-667：

```ts
/** Choose how tool calls are drawn. Resolvers run in extension load order. */
registerToolRenderer(resolver: ToolRendererResolver): void;

export type ToolRenderers = Pick<AnyToolDefinition, "renderShell" | "renderCall" | "renderResult">;

/**
 * Chooses how calls to a tool are drawn, including tools that are not registered. `next()` returns
 * the renderers the remaining resolvers, then the registered tool, would use.
 */
export type ToolRendererResolver = (
    toolName: string,
    next: () => ToolRenderers | undefined,
) => ToolRenderers | undefined;
```

**实现**——loader.ts:367-371（存 `extension.toolRenderers` 数组，追加序＝加载序）；
**解析链** runner.ts:789-795 逐字：

```ts
/** Renderers of calls to `toolName`: extension resolvers in load order, then `base`. */
resolveToolRenderers(toolName: string, base: () => ToolRenderers | undefined): ToolRenderers | undefined {
    const resolvers = this.extensions.flatMap((ext) => ext.toolRenderers ?? []);
    const resolve = (index: number): ToolRenderers | undefined =>
        index < resolvers.length ? resolvers[index](toolName, () => resolve(index + 1)) : base();
    return resolve(0);
}
```

**消费点**：TUI `interactive-mode.ts:2229`（`getRegisteredToolDefinition`——
`resolveToolRenderers(toolName, () => withBuiltInRenderers(toolName, ...))`）；
HTML 导出 `agent-session.ts:4296`（`createToolHtmlRenderer({getToolRenderers: ...})`）。

**内置生产者**＝MCP 扩展（extensions/mcp/index.ts:384-386）：

```ts
// A resumed session renders calls to MCP tools before their server connected, if it ever does.
pi.registerToolRenderer((toolName, next) => {
    const match = /^mcp__(.+?)__(.+)$/.exec(toolName);
    return next() ?? (match ? createMcpToolRenderers(`${match[1]}/${match[2]}`) : undefined);
});
```

`createMcpToolRenderers(label)`（extensions/mcp/tools.ts:298-339）：
renderCall＝`formatToolCallWithArgs(label, args, theme, context.expanded)` 单行文本；
renderResult＝取文本输出 trim，空 ⇒ 空组件；折叠臂 `VisualLinePreview`
（maxVisualLines=OUTPUT_PREVIEW_LINES，keep:"start"，hint `... (<n> more lines, <keyHint> to expand)`），
展开臂全量；`details.fullOutputPath` 存在时追加 `Full output: <path>` muted 行。

### 1.5 /reload（C-23/E5-29/B246）

**`AgentSession.reload()` 全本体**——core/agent-session.ts:3660-3701 逐字：

```ts
async reload(options?: { beforeSessionStart?: () => void | Promise<void> }): Promise<void> {
    const oldRunner = this._extensionRunner;
    const previousFlagValues = oldRunner.getFlagValues();
    await emitSessionShutdownEvent(oldRunner, { type: "session_shutdown", reason: "reload" });
    oldRunner.invalidate();
    const getDefaultTools = () =>
        this._usesDefaultTools
            ? applyToolModifiers(
                    this.settingsManager.getDefaultTools() ?? DEFAULT_TOOL_NAMES,
                    this._defaultToolModifiers,
                )
            : [];
    const previousDefaultTools = new Set(getDefaultTools());
    await this.settingsManager.reload();
    this.syncQueueModesFromSettings();
    resetApiProviders();
    await this._resourceLoader.reload();
    // Activate tools newly added to defaultTools. Removed ones stay active, and tools disabled
    // during the session stay disabled unless the setting newly adds them.
    const addedDefaultTools = getDefaultTools().filter((name) => !previousDefaultTools.has(name));
    // Tools the new extensions register later, such as MCP tools, are pending until then.
    for (const name of this.getActiveToolNames()) this._pendingToolNames.add(name);
    this._buildRuntime({
        activeToolNames: [...this.getActiveToolNames(), ...addedDefaultTools],
        flagValues: previousFlagValues,
        includeAllExtensionTools: true,
    });

    const hasBindings =
        this._extensionUIContext ||
        this._extensionCommandContextActions ||
        this._extensionShutdownHandler ||
        this._extensionErrorListener;
    if (hasBindings) {
        await options?.beforeSessionStart?.();
        await this._extensionRunner.emit({ type: "session_start", reason: "reload" });
        this._extensionRunner.reportUnhandledMcpServers();
        await this.extendResourcesFromExtensions("reload");
    }
}
```

七步序：① 旧 runner 发 `session_shutdown{reload}`（hasHandlers 门，runner.ts:255-268）→
invalidate（旧 ctx 此后抛 stale 错，runner.ts:722-733）→ ② **settings 重载前**快照
defaultTools → ③ `settingsManager.reload()`＋`syncQueueModesFromSettings`（steering/
followUp 模式随设置）→ `resetApiProviders`（ai/compat.ts:209-213：清 api-registry＋内置
provider 实例缓存，重注册内置）→ `resourceLoader.reload()`（重扫
extensions/skills/prompts/themes/context files，resource-loader.ts:513-679）→
④ defaultTools diff（`addedDefaultTools`＝新−旧）＋当前 active 全进 `_pendingToolNames` →
⑤ `_buildRuntime` 重建（flagValues 回灌、`includeAllExtensionTools: true`）→
⑥ **hasBindings 门**（四绑定任一存在才走）→ ⑦ `beforeSessionStart` 钩 →
`session_start{reload}` → `extendResourcesFromExtensions("reload")`（resources_discover
重放，:3284-3306）。

**defaultTools diff 三分支语义**（B246 核心）：
- **新增进 defaultTools 的** ⇒ `addedDefaultTools` 拼进 activeToolNames 激活；
- **从 defaultTools 移除的** ⇒ 仍在 `getActiveToolNames()` 里照样传下去，**保持激活**；
- **会话中 `-name` 移除的** ⇒ `_defaultToolModifiers` 照旧 apply 到新设置上，**保持移除**。
- `_usesDefaultTools=false`（显式纯名列表 `--tools`）的会话 **reload 完全不碰工具集**。

**支撑函数**（settings-manager.ts:83-138）：`DEFAULT_TOOL_NAMES=["read","bash","edit","write"]`、
`isToolModifier`（`+`/`-` 起头）、`applyToolModifiers`（`+name` 不在则 append、`-name`
在则 splice）、`getDefaultTools()`（:1329-1333）。

**sdk.ts 生产点**（:279-294 解析＋:461-478 接线）：
`usesDefaultTools = (options.tools === undefined || toolModifiers !== undefined) && !options.noTools`；
`defaultToolModifiers = options.tools?.some(isToolModifier) ? options.tools : undefined`。
**`Invalid tools option` 抛错**（:279-280）＝`getToolListError` 非空即抛——java 无 SDK 面，
CLI 等价物（解析期 error 诊断）批 1 已落，本批无新增。

**TUI handler**——modes/interactive/interactive-mode.ts:6430-6518 `handleReloadCommand()`：
双门警告逐字：

```ts
if (this.session.isStreaming) {
    this.showWarning("Wait for the current response to finish before reloading.");
    return;
}
if (this.session.isCompacting) {
    this.showWarning("Wait for compaction to finish before reloading.");
    return;
}
```

流程：resetExtensionUI → 显示 "Reloading keybindings, extensions, skills, prompts, themes,
and context files..." 边框盒 → `session.reload({beforeSessionStart: 重建chat})` →
keybindings.reload() → 主题表重设＋applyFromSettings → 补全 provider 重建 → 扩展快捷键
重绑 → 成功状态文案（逐字）：

```
Reloaded keybindings, extensions, skills, prompts, themes, and context files
```

失败臂：`Reload failed: <error.message>`。slash 元数据（core/slash-commands.ts:42）：
`{ name: "reload", description: "Reload keybindings, extensions, skills, prompts, themes, and context files" }`。
RPC 无 reload 线方法（扩展命令 ctx 有 `reload()`，rpc-mode.ts:341-343／print-mode.ts:98-100）。

### 1.6 model_select / mcp_servers_change（D#36/D#11）

**model_select**——载荷（types.ts:1103-1111）：

```ts
export type ModelSelectSource = "set" | "cycle" | "restore";

/** Fired when a new model is selected */
export interface ModelSelectEvent {
    type: "model_select";
    model: Model<any>;
    previousModel: Model<any> | undefined;
    source: ModelSelectSource;
}
```

唯一发射函数 `_emitModelSelect`（agent-session.ts:2457-2469）：**同模型
（modelsAreEqual）不发射**。调用点三处：`setModel` 尾（:2496，source `"set"`，在
appendModelChange＋setThinkingLevel **之后** await）；`cycleModel` 两处（:2565/:2597，
source `"cycle"`）。**`"restore"` 本锚点无发射点**（仅声明）。

**mcp_servers_change**——载荷（types.ts:730-734）：`servers: RegisteredMcpServer[]`
（变更后**全量**列表）。唯一发射点 runner.ts:456-461（bindCore 内）：
`runtime.mcpServers.setChangeListener(() => { void emit(...); reportUnhandledMcpServers(); })`。
触发＝扩展经 `registerMcpServer`/`unregisterMcpServer` 改注册表（loader.ts:471-498，
加载期注册的不发事件，由 session_start 时 `getMcpServers()` 读取）。**java 无
registerMcpServer（E5-23，map/07 域）⇒ 发射链无生产者**，见裁决⑤。

---

## §2 java 侧现状（取证 2026-10-23，main 3c7b351a）

1. **扩展 SPI**：`PiExtension`（register(ExtensionContext)＋sessionStartResources）；
   `ExtensionContext`（96 行）现有 tools/slashCommands/providers/skills/settings/ui/services/
   on/sendMessage——**无 registerFlag/getFlag、无激活门三件套、无 registerToolRenderer**。
   `DefaultExtensionContext`（202 行）：包装 SessionServices＋SkillManager＋ExtensionUI＋
   ExtensionEventBus；`PerExtensionContext` 除 `on()` 外全部透传。上下文创建点＝
   `AgentSession.loadExtensions`（:2288-2363，静态私有；fork 两路复用）。
2. **激活门**：`AgentHarness.getActiveTools/setActiveTools`（:433-445）——⚠️
   **setActiveTools 会 `toolRegistry.clear()` 再重注册**（注册面＝激活面的旧语义），
   扩展后注册的工具会被它抹掉；**全仓无生产消费者**（仅注释提及），修真安全。
   `AgentSession` 无 setActiveTools 入口。**扩展注册的工具进 ToolRegistry 但永不进
   `lane.activeTools`**（activeTools 在 harness 构造 :638 按 CLI/settings 固化，扩展在
   其后 :350 才加载，无再同步）——即 B221② 现状。过滤落点：
   `PiLaneEngine.activeTools`（:524-531，名集→注册表 activeOf）＋
   `ContextAssembler.activeToolsInRegistryOrder`（:157-163）。
3. **提示分节**：`SystemPromptOptions.Builder.sections(Map)` 定义在
   `prompt/SystemPromptOptions.java:173-177`，**生产代码零调用**（grep `.sections(` 主码
   命中全是 SystemMessage 读取侧）；`SystemPrompts.buildSections`（:66）会消费并
   `validateSectionNames`（:67）；diff 补丁链（`ContextAssembler.promptLoadout`
   :142-154）**已存在**。`BeforeAgentStartEvent`（ExtensionEvent.java:233）现只有
   `(prompt, systemPrompt)` 两字段，无可变 sections。
4. **CLI unmatched**：`ArgsParser` `@Unmatched`（:171-172）＋`toArgs` :271-282 收编
   （`--k=v` 整串进 parsedUnmatched；裸 `--k` 吞下一个非 `-`/`@` token 作值——**旗与值
   是列表两个元素**，与 pi 的 Map 形不同，绑定时需重新配对）；`Args.unmatched`（:104）
   **main 代码零消费者**。`Main.dispatch` 的 help 臂（:69-72）打印静态
   `HelpText.text()`——**在任何扩展加载之前**。
5. **/reload**：`SettingsCommands.java:52-63` 只 `settings().reload()` ＋返回
   "Reloaded settings (extensions/skills/prompts in Phase 6)"；description 现
   "Reload settings and keybindings"（pi 逐字见 §1.5）。重扫原语盘点：
   `SkillDiscovery.discoverAll`（可重跑）但 `SkillManager` **无 clear/replace**；
   `PromptTemplates.loadDefaults/load` 可重跑但 `PromptTemplateRegistry` **无
   clear/replace**；`ProviderRegistry` **有** `remove/clear/loadBuiltinProviders/
   discoverFromServiceLoader`（唯一有 reset 面）；主题 `PiTheme.resolveTheme` 在
   `PiTuiLauncher:269-272` 启动一次性，无运行期重载面；**context files 是晚读口**
   （`AgentSession:665-668`：每次 run 重新 `ContextFileDiscovery.discover`，与 pi 同）
   ⇒ reload 无须动作。`SettingsAccessors.getDefaultTools()`（:170-176）已存在；
   `ToolSelection.resolve/applyToolModifiers/mergeDefaultTools` 齐（批 1）。
   `AgentSession.isStreaming`（:998 区）/`isCompacting`（:993，含分支摘要在飞）均在。
6. **工具渲染**：无渲染器注册表类型；tui `MessageBubble` 按 ContentBlock 类型 switch
   （ToolUse→`ToolCallCard(name, args, "running")` :95-96；ToolResult→
   `ToolCallCard(name, truncate(joinText,500), isError?"error":"done")` :97-102），
   与工具名无关；MCP 工具同路通用渲染。
7. **事件面**：新增观察型事件的既有样式＝`ExtensionEvent` 加 record 变体＋
   `ExtensionEventSpec` 加常量＋发射侧（firer 直发 或 SessionEventBridge 翻译宿主事件）。
   `AgentSession.setModel`（:922-928）**现不发任何事件**；
   `cycleModelForward`（:897-911）**直呼 `harness.setModel(next)`（:905）绕过会话层**——
   Ctrl+P 轮转不会触发挂在会话 setModel 上的任何发射（要在两处分别布点，T6）。
8. **MCP 注册表**：`pi-java-mcp` 的 `McpServerRegistry`（:26-99）register/unregister 尾
   调 `changed()`→`changeListener.run()`，`setChangeListener` 缝现成；**main 代码零
   生产者**（B218），注册表无人实例化进会话。

---

## §3 任务分解

### T1：扩展 CLI flag 面（E5-7＋E5-30＋A-43；coding-agent，~260 行＋测试）

**T1a 数据型**——新文件 `extension/ExtensionFlag.java`（record）：

```java
/** pi {@code ExtensionFlag}（types.ts:2058-2064）；extensionPath 对位＝扩展名。 */
public record ExtensionFlag(String name, String description, Type type,
                            Object defaultValue, String extensionName) {
    public enum Type { BOOLEAN, STRING }
}
```

**T1b 注册/读取**——`ExtensionContext` 增两方法；`DefaultExtensionContext` 实现：

```java
/** pi registerFlag（loader.ts:331-349）：default 类型不匹配即抛；
 *  default set-if-absent（CLI 值/先到 default 不被覆盖）。 */
void registerFlag(String name, ExtensionFlagOptions options);

/** pi getFlag（loader.ts:373-378）：所有权门——只能读本扩展注册的名。 */
Object getFlag(String name);   // Boolean | String | null
```

- `DefaultExtensionContext` 增共享 `Map<String, Object> flagValues`（对位
  `runtime.flagValues`）＋ per-extension 声明表 `Map<String, Map<String, ExtensionFlag>>`
  （对位 `extension.flags`）。
- `PerExtensionContext` 的 registerFlag 把 `extensionName` 写进声明；getFlag 查本扩展
  声明表，无 ⇒ null。
- default 类型校验：BOOLEAN 型 default 必须 Boolean、STRING 型必须 String，否则抛
  `Invalid default for flag "<name>": expected <type>, got <实际类型>`（pi 文案逐字，
  java 侧类型名映射 boolean/string）。
- pi 的 pending/commit 两阶段是「加载期 default 不提前覆盖」的机制；java 加载与绑定
  同线程顺序执行（register 全在 loadAll 内、CLI 绑定在 loadAll 后），set-if-absent
  一刀即可达同一净效果——**不移植 pending 队列**（窄差不登记：无观察面差异）。

**T1c 绑定器**——新文件 `extension/ExtensionFlagBinder.java`（pi
`applyExtensionFlagValues` agent-session-services.ts:82-128 对位）：

```java
/** 返回诊断（pi AgentSessionRuntimeDiagnostic 对位）。 */
public static List<Args.ArgDiagnostic> apply(List<String> unmatchedTokens,
                                             DefaultExtensionContext context)
```

- 先把 `Args.unmatched` 的扁平 token 列表**重新配对**成 `Map<String, Object>`（pi
  args.ts:247-271 三态逐字）：`--k=v` ⇒ (k, v)；裸 `--k` 后跟值 token（ArgsParser 已吞进
  列表的下一个元素）⇒ (k, v)；否则 ⇒ (k, Boolean.TRUE)。
- 绑定：注册表查无 ⇒ 收集名；BOOLEAN ⇒ 恒写 `true`（**忽略解析期吞下的字符串值**，pi
  逐字）；STRING＋字符串值 ⇒ 写入；STRING＋无值 ⇒ 诊断
  `Extension flag "--<name>" requires a value`。
- 未注册汇总一条：`Unknown options: --a, --b`（单数 `Unknown option: --a`）。

**T1d 接线**——`AgentSession.loadExtensions` 尾部（`applySessionStartResources` 之前）
调 binder；诊断进会话级 `startupDiagnostics` 列表（新字段），TUI 启动经既有
资源/诊断展示通道呈现、print/RPC 模式打印 stderr（裁决⑧）。

**T1e help 面**——`Main.dispatch` help 臂改造：先**轻量装载扩展**（SPI
ServiceLoader＋global/project JAR＋显式 `-e`，用裸 registries 的 DefaultExtensionContext，
不建会话、不碰 session 文件；逐扩展异常隔离），收集全部 `ExtensionFlag`，
`HelpText.text()` 尾部追加段（格式逐字 §1.1：空 ⇒ 整段消失；`  --name[ <value>]`
padEnd(30)＋description，缺省 `Registered by <扩展名>`；段头 `Extension CLI Flags:`）。
⚠️ 轻量装载必须包 try/catch——扩展 register() 抛错不该让 `--help` 崩（pi 同样全量加载，
其 loader 有异常隔离）。

### T2：激活门暴露（E5-18；B221②；agent-core＋coding-agent，~200 行＋测试）

**T2a harness 修真**——`AgentHarness.setActiveTools`（:438-445）**删 clear/registerAll
两段**：pi 的注册面与激活面分离（`_applyToolLoadout` 只写 `state.tools`，不动注册表）。
java 现状该方法**无生产消费者**，语义收敛安全。同步修 javadoc。

**T2b 会话层三件套**——`AgentSession` 增：

```java
/** pi getActiveToolNames（agent-session.ts:1483-1485）。 */
public List<String> getActiveToolNames();

/** pi setActiveToolsByName（:1518-1525）：注册表查无的名静默忽略；
 *  系统提示不手动重建——java 的提示每 run 起点由 ContextAssembler 重装配＋
 *  promptLoadout diff 补丁（既有机制），与 pi _rebuildSystemPrompt 的净效果同。 */
public void setActiveToolsByName(List<String> names);

/** pi getAllTools（:1495-1506）的窄化投影（裁决⑥，窄差 D91）。 */
public List<ExtensionToolInfo> getAllTools();
```

`ExtensionToolInfo`（新 record，coding-agent）：`name/description/promptGuidelines`——
pi 的 `parameters/exposure/namespace/annotations/sourceInfo` java 无对位（窄差 D91）。
setActiveToolsByName 的过滤：`ToolRegistry.get(name)` 命中才收（pi 的「unknown 忽略」
逐字）；java 无 hidden/exposure 概念（窄差并入 D91）。pi 的 `_pendingToolNames.clear()`
臂 java 无 pending 概念（批 1 窄差 D4 同族，随 B221⑫b）——不移植，注记。

**T2c 扩展面**——`ExtensionContext` 增 `getActiveTools()/getAllTools()/setActiveTools(
List<String>)`；`DefaultExtensionContext` 经**晚绑定**（新增 `bindSession` 同款的
`bindToolControl(Supplier<AgentSession>)` 或直接把 owner session supplier 复用）委托到
T2b。基础上下文与 per-extension 行为一致（pi 此处无所有权门）。

**T2d 装配后扩展工具激活臂**——`AgentSession.loadExtensions` 在 manager 加载完成后：
注册表名集 − 装配前基线名集＝新增扩展工具名；经 `selection.isAllowed(name)` 门（含
mcp\_\_ 保留臂，批 1 裁决③）过滤后并入 `lane.activeTools`（harness 增
`activateTools(Collection<String>)` 或经 T2b）。对位 pi
`_refreshToolRegistry` 的「activatedOnRegistration」臂（agent-session.ts:3603）。
**pi 的 `defaultActive: false` 闸 java 无字段**（AgentTool 无 defaultActive）⇒ 扩展工具
注册即激活，窄差 D89（裁决②）。

### T3：提示分节接线（B221⑤/E8-7；agent-core＋coding-agent，~150 行＋测试）

**T3a 车道供应口**——`LaneState` 增 `promptSections`（`Map<String,String>`，默认空）；
`ExecutionContext` 增 `Supplier<Map<String,String>> promptSections`（默认 `Map::of`）；
`AgentHarness` 增 `getPromptSections/setPromptSections`（照 `setPromptGuidelines` 样式）。

**T3b 装配接线**——`ContextAssembler.promptOptions`（:104-114 builder 链）补
`.sections(ctx.promptSections().get())`。段名校验、`<name>` 包装、diff 补丁全部既有
（`SystemPrompts.buildSections`/:67 校验＋`promptLoadout`）——本批只接生产者。

**T3c 事件载荷**——`ExtensionEvent.BeforeAgentStartEvent`（:233）增第三字段
`Map<String, String> sections`（**可变 LinkedHashMap**，pi 的「同一 options 对象贯穿
所有处理器、后者见前者改动」逐字——runner.ts:1425-1471）。`SessionEventFirer.
beforeAgentStart`：基线＝harness 当前 promptSections 的**拷贝**传入事件；结束后把
终态写回 harness（`setPromptSections`）。`BeforeAgentStartOutcome` 不变（sections 经
harness 写回即生效，下一 run 的 promptLoadout diff 自动产补丁）。

**T3d 消费点核验**——`AgentSession:1187-1188` 的 beforeAgentStart 调用点无须改签名；
补集成测试：扩展 on(BEFORE_AGENT_START) 写 `sections.put("mcp_servers", ...)` ⇒ 下一
run 系统提示含 `<mcp_servers>` 段、转录出现段补丁消息。

### T4：registerToolRenderer（E5-11/B204；coding-agent＋tui，~250 行＋测试）

**T4a 渲染器接口**（裁决⑦分层：coding-agent 出**文本级**接口，tui 适配着色）——
新文件 `extension/ToolCallRenderer.java`：

```java
/** pi ToolRenderers（types.ts:658）的 java 文本级对位。null ⇒ 不管（落下一 resolver）。 */
public interface ToolCallRenderer {
    /** pi renderCall：工具名＋参数的展示行（已转义）。 */
    List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded);
    /** pi renderResult：结果文本的展示行。 */
    List<String> renderResult(String toolName, String textOutput, boolean isError, boolean expanded);
}
```

新文件 `extension/ToolRendererResolver.java`（函数式接口，
`(toolName, next) -> ToolCallRenderer`，pi types.ts:664-667 逐字语义）；新文件
`extension/ToolRendererRegistry.java`：resolvers 列表（追加序＝加载序）＋
`resolve(String toolName, Supplier<ToolCallRenderer> base)`——pi runner.ts:789-795
责任链逐字（index 递归 or 等价循环）。

**T4b 扩展面**——`ExtensionContext.registerToolRenderer(ToolRendererResolver)`；
`DefaultExtensionContext` 持 registry（loadExtensions 创建，随会话字段存，
fork 经 wireSession 重建——与 bus 同生命周期）。

**T4c 内置 MCP resolver**——装配时在 registry 注册（效果对位 pi 内置 MCP 扩展的
resolver，extensions/mcp/index.ts:384-386）：正则 `^mcp__(.+?)__(.+)$` 命中 ⇒
label `<server>/<tool>`；renderCall＝`label`＋参数单行（pi formatToolCallWithArgs 形）；
renderResult＝折叠臂首 N 行＋`... (<n> more lines)` 提示行（pi VisualLinePreview 的
文本级对位；java 无 keyHint/expanded 交互缝 ⇒ 窄差 D92 含此项）、展开臂全量。
**`next() ??` 委托优先**逐字。

**T4d TUI 消费缝**——`MessageBubble` 的 ToolUse/ToolResult 两臂（:95-102）在 new
ToolCallCard 之前查表：registry 经 tui 侧静态持有者（`ToolRenderers` holder，
`PiTuiApp` 在会话 attach 时注入 session 的 registry）获取；resolver 命中 ⇒ 用其行
（着色按 isError/状态由 tui 适配层补），未命中 ⇒ 原 ToolCallCard 路径不变。
HTML 导出面（pi agent-session.ts:4296 同款消费点）**不做**——java HtmlExporter 无
renderer 缝，登记窄差（并入 D92）。

### T5：/reload（C-23＋E5-29＋B246；coding-agent＋tui，~350 行＋测试）

**T5a reload 前置状态**——`ToolSelection.Resolved` 增两字段（pi sdk.ts:472-473 对位）：

```java
boolean usesDefaultTools;        // (!toolsFlagPresent || 纯 +/- 修饰符列表) && !noTools
List<String> defaultToolModifiers; // 修饰符列表（无 ⇒ List.of()）
```

`ToolSelection.resolve` 计算填充（需要 `toolsFlagPresent`——ArgsParser :204 已存该标记，
经 Args 传入）；`AgentSession` 装配时（SessionSetup.resolveToolSelection 消费点）存为
会话字段（fork 继承——pi 的 fork 重建带同配置）。

**T5b `AgentSession.reload()`**——pi :3660-3701 七步序的 java 对位（裁决③⑫）：

```java
public void reload() {
    // ① session_shutdown{reload}（firer，hasHandlers 门逐字）
    extensionFirer.sessionShutdown("reload", null);
    // ② settings 重载前快照 defaultTools（usesDefaultTools=false ⇒ 空集）
    var previousDefaultTools = resolvedDefaultTools();
    // ③ 设置重载
    services.settings().reload();
    // ④ provider 重置（裁决④）
    // ⑤ 资源重扫：skills（SkillManager 增 replaceAll＋SkillDiscovery.discoverAll 重跑
    //    ＋applySessionStartResources 重放扩展臂）、prompts（PromptTemplateRegistry 增
    //    replaceAll＋PromptTemplates.loadDefaults 重跑）；themes 随批 20（裁决⑨）；
    //    context files 晚读口恒新（无须动作，注记）
    // ⑥ defaultTools diff（B246）：added = 新 − 旧 ⇒ 经注册表过滤并入 activeTools；
    //    移除的保持激活（不动）；usesDefaultTools=false ⇒ 整臂跳过
    // ⑦ session_start{reload}＋资源重放（applySessionStartResources）
    extensionFirer.sessionStart("reload", null);
}
```

- flag 值：java 不重建扩展运行时 ⇒ flagValues 天然存活（pi 的快照回灌是重建的产物，
  净效果相同，注记不登记窄差）。
- `SkillManager.replaceAll(Collection<Skill>)`／`PromptTemplateRegistry.replaceAll(...)`
  为新增原语（pi `updateSkillsFromPaths/updatePromptsFromPaths` 的「替换」语义对位；
  现状无 clear ⇒ 重扫会叠积，必须补）。
- provider 重置（裁决④）：pi `resetApiProviders` 清的是**协议车道**缓存
  （ai/compat.ts:209-213）。java 对位调查点：`ProviderRegistry` 的内置车道是否构造期
  烘焙 settings/models.json。若烘焙 ⇒ reload 时 `remove` 内置车道＋`loadBuiltinProviders`
  重注册＋**保留扩展注册项**（按名快照，DefaultExtensionContext 已知哪些是扩展注册）；
  若车道本就懒读设置 ⇒ 无操作必要，登记窄差 D94。实现任务先做取证再两臂择一。

**T5c 命令层**——`SettingsCommands` 的 `/reload` 改写：

- description 改 pi 逐字：`Reload keybindings, extensions, skills, prompts, themes, and context files`。
- 双门（pi 文案逐字）：`ctx.session().isStreaming()` ⇒
  `Wait for the current response to finish before reloading.`；
  `ctx.session().isCompacting()` ⇒ `Wait for compaction to finish before reloading.`
  （java slash 返回 String 状态文案——警告与结果同通道，既有样式）。
- 成功：`Reloaded keybindings, extensions, skills, prompts, themes, and context files`；
  失败：`Reload failed: <msg>`（catch 包装，pi 逐字）。
- TUI 的 Reloading 边框盒/重建 chat/keybindings 重载属 pi TUI 组件层——java TUI 无
  对等组件体系，slash 结果文案即用户可见面（窄差 D93 含此注记）。

### T6：model_select 事件（D#36；coding-agent，~120 行＋测试）

**T6a 事件**——`ExtensionEvent` 增：

```java
/** pi ModelSelectEvent（types.ts:1103-1111）。source ∈ {"set","cycle","restore"}
 *  （"restore" pi 本锚点亦无发射点，声明保留）。 */
record ModelSelectEvent(ModelId<?> model, ModelId<?> previousModel,
                        String source) implements ExtensionEvent {}
```

`ExtensionEventSpec` 增 `MODEL_SELECT`（观察型，R=Void）。

**T6b 发射**——`SessionEventFirer.modelSelect(model, previous, source)`（hasHandlers
快走＋逐处理器异常 emitError，既有样式）。发射点两处（pi :2496/:2565/:2597 对位）：

1. `AgentSession.setModel`（:922-928）**方法尾**：`previous = 换前模型`；
   `previous.equals(model)` ⇒ 不发（pi modelsAreEqual 门逐字）；source `"set"`。
2. `AgentSession.cycleModelForward`（:897-911）：现直呼 `harness.setModel(next)`（:905）
   绕过会话层——在 `harness.setModel(next)` 后发射 source `"cycle"`（previous＝current）。
   ⚠️ 不改成「调会话 setModel」：cycleModelForward 的思考档语义与 setModel 不同
   （显式级别臂），只补发射。

---

## §4 不做项与重排（docs/20 §3 联动）

| 项 | 去向 | 理由 |
|---|---|---|
| D#11 `mcp_servers_change`（w2） | 随 E5-23（registerMcpServer）批 | 裁决⑤：发射链的唯一生产者是扩展注册 MCP 服务器（pi loader.ts:471-498），java 该 API 不在本批范围；只接订阅面＝零生产者的死 firing 点，不翻行 |
| E5-23/24/25（registerMcpServer 族）、B221①（MCP 六事件）、`mcp_servers` 提示节生产者 | map/07 后续批 | MCP 扩展面整体，非本批 |
| E5-31 `ctx.ui.custom`（B214）＋D#26/#27 `ui_prompt`×2 | 批 17（docs/20 §3 序 16） | TUI 注入层独立批 |
| B221⑥ RPC `get_commands` 扩展臂（source:"extension"＋sourceInfo） | 后续批 | 需扩展命令溯源（注册面改造），本批不扩 |
| E5-5 unregisterProvider／E5-6 registerShortcut／E5-8/9/10 渲染器族其余／E5-12 triggerTurn／E5-13…22 余行 | E5 后续批（序 16 及以后） | 序 15 只含点名三项＋并入项 |
| 主题运行期重载（pi reload 的 themes 臂） | 批 20（JSON 主题系统，docs/20 §3 序 20） | 裁决⑨：java `PiTheme` 启动一次性解析，运行期换主题依附 JSON 主题系统 |
| HTML 导出的工具渲染器消费点（pi agent-session.ts:4296） | 窄差 D92 注记 | java HtmlExporter 无 renderer 缝 |
| 扩展运行时重建（runner invalidate＋模块重 import） | 窄差 D90 | 裁决③：SPI 静态注册，重跑 register 需先建订阅/贡献回收面 |
| `_pendingToolNames` 全部语义 | 随 B221⑫b（批 1 窄差 D4 同族） | java 无 pending 概念；reload 的 pending 回填臂（pi :3681）不移植 |
| pi reload 的 `hasBindings` 门（:3687-3692） | 窄差 D93 | 裁决⑫：java 无「模式层绑定」概念，session_start{reload} 恒发 |
| pi TUI 的 Reloading 边框盒/beforeSessionStart 重建 chat | 窄差 D93 | java slash 结果文案即用户可见面 |

---

## §5 窄差登记（接续 D88；台账格式【场景/决定/风险】随闭环入 docs/04）

| # | 窄差 | 源 |
|---|---|---|
| D89 | 扩展工具注册即激活：pi 有 `ToolDefinition.defaultActive: false` 闸（types.ts:614-617，注册后不自动激活）；java `AgentTool` 无该字段，全量激活 | 裁决② |
| D90 | `/reload` 不重建扩展运行时：pi 重新 import 扩展模块（JAR 代码变更生效）；java SPI/ClassLoader 静态，`register()` 不重跑（重跑需订阅/贡献回收面，且 SPI 类变更本就要重启 JVM） | 裁决③ |
| D91 | `getAllTools()` 投影缺 `parameters/exposure/namespace/annotations/sourceInfo`（pi ToolInfo types.ts:2093-2099）：java 工具定义无这些字段 | 裁决⑥ |
| D92 | 工具渲染器是**文本级**接口（产 `List<String>`，tui 适配着色）：pi 产富 Component（VisualLinePreview/keyHint/expanded 交互）；MCP 折叠提示无 keyHint 缝；HTML 导出无 renderer 消费点 | 裁决⑦ |
| D93 | `/reload` 用户可见面收窄：无 Reloading 边框盒/beforeSessionStart 钩/keybindings 显式重载（settings 驱动即效）；`session_start{reload}` 恒发（pi 有 hasBindings 门） | 裁决⑫ |
| D94 | provider 重置视取证两臂择一：车道烘焙 settings ⇒ 重建内置车道保留扩展项；车道懒读 ⇒ 无操作（pi `resetApiProviders` 无 java 对位） | 裁决④ |

---

## §6 裁决

> 格式：【场景】/【决定】/【风险（代价）】三段式；标注【模型可见】/【内部】。

**裁决①：harness.setActiveTools 去清注册表**
- 【场景】java `AgentHarness.setActiveTools`（:438-445）现状会 `toolRegistry.clear()` 再
  重注册——「注册面＝激活面」的旧语义。pi 的注册面与激活面分离（`_applyToolLoadout`
  只写 `agent.state.tools`，不动注册表）。若保留旧语义，T2c 的扩展 `setActiveTools`
  会把扩展后注册的工具从注册表抹掉。
- 【决定】删 clear/registerAll 两段，`setActiveTools` 只写 `lane.activeTools`。
  javadoc 同步改。
- 【风险/代价】无影响［内部］：全仓无生产消费者（grep 实证，仅注释提及）；测试若有
  依赖旧语义的随批修。

**裁决②：扩展工具注册即激活（无 defaultActive 闸）**
- 【场景】pi 的扩展工具默认注册即激活（`_isActivatedOnRegistration`，
  agent-session.ts:3603），但工具可声明 `defaultActive: false` 退出。java `AgentTool`
  无该字段。
- 【决定】装配后激活臂（T2d）对全部经 `selection.isAllowed` 门的扩展工具生效；
  不加 defaultActive 字段，窄差 D89。
- 【风险/代价】边缘场景［内部］：未来某扩展想「注册但不激活」无表达面；pi 侧该用法
  低频（tool_search/codemode 族，R5 面外）。

**裁决③：/reload 不重建扩展运行时**
- 【场景】pi `reload()` 重建 ExtensionRunner 并重新 import 扩展模块（JAR/代码变更
  生效），重建前发 `session_shutdown{reload}`、invalidate 旧 ctx。java 扩展是 SPI
  静态注册＋URLClassLoader JAR，重跑 `register()` 的现实障碍：`ExtensionEventBus.on()`
  返回的 AutoCloseable 由扩展自持、管理器不追踪 ⇒ 重跑必双订阅；ToolRegistry/
  CommandRegistry 无按扩展回收面 ⇒ 重跑必重复注册。
- 【决定】本批 reload 做：shutdown/start{reload} 事件、settings、provider 重置、
  skills/prompts 重扫、defaultTools diff、resources_discover 重放。**不重跑
  register()、不重建 ClassLoader**。窄差 D90。
- 【风险/代价】边缘场景［内部］：扩展 JAR 代码变更需重启 JVM 生效（Java 平台约束，
  SPI 类即使换 ClassLoader 也只是同名新类）；扩展若在 register() 里读外部文件，pi 的
  reload 会重读、java 不会——实际影响取决于扩展写法，java 扩展生态当前只有测试夹具。
  回收面（订阅追踪/贡献追踪）建成后可翻本裁决。

**裁决④：provider 重置两臂取证后择一**
- 【场景】pi `resetApiProviders`（ai/compat.ts:209-213）清协议车道注册表＋内置实例缓存
  再重注册内置——目的是让 settings/models.json 的变更进车道。java `ProviderRegistry`
  有 `remove/clear/loadBuiltinProviders/discoverFromServiceLoader`，但扩展注册的
  provider 与内置同表。
- 【决定】实现时先取证 java 车道是否构造期烘焙 settings：烘焙 ⇒ reload 时按名快照
  扩展注册项、清内置、重注册内置、回灌扩展项；懒读 ⇒ 不做任何操作。结果登记 D94。
- 【风险/代价】无影响［内部］：两臂对外不可见；误清扩展 provider 是唯一真风险，快照
  回灌顺序保证不丢。

**裁决⑤：mcp_servers_change 随 E5-23 批，本批不翻 D#11**
- 【场景】pi 该事件的唯一发射链＝扩展 `registerMcpServer`/`unregisterMcpServer` 改
  注册表（runner.ts:456-461）。java 无 registerMcpServer（E5-23，map/07 域，不在本批）；
  `McpServerRegistry.setChangeListener` 缝虽现成但零生产者、注册表无人实例化进会话。
- 【决定】本批不接线（避免造「订阅面在、永不发射」的死 firing 点）；D#11 维持缺失，
  docs/20 §3 序 15 的「#11 随收」改注「随 E5-23 批」。
- 【风险/代价】无影响［内部］：D 域账面少 +2.0（本批 D 域只靠 #36 到 77.0/83）。

**裁决⑥：getAllTools 窄化投影**
- 【场景】pi `ToolInfo` 带 `parameters/exposure/namespace/annotations/sourceInfo`
  （types.ts:2093-2099）；java 工具定义只有 name/description/promptSnippet/
  promptGuidelines。
- 【决定】`ExtensionToolInfo` 只投影 `name/description/promptGuidelines`；缺口登记 D91。
- 【风险/代价】边缘场景［模型不可见］：扩展拿不到 schema/来源元数据；java 扩展生态
  当前只有测试夹具，无真实消费者。

**裁决⑦：渲染器分层——coding-agent 出文本级接口，tui 适配**
- 【场景】pi 的 renderer 产 TUI Component（Text/Container/VisualLinePreview），扩展与
  TUI 同生态。java 模块图 coding-agent 不依赖 tui（依赖方向自下而上，tui 与
  coding-agent 平级）。
- 【决定】`ToolCallRenderer` 产 `List<String>`（转义后文本行），着色/状态由 tui 适配层
  按 isError/expanded 补。MCP resolver 落 coding-agent（纯名称模式匹配，无 mcp 模块
  依赖）。窄差 D92。
- 【风险/代价】[模型不可见］用户可见差：无 VisualLinePreview 的精确视觉行折叠与
  keyHint 提示（折叠提示降级为文本行）；扩展不能产富组件——java 扩展若要富渲染需依赖
  tui 模块（后续批可开 `ctx.ui.custom` 面，序 16）。

**裁决⑧：flag 诊断落点**
- 【场景】pi 的绑定诊断（Unknown options / requires a value）经
  AgentSessionRuntimeDiagnostic 在启动时报告。java 的 Args 诊断在解析期打印，绑定
  诊断发生在会话装配内（loadExtensions），无既有回传通道。
- 【决定】`AgentSession` 增 `startupDiagnostics` 列表字段；binder 诊断追加进去。TUI
  启动随资源/诊断展示呈现；print/RPC/web 模式各自打印 stderr。
- 【风险/代价】无影响［内部］：诊断文案逐字 pi（模型可见面只有文案本身，落点差异
  不登记窄差——pi 各模式落点也不同）。

**裁决⑨：themes 重载随批 20**
- 【场景】pi reload 重扫 themes＋TUI `themeController.applyFromSettings()`。java
  `PiTheme.resolveTheme` 在 `PiTuiLauncher:269-272` 启动一次性解析，无运行期重载面；
  JSON 主题系统（E4-4/B163）本身就是批 20 主题。
- 【决定】本批 reload 不碰 themes；`/reload` 成功文案仍逐字含 "themes"（与 pi 文案
  一致），实际重扫登记在批 20 联动注记。
- 【风险/代价】边缘场景［用户可见］：改主题文件后 /reload 不生效，需重启；pi 行为
  一致化在批 20 完成。

**裁决⑩：--help 轻量扩展装载**
- 【场景】pi 的 --help 在完整运行时（含扩展加载）后打印，扩展 flag 列入
  "Extension CLI Flags:" 段。java 现状 `Main.dispatch` 的 help 臂打印静态文本、
  在任何加载之前。
- 【决定】help 臂先做轻量装载：SPI＋global/project JAR＋显式 `-e`，用裸 registries 的
  DefaultExtensionContext（不建会话、不碰 session 文件、不设 user.home 重定向），
  逐扩展 try/catch 隔离；收集 flags 后按 §1.1 格式追加段落。无 flags ⇒ 段落消失。
- 【风险/代价】边缘场景［内部］：扩展 register() 有副作用（建文件/连网）会在 --help
  时执行——pi 同样全量加载，语义一致；java 当前扩展只有测试夹具。

**裁决⑪：before_agent_start 的 sections 用可变 Map**
- 【场景】pi 的 `systemPromptOptions.sections` 是普通可变 Record，同一对象贯穿所有
  处理器（后者见前者改动，runner.ts:1425-1471）。java 事件现是不可变 record。
- 【决定】`BeforeAgentStartEvent` 增 `Map<String,String> sections` 字段——record
  组件但持**可变 LinkedHashMap**（firer 建基线拷贝传入、结束后读回写 harness）。
  这是 pi 可变语义的唯一直译；record 的不可变惯例在此让位逐字语义，javadoc 注明。
- 【风险/代价】无影响［内部］：字段新加，既有两组件构造点（firer 内）同步改；测试
  夹具跟改。

**裁决⑫：session_start{reload} 恒发（不移植 hasBindings 门）**
- 【场景】pi reload 尾部有 hasBindings 门（:3687-3692：四「模式层绑定」任一存在才发
  session_start{reload}＋重放 resources_discover）。java 无该概念（扩展 UI/命令动作/
  关闭钩/错误监听四绑定的对位物不存在或恒在）。
- 【决定】恒发 `session_start{reload}`＋恒重放 `applySessionStartResources`。
  窄差 D93。
- 【风险/代价】边缘场景［内部］：无模式层绑定的会话（如纯 print 模式）多一次事件
  发射——订阅者自行决定响应，与 pi 事件语义不冲突（pi 门是性能/时序优化，不是
  语义门）。

---

## §7 测试计划

| 任务 | 测试（新文件/类） | 用例要点 |
|---|---|---|
| T1 | `ExtensionFlagBinderTest`（coding-agent） | 三态配对（--k=v／--k v／裸 --k）；boolean 恒 true（吞值被忽略，pi 逐字）；string 无值 ⇒ `Extension flag "--x" requires a value`；未注册汇总 `Unknown options: --a, --b`／单数形；default 类型不匹配抛错文案逐字；getFlag 所有权门（读他扩展名 ⇒ null）；default set-if-absent（CLI 值优先）；`--help` 段格式（有/无 flags 两态、padEnd(30)、` <value>` 后缀、Registered by 回落） |
| T2 | `ExtensionToolActivationTest` | 装配后扩展工具进 activeTools（经 isAllowed 门）；mcp\_\_ 名被保留臂挡；setActiveToolsByName 未知名静默忽略；harness.setActiveTools 不再清注册表（回归钉）；getActiveToolNames/getAllTools 投影形状 |
| T3 | `PromptSectionsWireTest`（coding-agent） | 扩展 before_agent_start 写 sections ⇒ 下一 run 系统提示含 `<name>` 段；两处理器后者见前者改动；空内容不进段；段名非法抛 `Invalid system prompt section name: <name>`；diff 补丁消息进转录（content=""＋sections 补丁形） |
| T4 | `ToolRendererRegistryTest`＋tui `MessageBubbleRendererTest` | 责任链加载序；`next()` 委托优先（pi MCP 形：`next() ?? (match ? ... : undefined)`）；null 落 base；MCP 正则 label（mcp\_\_a\_\_b ⇒ a/b）；折叠提示行文本；MessageBubble 两臂查表命中/未命中 |
| T5 | `SessionReloadTest`（coding-agent） | 事件序：session\_shutdown{reload} 先于 session\_start{reload}；defaultTools diff 三分支（新增激活／移除保持激活／`-name` 保持移除）；usesDefaultTools=false（显式 --tools 纯名列表）⇒ 工具集不动；skills/prompts replaceAll 不叠积（重扫后数量不变）；flag 值跨 reload 存活；双门文案逐字（isStreaming/isCompacting）；成功/失败文案逐字；fork 继承 usesDefaultTools/modifiers |
| T6 | `ModelSelectEventTest` | setModel 发射 source="set"＋previous 正确；同模型不发（equals 门）；cycleModelForward 发射 source="cycle"；发射在 thinking 变更之后（序钉）；无订阅者零开销快走 |

回归钉更新：内置命令 description 文案钉（/reload 改 pi 逐字）；`--help` 输出钉（追加段）。
全量验证：`mvn clean verify`（显式 JAVA_HOME GraalVM 25；底层改动带 `-am`）。

---

## §8 对账表（判定列：前态→后态）

| 行 | 前态 | 后态 | 证据 |
|---|---|---|---|
| map/04 E5-7 `registerFlag`/`getFlag`（w1） | 缺失 | 对齐 | T1a/T1b（loader.ts:331-349/:373-378 对位） |
| map/04 E5-30 扩展注册 CLI flag（w1） | 缺失 | 对齐 | T1c/T1d（args.ts:247-271＋agent-session-services.ts:82-128 对位） |
| map/04 A-43 扩展注册的 CLI flag（w1） | 缺失 | 对齐 | T1e（args.ts:273-283 printHelp 对位） |
| map/04 E5-18 激活门三件套（w2） | 缺失 | 对齐 | T2（types.ts:1737-1750＋agent-session.ts:1483-1531 对位；窄差 D89/D91） |
| map/04 E8-7 提示分节（w1，不计分母） | 缺失 | 对齐 | T3（system-prompt.ts:127-229 链路接线；java diff 侧既有） |
| map/04 E5-11 registerToolRenderer（w1） | 缺失 | 对齐 | T4（types.ts:1695＋runner.ts:789-795 对位；窄差 D92） |
| map/04 E5-29 热重载（w2） | 存疑 | 对齐 | T5（agent-session.ts:3660-3701 对位；窄差 D90/D93/D94） |
| map/04 C-23 `/reload`（w1） | 存疑 | 对齐 | T5c（slash-commands.ts:42＋interactive-mode.ts:6430-6518 文案逐字） |
| map/04 D#36 `model_select`（w2） | 缺失 | 对齐 | T6（types.ts:1103-1111＋agent-session.ts:2457-2469 对位） |
| map/04 D#11 `mcp_servers_change`（w2） | 缺失 | **不动** | 裁决⑤（随 E5-23 批） |
| docs/04 B204 | 开放 | **销号** | T4（注册面＋MCP 内置 resolver＋TUI 消费缝） |
| docs/04 B246 | 开放 | **销号** | T5a/T5b⑥（defaultTools diff 三分支落地） |
| docs/04 B221 | 开放 | **部分收编** | ②（T2）④（T4）⑤（T3）收编；①③⑥ 留（条目更新） |

---

## §9 闭环回填（2026-10-23 闭环）

- [x] docs/04：B204/B246 销号；B221 条目更新（②④⑤ 收编，余 ①③⑥）；D89–D94 窄差入册（批 16 段）；E1 涨到 **2,532**、E6 **924**、E7 734 不动
- [x] docs/map/04：E5-7/E5-11/E5-18/E5-29/E5-30/A-43/C-23/D#36 行翻动＋E5/A/C/D 域汇总行重算（E5 22.0/58 ≈ 37.9%、A 61.5/62 ≈ 99.2%、C 34.5/38 ≈ 90.7%、D 77.0/83 ≈ 92.8%）；E8-7 注记行随翻（不计分母）；D#11 不动（裁决⑤）
- [x] docs/05：模块账面 341.0→**350.5/485 ≈ 72.3%**；全仓 1,020.5→**1,030.0/1379 ≈ 74.7%**
- [x] docs/20：§2 追加批 16 行；§3 序 15 划线闭环（序 16 改「下一批」＋#11 注记改挂 E5-23 批；主题运行期重载撞出归批 20）；§1 四项读数重填（已闭环 16、下一包号 docs/28）
- [x] 记忆：批 16 闭环条目＋「下一包号 docs/28」

**闭环实绩**（与设计预期的偏差）：
- 账面 Δ 全中预期（+9.5；E5-7/E5-11/E5-18/E5-29/E5-30/A-43/C-23/D#36 八行翻动）。
- **评审回补（修真）**：裁决① 连带——工具执行查找面接活跃集活视图
  （`PiToolRunner` 7 参＋`PiLaneEngine` 装配；pi agent-loop.ts:716
  `currentContext.tools.find` 对位，失活即 `Tool <name> not found`）。
  连带夹具修形 ×4：PromptLoadoutTest（注册面补齐 second）/
  AgentLoopL1Test/CrossLayerLengthGateTest/HarnessToolExecutionSpansTest
  （激活面=注册面）；全量回归抓回 SessionFailurePathTest（夹具先注册再激活）。
- **责任链语义自纠**：首版测试把「resolver 返回 null ⇒ 自动落后续/base」写反——
  pi runner.ts:789-795 是**返回值直返**（「不管」须显式 `next()`），
  实现本就逐字，测试改钉 pi 语义（ToolRendererRegistryTest 两例改写）。
- 验证：四模块全量测试绿→全仓 `mvn clean verify` **BUILD SUCCESS**（含 spotbugs）；
  新测试 50 用例（coding-agent 44＋tui 3＋agent-core 3）。
- commits：`9fb30304`（agent-core T2a/T3）＋`03d991a5`（agent-core 评审回补）＋
  `8e553d33`（coding-agent T1–T6）＋`cc4051b2`（tui/web）＋`d94b6d3c`（回归回补）。
