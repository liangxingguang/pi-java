# 28 — agent-core 架构决策：自建显式状态机 vs 1:1 复刻 pi 双循环

> **状态：待裁决。** 编制日期 2026-09-13。基线 pi `v0.85.1`（见 `docs/27`）。
> **前置**：`docs/27-pi-alignment-baseline.md`（对齐规则 = pi 最新 release tag = pi 产品层）。
>
> **本文只做决定，不动代码。** 决定前不实施 `docs/27 §5.2` 的欠写修复 —— 见 §6。

---

## 0. 结论摘要

**问题**：pi-java 的 `agent-core` 自建了一套显式状态机（`Action` / `RunPhase` / `peekAction` /
`executeAction` / `LaneRecord` 记录日志 / `LaneStateFolder` 折叠），而 **pi 的产品层没有这些东西** ——
它只有一个约 90 行的双循环函数。该不该改成 1:1 复刻？

**本文的建议**：**选项 C —— 1:1 复刻驱动循环，把记录日志降级为纯旁路审计**（不再是恢复的真源）。
理由见 §4。**但先做最小验证性手术（§5），不要直接重写。**

---

## 1. 问题

`docs/27` 确立的对齐规则是「**pi-java 与 pi 目前已经稳定的版本对齐**」，而稳定版的产品层是
`agent-loop.ts`（驱动） + `agent-session.ts`（会话/持久化） + `session-manager.ts`（JSONL 追加）。

在这条规则下，`agent-core` 的显式状态机**没有对齐目标** —— 它不对齐 pi 的产品（产品没有），
也不该对齐 harness/pico（不在范围内，且 pi 正用 pico 替换 harness）。

于是有一个必须回答的问题：

> **这套状态机是"实现方式不同"（允许），还是"多出来的东西"（负担）？**

---

## 2. 证据

### 2.1 pi 侧：循环有多小、有多稳

`packages/agent/src/agent-loop.ts` 的双循环（实读 `:100-300`）：

```
runAgentLoop(:~109)           → emit agent_start / turn_start / 各 prompt 的 message_start+end → runLoop
runAgentLoopContinue(:~138)   → emit agent_start / turn_start → runLoop
runLoop(:~165)
  outer: while (true)                        ← follow-up 队列决定是否再来一轮
    inner: while (hasMoreToolCalls || pendingMessages.length > 0)
      注入 pendingMessages（steer）           → 各条 message_start/end
      streamAssistantResponse()              → message_start / message_update* / message_end
      error|aborted → turn_end + agent_end → return
      toolCalls → executeToolCalls()（或 length 截断则全部失败）
      emit turn_end(message, toolResults)
      prepareNextTurn() / shouldStopAfterTurn() → 命中则 agent_end + return
      pendingMessages = getSteeringMessages()
    followUpMessages = getFollowUpMessages()  → 非空则 continue
    break
  emit agent_end
```

**状态**：`currentContext.messages` + `newMessages` + `config` + `pendingMessages` + `hasMoreToolCalls`。
**全部在内存里，没有持久状态机。**

**稳定性（实测）**：`v0.85.1` → `origin/main`（67 个提交）之间
`agent-loop.ts` / `types.ts` / `agent.ts` / `session-manager.ts` **零变化**。
→ **这是一个冻结的参照物。**

### 2.2 pi-java 侧：状态机有多大、谁在用

| 度量 | 数值 |
|---|---|
| `agent-core` 全模块 | 171 文件 / **14,022 行** |
| `harness/` 包 | 39 文件 / **4,631 行** |
| 其中「状态机 + 记录日志」子集 | **2,362 行**（`ActionExecutor` 497、`LaneOperationFold` 471、`RecordLogValidator` 254、`AssistantStreamExecutor` 219、`LaneState` 130、`Action` 129、`LaneStateFolder` 120、`LoopInvariants` 72、`SnapshotService` 116、`RunPhase`/`PeekAction` + `record/LaneRecord` 318） |

**两个刺眼的消费数据（实测）**：

| 设施 | 生产消费者 |
|---|---|
| `DriveMode.MANUAL` | **零**。它是 `HarnessConfig:115` / `HarnessState:24` / `AgentHarness:99` 三处的**默认值**，但没有任何生产代码引用该常量（`SessionRunner` 用 `run()`+`executeAction()` 手动步进 —— 本质就是 MANUAL，只是没写常量名）。**22 个测试文件**在用它 |
| `records()`（12 变体 / 318 行记录日志） | **2 个**：`SessionPersistence:47`（持久化记录日志本身）、`SessionRunner:219`（run summary） |

> **记录日志的主要用途，是把记录日志存下来，外加一个 run 汇总。**

