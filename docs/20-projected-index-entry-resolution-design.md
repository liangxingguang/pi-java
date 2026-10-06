# 包 20：省略目标定位的投影下标兜底（B170）

> **状态：✅ 已闭环（2026-10-06，R-A）—— B170 销号。commits `392a102` / `4e0e326`。**

## 1. 问题

B170（`docs/13 §4.5` 连带登记）：pi 的 `_findPersistedMessageEntryId`
（`agent-session.ts:1185-1206`）有三档定位：

1. `_entryIdsByMessage`（WeakMap 缓存）；
2. 当前分支 transcript 的**反向身份扫描**；
3. **投影下标兜底**：消息是工作副本（`state.messages`）中的实例时，按其下标
   经 `buildSessionProjection()` 反查贡献该投影位置的源条目 id。

pi-java 的 `RecoveryOmissions.identityEntryId` 只做了第 2 档。于是当失败助手
在工作副本里是一个**投影副本**（与其 transcript 载荷不同实例）时，解析返回
null，被投影守卫误判成「projected message has no source entry」**硬失败**，
而 pi 会经第 3 档正确定位。

当前生产不可达该失败（`PiLaneSink.append` 与 `lastAssistant()` 同一实例，
已核），所以这是定位规则的面向上的缺口，不是现网 bug。

## 2. pi 锚点事实（`200387122`，`git show` 取证）

`packages/coding-agent/src/core/agent-session.ts:1185-1206`，逐字：

```ts
private _findPersistedMessageEntryId(message: AgentMessage): string | undefined {
	const mapped = this._entryIdsByMessage.get(message);
	if (mapped) return mapped;
	for (const entry of [...this.sessionManager.getBranch()].reverse()) {
		if (entry.type === "message" && entry.message === message) return entry.id;
	}

	const messageIndex = this.agent.state.messages.indexOf(message);
	if (messageIndex < 0) return undefined;
	const projection = this.sessionManager.buildSessionProjection();
	let projectedIndex = 0;
	for (const entry of projection.entries) {
		for (let i = 0; i < entry.messages.length; i++) {
			if (projectedIndex === messageIndex) {
				this._entryIdsByMessage.set(message, entry.sourceEntry.id);
				return entry.sourceEntry.id;
			}
			projectedIndex++;
		}
	}
	return undefined;
}
```

要点：

- `state.messages.indexOf(message)` 是 **JS 对象身份**（不是值相等）；
- 比较发生在自增**之前**：第 N 个投影位置（扁平展开）⇒ 该位置所属
  `entry.sourceEntry.id`；走完全程没命中 ⇒ undefined；
- `state.messages` 与投影扁平视图同源：`_refreshFinalizedContext`
  （`:909-915`）先重建投影再赋值 `state.messages = projection.messages`。

第 1 档 WeakMap 的全部写入点只有三处：`:913`（`_refreshFinalizedContext`
把每条投影消息映射到源 id）、`:1135`（消息落盘后映射 event.message）、
`:1199`（第 3 档命中后备忘）——缓存值全部来自第 2/3 档本就能推出的映射，
是纯备忘，不产生不同的解析结果。

## 3. pi-java 现状（逐处核实）

- `RecoveryOmissions.identityEntryId`（`:89-96`）：仅反向 transcript
  身份扫描（第 2 档）；
- `persist`（`:35`）与 `persistRetryOmission`（`:58`）都只调它；守卫
  `inWorkingCopy` 已正确使用引用身份；
- 投影零件齐备：`ContextEntries.projectEntries(transcript, leafId)`
  返回 `ProjectedEntry(source, messages)`（`ContextEntries.java:167-214`），
  且 `HarnessUtils.rebuildLaneMessages`（`:57-63`）就是把其扁平视图灌进
  `lane.messages` —— 与 pi `_refreshFinalizedContext` 同构。

## 4. 方案（R-A：照 pi 补第 3 档）

`RecoveryOmissions.java` 加一个解析器，两处入口改用它：

```java
/** pi _findPersistedMessageEntryId（agent-session.ts:1185-1206）：先身份扫描，再投影下标兜底。 */
private static String resolveEntryId(LaneState lane, Message target) {
    String identity = identityEntryId(lane, target);
    return identity != null ? identity : projectedEntryId(lane, target);
}

/**
 * 第三档：目标是工作副本中的实例时，按其身份下标经投影反查源条目 id
 * （pi :1191-1205）。{@code lane.messages} 与投影扁平视图同源
 * （{@link HarnessUtils#rebuildLaneMessages}）。
 */
private static String projectedEntryId(LaneState lane, Message target) {
    int messageIndex = -1;
    for (int i = 0; i < lane.messages.size(); i++) {
        // pi indexOf 是对象身份：消息是值相等的 record，不能用 List.indexOf。
        if (lane.messages.get(i) == target) {
            messageIndex = i;
            break;
        }
    }
    if (messageIndex < 0) {
        return null;
    }
    List<ContextEntries.ProjectedEntry> projected =
        ContextEntries.projectEntries(lane.transcript, HarnessUtils.lastEntryId(lane));
    int projectedIndex = 0;
    for (ContextEntries.ProjectedEntry entry : projected) {
        for (int i = 0; i < entry.messages().size(); i++) {
            if (projectedIndex == messageIndex) {
                return entry.source().id();
            }
            projectedIndex++;
        }
    }
    return null;
}
```

