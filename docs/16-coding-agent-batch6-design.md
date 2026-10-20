# coding-agent 批 6 设计稿：fork 扩展装配＋会话关闭治理（B251＋B252）

> **状态：待审核**。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（coding-agent 批 5 闭环后，`1a8878f0`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`15-coding-agent-batch5-design.md`（已闭环，结果臂事件族＋生命周期发射点；B251/B252 即彼批新登记）。

| 项 | 内容 |
|---|---|
| 批次 | coding-agent 批 6（docs/16）＝台账 **B251**（forked 会话不加载扩展）＋**B252**（切换路径旧会话不 close）合包；pi 锚点 `ea448f454`；判定权威＝map/04 逐行 |
| 重件 | **T1**：fork 装配重建——assemble 的「harness＋会话绑定装配」抽取为共用方法，fork 三路改走它（fresh 注册表＋fresh 会话绑定闭包），forked 会话 `loadExtensions` 全量重装并发射 `session_start{fork, previousSessionFile}`（§3.1–§3.4） |
| 中件 | **T2**：teardown 集中——`closeWith` 三参化（abort 对位＋句柄开关＋幂等）、fork 内部拆解（保共享句柄）、/new 三调用点改 `closeForReplacement`、原地 attach 关旧 storage（§3.5–§3.8）；**T3**：退出面——TUI exit 关当前会话、RPC EOF 关 dispatcher 当前会话（§3.9） |
| 新发现 | **fork 共享 config 的会话绑定闭包串线**（§2.2）：`harness.fork()` 共享 config ⇒ 压缩/重试事件、重试中止旗、图片策略、**PI_\* 会话身份**五组闭包全部读父会话——与 B251 同根（fork 不过 assemble），随 T1 同修，不单列 B 号 |
| 不碰 | `session_before_tree`/`session_tree`（**B245**）；reload 臂（**B246**）；provider 请求面（**B248**）；E1（`AgentSession.java` 拆分债在册，本批控制在 +150 行内）；E6；D′ 扩展 UI 面；窄差 D9–D23 维持 |
| 一句话 | 批 5 把生命周期的「发射点」点着了，但 fork 后的会话是个没有扩展的空壳、被替换的旧会话从不真正关闭；本批让 fork/clone 产出**完整会话**（扩展工具/命令/订阅/资源发现全量重装、`start{fork}` 有消费者），并按 pi `teardownCurrent`/`dispose` 语义关掉每一条替换/退出路径上的旧会话——`session_shutdown{quit}` 自此在 TUI 与 RPC 退出路径发射。 |

预期账面（对账表见 §8）：**行翻转 0**（D-3/D-10 早已判对齐，本批摘证据列「挂 B251/B252」帽）；销号 **B251**/**B252**；D 域 66.0/83＝79.5% **不变**、模块 283.5/496≈57.2% **不变**；窄差新增 D24–D30（均内部/边缘）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`，只走 `git show`）

### 1.1 替换总线四件（agent-session-runtime.ts:133-194，逐字）

pi 的会话替换集中在 `AgentSessionRuntime`——它持有当前 `AgentSession`＋cwd 绑定服务＋**重建工厂** `createRuntime`：

```ts
private async emitBeforeSwitch(
	reason: "new" | "resume",
	targetSessionFile?: string,
): Promise<{ cancelled: boolean }> {
	const runner = this.session.extensionRunner;
	if (!runner.hasHandlers("session_before_switch")) {
		return { cancelled: false };
	}
	const result = await runner.emit({
		type: "session_before_switch", reason, targetSessionFile,
	});
	return { cancelled: result?.cancel === true };
}

private async emitBeforeFork(
	entryId: string, options: { position: "before" | "at" },
): Promise<{ cancelled: boolean }> { /* 同构，:150-165 */ }

private async teardownCurrent(reason: SessionShutdownEvent["reason"], targetSessionFile?: string): Promise<void> {
	// Settle any active response first so the aborted turn (including tool
	// results) is persisted to the outgoing session before it is replaced.
	await this.session.abort();
	await emitSessionShutdownEvent(this.session.extensionRunner, {
		type: "session_shutdown", reason, targetSessionFile,
	});
	this.beforeSessionInvalidate?.();
	this.session.dispose();
}