### 2.3 代价已经付过了：三个缺陷同源

| 真源 | |
|---|---|
| **pi 产品** | 消息数组 + entry 日志 —— **一个** |
| **pi-java** | `lane.transcript` + `pendingWrites` + `records` —— **三个** |

三个真源直接产出了三个真实缺陷，且**全部在 pi 产品里不存在**：

| 缺陷 | 根因 |
|---|---|
| 欠写不收敛（`pendingWrites` 一旦欠账就永远欠着） | 第二个真源 `pendingWrites` |
| 崩溃窗口 = 一整个 run（pi 是一条 entry） | 状态机在 run 边界统一 flush |
| fold 派生不可靠（entry 被删则谓词失效） | 第三个真源 `records` 被当作恢复依据 |

**已修一条**：崩溃窗口已按 pi 的时机改为逐 action 落盘（`docs/27 §5.2`，含回归测试）。
另两条随本决策处理。

---

## 3. 选项

### A. 保持现状，继续修补

保留 `Action` / `RunPhase` / `LaneRecord` / fold / `pendingWrites`，逐条修缺陷。

- ✅ 零返工风险；已有测试全保留
- ❌ **等价性永远靠人工追认** —— 每一个新行为都要重新解释一遍 pi；我们已因此漂了 turn 事件、
  工具三帧、落盘时机三处
- ❌ `pendingWrites` / fold 要继续维护，而它们在 pi 里没有对应物，无法用差分验证对错
- ❌ 状态机 + 记录日志 2,362 行 + 三个真源的复杂度长期存在

### B. 1:1 复刻双循环，删除状态机与记录日志

用 Java 重写 `runAgentLoop` / `runLoop` / `streamAssistantResponse` / `executeToolCalls` 的对等物；
删除 `Action` / `RunPhase` / `ActionExecutor` / `PeekAction` / `LaneStateFolder` / `LaneOperationFold` /
`RecordLogValidator` / `LoopInvariants` / `pendingWrites` / `LaneRecord`。

- ✅ **等价性由结构保证**，不再依赖人工对齐
- ✅ 崩溃恢复退化为 pi 的模型：加载 entry → 重建消息 → 继续（`seedTranscript` 已具备）
- ✅ 2,362 行 + 三个真源一并消失
- ❌ **破 SDK 面**：`AgentHarness` 是 CLAUDE.md 写明的 SDK 入口，`peekAction`/`executeAction` 是公开 API
- ❌ 22 个测试文件基于 MANUAL 步进；TUI / web / RPC / SQLite 四个消费者要跟着改
- ❌ 失去 run summary 的数据源与审计日志

### C.（建议）1:1 复刻驱动 + 记录日志降级为「纯旁路审计」

- **驱动**：按 B 换成 pi 的双循环 —— 等价性由结构保证
- **记录日志**：`LaneRecord` 的发射点**保留**，由新循环的事件驱动；但**不再是恢复的真源**
- **删除**：`LaneStateFolder`（折叠恢复）、`pendingWrites` + `ApplyPendingWrite`、`LoopInvariants`
  的 owed-write 断言、`LaneOperationFold` 的 pendingWrites 派生
- **恢复**：改为 pi 的模型 —— 加载 entry → `seedTranscript` → 继续
- **保留**：`records` 只服务 `RunSummaryAggregator` 与审计/回溯

- ✅ 拿到 B 的全部等价性收益
- ✅ **保住可观测性投资**（记忆里那条"可观察性层 9/9 闭环"不被推翻），run summary 继续工作
- ✅ 恢复机制从"折叠派生"变成 pi 的"读 entry"，欠写问题**失去存在土壤**
- ⚠️ 仍需破 `peekAction`/`executeAction` 的 SDK 面（与 B 相同）

---

## 4. 为什么推荐 C

1. **它把"等价"从持续追认变成结构性成立。** pi 的循环是 ~90 行且**已冻结**；1:1 之后，
   行为差异只可能来自移植错误，而不是设计分歧 —— 而且立刻可以用 `docs/23c` 的 L5 差分框架钉住。
2. **它消灭三个真源。** 欠写、崩溃窗口、fold 不可靠三条缺陷同根；换驱动即可一并消除，
   不需要单独设计"清偿方案"。
3. **它保住已经付过钱的东西。** 记录日志与 run summary 是真实资产（可观测性 9 个提交已闭环），
   降级为旁路审计即可继续用，不必删。
4. **它不需要"重写整个 agent-core"。** `QueueManager` / `CompactionExecutor` / `ContextAssembler` /
   `ToolExecutionPipeline`（约 2,269 行）是**真功能**，pi 产品里也有对应物，全部保留。

---

## 5. 验证方案：最小验证性手术（**不要直接重写**）

