# D29 对齐包设计稿：扩展 ctx 失效（stale）防御

> **状态**：**已实施并闭环（2026-11-25）**——单测 `ExtensionStaleContextTest` 7 例通过，全仓 `mvn verify` 退出码 0。
> **实施偏差**：stale 状态机抽到 `ExtensionContextStaleness`（`DefaultExtensionContext` 触及 FileLength 500 行上限）；
> 守卫集中在 `surface()`／`requireToolControl()`／`requireHost()` 三个缝上，行为与设计一致。
> **锚点**：pi `a00a5048d`。
> **范围**：`pi-java-coding-agent`（`DefaultExtensionContext`、`SessionDiscoveryFork.closeWith/closeForReplacement/closeForFork/switchTo`）＋ 扩展测试；不改 `ExtensionChannelBus` 的退订逻辑，不改 agent-core。
> **关闭条件**：会话被替换/关闭后，扩展持有的旧 ctx 调用宿主面即抛 `IllegalStateException`（文案与 pi 逐字）；`map/04` D29 行销号。

## 1. 差异（`map/04` D29）

> 无 `extensionRunner.invalidate` 对位（pi dispose 的 stale ctx 防御）。等级：[内部，边缘]。

Java 现状只做了 invalidate 的 **eventBus 半**（`DefaultExtensionContext.invalidateChannels()` → `ExtensionSurfaceRegistries.invalidateChannels()`，会话换装前统一退订通道）。**ctx 失效半缺失**：扩展在 `newSession()`／`fork`／`switchSession`／会话关闭之后继续使用捕获的 ctx，不会报错，而是静默操作一个已关闭/已换装的会话。

## 2. pi 事实

### 2.1 运行时状态与失效（`core/extensions/loader.ts:164-218`，逐字）

```ts
const state: { staleMessage?: string } = {};
const eventBusUnsubscribers = new Set<() => void>();
const assertActive = () => {
	if (state.staleMessage) {
		throw new Error(state.staleMessage);
	}
};
...
		assertActive,
		invalidate: (message) => {
			if (state.staleMessage) return;
			state.staleMessage =
				message ??
				"This extension ctx is stale after session replacement or reload. Do not use a captured pi or command ctx after ctx.newSession(), ctx.fork(), ctx.switchSession(), or ctx.reload(). For newSession, fork, and switchSession, move post-replacement work into withSession and use the ctx passed to withSession. For reload, do not use the old ctx after await ctx.reload().";
			for (const unsubscribe of eventBusUnsubscribers) unsubscribe();
			eventBusUnsubscribers.clear();
		},
```

要点：**首条文案胜出**；`invalidate` 同时完成两件事——置 stale 文案、退订全部 eventBus 订阅（Java 已有的 `invalidateChannels` 正是后半）。

### 2.2 Runner 层的转发与守卫（`core/extensions/runner.ts:398-416, 733-746`，逐字）

```ts
	private staleMessage: string | undefined;
...
			if (!this.staleMessage) this.emitError(error);      // :413 失效后不再上报处理错误
...
	invalidate(
		message = "This extension ctx is stale after session replacement or reload. ...",
	): void {
		if (!this.staleMessage) {
			this.staleMessage = message;
			this.runtime.invalidate(message);
		}
	}

	private assertActive(): void {
		if (this.staleMessage) {
			throw new Error(this.staleMessage);
		}
	}
```

### 2.3 被守护的面（`runner.ts` 行号）

| 面 | 行 |
|---|---|
| `getActiveTools()` | 887 |
| `createContext()` 的 17 个 getter（ui/mode/hasUI/cwd/sessionManager/modelRegistry/model/scopedModels/thinkingLevel/isIdle/isProjectTrusted/signal/abort/hasPendingMessages/shutdown/getContextUsage/compact/getSystemPrompt） | 901-969 |
| 工具 ctx（`tools`、`executeTool`） | 985-991 |
| 命令 ctx（`getSystemPromptOptions`） | 1017-1041 |
| `sendCustomMessage` 等 action | 1449 |

`runtime` 侧 20＋ 个 action（`sendMessage`/`sendUserMessage`/`appendEntry`/`setSessionName`/`setLabel`/`setModel`/`setThinkingLevel`/`getCommands`/`exec`…）在 `loader.ts:284-510` 逐个 `assertActive()`。

### 2.4 失效触发点

