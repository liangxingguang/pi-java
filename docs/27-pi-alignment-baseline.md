# 27 — pi 对齐基线与规则

> **编制日期**：2026-09-13。**基线**：pi 最新 release tag **`v0.85.1`**（2026-09-05，`d981de122`）。
>
> **本文取代 `docs/23`、`docs/26`**（两份已随本文提交删除），并修正 `docs/23c` 的参照物假设。
> `docs/23` 的历史评审记录（`docs/23b`）亦已删除 —— 其审查对象不存在了，留着只会误导。

---

## 1. 对齐规则（**唯一权威，先读这条**）

> **pi-java 与 pi 的 Java 版本对齐，永远是和 pi 目前已经稳定的版本对齐。**

落到操作上：

| 规则 | 具体 |
|---|---|
| **基线是什么** | **最新 release tag**（今天 = `v0.85.1`），**不是 `origin/main` HEAD** |
| **何时重新评估** | pi 发新 tag 时。节奏约**每周一次**（`v0.82.0` 07-24 → `v0.85.1` 09-05，10 个 release / 6 周） |
| **基线内的 `experimental/`** | **不算**。它未稳定 |
| **pi 的下一条线（harness / pico）** | **不在对齐范围内**。记录在 §4，只作参考 |

### 1.1 为什么这条规则让工作量可控：稳定层**已经冻结**

`v0.85.1` → `origin/main`（67 个提交）之间，pi-java 关心的文件：

```
packages/agent/src/agent-loop.ts                0 变化
packages/agent/src/types.ts                     0 变化
packages/agent/src/agent.ts                     0 变化
packages/coding-agent/src/core/session-manager.ts   0 变化   (1746 行，逐字节相同)
packages/coding-agent/src/core/agent-session.ts    81+/53-   (小改)
```

**pi 的产品层在稳定版与 HEAD 之间几乎没动过。** 那 770 + 67 个提交全砸进了 `harness/`、`pico/`、
`experimental/` —— 即下一代的线。

**结论：pi-java 要对齐的那一层是个近乎静止的目标。** 「pi 自己在动」对产品层不成立。

---

## 2. 稳定版基线核实（`v0.85.1`）

| 检查项 | 结果 | 证据 |
|---|---|---|
| 产品线用什么事件 | **`AgentEvent`**（10 变体） | `core/agent-session.ts:21/143/145/402/643` |
| `AgentHarness` 的消费者 | **只有 `experimental/`** | `git grep -l AgentHarness v0.85.1 -- packages/coding-agent/src/` → 仅 `experimental/mini`、`experimental/services`、`experimental/session-worker.ts` |
| 产品持久化 | `core/session-manager.ts`（**1746 行，追加式 JSONL**） | `:1029` `_persist` / `:1058` `_appendEntry` |
| fold 模型（旧 667 行 `harness/reducer.ts`） | **已删除**，只剩 `runtime/reducer.ts` | `git ls-tree v0.85.1 -- .../harness/ \| grep reducer` |
| pico | **不存在** | `git ls-tree v0.85.1 \| grep -i pico` → 空 |
| harness 规模 | 21851 行 / 82 文件（已实现，无产品消费者） | — |

### 2.1 pi 产品的持久化模型（对齐目标，`session-manager.ts:1029-1056`）

```ts
_persist(entry) {
    if (!this.persist || !this.sessionFile) return;
    const hasAssistant = this.fileEntries.some(e => e.type === "message" && e.message.role === "assistant");
    if (!hasAssistant) {                                  // 尚无助手回复 ⇒ 攒着不落盘
        if (this.flushed) appendFileSync(this.sessionFile, `${JSON.stringify(entry)}\n`);
        else this.flushed = false;
        return;
    }
    if (!this.flushed) {                                  // 第一条 assistant ⇒ 整批落盘
        const fd = openSync(this.sessionFile, "wx");
        for (const e of this.fileEntries) writeFileSync(fd, `${JSON.stringify(e)}\n`);
        this.flushed = true;
    } else {
        appendFileSync(this.sessionFile, `${JSON.stringify(entry)}\n`);   // 之后逐条追加
    }
}
```

**三个要点**：① 内存 `fileEntries` 是 run 期间的真源；② 攒到**第一条 assistant** 才落盘；
③ 之后**逐条** `appendFileSync`。**没有事务、没有操作状态、没有 pending 值、没有 in-flight 操作恢复**
—— `git grep "pendingWrite\|deferredWrite\|owedWrite" v0.85.1 -- packages/coding-agent/src/` → **零命中**。

---

## 3. pi-java 现状 vs pi 稳定版