改动点：

1. 加 `import com.pijava.agent.session.ContextEntries;`；
2. `persist` 第 36 行、`persistRetryOmission` 第 59 行的
   `identityEntryId(lane, failed)` 改为 `resolveEntryId(lane, failed)`；
3. 守卫、`toolResultEntryIds`、edit 追加顺序均不动。

行为面：仅当第 2 档落空而目标确在副本中时，由「抛 ISE」变为「定位到源
条目」；目标两处皆无 / 投影为空仍走原路径（静默跳过或抛错）。

## 5. 实施步骤（严格 RED-first）

| # | 内容 | 预期 |
|---|---|---|
| 1 | 新增 `RecoveryOmissionsResolutionTest`（agent-core，见下）3 条用例 | 先红（ISE） |
| 2 | 落 §4 生产改动 | 转绿 |
| 3 | 变异探针（§6）→ 全部还原 | 有牙 |
| 4 | `mvn -o clean verify`（`-am`，全 14 模块） | 零错误 |

用例（夹具：transcript 放 user/assistant 条目，再追加一条 **replacement
非 null** 的 context_edit 指向助手，然后 `rebuildLaneMessages` —— 副本里的
助手即与 transcript 载荷不同实例的投影副本）：

1. `projectedAssistantCopyResolvesViaIndexToSourceEntry`（retry 路径）：
   `persistRetryOmission` 不抛错；transcript 尾是 `targetId=e-asst、
   replacement=null` 的 edit；重建后副本只剩 user；
2. `projectedAssistantCopyInOverflowPersistResolvesViaIndex`（溢出路径）：
   助手无 tool call，`persist` 同样定位、只落一条 omission edit；
3. `valueEqualAssistantEarlierInCopyDoesNotStealTheIndex`：副本中更早位置有
   一条**值相等**的助手，身份下标必须跳过它，omission edit 指向靠后的源
   条目 e-a2（钉 pi `indexOf` 的身份语义，防值相等误取下标）。

存量 `PostRunRetryTest.projectedAssistantWithoutSourceEntryThrows`（副本有、
transcript 空 ⇒ 投影为空、第 3 档走完全程返回 null ⇒ 仍抛 ISE）保留不动，
作为第 3 档无假阳性的对照组。

提交：

- `test(agent-core): pin projected-index fallback for omission targets (B170)`
- `feat(agent-core): resolve omission targets via the projected index (B170)`

## 6. 变异探针（预期红数以实测为准）

| 探针 | 变异 | 预期红灯 |
|---|---|---|
| M1 | 第 3 档不接线（`resolveEntryId` 只留身份扫描） | ①②③ 抛 ISE |
| M2 | 身份下标改值相等（`lane.messages.get(i).equals(target)`） | ③ 指错源条目 |
| M3 | 自增移到比较之前（下标整体错一位） | ①②③ |

## 7. 明确不做

- **不移植第 1 档 WeakMap 缓存**：其全部值都由第 2/3 档同源推出，解析结果
  无差异；JDK 的 `WeakHashMap` 按 `equals` 判键，对值相等 record 不安全，
  移植要自发明弱身份表、纯属死机器；
- 不改 `toolResultEntryIds` 的 toolUseId 定位形状（docs/13 定下的刻意方言）；
- 不动错误文案、edit 落盘车道与既有守卫；
- 不重开 B2（历史摘要主 prompt 方言）。

## 8. 闭环记录（2026-10-06）

**裁决：R-A 照 pi 补第三档，B170 销号。**

探针实测（3 颗全部与预案一致）：

- **M1 = 3 红**：断第三档接线，三用例全部回到 ISE；
- **M2 = 1 红**：身份下标改值相等，恰 ③（值相等助手抢下标、edit 误指 e-a1）；
- **M3 = 3 红**：自增移到比较之前，三条全部指错/落空。

探针运行踩中一次既知陷阱：不带 `-am` 时 ~/.m2 旧 ai 构件导致 agent-core
假编译失败，加 `-am` 后正常；另复现一次「`-Dtest` 的 `+` 非分隔符」，
已改逗号。

### 证据

- 新增 `RecoveryOmissionsResolutionTest` 3 条（retry 路径定位、溢出路径
  定位、值相等不抢下标），RED 阶段 3/3 ISE，生产改动后 3/3 绿；
- 存量 `PostRunRetryTest`（15）、`PostRunCompactionCheckTest`（15）连带
  全绿；`projectedAssistantWithoutSourceEntryThrows` 作为第三档无假阳性
  对照保留；
- agent-core 598 ⇒ **601 tests**；全 reactor `mvn -o clean verify`
  **14/14 SUCCESS**（2026-10-06）。

### 提交

- `392a102` test(agent-core): pin projected-index fallback for omission targets (B170)
- `4e0e326` feat(agent-core): resolve omission targets via the projected index (B170)
