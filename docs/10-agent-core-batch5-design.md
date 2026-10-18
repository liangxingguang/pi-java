# 25 agent-core 批 5 设计 —— 存疑 22 条全收编，目标 100%

> **状态：✅ 已闭环（2026-10-18）**——12＋1 commits（§15 十二计划＋S16 对拍验收一个）；agent-core **180/180 = 100%**（未加权 90/90），存疑清零，全仓 882.5/1381 ≈ 63.9%；回填 map/03＋docs/04＋docs/05＋docs/07。
> 基线：pi `ea448f454`（2026-10-11）；pi-java main（批 4 闭环后，`c5f0f06c`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树。
> 前批：`24-agent-core-batch4-design.md`（已闭环）。搁置中：`26-coding-agent-batch1-design.md`（B236，待审核，本批不动它）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围（用户 2026-10-18 选定） | map/03 现存**全部 22 条存疑**（w2 九条 #14/#53/#57/#60/#75/#77/#80/#82/#84，w1 十三条 #56/#81/#83/#85/#87/#89/#90/#91/#92/#93/#94/#95/#96）——逐条收编：认账的做，不认的裁决，参照物没了的作废 |
| 基线账面 | agent-core 166.5/182 = **91.5%**（未加权 69/91 = 75.8%）；全仓 869.0/1383 ≈ 62.8% |
| 目标账面 | agent-core **180/180 = 100%**（21 条翻对齐 +14.5、#60 R1 出分母 Σw −2）；未加权 **90/90 = 100%**；全仓 **882.5/1381 ≈ 63.9%** |
| 重件 | #57 bashExecution 面（§3，Entry 变体＋编解码＋投影＋`recordBashResult`＋流中暂存）；#95 字节精确假 shell（§9，A14）；#94 拆两超限文件（§8，E3/E4） |
| 中件 | #77 navigateTree 工具恢复（§4）；#75 fork 调用面小修（§5）；#87 夹具对称（§6）；#81 工具线程安全审计（§10，A10） |
| 小件 | #89 源序夹具、#96 错误路夹具（A15）、#85 兜底夹具、#92/#93/#91 javadoc、#83 清点（§7/§10/§11） |
| 裁决类（证据已齐，无代码） | #14（F1）、#53（**B49 销号**）、#80（A5 收尾）、#82（旧 A7）、#84（F4）、#56（A1）、#90（C3）、#91（C4）——§12 |
| 作废 | **#60 R1**（`PendingEntry`/`InboxItem` 是 Pico 实验 harness 类型，随 pi `7fd478a2e` 整包删除，Σw 182→180）——§13 |
| 销号 | **B49**；旧字母编号 F1/F2/F4/C2/C3/C4/D2/D3/D6/E8/A1/A2/A5/A7/A10/A14/A15 全收尾（docs/07 §2 表随之清空） |
| 不碰 | E1（AgentSession 1,554 行—— coding-agent 层，随 coding-agent 批）；B236（docs/11 已搁置）；B244/B245；扩展事件 D 域；TUI 渲染 |

**落点**：主战场 pi-java-agent-core；#57 的 `recordBashResult` 与 #77 的 navigateTree 接线在 pi-java-coding-agent；#87 一侧在 `conformance/pi/run.test.ts`（pi twin）。

---

## 1. 裁决总表（22 条）