private async finishSessionReplacement(withSession?: ...): Promise<void> {
	if (this.rebindSession) {
		await this.rebindSession(this.session);
	}
	if (withSession) { await withSession(this.session.createReplacedSessionContext()); }
}
```

要点：① before 事件 cancel ⇒ 旧会话**不动**；② teardown 全序＝**abort（让在途 turn 落盘到出站会话）→ shutdown 事件 → 宿主 UI 拆解钩 → dispose**；③ 替换后由模式层注入的 `rebindSession` 完成新会话绑定。

### 1.2 fork 全序（agent-session-runtime.ts:262-345，逐字要点）

```ts
async fork(entryId: string, options?: {...}): Promise<{ cancelled: boolean; selectedText?: string }> {
	const position = options?.position ?? "before";
	const beforeResult = await this.emitBeforeFork(entryId, { position });
	if (beforeResult.cancelled) { return { cancelled: true }; }
	// …解析 targetLeafId / selectedText（position "at" ⇒ 选中 entry 本身；
	//   "before" ⇒ 须 user message，target=parentId，取回文本）…
	const previousSessionFile = this.session.sessionFile;
	if (this.session.sessionManager.isPersisted()) {
		// targetLeafId 空 ⇒ 新会话文件（parentSession 链）；否则
		// SessionManager.open(当前文件).createBranchedSession(targetLeafId)
		// 两臂随后同形：
		await this.teardownCurrent("fork", sessionManager.getSessionFile());
		this.apply(await this.createRuntime({
			cwd, agentDir, sessionManager,
			sessionStartEvent: { type: "session_start", reason: "fork", previousSessionFile },
		}));
		await this.finishSessionReplacement(options?.withSession);
		return { cancelled: false, selectedText };
	}
	// 内存态：复用同一 sessionManager（数据随 manager 存活），仍先 teardown：
	const sessionManager = this.session.sessionManager;
	await this.teardownCurrent("fork", sessionManager.getSessionFile());
	if (!targetLeafId) { sessionManager.newSession({ parentSession: previousSessionFile }); }
	else { sessionManager.createBranchedSession(targetLeafId); }
	this.apply(await this.createRuntime({ ..., sessionStartEvent: { ..., reason: "fork", previousSessionFile } }));
	await this.finishSessionReplacement(options?.withSession);
	return { cancelled: false, selectedText };
}
```

要点：① **序逐字＝before_fork →（备齐目标 manager）→ teardownCurrent（含 shutdown{fork}）→ createRuntime（全量重建：新 AgentSession＋新扩展运行时）→ rebind（发射 start{fork}）**——shutdown 先于 start；② `createRuntime` 是进程级固定输入的闭包（:420-435 `createAgentSessionRuntime` 把它存进 runtime 复用），每次替换**重新装配 cwd 绑定服务与扩展**（agent-session-services.ts 的 `createAgentSessionServices`/`createAgentSessionFromServices`）；③ 内存态同样 teardown 旧会话——pi 内存 SessionManager 的「切回」不存在（`switchSession` 恒要文件路径）。

`newSession`（:226-261）/`switchSession`（:196-225）同构：before_switch{cancel} → 备新 manager → teardownCurrent("new"/"resume", 目标文件) → createRuntime（`sessionStartEvent{reason, previousSessionFile}`）→ rebind。

### 1.3 dispose 面（逐字）

`runtime.dispose()`（agent-session-runtime.ts:404-412）——退出路径唯一口：

```ts
async dispose(): Promise<void> {
	await emitSessionShutdownEvent(this.session.extensionRunner, {
		type: "session_shutdown", reason: "quit",
	});
	this.beforeSessionInvalidate?.();
	this.session.dispose();
}
```

`emitSessionShutdownEvent`（runner.ts:258-267）：`hasHandlers("session_shutdown")` 门 ⇒ emit ⇒ 返回是否有人处理。

`AgentSession.dispose()`（agent-session.ts:1393-1414，逐字要点）：try 内 `abortRetry/abortCompaction/abortBranchSummary/abortBash/agent.abort()`（**dispose 必须成功，单个 abort 钩抛也吞**）→ `_extensionRunner.invalidate("This extension ctx is stale after session replacement or reload. …")`（**:1405**——替换/重载后旧扩展 ctx 调用即抛的防御）→ 断 agent 连接、清事件监听、cacheWarmer 取消、`cleanupSessionResources(sessionId)`。

### 1.4 `session_start` 的发射机制（agent-session.ts:3258-3281，逐字）

构造期存载荷（:509：`this._sessionStartEvent = config.sessionStartEvent ?? { type: "session_start", reason: "startup" }`）；发射在模式层绑定点：

```ts
async bindExtensions(bindings: ExtensionBindings): Promise<void> {
	// …uiContext/mode/commandContextActions/abortHandler/shutdownHandler/onError 逐项绑…
	this._applyExtensionBindings(this._extensionRunner);
	await this._extensionRunner.emit(this._sessionStartEvent);
	this._extensionRunner.reportUnhandledMcpServers();
	await this.extendResourcesFromExtensions(
		this._sessionStartEvent.reason === "reload" ? "reload" : "startup");
}
```

模式层经 `setRebindSession` 把 `bindExtensions` 挂进替换序列：interactive-mode.ts:617-620（`rebindCurrentSession{renderBeforeBind:true}`＋主题应用）、print-mode.ts:70-76、rpc-mode.ts:313-320。**初始装配与替换后走的是同一个 bindExtensions**——fork 后的会话与全新会话享有完全相同的扩展面。

### 1.5 退出面（逐字）

TUI（interactive-mode.ts:4290-4328）两臂：

```ts
private async shutdown(options?: { fromSignal?: boolean }): Promise<void> {
	if (this.isShuttingDown) return;
	this.isShuttingDown = true;
	if (options?.fromSignal) {
		// 信号臂：先 dispose（扩展拆解不写 tty，不能被后续终端恢复失败跳过）…
		await this.runtimeHost.dispose();
		this.themeController.disableAutoSync();
		await this.ui.terminal.drainInput(1000);
		this.stop();
		process.exit(0);
	}
	// 交互 quit（Ctrl+D/Ctrl+C//quit/扩展 shutdown()）：先停 TUI 再发关闭事件，
	// 扩展 UI 清理不能在进程退出时重绘最后一帧。
	this.themeController.disableAutoSync();
	await this.ui.terminal.drainInput(1000);
	this.stop();
	await this.runtimeHost.dispose();
	// …打印 resume 命令…
	process.exit(0);
}
```

RPC EOF：rpc-mode.ts:736 `await runtimeHost.dispose();`（shutdown{quit}）。print 完成：print-mode.ts:42-47 `disposeRuntime()` 幂等包装 `:47 await runtimeHost.dispose()`。

### 1.6 fork 后模型从哪来（agent-session.ts:2477-2490，逐字要点）

```ts
async setModel(model: Model<any>, options: ModelMutationOptions = {}): Promise<void> {
	// …auth 检查…
	this.agent.state.model = model;
	this.sessionManager.appendModelChange(model.provider, model.id);
	if (options.persist) {
		this.settingsManager.setDefaultModelAndProvider(model.provider, model.id);
		// …
	}
	this.setThinkingLevel(thinkingLevel);
	await this._emitModelSelect(model, previousModel, "set");
}
```

pi 的 fork 新运行时**从 settings 重解析**模型——交互层 `/model` 选择以 `{persist:true}`（interactive-mode.ts:6105）落 settings，故实际效果＝fork 携带当前模型。Ctrl+P 轮转 `{persist:false}`（:5203/5346 按 persist 旗标）⇒ 不携带。

---

## §2 java 侧现状与缺口（实读）

### 2.1 fork 三路不过扩展装配（B251 证据）

`AgentSession.java`：

- `forkCopyInternal`（:1463-1479）持久臂：`new AgentSession(harness.fork(), services, args, branchName)`＋`SessionPersistence.attach`；内存臂落 `forkInMemory`。
- `forkFromEntryInternal`（:1498-1513）同形。
- `forkInMemory`（:1540-1547）：`harness.fork()`＋`seedTranscript`＋`new AgentSession(...)`＋`repository.create(forked)`。

三路全部**直 new 私有构造、不过 `loadExtensions`**（:1769-1812）⇒ forked 会话无总线、无 ExtensionManager、无引擎钩桥、无 `extensionFirer`——扩展工具/命令/事件订阅/资源发现整体消失，`session_start{fork}` 无发射点（map/04 D-3 证据列挂此）。

### 2.2 【本批新发现】共享 config 的会话绑定闭包串线

`AgentHarness.fork()`（AgentHarness.java:59-61）逐字＝`return new AgentHarness(config);`——**同一 config 对象**。assemble 期装进 config 的会话绑定闭包全部经 `sessionRef`（:407 的 `AtomicReference<AgentSession>`）指向**父会话**：

| 闭包 | 位置 | fork 后的错位后果 |
|---|---|---|
| `compactionObserver` 两方法 | :429-450 | forked 的压缩 start/end 事件进**父会话**事件流（forked 订阅者/TUI 看不到压缩指示） |
| `retryObserver` 六方法 | :463-511 | forked 的 auto_retry_*/summarization_retry_* 事件同样错流 |
| `retryAborted` | :459-462 | forked 的重试退避读**父会话**中止旗 |
| image policy supplier | :410-413 | forked 的图片策略读父会话 |
| `SessionEnvironment` supplier（**PI_\***） | :416-428 | forked 的 bash 环境 `PI_SESSION_ID`/`PI_SESSION_FILE` 是**父会话身份**——**[模型可见]** |

与 B251 同根（fork 不过 assemble）：五组闭包本是 assemble 为「自己这个会话」现造的，fork 却共享了成品 config。修法即 T1 的重建，不单列 B 号。

### 2.3 切换/退出路径的关闭现状（B252 证据，逐调用点）

| 路径 | 位置 | 现状 |
|---|---|---|
| /new | SessionCommands.java:68-78 | `fireSessionShutdown`（只发事件不关闭） |
| /fork /clone | SessionCommands.java:42-59 | fork 内部 `fireSessionShutdown`，旧会话弃置 |
| /resume | SessionCommands.java:89-101 | `switchTo`（持久路原地；内存路旧对象留存注册表） |
| RPC new_session | RpcDispatcher.java:134-145 | `closeSession()`＝**仅退订**（:401-410）＋`fireSessionShutdown` |
| RPC fork/clone | :263-292 | `closeSession()`（仅退订）后换新 |
| RPC EOF | RpcMode.java:40-58 | `dispatcher.close()` 仅退订；try-with-resources 关的是**原始**会话——替换过的 run 里当前会话永不关、原始会话被关两次（无幂等保护） |
| Web newSession | WebDispatcher.java:143-157 | **已** `closeForReplacement`（全仓唯一关旧会话的切换路径） |
| Web fork/clone | :346-380 | 仅退订换新（注释自承「不关旧会话」:345） |
| Web 断开 | :126-133 | 已 `session.close()`（quit） |
| TUI 退出 | PiTuiApp.java:500-513 | 不碰 session ⇒ `session_shutdown{quit}` 在 TUI **永不发射**（map/04 D-10 证据列挂此） |
| TUI 切换 | PiTuiApp.java:487-498 | 只换引用重订阅 |

### 2.4 原地 attach 的旧 storage 泄漏

`SessionPersistence.attachOpened`（:79-96）：`owner.session(opened)` 直接覆盖字段——`switchTo`/`findSession` 持久路把**上一个持久会话的 storage Session**（JDBC/文件句柄）丢弃不关；覆盖前也不 `persistPending`（未落盘写入依赖下一次 flush 撞运气）。

### 2.5 `closeWith` 现状（:1742-1756）

```java
private void closeWith(String reason, String targetSessionFile) {
	fireSessionShutdown(reason, targetSessionFile);
	abortRetry();
	if (session != null) { SessionPersistence.persistPending(this, session, laneName()); session.close(); }
	if (persistentRepository != null) { persistentRepository.close(); }
	services.settings().flush();
	harness.close();
}
```

三个不适配：① **非幂等**——二次 close 重发 shutdown 事件、双关句柄；② 无 `abort()`——pi teardown/dispose 第一步是 settle 在途 turn（:168/:1395-1401），java 只 abortRetry；③ `persistentRepository.close()` 无条件——**fork 的 copy 与父共享同一 handle**（:1469/:1506），父关即杀子。

### 2.6 扩展 UI 的传递断点

`loadExtensions` 把 `ExtensionUI` **烘进** `DefaultExtensionContext`（DefaultExtensionContext.java:33/:41-47）；`AgentSession.extensionUI(ui)` 注入（:1271-1276）只写会话字段、不回灌 context。RpcDispatcher 仅在构造时注入一次（:70）⇒ 即使只修 B251 不修传递，fork 后 RPC 模式的扩展 UI 仍是 noop。

---

## §3 设计

### T1（B251＋§2.2 串线）：fork 装配重建

#### 3.1 `wireSession` 抽取

把 assemble（:321-594）从「解析 effective」到「branchSummarization 装好」的**harness＋会话绑定装配**整段抽为私有静态方法，`assemble` 与 fork 三路共用：

```java
/**
 * docs/16（T1）：assemble 与 fork 共用的「会话＋harness」装配——pi createRuntime
 * （agent-session-runtime.ts:29-42 工厂型）对位：每次替换都重建 cwd 绑定服务与
 * 会话绑定闭包，只共享真正的进程级件。**不加载扩展**（loadExtensions 由调用方
 * 按 pi 的序另发——初始装配立即、fork 在旧会话 teardown 后，§3.4）。
 *
 * @param carryModel     非 null ⇒ 携带父会话当前模型（fork 臂，裁决①）；
 *                       null ⇒ 按 args/settings/scope 解析（初始装配臂）
 * @param carryThinking  同上，携带思考级别
 */