| 步 | 做什么 | 通过判据 |
|---|---|---|
| 0 | 本文评审通过 | 你点头 |
| 1 | **新写独立的 `PiLoop`**（新类，**不删旧的**），一对一译出 `runLoop` / `streamAssistantResponse` / `executeToolCalls`。估计 150–200 行 | 编译通过；与 `agent-loop.ts` 逐段对照审查 |
| 2 | 让 `SessionRunner` 切到 `PiLoop`，**保留** `AgentHarness` 旧 API 不动 | `pi-java-coding-agent` 全模块测试通过（当前 213 个） |
| 3 | 用 `docs/23c` §2 的 L5 差分跑 **S1–S8** 剧本 | 零 P0；差异按 P1/P2 归档 |
| 4 | 若 1–3 通过 ⇒ 旧的状态机成为**死代码**，此时删除**无风险**，规模就是 §2.2 那 2,362 行 | 全 reactor `mvn -o clean verify` 绿 |
| 5 | 把 `records` 发射点接到新循环上，`LaneOperationFold` / `LaneStateFolder` 退休 | run summary 相关测试保持通过 |

**若第 2 或第 3 步不通过** ⇒ **不进行第 4 步**，退回选项 A，并把"pi-java 的哪一条需求 pi 的循环满足不了"
写成具体结论 —— 那才是真正的架构依据，而不是现在的推测。

---

## 6. 被本决策阻塞的工作

| 工作 | 状态 |
|---|---|
| `docs/27 §5.2` 的**欠写不收敛**修复 | ⛔ **暂停**。选项 C 下 `pendingWrites` 整体消失，现在修等于白做 |
| **A1–A4**（legacy 事件层：turn 事件、工具三帧缺失） | ✅ **不阻塞**，两种架构都需要。可先做 |
| **A8**（流中途 abort 终态） | ✅ 已完成（`175e9fa`） |
| 落盘时机（`docs/27 §5.2` 第 1 条） | ✅ 已完成（逐 action 落盘 + `SessionFlushTimingTest`） |

---

## 7. 退出判据 / 回退条件

**推进条件**：§5 第 1–3 步全部通过。

**回退到选项 A**（并接受长期人工对齐成本）的条件，任一成立即回退：

1. `PiLoop` 无法用 ≤ 300 行 Java 表达 pi 的循环语义；
2. 有 pi-java 消费者的需求**确证**依赖显式步进（例如某个 UI 需要"执行一步再看"）——
   注意 `DriveMode.MANUAL` 目前**无生产消费者**，此条需要新证据；
3. L5 差分出现**无法归档**的 P0，且根因在 pi 的循环本身（即 pi 的行为不能满足 pi-java 的需求）。

---

## 8. 复核方式

工作副本 `D:\workplaceForai\pi`（跟踪 `my-pi` = `origin/main`，可用 `git show v0.85.1:<path>` 读稳定版）。

```bash
# §2.1 循环的规模与稳定性
git show v0.85.1:packages/agent/src/agent-loop.ts | wc -l
git diff --stat v0.85.1 origin/main -- packages/agent/src/agent-loop.ts   # 期望：无输出

# §2.2 谁在用
grep -rn "DriveMode.MANUAL" --include=*.java pi-java-*/src/main/java/     # 期望：仅默认值 3 处
grep -rn "\.records()" --include=*.java pi-java-*/src/main/java/          # 期望：2 个真实消费者
wc -l pi-java-agent-core/src/main/java/com/pijava/agent/harness/*.java    # 期望：4631 total
```

---

## 9. 未验证的假设（**必须显式列出**）

本文的推荐建立在以下**尚未验证**的前提上。第 5 步的验证性手术正是为了证伪它们：

1. **`peekAction` / `executeAction` 没有仓库外的 SDK 消费者。** 本文只检查了本仓库；
   它们是否被外部集成方使用**未知**。若被使用，选项 B/C 都破兼容（需要保留薄适配层）。
2. **pi-java 没有"需要显式步进"的产品需求。** 依据是 `DriveMode.MANUAL` 无生产消费者，
   但 `SessionRunner` 事实上在做逐步驱动 —— 是否存在某个消费者依赖"一步一停"的语义**未验证**。
3. **`records` 降级为旁路审计后 `RunSummaryAggregator` 仍能得到所需数据。** 未验证 ——
   它读的是 `lane records (snapshot.records())`，新循环必须能产生等价的记录流。
4. **`seedTranscript` 足以承担恢复。** 它现在只服务显示上下文；改为恢复的唯一真源后，
   分支/编辑（`leafId` 语义）等场景是否等价**未验证**（`docs/27 §3.3` 已标为待核）。