| # | w | 判定 | 一句话证据 | 节 |
|---|---|---|---|---|
| #14 QueueMode.All 宿主语义 | 2 | **裁决·对齐** | drain 语义 `QueueManager.java:144-160` ≡ pi `agent.ts:156-170`；默认 one-at-a-time 两侧同；运行时 setter 面齐（`AgentHarness.java:232/242`＋RPC）；pi 设置改动经 reload 生效、java 无 reload 面 ⇒ 净效果一致 | §12.1 |
| #53 turn_end 颗粒度 | 2 | **裁决·对齐**（**B49 销号**） | pi 会话事件 union 含引擎逐回合 `turn_end{message,toolResults}`（`agent-session.ts:196-240`＋`agent-loop.ts:253,287`）；java 批 2 起 `PiLoopRunner.java:134,162` 逐回合真发＋`SessionRunner.java:229-230` 透传＋`JsonEventMapper.java:75-82` 线形逐字。B49 原文（「AgentSettled 每驱动一条」）描述的是批 2 前状态 | §12.2 |
| #57 四个消息类型 | 2 | **做** | bashExecution 面缺失：java `executeBash` 不记录进会话（pi `recordBashResult` `agent-session.ts:3878-3899`）；pi 会话文件里 `type:"message"` + `role:"bashExecution"` 的条目 java **解不开**（互读断）；`excludeFromContext` 入参被丢弃。branchSummary/compactionSummary/custom 三面 java 已有对位（Entry 变体＋投影） | §3 |
| #60 PendingEntry/InboxItem | 2 | **R1 作废** | 现锚点全仓零命中；两者是 Pico 实验 harness 类型，随 `7fd478a2e feat(agent): remove the experimental harness from pi-agent-core` 删除（R1 同批） | §13 |
| #75 session fork | 2 | **做**（小修） | 调用面三差：① `--fork` 分支不消费 `--session-id`（pi `main.ts:383` 组合支持）；② java 私加 `" (fork)"` 名后缀（pi 无）；③ 源 empty/no-header 校验文案（pi `session-manager.ts:1820-1826`）。`parentSession` 值形（pi=路径 vs java=id）是已裁决的 v4 方言（`legacyParentSessionPath` 保留互读），不再动 | §5 |
| #77 resume seed/replay | 2 | **做** | pi `_restoreToolsFromTranscript`（`agent-session.ts:1809-1820`）在 **navigateTree 后**按新叶系统消息 `toolsAdded` 恢复激活装配（`:4138`）；java `navigateTree` 只重建消息不恢复工具。resume 侧已核：pi CLI 装配恒给 `initialActiveToolNames`（`sdk.ts:294`）⇒ 构造器恢复臂不可达，java resume 不恢复 ≈ pi | §4 |
| #80 streamingBehavior 两门 | 2 | **裁决·对齐** | 两门抛文逐字（pi `:1988`/`:2016` = `PromptRouter.COMPACTING_MESSAGE`/`ALREADY_PROCESSING_MESSAGE`）；展开先于门（pi `:1997-2011` → `:2012`，java `AgentSession.java:808` → `:812`）；steer/followUp 排队＋`queue_update`（B34）齐 | §12.3 |
| #82 retry.provider ↔ RetryPolicy | 2 | **裁决·对齐** | 三键映射逐项同：`maxRetries` 缺席 ⇒ **0**（pi `provider-retry.ts:106` `?? 0` = java `DefaultProviders.java:330-331`）；`maxRetryDelayMs ?? 60000`（pi `settings-manager.ts:925-931` = java `:332-333`）；`timeoutMs` 明给则替换默认（pi `sdk.ts` buildRequestOptions = java `:345-346`） | §12.4 |
| #84 两门行为裁决（F4） | 2 | **裁决·对齐** | 即 #80 的裁决本体：压缩窗关窗抛（B175）≡ pi `:1988` 同款抛；java 压缩窗语义来自包 25–27（B175/B176），pi 无窗概念但主流等价（压缩单飞＋门抛） | §12.3 |
| #56 EntryProjector | 1 | **裁决·对齐** | pi `ProjectedSessionEntry{sourceEntry,messages}`（`session-manager.ts:208-213`）≡ java `ContextEntries.ProjectedEntry{source,messages}`（`:152-158`）；`buildSessionProjection`（`:543-575`）≡ `projectEntries`（`:161-199`——含「只有下标 0 的 compaction 贡献摘要」同款守卫）。`thinkingLevel/model` 便捷字段 java 走自有访问器（窄差免登：无消费者） | §12.5 |
| #81 工具线程安全契约 | 1 | **做**（审计 A10） | pi 契约：并行模式下工具并发执行（`agent-loop.ts:508-520`）；java 无显式审计。逐工具列共享可变状态表＋钉契约注释；挖出真问题则修或登记 | §10.1 |
| #83 transcript() 消费者清点 | 1 | **做**（清点 A2） | `AgentHarness` 无公开 transcript()；清点转录读取面（entries()/navigateTree/fork/export/RPC/web 快照）出表，无未核消费者 ⇒ 销号 | §10.2 |
| #85 recordEvent 无宿主跨度兜底 | 1 | **做**（小） | 兜底已在：`JsonlFileTelemetry.recordEvent:174-188` 无当前跨度 ⇒ 事件照写、无 `spanId` 键；补 javadoc＋夹具钉住 | §11.1 |
| #87 L5 夹具 thinking 块 | 1 | **做** | 不对称坐实：pi twin `ScriptedStream`（`conformance/pi/run.test.ts:209-260`）对 thinking 块**静默丢弃**（forEach 无 thinking 分支）；java `ScriptedStreams.java:99-103` 发 ThinkingStart+ThinkingEnd 帧。同一份剧本两侧帧列不同 ⇒ 修平 | §6 |
| #89 批内 join 源序 | 1 | **做**（夹具 C2） | 代码已对（`PiLoopTools.java:149-189` 完成序/源序分家，pi `Promise.all` 对位注释在）；缺受控延时夹具钉「end 帧完成序、结果与 terminate 源序」 | §7.1 |
| #90 transcript 可见性 | 1 | **裁决·对齐** | 写者唯一（驱动线程经 `lane.appendEntry`，lane 锁内）；读者走快照（`LaneSnapshot` COW——records COW 裁决同族）；无锁读面不存在。留痕 | §12.6 |
| #91 reset 不 close runSpan | 1 | **裁决·对齐** | `finishRun` 在 `PiLaneEngine.java:217-231` 的 **finally** 收口（含 error 路 `:231`）⇒ 空闲时 runSpan 必已关；`RunLifecycle.java:250` 的置 null 是防御性。补 javadoc 一句 | §12.7 |
| #92 遥测 javadoc | 1 | **做**（小） | `TelemetryContext.java:16,40,47` 三处语义注释过期（D2/D3） | §11.2 |
| #93 SummaryGenerator javadoc | 1 | **做**（小） | `:9-12` 仍写「truncating() 是 Phase 6 前占位」；真路 `LlmSummaryGenerator` 已接。查 `truncating()` 消费者：无 ⇒ 删占位；有 ⇒ 改 javadoc（D6） | §11.3 |
| #94 文件超限 | 1 | **做** | `PiLaneSink` 562 行 / `AgentHarness` 595 行（>500 红线，E3/E4）。行为不改编排拆分 | §8 |
| #95 shell 夹具无牙 | 1 | **做**（A14） | `ShellOutputStreamingTest` 非字节精确：多字节字符跨 8 KiB 边界的用例表达不了。做字节级假 shell＋过界用例；若泵出替换符/丢字则修泵 | §9 |
| #96 错误路 AgentSettled 夹具 | 1 | **做**（A15） | 错误回合带 toolResults 时 `AgentSettled`/TurnEnd 形状无断言守护（`SessionRunner.java:196`、`RunFailure.java:62`） | §7.2 |

---