| 位置 | 代码 |
|---|---|
| `AgentSession.dispose()`（`agent-session.ts:1492-1494`） | `this._extensionRunner.invalidate("This extension ctx is stale after session replacement or reload. …")`，在 abort 与 `_disconnectFromAgent()` 之间 |
| `reload()`（`agent-session.ts:3839-3842`） | `await emitSessionShutdownEvent(oldRunner, {reason:"reload"}); oldRunner.invalidate();` —— 随后**重建 runner** |
| 运行时拆解（`agent-session-runtime.ts:189-197`） | `abort → session_shutdown → beforeSessionInvalidate → session.dispose()`（new/resume/fork/quit 共用） |

## 3. Java 现状

| 位置 | 现状 |
|---|---|
| `DefaultExtensionContext.invalidateChannels()`（[L480-484](file:///d:/workplaceForai/pi-java/pi-java-coding-agent/src/main/java/com/pijava/coding/agent/extension/DefaultExtensionContext.java#L480-L484)） | 只转发 `surface.invalidateChannels()`，无 stale 标志 |
| `PerExtensionContext` | 逐成员委托到共享的 `DefaultExtensionContext` ⇒ 失效态只需放在 base |
| `SessionDiscoveryFork.switchTo`（[L231-236](file:///d:/workplaceForai/pi-java/pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/SessionDiscoveryFork.java#L231-L236)） | 退化 UI ＋ `invalidateChannels()`；持久路是**原地 attach**（同一 ctx 继续用） |
| `closeForReplacement` / `closeForFork`（[L438](file:///d:/workplaceForai/pi-java/pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/SessionDiscoveryFork.java#L438)、[L454](file:///d:/workplaceForai/pi-java/pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/SessionDiscoveryFork.java#L454)） | 同上，仅通道半 |
| `closeWith`（[L476-499](file:///d:/workplaceForai/pi-java/pi-java-coding-agent/src/main/java/com/pijava/coding/agent/core/SessionDiscoveryFork.java#L476-L499)） | 关会话的**唯一漏斗**（`fireSessionShutdown → persist → close → harness.close`），**无文件失效** |
| `SessionReload.reload` | 复用 `session.extensionManager()`（同一 manager/ctx），不重建 —— pi 的 reload 臂在 Java **不适用**（见 §5 不做的事） |

## 4. 后果

- 扩展在会话替换/关闭后仍持有旧 ctx：`sendMessage`／`setModel`／`ui()` 等会作用到已关闭或已换装的会话上（可能 NPE、写到错误会话、或新 UI 收到旧扩展的状态变更）。
- pi 的行为是可预期的 `Error("This extension ctx is stale …")`，扩展作者能据此改写为 `withSession` 模式。
- 等级 `[内部，边缘]` 仍成立：主路不触发，只有扩展/异步回调跨会话时才可观察。

## 5. 方案

### T1 `DefaultExtensionContext`：stale 状态＋invalidate

```java
/** pi runner.invalidate / runtime.invalidate 默认文案（loader.ts:204，逐字）。 */
static final String STALE_MESSAGE = "This extension ctx is stale after session replacement or reload. ...";

private volatile String staleMessage;

/** pi loader.ts:200-207：首条文案胜出；同时退订通道（既有半）。 */
public void invalidate(String message) {
    if (staleMessage != null) {
        return;
    }
    staleMessage = message != null ? message : STALE_MESSAGE;
    surface.invalidateChannels();
}

void assertActive() {
    var stale = staleMessage;
    if (stale != null) {
        throw new IllegalStateException(stale);
    }
}
```

`invalidateChannels()` 保留（`switchTo` 仍只走通道半），由 `invalidate` 内部调用。

### T2 守卫点（pi → Java 映射）

在以下方法**首行**加 `assertActive()`（`PerExtensionContext` 委托即自动生效）：

| pi 面 | Java 方法 |
|---|---|
| runtime action（loader.ts:284-510） | `sendMessage`、`sendUserMessage`、`appendEntry`、`setSessionName`、`getSessionName`、`setLabel`、`setModel`、`getThinkingLevel`、`setThinkingLevel`、`getCommands`、`getActiveTools`、`getAllTools`、`setActiveTools` |
| createContext getter（runner.ts:901-969） | `ui()`、`mode()`、`hasUi()`、`cwd()`、`sessionManager()`、`modelRegistry()`、`getModel()`、`getThinkingLevel()`、`isIdle()`、`isProjectTrusted()`、`shutdown()`、`compact()`、`getContextUsage()`、`getSystemPrompt()`、`newSession()` |
| 工具 ctx（985-991） | `callableTools()`（经 `requireToolControl()`）、`executeTool` 所在缝 |
| 注册面 | `surface()`（单点覆盖 `tools/slashCommands/providers/skills/settings/events/forExtension/register*` 等） |
| 宿主操作簇 | `ExtensionHostOperations.requireHost()` 与 10 个直接成员（`getModel/modelRegistry/getContextUsage/compact/reload/shutdown/isIdle/getSystemPrompt/isProjectTrusted/newSession`） |

`events()` 不守（pi 也不守：订阅在 `invalidate` 里统一退订）。`reload()` 守——pi 明确禁止 `await ctx.reload()` 后继续用旧 ctx。

### T3 失效接线

- `closeWith(…)`：在 `fireSessionShutdown(reason, targetSessionFile)` **之后**调用
  `if (owner.extensionContext() != null) owner.extensionContext().invalidate(null)`（pi dispose :1492 与 shutdown 事件的相对序）。覆盖 quit／fork／replacement 三条真关闭路。
- `closeForReplacement` / `closeForFork`：删除各自的 `invalidateChannels()` 块（`closeWith` 的 `invalidate` 已含通道半，重复调用幂等但冗余）。
- `switchTo`：**保持** `invalidateChannels()`。Java 的持久路是原地 attach（同一会话对象、同一 ctx，pi 是旧/新两条 runner，见 D23）——此刻置 stale 会把**在用**的 ctx 打死。

### T4 失效后的错误上报（不移植，说明理由）

pi `runner.ts:413` 在 stale 后吞掉处理器的 `emitError`。Java 的 `ExtensionChannelBus` 属于**该会话自己的** manager，会话关闭后旧 bus 不再挂到新 UI（无跨会话监听），故本包不引入额外抑制；若实现中出现旧 bus 向新 UI 打日志，再单开一条补齐。

### 不做的事

- 不重建 Java `reload` 的扩展 runner/ctx（pi reload 之所以 invalidate，是因为它换新 runner；Java 复用同一 manager ⇒ 旧 ctx 即新 ctx，失效会误伤）。若将来把 reload 改成重建，必须同时补 `invalidate`。
- 不改 `ExtensionChannelBus` 的退订实现，不改 `PerExtensionContext` 的归属校验。
- 不处理内存路 `switchTo` 旧会话不关闭（D25/D28 既有方言）。

## 6. 回归测试

1. 新增 `ExtensionStaleContextTest`（`pi-java-coding-agent`）：
   - 扩展在 `session_start` 捕获 `ctx`；`close()` 后调用 `ctx.sendMessage(...)` ⇒ `IllegalStateException`，文案以 `This extension ctx is stale after session replacement or reload.` 开头；
   - `newSession()`／`fork` 后同样断言（旧 ctx 失效、`withSession` 传入的新 ctx 可用）；
   - `invalidate("first")` 后再 `invalidate("second")` ⇒ 仍抛 `first`（首条胜出）；
   - 会话内正常 ctx 不受影响（`sendMessage` 仍生效）。
2. 通道退订：现有通道测试原样通过（`invalidate` 内含 `invalidateChannels`）。
3. 方向性：`switchTo` 原地 attach 后**同一** ctx 仍可用（不得误伤）。
4. `mvn -q -pl pi-java-coding-agent -am test -Dtest=ExtensionStaleContextTest,...` → 全仓 `mvn -q verify`。

## 7. 风险与回滚

| 风险 | 处置 |
|---|---|
| 扩展在 `session_shutdown` 处理器里用 ctx | 失效发生在 shutdown 处理器**之后**（pi 同序），处理器内仍可用 |
| `switchTo` 误伤在用 ctx | T3 明确只在 `closeWith` 置 stale；`switchTo` 只退订通道 |
| 双次 `invalidate` 覆盖文案 | 首条 wins（pi loader.ts:201） |
| 线程可见性 | `volatile String staleMessage` |
| 回滚 | `DefaultExtensionContext` 新增字段＋`closeWith` 一行调用；删除即回现状 |

## 8. 文档回填（闭环时）

- `docs/map/04-coding-agent.md`：删 D29 行；D 域读数不变（100%，窄差不进分子）。
- `docs/04-open-items-register.md`：§0 补 D29 销号记录。
- `docs/05-module-alignment-map.md`：L5 读数确认（预期不变，457.0/459＝99.6%）。