### 3.1 已核实的行为差异（**真差异，需修**）

| 维度 | pi 产品 | pi-java | 影响 |
|---|---|---|---|
| **落盘时机** | 第一条 assistant 整批 + 之后**逐条** | **run 边界**批量 flush（`SessionRunner:169/210`、`AgentSession:741`） | **崩溃窗口：一条 entry vs 一整个 run**（多轮 + 工具调用） |
| **欠账概念** | **无** | `pendingWrites` + `persistedEntryIds` 水位线 | pi-java 的欠写机制在 pi 里没有对应物 |
| **in-flight 恢复** | **无**（崩溃即丢未落盘部分） | 有（fold 派生 owed writes） | 自造的复杂度，且当前实现**不收敛** |

### 3.2 pi-java 的 `agent-core` 是一套自造架构

`Action` / `RunPhase` / record-log / `LaneStateFold` 在 pi 的**产品层里没有对应物** —— 产品层
（`agent-loop.ts`）根本没有持久化架构，只有"内存消息数组 + 追加日志"。

在「**功能等价，实现方式不一定一样**」口径下，这套自造架构**允许存在**。但约束是：
**它的可观察行为必须与 pi 产品等价。** §3.1 第 1 行即为不等价处。

### 3.3 需要单独核实的差异

见 §7 的复核命令。**待核**：会话恢复的定位语义（pi 用 `leafId` + `byId` 内存索引重建；
pi-java 用 `persistedEntryIds` 水位线）在分支/编辑场景下是否产生同一可观察结果。

---

## 4. 不在对齐范围内（记录，不追）

pi 有**下一代的两条线**，都不在范围内。记录在此是为了下次同步时不必重新侦察。

| 线 | 状态（`v0.85.1` / `origin/main`） | 为什么不对齐 |
|---|---|---|
| **harness** `AgentHarness` / `HarnessEvent` | 21851 / 22861 行，已实现；消费者仅 `experimental/` | **没有产品消费者**；且 pi 正用 pico 替换它 |
| **pico** | 设计定稿（`pico-simple-handoff.md` 2400 行，自称唯一规范）；**实现仅 WP1（纯类型，零运行时）**；代码只在 `origin/pico` | 稳定版里**根本不存在**；11 个 WP + 一张 gated 清单（provider / tools / hooks / collapse / jobs 连规格都没定），且每个 WP 需人工 review 才能开下一个 |

**两条已知的"下一代会推翻现在"的事实**（仅供记录，不作为行动依据）：

1. harness 在规范里明文**禁止**了 record-log 折叠模型 —— 旧 `harness/reducer.ts`（667 行）已删除，
   `harness.md:1317`（invariant 5）：*"No read on a hot path may fold history or infer state from an
   absent value — no value history exists to fold."* 替代品是 `OperationState` 的 13 个扁平叶子。
   → 这说明 `docs/21` 当初对齐的是 pi 的**下一代**，不是 pi 的产品。
2. pico 明文**排除**了 harness 的若干机制（`effect gate`、`patch`、`settle`、`status epoch`、
   `workflow replay`），自述 *"pico must build with the rest of `src/harness` deleted"*。

---

## 5. 幸存清单

### 5.1 `docs/23` 的 A1–A4 —— 在 legacy 层**仍然成立**

pi 的 legacy 层**确实**发全 turn 事件与工具三帧，pi-java **确实**没发：

| 项 | 缺陷 | pi 侧证据 |
|---|---|---|
| A1 | 工具结果 run 中不可见 | `agent-loop.ts:794` |
| A2 | `turn_start`/`turn_end` 缺失 | `agent-loop.ts:110/139/176/197/224` |
| A3 | `Start → agent_start` 错位 | `agent-loop.ts:109/138`（每个 `agentLoop` 恰好一次） |
| A4 | `tool_execution_*` 冒牌翻译 | `agent-loop.ts:386/446/501` |

**另两条经核实为真**：① 被拒绝/未找到的调用**也发** `tool_execution_start` + `_end`；
② 助手消息**发** `message_start`，由流的 `start` 事件触发（`agent-loop.ts:321-325`）
—— 原 `docs/23` 三处都漏了它。

`turnIndex` 确实存在，但在 **session 层**（`agent-session.ts:347/767/774/781/786`），不是事件字段。

### 5.2 与 pi 无关的 pi-java 自身缺陷

| 缺陷 | 状态 |
|---|---|
| 落盘时机导致崩溃窗口 = 一整个 run | **待修**（§3.1） |
| `pendingWrites` 欠写不收敛（一旦欠账则永远欠着） | **随落盘时机修复一并作废** |
| A8：流中途 abort 的终态 | **已修**（`175e9fa` + `MidStreamAbortTest`）。`stopReason=aborted` 与 pi 一致 |