## 2. 本批不动的既有判定（复核确认）

- `parentSession` 值形（pi 写源**路径** `session-manager.ts:1854`；java 写源 **id**＋`legacyParentSessionPath` 保留互读，`JsonlCodec.java:133-143`）——v4 头方言，前批已裁决，#75 不重开。
- resume 不恢复工具装配——pi CLI 装配恒给 `initialActiveToolNames`（`sdk.ts:294` 恒非 undefined；`createAgentSessionFromServices` 委托同路，`agent-session-services.ts:214-232`）⇒ 构造器恢复臂在 CLI 不可达；java resume 不恢复 ≈ pi CLI。#77 只做 navigateTree 臂。

---

## 3. #57：bashExecution 面（最大件，w2 存疑 → 对齐）

### 3.1 pi 参照（逐字）

**消息类型**（`core/messages.ts:29-42`）：

```ts
export interface BashExecutionMessage {
	role: "bashExecution";
	command: string;
	output: string;
	exitCode: number | undefined;
	cancelled: boolean;
	truncated: boolean;
	fullOutputPath?: string;
	timestamp: number;
	/** If true, this message is excluded from LLM context (!! prefix) */
	excludeFromContext?: boolean;
}
```

**记录点**（`core/agent-session.ts:3878-3899`）：

```ts
	recordBashResult(command: string, result: BashResult, options?: { excludeFromContext?: boolean }): void {
		const bashMessage: BashExecutionMessage = {
			role: "bashExecution",
			command,
			output: result.output,
			exitCode: result.exitCode,
			cancelled: result.cancelled,
			truncated: result.truncated,
			fullOutputPath: result.fullOutputPath,
			timestamp: Date.now(),
			excludeFromContext: options?.excludeFromContext,
		};

		// If agent is streaming, defer adding to avoid breaking tool_use/tool_result ordering
		if (this.isStreaming) {
			// Queue for later - will be flushed on agent_end
			this._pendingBashMessages.push(bashMessage);
		} else {
			this.sessionManager.appendMessage(bashMessage);
			this._refreshFinalizedContext();
		}
	}
```

**持久化**：`sessionManager.appendMessage`（`session-manager.ts:1204-1214`）——`type:"message"` 条目、`message.role:"bashExecution"`（**不是独立条目类型**）。

**LLM 文本形**（`messages.ts:82-99`）：

```ts
export function bashExecutionToText(msg: BashExecutionMessage): string {
	let text = `Ran \`${msg.command}\`\n`;
	if (msg.output) {
		text += `\`\`\`\n${msg.output}\n\`\`\``;
	} else {
		text += "(no output)";
	}
	if (msg.cancelled) {
		text += "\n\n(command cancelled)";
	} else if (msg.exitCode !== null && msg.exitCode !== undefined && msg.exitCode !== 0) {
		text += `\n\nCommand exited with code ${msg.exitCode}`;
	}
	if (msg.truncated && msg.fullOutputPath) {
		text += `\n\n[Output truncated. Full output: ${msg.fullOutputPath}]`;
	}
	return text;
}
```

**convertToLlm**（`messages.ts:147-160`）：`excludeFromContext` ⇒ 不产消息（`!!` 前缀）；否则转 user 文本消息（`timestamp: m.timestamp`）。

**压缩域**（`compaction.ts:337-340,355-361,369-375`）：token 估算 = `(command+output) chars / 4`；`isCutPointMessage`/`isTurnStartMessage` 均含 bashExecution。

**流中暂存**（`agent-session.ts:421,3925-3940`）：`_pendingBashMessages` 在 agent_end 时 flush（保 tool_use/tool_result 次序）。

### 3.2 差距清单

| # | 面 | java 现状 | pi |
|---|---|---|---|
| G1 | 记录 | `AgentSession.executeBash:1404-1417` 只发事件，**不进会话**；`excludeFromContext` 入参丢弃 | `recordBashResult` 追加＋刷新上下文 |
| G2 | 持久化互读 | `EntryJsonCodec` 走 `MessageJsonCodec.decode`，`role:"bashExecution"` 撞 default ⇒ **DecodeError**（pi 会话文件带 `!` 记录即断读） | `type:"message"`＋role 判别 |
| G3 | 投影 | 无（无此条目概念） | user 文本（`bashExecutionToText`），`excludeFromContext` 跳过 |
| G4 | 流中暂存 | 无（直接执行，运行中调用时序未定义） | `_pendingBashMessages`，agent_end flush |
| G5 | 计量/切点 | 无（条目不存在） | `(command+output)/4`；切点/回合开始均算 |

分层决定（关键）：pi 的 `AgentMessage` 超集经 declaration merging 扩四个角色，ai 层 `Message` 不见它们，`convertToLlm` 在请求前转译。java **没有也不引入** AgentMessage 层（批 2 #58 已裁决消解 convertToLlm）；既有分层里 branchSummary/compactionSummary/custom 都以 **Entry 变体**存在、投影点转 user 文本。bashExecution 走同一条路 ⇒ **Entry 变体**（不是 ai `Message` 变体——ai 包只持 LLM 对话消息，语义不错位，provider 面零波及）。

⚠️ 一处语义收窄（登记窄差 D-注）：pi 的 `excludeFromContext` 消息**留在** `state.messages`（占工作副本位），仅 `convertToLlm` 跳过；java 投影层直接不产消息。下游唯一消费者是 LLM 请求装配 ⇒ 模型可见面等价；token 估算 java 走投影后文本（比 pi 的 `command+output` 多 `Ran `/``` 包装 ~20 字符）——阈值精度差忽略不计，登记。

### 3.3 java 待写代码