private static AgentSession wireSession(Args args, SettingsManager settings,
        ProviderRegistry providers, ToolContext toolContext,
        SessionRepository<?, ?, ?> harnessRepository,
        boolean existingSession, String name,
        String carryModel, ThinkingLevel carryThinking) {
	var effective = settings.effective();
	var catalog = com.pijava.ai.provider.ModelsJsonConfig.allModels();
	var models = new DefaultModelResolver(catalog);
	var scope = ModelScoping.resolve(args, settings, catalog);
	// fresh 注册表（裁决②）：扩展在 fork 两侧重复注册不叠加、旧会话的扩展
	// 工具/命令不泄漏进 forked——pi 每运行时 fresh services 的对位。
	var tools = new ToolRegistry(null);
	var selection = SessionSetup.resolveToolSelection(args, settings);
	var toolList = ToolSetFactory.createAllTools(commandPrefix).stream()
		.filter(t -> selection.isAllowed(t.name())).toList();
	tools.registerAll(toolList);
	// …promptTemplates 加载（同 assemble 现状）…
	var services = new SessionServices(settings,
		new TrustManager(effective.defaultProjectTrust), providers, models, tools,
		CommandRegistry.withBuiltins(), harnessRepository, promptTemplates);
	// 模型：carryModel != null ⇒ models.resolve(carryModel 拆出的 pattern/provider)；
	// null ⇒ 走 assemble 现状（scopedInitial/args/effective 解析链，:372-402）。
	// …streamFn/telemetry/两 observer/policyToolContext（sessionRef 全部现造）…
	// …harness 构建（.thinkingLevel(carryThinking != null ? carryThinking
	//      : SessionSetup.thinkingLevelFor(args, effective.defaultThinkingLevel, scopeLevel))）…
	var agentSession = new AgentSession(harness, services, args, name);
	sessionRef.set(agentSession);
	agentSession.setScopedModels(scope.models());
	agentSession.modelCatalog = catalog;
	agentSession.modelResolver = models;
	agentSession.branchSummarization = new BranchSummarization(...现造...);
	agentSession.baseToolContext = toolContext;   // 新字段：fork 重建的原料（§2.2）
	return agentSession;
}
```

`assemble(...)` 瘦身成：参数归一 → `wireSession(args, settings, providers, toolContext, repository, existingSession, args.name()!=null?args.name():"session", null, null)` → `persistentRepository` 赋值 → `loadExtensions(..., startInfo)`（初始装配仍立即，pi 初始序）。行为对现有调用方零变化。

携带/重解析清单（裁决①）：

| 件 | fork 臂来源 | 理由 |
|---|---|---|
| model / thinkingLevel | **携带**父会话 `harness.getModel()`/`getThinkingLevel()` | java 的 setModel 无 persist 面（§1.6 的 settings 落盘不存在）；携带＝pi persist 臂的实际效果 |
| systemPrompt / appendSystemPrompt / guidelines | 重解析 args（`SessionSetup.customPromptFor` 等） | pi 新运行时重解析；`before_agent_start` 覆盖由扩展在 forked 首轮重发（D11 语义延续） |
| activeTools / queue modes / skills / 上下文文件 | 重解析 args/settings（现状代码路径） | pi createRuntime 语义；扩展经 `start{fork}` 重激活 |
| settings / providers / 两侧 repository / promptTemplates | **共享** | 进程级件；providers 共享是注入缝所限（窄差 D30） |
| ToolRegistry / CommandRegistry / TrustManager / harness（含 SkillManager） | **fresh** | 扩展注册不叠加、不泄漏（裁决②） |

#### 3.2 fork 全序（pi §1.2 逐字序）

```java
public Optional<AgentSession> forkCopy(String branchName) {
	var leafId = harness.snapshot(laneName()).leafId();
	if (extensionFirer != null && extensionFirer.sessionBeforeFork(leafId, "at")) {
		return Optional.empty();
	}
	var previousId = sessionId();
	var copy = forkCopyInternal(branchName);                 // T1 重建（无扩展）
	closeForFork(copy.sessionId());                          // T2：teardown 保共享句柄（§3.6）
	loadExtensions(args, copy.services(), copy.harness,      // T1：pi rebind→bindExtensions 对位
		extensionUI, copy, new SessionStartInfo("fork", previousId));
	return Optional.of(copy);
}
```

`forkFromEntry` 同形（before_fork{entryId,"before"}）。`forkCopyInternal`/`forkFromEntryInternal`/`forkInMemory` 的 `new AgentSession(harness.fork(), ...)` 全部改 `wireSession(args, services.settings(), services.providers(), baseToolContext, <harnessRepository>, true, branchName, harness.getModel() 的 "provider/id"，harness.getThinkingLevel())`；持久臂随后照旧 `persistentRepository.fork(...)`＋`attach`，内存臂照旧 `seedTranscript`＋`repository.create(forked)`。`harness.fork()` 自此无引用——**删除**（AgentHarness.java:59-61；先查测试引用，有则同步改）。

#### 3.3 序的取舍（裁决③）

pi 逐字序＝shutdown{fork} → start{fork}（§1.2）。上表代码即此序：`loadExtensions`（内含 start 发射）放在 `closeForFork` **之后**。失败窗与 pi 同形（teardown 后 loadExtensions 抛 ⇒ 旧会话已拆、新会话半成品——pi 的 createRuntime 抛同样如此），不额外防御。

#### 3.4 `loadExtensions` 的两处补

- `ExtensionUI` 参数改传**当前会话的 `extensionUI` 字段**（mode 层注入值）而非 noop——forked 的 context 与会话字段一致，RPC fork 后扩展 UI 不再丢（§2.6 同步修：方法内补 `owner.extensionUI = ui`，初始装配 noop→RPC 注入的既有顺序不变）。
- `SessionStartInfo("fork", previousId)`：`session_start` 载荷的 `previousSessionFile` 装**会话 id**（窄差 D14 的既有裁决延续——SQLite/JSONL 仓无文件路径面）。

### T2（B252）：teardown 集中

#### 3.5 `closeWith` 三参化（abort 对位＋句柄开关＋幂等）

```java
/** docs/16（B252）：替换/退出关闭幂等门。 */
private boolean closed;