### 5.3 `docs/23c` 的方法论 —— **与参照物无关，照用**

§2 差分框架（归一化规则 / P0-P1-P2 分诊 / 不可比清单）、§4 `CrashInjectingPersister` 崩溃注入、
§5 四路载荷往返、§7「**先建框架再改代码**」—— 换基线后全部适用。

---

## 6. 已删除的文档与理由

| 文档 | 删除理由 |
|---|---|
| `docs/23-turn-lifecycle-alignment-design.md` | 628 行里 A6（给钩子传 `toolResults`）**方向错** —— pi 任何钩子都没有该字段；§4.3 ③' 与 pi **相反**（挂起时 pi 发 `turn_end` 再发 `run_suspend`）；A8 的 outcome 半边相反。碎片多，重写比挑拣便宜。A1–A4 已由 §5.1 承接 |
| `docs/26-write-durability-and-deferred-runtime-design.md` | **立论整段失效**：引用的 `harness-v2*.md` 已删除；两个核心机制（`EntryDropped`、`pendingWrites` 清偿）在 pi 里**都不存在**（pi 产品无欠账概念，harness 是永不删 entry + 投影期过滤） |

`docs/23c` **保留**（方法论是资产，L5 差分框架仍在使用）。

**2026-09-13 追加删除**（起因：agent loop 宿主层对齐 pi `agent.ts`，见 `docs/31`）：

| 文档 | 删除理由 |
|---|---|
| `docs/23b-turn-lifecycle-design-review.md` | 审查对象 `docs/23` 已删除，成为孤儿 |
| `docs/16-agent-loop-alignment-design.md` | 立的框架是「补齐 `AgentHarness`」，而该框架正是本次要拆的；4 个缺口均已实现 |
| `docs/19-pi-agent-loop-behavior-diffs.md` | 基准指向即将删除的 `ActionExecutor`（含行号引用）；差分记录已由 `docs/29` 的**机器差分**取代 |
| `docs/20-agent-loop-optimization-plan.md` | `docs/19` 的落地方案，同上 |
| `docs/21-record-log-fold-design.md` | 折叠模型已退休（`docs/30`），自带停止横幅 |
| `docs/22-deferred-writes-and-entry-provenance-design.md` | #1/#2/#3 全废（`pendingWrites` 已定删，`toolBatch`/`terminalFailure` 随折叠链删），仅 #4 仍有效 |
| `docs/25-subagent-support-design.md` | pi-java 无 subagent（`src/main` 零命中）；pi 侧是**扩展示例**而非内核能力 |
| `docs/07c-phase2c-orchestration-design.md` | Phase 2c 阶段设计；其目标（多车道、手动驱动）与本次对齐**直接冲突** —— 留着会被读成当前设计依据 |

> #4「stopReason 入 entry」仍然有效，已由 `docs/03` 的附注与 `docs/29 §9.1` 承接。

**删除不是不可逆**：

```bash
git show 5c8c59c:docs/23-turn-lifecycle-alignment-design.md   # 含 §12 验收测试清单
git show b55cf3f:docs/26-write-durability-and-deferred-runtime-design.md
```

---

## 7. 复核方式

基线 `v0.85.1`；工作副本 `D:\workplaceForai\pi`（跟踪 `my-pi` = `origin/main`，可 `git show v0.85.1:...` 读稳定版）。

```bash
# 规则 1.1：稳定层已冻结
git diff --stat v0.85.1 origin/main -- \
  packages/agent/src/agent-loop.ts packages/agent/src/types.ts \
  packages/coding-agent/src/core/session-manager.ts

# §2：稳定版的产品层归属
git grep -n "AgentEvent" v0.85.1 -- packages/coding-agent/src/core/agent-session.ts | head
git grep -l "AgentHarness" v0.85.1 -- packages/coding-agent/src/     # 期望只有 experimental/
git ls-tree v0.85.1 | grep -i pico                                    # 期望空

# §2.1：产品持久化模型 + 无欠账概念
git show v0.85.1:packages/coding-agent/src/core/session-manager.ts | sed -n '1029,1056p'
git grep "pendingWrite\|deferredWrite\|owedWrite" v0.85.1 -- packages/coding-agent/src/  # 期望空

# §4：下一代的两条线（仅记录）
git ls-tree -r --name-only origin/pico -- packages/agent/src/harness/pico/
git log --oneline -3 origin/pico
```

**备份**：本地 `my-pi-backup-20260913` 停在 `936aff0`（2026-09 更早的状态），可对照历史。