**① `Entry.BashExecution` 变体**（`Entry.java`；`@JsonSubTypes` 不加——线判别仍是 `TYPE_MESSAGE`，编解码在 codec 特判 role）：

```java
    /**
     * pi {@code BashExecutionMessage}（{@code messages.ts:29-42}）——{@code !}/{@code !!}
     * 会话级 bash 执行的记录。线形是 {@code type:"message"} 条目、
     * {@code message.role:"bashExecution"}（pi 无独立条目类型，
     * {@code session-manager.ts:1204-1214}）；域内按条目变体承载，投影点转 user 文本
     * （{@link ContextEntries}），与 branchSummary/compactionSummary 同分层（docs/10 §3）。
     *
     * @param exitCode           {@code null} ≙ pi 的 undefined
     * @param fullOutputPath     截断时完整输出落盘路径；{@code null} ≙ undefined
     * @param excludeFromContext true ⇒ 持久化但不进 LLM 上下文（{@code !!} 前缀）
     */
    record BashExecution(
        String id,
        long seq,
        String parentId,
        Instant timestamp,
        String command,
        String output,
        Integer exitCode,
        boolean cancelled,
        boolean truncated,
        String fullOutputPath,
        Boolean excludeFromContext
    ) implements Entry {}
```

`committed()` 与 `type()` 各加一臂（`type()` 归 `TYPE_MESSAGE`——线判别共享）。

**② `EntryJsonCodec` 编解码特判**：`TYPE_MESSAGE` 分支先探 `message.role`：

```java
            case Entry.TYPE_MESSAGE -> {
                JsonNode messageNode = node.get("message");
                // pi 的 BashExecutionMessage 寄居在 message 条目里（session-manager.ts:1204-1214）
                if ("bashExecution".equals(messageNode.path("role").asText())) {
                    yield new Entry.BashExecution(id, seq, parentId, timestamp,
                        JsonlCodec.requireString(messageNode, "command"),
                        JsonlCodec.requireString(messageNode, "output"),
                        messageNode.has("exitCode") && messageNode.get("exitCode").isNumber()
                            ? messageNode.get("exitCode").asInt() : null,
                        messageNode.path("cancelled").asBoolean(false),
                        messageNode.path("truncated").asBoolean(false),
                        JsonlCodec.optionalString(messageNode, "fullOutputPath"),
                        messageNode.has("excludeFromHook") ? null  // 防呆，pi 无此键
                            : messageNode.has("excludeFromContext")
                                ? messageNode.get("excludeFromContext").asBoolean() : null);
                }
                Message message = MessageJsonCodec.decode(messageNode);
                ... 原臂 ...
            }
```

编码臂（`JsonlCodec`/encodeMutation 侧同位加）：`Entry.BashExecution` ⇒ `{type:"message", id, parentId, timestamp, message:{role:"bashExecution", command, output, exitCode?, cancelled, truncated, fullOutputPath?, timestamp:<ms>, excludeFromContext?}}`——可选键缺席省略（NON_NULL 惯例）；`message.timestamp` 写 epoch 毫秒数（pi `Date.now()`，`messages.ts:39`）。

**③ 投影**（`ContextEntries.project` 加臂，pi `messages.ts:147-160` 对位）：

```java
        if (e instanceof Entry.BashExecution be) {
            // pi convertToLlm "bashExecution" 支：excludeFromContext（!! 前缀）⇒ 不产消息；
            // 否则 bashExecutionToText 的 user 文本（messages.ts:82-99,147-160）。
            if (Boolean.TRUE.equals(be.excludeFromContext())) {
                return null;
            }
            return userMessage(bashExecutionText(be), be.timestamp());
        }
```

`bashExecutionText` 按 §3.1 逐字（`Ran \`cmd\`\n` / 输出三态 / cancelled 优先于 exitCode / 截断尾注）。

**④ `ContextUsageEstimator`**：估算臂按 pi `compaction.ts:337-340`——投影后文本估算与 pi 的 `command+output` 差一个包装（D-注，§3.2），不另开 arm（实现期若 estimator 有 entry 级入口则按 `command+output` 逐字）。

**⑤ `AgentSession.recordBashResult`**（coding-agent，pi `agent-session.ts:3878-3899` 对位）＋ `executeBash` 接线：

```java
    /**
     * pi {@code recordBashResult}（agent-session.ts:3878-3899）：会话级 bash 结果落会话——
     * 追加 bashExecution 条目并刷新上下文；运行中则暂存，run 收口时 flush
     * （pi 的 _pendingBashMessages/agent_end，:421,3925-3940，保 tool_use/tool_result 次序）。
     */
    public void recordBashResult(String command, ShellResult result, boolean excludeFromContext) {
        var entry = new Entry.BashExecution(
            UUID.randomUUID().toString(), 0, null, Instant.now(),
            command, result.output(),
            result.exitCode() >= 0 ? result.exitCode() : null,   // ShellResult 语义现场确认
            result.cancelled(), result.truncated(),
            result.fullOutputPath(), excludeFromContext ? Boolean.TRUE : null);
        if (harness.isRunning(laneName())) {
            pendingBashMessages.add(entry);          // flush 点：SessionRunner 的 settled 路径
        } else {
            harness.appendLaneEntry(laneName(), entry);   // 追加＋重建工作副本（下 ⑥）
        }
    }
```

`executeBash` 尾部调 `recordBashResult(command, result, excludeFromContext)`（pi `executeBash` 同位调用）。