public void close() { closeWith("quit", null, true); }

public void closeForReplacement(String reason, String targetSessionFile) {
	closeWith(reason, targetSessionFile, true);
}

/** docs/16：fork 臂拆解——persistentRepository 句柄与 services 由 forked 继承，不关。 */
private void closeForFork(String forkedSessionId) {
	closeWith("fork", forkedSessionId, false);
}

/**
 * pi teardownCurrent（agent-session-runtime.ts:167-178）＋ dispose
 * （agent-session.ts:1393-1414）对位：abort 在途（让被中止的 turn 落盘到出站
 * 会话）→ shutdown 事件 → 关独占资源。幂等：二次调用零效果。
 */
private void closeWith(String reason, String targetSessionFile, boolean closeHandle) {
	if (closed) {
		return;
	}
	closed = true;
	abort();                                 // pi :168/:1395-1401 第一步（含 abortRetry）
	fireSessionShutdown(reason, targetSessionFile);
	if (session != null) {
		SessionPersistence.persistPending(this, session, laneName());
		session.close();
	}
	if (closeHandle && persistentRepository != null) {
		persistentRepository.close();
	}
	services.settings().flush();
	harness.close();
}
```

`abort()` 入列是行为变化（quit/替换路径现在在途 turn 会被 settle）——pi 逐字，本批对齐（裁决⑤）。

#### 3.6 /new 三调用点改 `closeForReplacement`

- SessionCommands.java:75：`fireSessionShutdown("new", fresh.sessionId())` → `closeForReplacement("new", fresh.sessionId())`。
- RpcDispatcher.java:134-145：`closeSession()` 改名 `unsubscribe()`（只剩退订语义），`old.fireSessionShutdown(...)` → `old.closeForReplacement("new", session.sessionId())`。
- RpcDispatcher Fork/Clone（:270/:286）：`closeSession()` → `unsubscribe()`（旧会话关闭已收进 fork 内部，§3.2）。
- WebDispatcher forkFrom/cloneSession（:357/:375）：自动获得 fork 内部 teardown（注释 :345 改写）。

**/new 的序差 D20 保留**（裁决④）：`createReplacing` 内 assemble 仍立即 `loadExtensions`（start{new} 先于旧侧 shutdown{new}）——把 start 推迟到 teardown 后需要把 createReplacing 改成「半成品返回」的公共 API，为一条 [内部] 窄差不值；fork 臂无此问题（fork 方法是实例方法，序自然可控）。

#### 3.7 原地 attach 关旧 storage

`SessionPersistence.attachOpened`（:79-96）`owner.session(opened)` 之前插：

```java
// docs/16（B252）：原地 attach 关旧 storage——先 persistPending（未落盘写入
// 不丢），再关 JDBC/文件句柄。fork 臂 copy.session 已先置为同一对象 ⇒ 恒等跳过。
var previousStorage = owner.session();
if (previousStorage != null && previousStorage != opened) {
	SessionPersistence.persistPending(owner, previousStorage, owner.laneName());
	previousStorage.close();
}
owner.session(opened);
```

内存路 `switchTo` 旧会话对象**不关闭**（窄差 D25——内存注册表的会话对象即存储本体，关即毁数据；pi 内存态无切回面，§1.2 ③）。

#### 3.8 TUI /resume 与树选择器

均走 `switchTo`（持久路）⇒ §3.7 自动覆盖；`TreeSelectorScreen` 的 `forkFromEntry` ⇒ §3.2 自动覆盖。两调用点零改动。

### T3：退出面

#### 3.9 两处

`PiTuiApp.exit()`（:500-513）末尾加（pi 交互 quit 臂：先停 TUI 后 dispose，§1.5）：

```java
// docs/16（B252）：pi interactive shutdown 交互臂（interactive-mode.ts:4316-4322）
// ——先停 UI 再 dispose（shutdown{quit} 自此在 TUI 发射）。关的是**当前**会话
// （this.session 随 switchSession 跟踪替换）。
try {
	session.close();
} catch (Exception ignored) {
	// 退出期关闭失败忽略（pi dispose 的 try/catch 同旨，:1394-1402）
}
```

`RpcDispatcher`：`closeSession()` 拆分——`unsubscribe()`（退订）供替换路径；`close()`（:336-338）改为 `unsubscribe()`＋`session.close()`（**当前**会话，pi rpc-mode.ts:736 对位）。RpcMode 的 try-with-resources 关原始会话在幂等门下无害（已关则 no-op），保留不动。

---

## §4 裁决条目

### 裁决① fork 携带 model/thinkingLevel、其余重解析

- 【场景】pi 的 fork 新运行时从 args/settings 重解析一切；`/model` 选择以 persist 落 settings（§1.6）故模型实际携带。java 的 setModel 无 persist 面，重解析会丢运行时模型切换（对 java 现状是回退）。
- 【决定】model/thinkingLevel 由 fork 显式携带父会话当前值；systemPrompt/activeTools/skills/queue modes 按 args/settings 重解析（扩展经 `start{fork}` 重激活，pi createRuntime 语义）。
- 【风险/代价】[内部]：Ctrl+P 式未持久选择在 pi 本就不携带，java 携带略宽于 pi——实际效果与 pi persist 臂一致，登记窄差 D24。

### 裁决② fork 重建 fresh ToolRegistry/CommandRegistry，共享 providers

- 【场景】`SessionServices` 现状整体共享；`loadExtensions` 会把扩展工具/命令注册进 `services` 的注册表。若 fork 继续共享，每 fork 一次扩展工具/命令叠加一份，且旧会话的扩展注册泄漏进 forked。
- 【决定】`wireSession` 每会话新建 ToolRegistry（内置件按 selection 重注册）与 CommandRegistry（`withBuiltins`）；providers 注册表共享（初始装配的注入缝——测试注入 FauxProvider——无复制面）。
- 【风险/代价】[内部，边缘]：扩展在 fork 两侧重复注册 **provider** 会叠加（窄差 D30）；pi 每运行时 fresh services，java  providers 例外共享。

### 裁决③ fork 序按 pi 逐字：teardown → start{fork}

- 【场景】pi 的 fork 先 teardownCurrent（含 shutdown{fork}）再 createRuntime＋rebind（start{fork}）。
- 【决定】`forkCopy`/`forkFromEntry` 内：before_fork → 重建 copy（无扩展）→ `closeForFork` → `loadExtensions`（start{fork}）。
- 【风险/代价】边缘场景：loadExtensions 抛 ⇒ 旧已拆新未成——pi 同形失败窗，不额外防御。

### 裁决④ /new 保留 start 先于 shutdown（D20 不销）

- 【场景】`createReplacing` 的 assemble 内立即 `loadExtensions`（start{new} 先发射）；pi 是先 teardown 后 start。
- 【决定】维持现状（D20 保留在册）。推迟 start 需要「半成品会话」公共 API，为一条 [内部] 窄差不引入。
- 【风险/代价】[内部]：扩展观察到的 start/shutdown 序与 pi 反——D20 已登记，等级不变。

### 裁决⑤ 关闭路径补 abort()（pi 逐字）

- 【场景】pi teardownCurrent/dispose 第一步 settle 在途 turn；java `closeWith` 只 abortRetry。
- 【决定】`closeWith` 首步 `abort()`（含 abortCompaction/abortBranchSummary/abortRetry/置中止旗）。
- 【风险/代价】[用户可见，边缘]：退出/替换时在途 turn 产生 abort 事件与落盘记录——pi 同行为。

### 裁决⑥ close 幂等

- 【场景】RPC try-with-resources 关原始会话＋dispatcher.close() 关当前会话；替换过后两者可能是同一对象也可能不是。
- 【决定】`closed` 门：二次 close 零效果（不重发事件、不双关句柄）。
- 【风险/代价】无影响。

---

## §5 窄差表（本批新增，接续批 5 的 D9–D23）

| # | 内容 | 等级 |
|---|---|---|
| D24 | fork 携带当前 model/thinkingLevel（pi 经 persist→settings 重解析，效果同；java 无 persist 面故直接携带）；systemPrompt/activeTools/skills 重解析＋扩展 `start{fork}` 重激活（pi createRuntime 语义） | [内部] |
| D25 | 内存路 `switchTo` 旧会话对象不关闭（内存注册表的会话对象即存储本体，关即毁数据；pi 内存 SessionManager 无切回面——`switchSession` 恒要文件路径） | [内部] |
| D26 | 内存 CLI `--fork` 经 `forkCopy` ⇒ 发射 `start{fork}`（pi CLI fork 恒 `startup`；持久路不过 forkCopy＝startup，与 pi 一致） | [内部，测试路] |
| D27 | 内存 CLI `--fork` 的初始会话空转 `start{startup}` 后被弃置不 close（两步装配的存量形状；持久路无此问题） | [内部，测试路] |
| D28 | 内存 fork/clone 后旧会话已关但仍列在内存注册表（`/resume` 选到 ⇒ 拿到 closed 会话）；pi 内存态无切回面 | [内部，边缘] |
| D29 | 无 `extensionRunner.invalidate` 对位（pi dispose :1405 的 stale ctx 防御）；java 会话关闭后扩展持有的旧 ctx 仍可调共享 services | [内部，边缘] |
| D30 | providers 注册表 fork 间共享（初始装配注入缝无复制面）；扩展在 fork 两侧重复注册 provider 会叠加 | [内部，边缘] |

**销号**：**B251**（fork 三路全走 wireSession＋loadExtensions；`start{fork}` 发射＋§2.2 五组闭包串线同修）、**B252**（/new、fork/clone、TUI 退出、RPC EOF、原地 attach 旧 storage 五面全落；`session_shutdown{quit}` 在 TUI/RPC 退出路径发射）。

---

## §6 测试计划（新夹具 4 件，复用批 4/5 的扩展注入缝）

| 夹具 | 用例 |
|---|---|
| `ForkExtensionWiringTest`（T1） | ① 测试扩展（`on(SESSION_START)` 记录＋注册工具＋注册命令）下 `forkCopy` ⇒ copy 收到 `start{reason:"fork", previousSessionFile:源 id}`；② 序断言：源侧 `shutdown{fork, copy id}` **先于** copy 的 `start{fork}`（pi §1.2 序）；③ copy 的 harness toolRegistry 含扩展工具、CommandRegistry 含扩展命令，且**各一份**（fresh 注册表）；④ 二次 fork 无叠加；⑤ `--no-extensions` ⇒ 零 firing、fork 行为不变 |
| `ForkWiringIsolationTest`（T1/§2.2） | ① fork 后 copy 触发压缩 ⇒ CompactionStart/End 进 copy 订阅流、**不进**源会话订阅流；② copy 的 `toolContext` sessionEnvironment ⇒ copy 的 sessionId/sessionFile（PI_\* 身份修真）；③ copy 的重试中止旗独立于源 |
| `SessionTeardownTest`（T2） | ① `closeForFork`：源 harness closed、共享 handle 仍活（copy 落盘成功）；② `closeForReplacement`（/new RPC 路）：旧会话关闭＋shutdown{new, 新 id} 恰好一次；③ 幂等：二次 close 零事件零异常；④ close 首步 abort：在途 run 被 settle；⑤ 原地 attach：旧 storage 关闭前 persistPending 被调（计数缝） |
| `ExitPathCloseTest`（T3） | ① `PiTuiApp.exit()` ⇒ 当前会话关闭、`shutdown{quit}` 发射（firer 记录缝，复用批 5 夹具手法）；② 替换后 exit ⇒ 关的是**新**会话；③ RPC EOF ⇒ dispatcher 当前（替换后）会话关闭 |

回归：coding-agent 全量＋tui/web（`-am` 连带 agent-core/ai——批 4 教训：不带 `-am` 会撞旧构件假绿）。

---

## §7 验证口径

1. `mvn test -pl pi-java-coding-agent -am`（新夹具＋既有 758 全绿）。
2. `mvn test -pl pi-java-tui,pi-java-web -am`。
3. 收口 **全量 `mvn clean verify`**（spotbugs 在 verify 阶段——agent-core 批 4 教训；`RichSessionListTest` 存量红按批 5 偏差⑨排除，`-Dtest='!RichSessionListTest'`＋`failIfNoSpecifiedTests=false`）。
4. `mvn checkstyle:check`；无 `System.out.println` 残留；文件行数：`AgentSession.java` 现 1,841 ⇒ 本批 ≤ 1,990（E1 在册不扩大）。
5. commit 粒度：T1（wireSession 抽取＋fork 重建＋harness.fork() 删除）→ T2（closeWith 三参＋调用点＋attach）→ T3（退出面）→ docs 回填（本稿状态行＋map/04 D-3/D-10 摘帽＋docs/04 销号＋docs/05 日期）。

---

## §8 对账表

| 项 | 基线 | 闭环后 | Δ |
|---|---|---|---|
| map/04 D-3 `session_start` 证据 | 「fork 臂挂 B251」 | 「fork 臂批 6 落（docs/16）」 | 摘帽 |
| map/04 D-10 `session_shutdown` 证据 | 「TUI 退出不 close 是存量（B252）」 | 「TUI 退出/RPC EOF 批 6 落（docs/16）」 | 摘帽 |
| 台账 B251 / B252 | 开放 | **销号** | −2 开放项 |
| D 域加权 | 66.0/83＝79.5% | 66.0/83＝79.5% | 0（本批为缺陷闭环，无行翻转——同批 3 口径） |
| 模块加权 | 283.5/496≈57.2% | 283.5/496≈57.2% | 0 |
| 窄差 | D9–D23 | D9–D30（+D24–D30） | +7（均内部/边缘） |
| 引擎钩子数 | 15 | 15 | 0 |
