# 包 19：环 A 重试前持久 omission edit（B172）

> **状态：✅ 已闭环（2026-10-06，R-A）—— B172 销号。commits `7b45a9e` / `599b57e`。**

## 1. 问题

B172（`docs/17 §6` 连带登记项，低）：pi 的 `_prepareRetry` 在发完
`auto_retry_start` 之后、退避之前，调用 `_omitRecoveryAttempt(message)` 给失败
助手**持久追加**一条 `replacement:null` 的 context_edit，再刷新工作副本。

pi-java 的 `PostRunRetry.prepareRetry` 只从工作副本**内存里摘掉**尾助手
（`PostRunRetry.java:77-82`），transcript 上没有 edit —— 持久状态形状与 pi 不同。

## 2. pi 锚点事实（`200387122`，`git show` 取证）

`agent-session.ts:3736-3740`：

```ts
// Keep the failed attempt in raw history while durably omitting it from model projection.
this._omitRecoveryAttempt(message);
```

`_omitRecoveryAttempt`（`:1208-1225`，默认 `toolResults = []`）：

```ts
private _omitRecoveryAttempt(message: AssistantMessage, toolResults: AgentMessage[] = []): void {
	const targets = [message, ...toolResults];
	const targetIds = targets.map((target) => this._findPersistedMessageEntryId(target));
	const unresolvedProjectedTarget = targets.some(
		(target, index) => targetIds[index] === undefined && this.agent.state.messages.includes(target),
	);
	if (unresolvedProjectedTarget) {
		throw new Error("Cannot persist recovery omission because a projected message has no source entry");
	}
	for (const targetId of targetIds) {
		if (!targetId) continue;
		const editId = this.sessionManager.appendContextEdit(targetId, null);
		const entry = this.sessionManager.getEntry(editId);
		if (entry) this._emit({ type: "entry_appended", entry });
	}
	this._refreshFinalizedContext();
}
```

要点：① 环 A **只省略助手一条**（无 toolResults，与溢出路径 `:2996` 不同）；
② 目标在投影中却解析不到源条目 ⇒ 抛固定错误；既不在 transcript 也不在工作副本
⇒ 静默跳过；③ 落 edit 后重建工作副本。

## 3. pi-java 现状

1. `PostRunRetry.prepareRetry`（`PostRunRetry.java:62-90`）：发 start 后手工
   `messages.remove(尾)`，无持久 edit、无抛错守卫；
2. `RecoveryOmissions`（溢出恢复）已具备全部所需零件：`identityEntryId`、
   `inWorkingCopy`、`appendOmission`、`rebuildLaneMessages`；其 `persist(…)` 额外
   省略本轮工具结果，是溢出形状。

等价性背景（B172 登记分析）：两侧的 error 助手分别由 pi-java `project()` 的停因
过滤 / pi 请求门过滤，resume 与互读均不可观察差异；本包唯一可观察效果＝
JSONL 上多出与 pi 同形的 context_edit 行。

## 4. 方案（R-A：镜像 pi，约 3 行生产改动）

1. `RecoveryOmissions` 加静态方法：

```java
/** pi _prepareRetry → _omitRecoveryAttempt(message)（agent-session.ts:3738）：只省略助手一条。 */
static void persistRetryOmission(LaneState lane, Message.AssistantMessage failed) {
    String assistantId = identityEntryId(lane, failed);
    if (assistantId == null && inWorkingCopy(lane, failed)) {
        // pi :1216-1218：投影中的消息没有源条目 ⇒ 无法安全持久省略。
        throw new IllegalStateException(
            "Cannot persist recovery omission because a projected message has no source entry");
    }
    if (assistantId != null) {
        appendOmission(lane, assistantId);
    }
    HarnessUtils.rebuildLaneMessages(lane);
}
```

2. `PostRunRetry.prepareRetry` 中把手工摘尾块替换为一行：

```java
// pi :3738：持久 omission edit + 刷新工作副本（不再手工 remove）。
RecoveryOmissions.persistRetryOmission(lane, message);
```

位置不变：发 `auto_retry_start` 之后、退避睡眠之前（取消不回滚）。

## 5. 实施步骤（RED-first）

| # | 内容 | 预期 |
|---|---|---|
| 1 | `PostRunRetryTest` 新增：① prepareRetry 后 transcript 尾为 `ContextEdit(targetId=失败助手条目, replacement=null)`，工作副本重建为 [user]；② 助手在工作副本但无 transcript 源条目 ⇒ ISE 逐字文案；③ 助手两处皆无 ⇒ 无 edit、返回 true | 先红（①②） |
| 2 | 更新存量 `PostRunRetryTest` 夹具：`prepareEmitsStart…`/`emptyErrorMessage…`/`abortDuringBackoff…` 三个用例给 lane 补 transcript 条目（error 助手现在必须可解析，否则走抛错路径）；`onlyAssistantTailIsDropped` 语义注释改为「未知目标静默跳过」 | — |
| 3 | 落 §4 两处生产改动 | 转绿 |
| 4 | 探针：M1 注释掉 persistRetryOmission 调用 ⇒ ① 红；M2 persist 后不 rebuild ⇒ ① 副本断言红。还原 | 有牙 |
| 5 | `mvn -o clean verify`（`-am`，14 模块） | 零错误 |

提交：

- `test(agent-core): pin the durable retry omission edit (B172)`
- `feat(agent-core): persist the omission edit before auto-retry continues (B172)`

## 6. 明确不做

- 不在环 A 省略工具结果（pi 只传 message；工具结果省略属溢出路径）；
- 不补 entry_appended 事件发射（溢出路径现有形状也未单显，跟随既有方言）；
- 不动 B170（投影下标兜底）。

## 7. 闭环记录（2026-10-06）

**裁决：R-A 镜像 pi，B172 销号。**

探针实测（红数与预案不一致，行为面无偏差）：

- M1 只删 `appendOmission`（守卫/重建保留）⇒ 恰 **1 红**（retryOmissionIsPersistedAsAContextEdit）；
- M2 跳过 rebuild ⇒ **3 红**（prepareEmitsStart…／unknownTarget…／abortDuringBackoff…
  的工作副本断言；预案只写 1 红）。

### 证据

- 新用例 3 条：`retryOmissionIsPersistedAsAContextEdit`／
  `projectedAssistantWithoutSourceEntryThrows`／`assistantInNeitherPlaceAppendsNoEdit`；
  存量 3 个夹具补 transcript 条目（error 助手现在必须能解析源条目），
  `onlyAssistantTailIsDropped` 改为 `unknownTargetIsSilentlySkipped`。
- agent-core 595 ⇒ **598 tests**；全 reactor `mvn -o clean verify` **14/14 SUCCESS**（2026-10-06）。

### 提交

- `7b45a9e` test(agent-core): pin the durable retry omission edit (B172)
- `599b57e` feat(agent-core): persist the omission edit before auto-retry continues (B172)