**⑥ harness 追加口**：`AgentHarness.appendLaneEntry(lane, entry)`（公共委托 → `RunLifecycle`：lane 锁内 `appendEntry`＋`HarnessUtils.rebuildLaneMessages(lane)`——≙ pi `appendMessage`＋`_refreshFinalizedContext`）。`navigateTree` 的 BranchSummary 写入已有同形通道则复用，不新开。

**⑦ flush**：`SessionRunner` settled 路径（发 `AgentSettled` 前）取 `session.drainPendingBashMessages()` 逐条 `appendLaneEntry`（pi `_flushPendingBashMessages`，agent_end 时机）。

**⑧ 波及面清扫**（Entry 封闭 switch 编译期全显形）：`BranchSummarization.messageFromEntry` 加 BashExecution 臂（投影同 ③，pi 的摘要输入走 convertToLlm 同文）；`NavigateTree`/树显示（按消息条目同格处理，显示文本取 `bashExecutionText`）；TUI 树选择器渲染登记窄差（R3 域，不逐字）。

**⑨ 夹具**：编解码往返（含 pi 形状手写 JSONL 行 → 解码 → 投影文本逐字 / `excludeFromContext` 跳过 / 可选键全省略）；`recordBashResult` 空闲即写、运行中暂存、settled flush 时序；pi 会话文件互读（手造带 bashExecution 行的 jsonl 播种 resume）。

---

## 4. #77：navigateTree 工具恢复（w2 存疑 → 对齐）

### 4.1 pi 参照（逐字，`agent-session.ts:1809-1820`＋调用点 `:4138`）

```ts
	private _restoreToolsFromTranscript(): void {
		this._pendingToolNames.clear();
		const current = getCurrentSystemMessage(this.sessionManager.buildSessionContext().messages);
		if (!current) return;
		const names = (current.toolsAdded ?? []).map((tool) => tool.name);
		this._pendingToolNames = new Set(names.filter((name) => this._isAllowedTool(name)));
		this._setActiveTools(names);
	}
```

navigateTree 内：`_refreshFinalizedContext()` → `_restoreToolsFromTranscript()`（`:4136-4138`）。

### 4.2 差距

java `AgentSession.navigateTree`（批 4）走 `harness.replaceTranscript` → `rebuildLaneMessages`——只重建消息，**activeTools 留在导航前**（新叶声明的工具集可能不同）。

### 4.3 java 待写代码

**`RunLifecycle`**（`replaceTranscript` 旁新方法）：

```java
    /**
     * pi {@code _restoreToolsFromTranscript}（agent-session.ts:1809-1820）：树导航后按
     * 新叶最新系统消息的 {@code toolsAdded} 恢复激活装配。未注册名静默落
     * （pi 的 pending 晚注册臂随 B221 ⑫b——docs/10 窄差 D4 同族）。无系统消息 ⇒ 不动
     * （pi {@code if (!current) return}）。
     */
    void restoreToolsFromTranscript(String laneName) {
        var lane = ctx.requireLane(laneName);
        for (int i = lane.messages.size() - 1; i >= 0; i--) {
            if (lane.messages.get(i) instanceof Message.SystemMessage sm) {
                var names = sm.toolsAdded().stream().map(ToolDefinition::name)
                    .collect(java.util.stream.Collectors.toSet());
                lane.activeTools = ctx.toolRegistry().all().stream()
                    .filter(t -> names.contains(t.name()))
                    .collect(java.util.stream.Collectors.toSet());
                return;
            }
        }
    }
```

（`ctx.toolRegistry()` 的既有取用形以实现期为准——`PiLaneEngine.activeTools` 已有「注册表按名过滤」同型代码。）

**`AgentHarness`** 加公共委托；**`AgentSession.navigateTree`** 在 `harness.replaceTranscript(...)` 之后调 `harness.restoreToolsFromTranscript(laneName)`（pi `:4136-4138` 同序）。

**夹具**：navigateTree 到工具集不同的叶 ⇒ activeTools 换；叶无系统消息 ⇒ 不动；toolsAdded 含未注册名 ⇒ 静默落。

---

## 5. #75：fork 调用面小修（w2 存疑 → 对齐）

### 5.1 pi 参照（逐字）

`main.ts:380-386`（`--fork` 与 `--session-id` 组合）：

```ts
			case "path":
			case "local":
			case "global":
				return forkSessionOrExit(resolved.path, cwd, sessionDir, parsed.sessionId);
```

`session-manager.ts:1815-1830`（forkFrom 校验）：

```ts
		const sourceEntries = loadEntriesFromFile(resolvedSourcePath);
		if (sourceEntries.length === 0) {
			throw new Error(`Cannot fork: source session file is empty or invalid: ${resolvedSourcePath}`);
		}
		const sourceHeader = sourceEntries.find((e) => e.type === "session") as SessionHeader | undefined;
		if (!sourceHeader) {
			throw new Error(`Cannot fork: source session has no header: ${resolvedSourcePath}`);
		}
```

pi 不给 fork 会话改名（`forkSessionOrExit` 全文见 §取证，无 name 操作）。

### 5.2 差距与待写

| # | 差 | 待写 |
|---|---|---|
| F-b | `SessionPersistence.resolvePersistent` 的 fork 分支（`:213-224` 及 id 形分支）不消费 `args.sessionId()` | fork 的 createOptions 带上 `args.sessionId()`（pi `forkFrom(..., { id: sessionId })`）；id 校验走 create 路径既有校验，不另写 |
| F-c | `session.name(session.sessionName() + " (fork)")`（`:219`）—— pi 无此后缀 | **删除该行**（裁决记录进 docs/04 §4） |
| F-d | 空文件/无头文件的错误形是 `DecodeError`（load 路径），pi 是两条专属文案 | 登记窄差（java 的 openPath 失败文案 `"Session not found: ..."` 覆盖不存在；空/无头在 load 期即抛，文案不同形——不逐字，登记） |

`parentSession` 值形不动（§2）。夹具：`--fork <path> --session-id <id>` ⇒ 新会话用给定 id；fork 后 `sessionName` 无后缀。

---

## 6. #87：L5 夹具 thinking 块对称（w1 存疑 → 对齐）

### 6.1 坐实的不对称

- **pi twin**（`conformance/pi/run.test.ts:231-258`，`ScriptedStream` 的 `queueMicrotask` 块）：`forEach` 只有 `text`/`toolCall` 两支——thinking 块**静默丢弃**（终局消息仍含 thinking 块，`buildContent:168-170`）。
- **java twin**（`ScriptedStreams.java:99-103`）：thinking 块发 `ThinkingStart`＋`ThinkingEnd` 两帧＋`ThinkingContent`——同一份剧本，java 多出两条流帧。

### 6.2 修平方向（双 runner 帧列一致）

pi 真实 provider 流**有** thinking_start/delta/end（`FrameNormalizer.java:127-129` 三帧在册）——twin 丢帧是 twin 不忠实，不是 java 多发。修**两侧**：

**pi twin**（`run.test.ts` 的 `ScriptedStream`，thinking 分支补全）：

```ts
				} else if (block.type === "thinking") {
					const text = block.thinking ?? "";
					this.push({ type: "thinking_start", contentIndex: index, partial: blank });
					this.push({ type: "thinking_delta", contentIndex: index, delta: text, partial: blank });
					this.push({ type: "thinking_end", contentIndex: index, content: text, partial: blank });
				}
```

**java twin**（`ScriptedStreams.java:99-103` 改三帧序，补 delta）：

```java
                case "thinking" -> {
                    var text = block.thinking() == null ? "" : block.thinking();
                    events.add(new StreamEvent.ThinkingStart(index, blank));
                    events.add(new StreamEvent.ThinkingDelta(index, text, blank));
                    events.add(new StreamEvent.ThinkingEnd(index, text, blank));
                    content.add(new ContentBlock.ThinkingContent(text));
                }
```

（确切事件形——`thinking_end` 的载荷键、`ThinkingDelta` 的构造签名——实现期以两侧 Normalizer 的现有消费为准对齐；验收 = 带 thinking 块的剧本双 runner 对拍零 diff。）

**验收依赖**：pi twin 要 node＋pi 仓（`conformance/pi` 既有跑法，`check-drift.sh` 同环境）。环境缺 ⇒ 夹具改动先行落地，对拍命令写进闭环汇报由用户跑。

---

## 7. 夹具两件

### 7.1 #89：批内 join 源序夹具（C2）

`PiLoopTools.executeParallel:149-189` 的行为已对（完成序 end 帧／源序结果与 terminate，`Promise.all` 对位注释在）。补受控夹具（agent-core harness 测试）：三个并行调用，用 `CountDownLatch` 控完成序为 3→1→2，断言——① `ToolExecutionEnd` 帧序 = 3,1,2（完成序）；② 结果消息序 = 1,2,3（源序）；③ `terminate` 判定按源序收束段。对拍 pi `agent-loop.ts:646` 的 `Promise.all` 语义。

### 7.2 #96：错误路 AgentSettled 夹具（A15）

`SessionFailurePathTest` 增补：工具结果已产生、回合随后失败（`RunFailure.java:62` 的 `TurnEnd(failure, List.of())` 路——等等，该路 toolResults 恒空，正是要钉的形状）⇒ 断言错误路的 TurnEnd `toolResults` **为空表**（pi `agent-loop.ts:253` 同形：`emit({ type: "turn_end", message, toolResults: [] })`），且 `AgentSettled` 载荷 `{aborted}` 单字段不被错误路改写。守护的是「非空 toolResults 泄漏进错误帧」这个回归。

---

## 8. #94：拆两超限文件（w1，E3/E4）

红线 ≤500 行。原则：**行为零变化、包私有抽离、公开签名不动**。

- **`PiLaneSink.java`（562）**：按方法族抽——候选 `PiLaneSinkLoadout`（loadout/补丁构建族）或 emit 帧族。实现期以「最大内聚族、抽后 ≤500」定线。
- **`AgentHarness.java`（595）**：抽 fork/初始化装配族 → `HarnessBootstrap`（包私有）；公共 API 面全留本类。

验收：两文件 ≤500；agent-core 全测试绿（无行为变化的纯移动，不改任何测试）。

---

## 9. #95：字节精确假 shell（A14）

现状 `ShellOutputStreamingTest` 经「假 shell 进程」喂输出，但喂的是**字符串**——多字节 UTF-8 序列跨 8 KiB 泵边界的情形表达不了（字符级写入永远不会切在半字符）。

待写：

1. **字节级假 shell**：fixture 直接把预制的 `byte[]` 按指定块界写 stdout——构造「一个 3 字节 UTF-8 字符的第 2 字节恰好落在 8192 边界」的输出（前缀填充 8191 字节 ASCII＋3 字节字符＋尾巴）。
2. **用例**：`DefaultShellExecutor` 读完后输出含完整字符、无 U+FFFD 替换符、无丢字；stderr 同法。
3. **审计泵实现**：若现状按 `byte[]→new String` 分块解码 ⇒ 真 bug，改流式 `InputStreamReader`（ decoder 跨 read 保持部分序列）；若已是流式解码 ⇒ 夹具钉住即闭环。

---

## 10. 审计两件

### 10.1 #81：工具线程安全审计（A10）

pi 契约（`agent-loop.ts:516-520` 的并行分支）：`Promise.all` 真并发 ⇒ **工具必须并发安全**（`Promise.all` 不设上限；java 侧虚拟线程×1/任务同形，`PiLoopTools.java:185-189`）。

审计表（每工具一行：共享可变状态 → 结论）：

| 工具 | 关注点 |
|---|---|
| read/grep/find/ls | 预期纯函数式——确认无共享缓存 |
| edit/write | `FileMutationQueue` 的串行化覆盖面（同文件互斥？队列是全局面还是会话面） |
| bash | 每调用独立进程——确认无共享进程表/输出缓冲复用 |
| powershell | **重点**：持久 pwsh 会话若复用 ⇒ 并发下交错，可能要做「每调用一会话」或互斥锁 |

产出：审计表入闭环汇报；契约注释钉在 `AgentTool`（`PiLoopTools` 并发语义指向）；挖出真问题 ⇒ 本批修（小）或登记 B 号（大）。

### 10.2 #83：transcript() 消费者清点（A2 尾）

`AgentHarness` 无公开 `transcript()`（已核）。清点转录读取面并出表：`AgentSession.entries()`、`NavigateTree`、fork（`createForkMutations`）、export（HTML 快速道）、压缩（`CompactionExecutor`）、RPC `get_state`、web 快照——逐一确认走「lane 锁内读」或「快照 COW」。无未核消费者 ⇒ 销号；发现裸读 ⇒ 本批修。

---

## 11. 注释/兜底四件

### 11.1 #85：recordEvent 无跨度兜底（F2）

`JsonlFileTelemetry.recordEvent:174-188` 兜底已在（无当前跨度 ⇒ 事件照写、无 `spanId` 键，`traceId` 同键缺席）。补：javadoc 写明该兜底＋夹具（无跨度 recordEvent ⇒ 线有 `name`/`payload`、无 `spanId`/`traceId` 键）。java 自有面（pi telemetry 是休眠契约，无对位）⇒ 行为自定、钉住即可。

### 11.2 #92：TelemetryContext javadoc（D2/D3）

`:16,40,47` 三处按现状重写：`startSpan`（回调作用域绑定＋对称解绑＋异常自动 error 态）、`openSpan`（不绑定，调用方持柄）、push/popCurrent 的线程归属。

### 11.3 #93：SummaryGenerator javadoc（D6）

`:9-12` 的「`truncating()` 是 Phase 6 前占位」过期。实现期先查 `truncating()` 消费者：**无 ⇒ 删占位方法**（真路 `LlmSummaryGenerator` 已全线接）；**有 ⇒ javadoc 改写**（说明占位与真路的分工）。

### 11.4 #91：reset 注释

`RunLifecycle.reset:250` 处补一句：runSpan 在 `finishRun`（`PiLaneEngine.java:217-231` finally）必已关闭，置 null 是防御性，不是关跨度。

---

## 12. 裁决类证据块（无代码，进 docs/04 §4）

### 12.1 #14（F1）QueueMode.All 宿主语义 ≡

- drain：`QueueManager.java:144-160` ≡ pi `PendingMessageQueue.peek/drain`（`agent.ts:156-170`）——All 全排、one-at-a-time 排最老一件。
- 默认：pi `SETTINGS_DEFAULTS.steeringMode`（`settings-manager.ts:721-723`）= one-at-a-time = `QueueMode.defaultMode()`。
- 运行时面：pi `agent-session.ts:2701/2710` setter 直写 agent ≡ java `AgentHarness.steeringMode/followUpMode` setter＋`RpcDispatcher.java:210/214`。
- 设置同步：pi 写入设置后**reload 才**同步进 agent（`syncQueueModesFromSettings` 只在 `_buildRuntime`/`reload` 调，`:2691-2693,3674`）；java 无 reload 面，装配时一次读入 ⇒ 净效果一致（设置改动都非即效）。裁决：对齐。

### 12.2 #53（B49）turn_end 颗粒度 ≡（B49 销号）

pi 会话事件 union = `WithParentToolCallId<Exclude<AgentEvent,"agent_end">> | ...`（`agent-session.ts:196-240`）⇒ 逐引擎回合的 `turn_end{message,toolResults}`（`agent-loop.ts:253,287`）直通会话/RPC 面。java 批 2：`PiLoopRunner.java:134,162` 逐回合发（错误路 `RunFailure.java:62` 同形空 toolResults）→ `SessionRunner.java:229-230` 透传 → `JsonEventMapper.java:75-82` 线形 `{type,message,toolResults}` 逐字。B49 原文「Java 的 AgentSettled 每次驱动发一条」是批 2 前状态，前提已消 ⇒ **销号**。窄差登记：pi 会话事件的 `parentToolCallId` 包装（子代理上下文）java 无（无嵌套代理面，顶层会话恒 absent）。

### 12.3 #80/#84（A5 尾/F4）两道门 ≡

- 抛文逐字：pi `:1988`（"Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry."）= `PromptRouter.COMPACTING_MESSAGE`；pi `:2016`（"Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message."）= `ALREADY_PROCESSING_MESSAGE`。
- 顺序：展开（输入钩子/`/skill:`/模板）先于门（pi `:1997-2011`→`:2012`；java `AgentSession.java:808`→`:812`）。
- 排队：steer/followUp 入队＋`queue_update`（B34）。
- F4 裁决：java 的「压缩窗」来自包 25–27（B175 关窗/B176 worker）；pi 无窗概念——pi 的压缩与 run 单飞、门在 prompt 入口抛（`:1988`），与 java 关窗抛同效 ⇒ 对齐，裁决入 §4。

### 12.4 #82（旧 A7）retry.provider 三键 ≡

| 键 | pi | java |
|---|---|---|
| `maxRetries` | `provider-retry.ts:106` `?? 0` | `DefaultProviders.java:330-331` 缺席 ⇒ 0 |
| `maxRetryDelayMs` | `settings-manager.ts:925-931` `?? 60000` | `:332-333` 同 |
| `timeoutMs` | `sdk.ts` buildRequestOptions `?? providerRetry.timeoutMs ?? effective` | `:345-346` 明给替换默认 |

裁决：对齐。（`httpIdleTimeoutMs` 默认值差异属 map/04 E2-48，另案。）

### 12.5 #56（A1）EntryProjector ≡

`ProjectedSessionEntry{sourceEntry,messages}`（`session-manager.ts:208-213`）≡ `ContextEntries.ProjectedEntry{source,messages}`（`:152-158`）；`buildSessionProjection`（`:543-575`）≡ `projectEntries`（`:161-199`），含同款「仅下标 0 的 compaction 贡献摘要」守卫（java `:182-186`）。投影的 `thinkingLevel/model` 便捷返回 java 无——消费点各有访问器（`lastRecordedThinking` 等），无消费者 ⇒ 免登。裁决：对齐，A1 随 #57 落地销号。

### 12.6 #90（C3）transcript 可见性 ≡

写者唯一（驱动线程 `lane.appendEntry`，lane 锁内）；读者全部走快照（`LaneSnapshot`/`SessionSnapshot` COW——records COW 裁决同族，docs/04 §4 在册）；无锁裸读面不存在。裁决：对齐，留痕一句。

### 12.7 #91（C4）reset/runSpan ≡

`finishRun` 于 `PiLaneEngine.java:217-231` 的 finally 收口（error 路 `:231` 同走）⇒ 空闲车道 runSpan 必已关；`RunLifecycle.reset:250` 置 null 是防御。裁决：对齐（注释补强见 §11.4）。

---

## 13. #60：R1 作废（Σw 182 → 180）

`PendingEntry`/`InboxItem` 现锚点全仓零命中（`git grep` 双词均空）；二者是 Pico 实验 harness（`agent/src/harness/**`）的类型，随 **`7fd478a2e feat(agent): remove the experimental harness from pi-agent-core`** 删除——R1 同批删除链（map/03「作废清单」19 单元同源）。按「pi 删掉的，pi-java 也删」⇒ 单元作废出分母（w2，存疑 1.0 从分子退、2.0 从分母退：166.5−1.0 / 182−2 ⇒ 后续翻转在此基准上加）。java `QueueManager` 的队列契约以 pi `PendingMessageQueue`（`agent.ts:142-176`）为参照，已在 #14 核齐。

---

## 14. 账面

| 面 | 前 | 后 |
|---|---|---|
| agent-core 加权 | 166.5/182 = 91.5% | **180/180 = 100%**（#60 出分母后 165.5/180；21 条翻转 +14.5） |
| 未加权 | 69/91 = 75.8% | **90/90 = 100%**（21 翻对齐，#60 出分母 91→90） |
| 存疑 | 22 | **0** |
| 全仓合计 | 869.0/1383 ≈ 62.8% | **882.5/1381 ≈ 63.9%** |

风险挂账：#81/#83 审计可能挖出新缺口（转本批做或新 B 号，账面目标随闭环汇报调整）；#87 对拍依赖 node＋pi 仓环境。

## 15. commit 划分（12 个）

| # | commit | 内容 |
|---|---|---|
| 1 | `docs(agent-core): batch-5 design — remaining 22 uncertain rows to 100%` | 本稿 |
| 2 | `feat(agent-core): Entry.BashExecution variant + jsonl codec (map #57)` | §3.3 ①②＋编解码夹具 |
| 3 | `feat(agent-core): bashExecution projection + bashExecutionToText (map #57)` | §3.3 ③④＋投影夹具 |
| 4 | `feat(coding-agent): recordBashResult + pending flush on settle (map #57)` | §3.3 ⑤⑥⑦⑧⑨ |
| 5 | `feat(agent-core): restore tools from transcript after navigateTree (map #77)` | §4 |
| 6 | `fix(coding-agent): fork honors --session-id, drop " (fork)" suffix (map #75)` | §5 |
| 7 | `test(agent-core): L5 thinking frames parity both twins (map #87)` | §6 |
| 8 | `test(agent-core): parallel join order + error-path AgentSettled fixtures (map #89/#96)` | §7 |
| 9 | `refactor(agent-core): split PiLaneSink/AgentHarness under 500 lines (map #94)` | §8 |
| 10 | `test(agent-core): byte-exact fake shell at 8KiB boundary (map #95, A14)` | §9（泵有 bug 则同修） |
| 11 | `docs(agent-core): tool concurrency audit + transcript consumers + javadoc sweep (map #81/#83/#85/#91/#92/#93)` | §10/§11（审计挖出真问题则另开 fix commit） |
| 12 | `docs(agent-core): batch-5 closure — 91.5% -> 100%` | 闭环回填：map/03（21 翻对齐＋#60 R1＋权重表＋「存疑清零」节）、docs/07（§2 清空＋§1 注记）、docs/04（B49 销号＋裁决入 §4＋旧字母编号清理）、docs/05（agent-core 100%、合计 63.9%）、本稿横幅 |

门禁：每 commit `mvn test -pl pi-java-agent-core,pi-java-coding-agent -am`；**收口前全量 `mvn clean verify`**（spotbugs 绑 verify——批 4 教训）。
