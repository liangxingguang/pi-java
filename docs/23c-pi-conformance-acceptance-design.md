# 23c — pi 一致性验收设计（含 L5 差分框架）

> **编号说明**：本文是 `docs/23`（turn/tool 生命周期）与 `docs/26`（写入清偿 + deferred 运行时）
> **共同的验收设计**。取 `23c` 而非下一个空闲号，是为了让它与 `23`/`23b` 同线 —— 它不接在
> 无关的 `24`（扩展系统）/`25`（subagent）之后。若你更想要独立编号，改名不影响内容。
>
> **上游**：`docs/23` §12（现有验收，仅 L1–L3）、`docs/26` §A.6/§B.8、`docs/19` §10（行为条款映射）。
> **本文补齐的是 `docs/23 §12` 最大的缺口：没有任何手段能证明「与 pi 等价」。**

---

## 0. 三档对齐与验证手段矩阵

`pi/packages/agent/src/types.ts:428-446` 的 `AgentEvent` 是**恰好 10 个**变体；
`pi/packages/agent/src/agent-loop.ts` 是**跑得起来的真代码**（`test/agent.test.ts`、`test/e2e.test.ts` 在跑）；
而 `harness/agent-harness.ts` 是 `HarnessNotImplemented` 桩。**能对什么，取决于这一刀切在哪里。**

| 档 | pi 侧基准 | 「等价」可判定？ | 覆盖项 | 可用手段 |
|---|---|---|---|---|
| **① 可执行对齐** | `Agent`/`AgentSession` 运行中的真代码 + 10 个 `AgentEvent` | **能** —— 同剧本两边跑，逐帧 diff | A1–A4、A6、A7、A8 | L1 L2 L3 **L5** |
| **② spec 对齐** | 只有 `harness-v2.md` 文本；无运行时、无发射点（**子模块级边界见 §0.1** —— 不是整个 harness 层） | **不能** —— 只能「逐条符合 spec + 裁决在案」 | A5、B1、B2 | L1 L2 L3 L4 |
| **③ 在案差异** | 两边结构不同且**不打算改** | 明确**不追求**等价 | `docs/23 §13` 9 条、`docs/26 §B.9` | 附录归档（本文 §8） |

**因此「全部修完」的终态不是「完全对齐 pi」，而是：**

> **①档逐帧等价（L5 证明）+ ②档符合 spec（含裁决记录）+ ③档差异有据可查（不可静默丢失）。**

**②档的「等价」会过期**：基准是 pi 自己都还没实现的 spec 文本，pi 落地时若改动 spec，
「符合」即成历史。**每份验收报告必须写明 spec 基准的 commit / 日期。**

---

### 0.1 子模块级口径（**先于**上文三档适用）

「harness 层对 spec」是**过度概括**。实测 `pi/packages/agent/src/harness/` 共 **3109 行**，
其中**只有一个文件**是空壳。判据必须落到子模块：

| pi 侧 | 行数 | 状态 | 对齐对象 |
|---|---|---|---|
| `harness/reducer.ts` | 667 | ✅ 真实现（`test/harness/reducer.test.ts` 在跑） | **代码** |
| `harness/telemetry.ts` | 615 | ✅ 真实现 | 代码 |
| `harness/skills.ts` | 375 | ✅ 真实现 | 代码 |
| `harness/types.ts`（记录 / Action 类型） | 315 | ✅ 真定义 | 代码 |
| `harness/prompt-templates.ts` / `messages.ts` | 262 / 168 | ✅ 真实现 | 代码 |
| `harness/session/`（jsonl / codec / storage / memory / context） | — | ✅ 真实现（5 个测试） | 代码 |
| **`harness/agent-harness.ts` 的驱动循环** | 508 | ❌ 签名齐、行为全空：`peekAction`/`executeAction`/`runToCompletion` 全是 `return this.unavailable(...)`（`:413-419`），`create.restore` 直接抛（`:351`） | **spec**（`harness-v2.md`） |
| deferred / 挂起 / 赎回 | — | ❌ 只有 spec 文本 | **spec** |

**推论**：`docs/21` 的 `LaneStateFold` 对 `reducer.ts`（真代码）**是对的**；
`docs/26` Part B 对 `harness-v2.md`（spec）**也是对的**。两者不矛盾 —— 对的是不同子模块。

**「判据只能是 spec」的含义**：不是"不对代码"，是**那里没有代码可对**。判断「pi-java 的
`executeAction` 实现对不对」时，pi 侧不存在可比的 `executeAction` —— 只能逐条对照
`harness-v2.md` 的文字。**这台机器为什么必须存在**：pi-java 是产品（SQLite session / resume /
TUI 重连），要求状态是可持久化、可折叠、可恢复的日志，而 `Agent` 层的内存 `AgentMessage[]` 做不到。
它对应 pi 正在重构的目标架构，不是可有可无的装饰。

| | `Agent` 层 | `AgentHarness` 层 |
|---|---|---|
| 判据 | pi 的**行为** | spec 的**文字** |
| 验证 | 同剧本两边跑，逐帧 diff | 人读 spec、逐条打勾 |
| 性质 | **事实核对**（客观、可自动） | **文本解读**（主观、不可自动） |
| **脑补能活下来吗** | **不能** —— 首轮 L5 即暴露 | **能** —— 无机器可证伪 |

> 上一轮的历史证据正好印证最后一行：`docs/23`（①档）的错误是「顺序写反」这种低级 slips，
> 被逐条核对抓到；`docs/22`（②档）的错误是「pi 没实现 ⇒ 不用做」这种**方向性**误判，安安静静
> 活了一整届，直到人工裁决才翻案。

**要付的三笔账**：① spec 有沉默处 ⇒ 必须自己裁决并归档（`docs/26 §B.9`）；
② spec 会变 ⇒ pi 实现落地时「符合」会过期，验收报告须写基准 commit / 日期；
③ 无法自动验证 ⇒ 只能人读，**且这正是脑补最难的藏身处**。

---

## 1. 验收总账

| 项 | 缺陷 | 档 | 主验证层 | 判据 |
|---|---|---|---|---|
| A1 | 工具结果 run 中不可见 | ① | L2 + **L5 S2** | `message_start/end(toolResult)` 在 `turn_end` **之前**出现 |
| A2 | `turn_start`/`turn_end` 缺失 | ① | L2 + **L5 S1–S8** | 帧数与轮数相等、成对、`turn_end` 带 `toolResults` |
| A3 | `Start → agent_start` 错位 | ① | **L5 S1/S8** | `agent_start` 每个 `agentLoop` 恰一次 |
| A4 | `tool_execution_*` 冒牌翻译 | ① | **L5 S2/S4/S5** | 三帧齐备，含被拒/被截断调用 |
| A5 | 崩溃恢复欠写被丢 | ② | **L4** | 崩溃后 entry 真的落库；不复活、幂等 |
| A6 | 钩子拿不到工具结果 | ① | L1 + **L5 S2/S3** | `shouldStopAfterTurn` 收到的 `toolResults` 非空且顺序 == calls |
| A7 | `details` 端到端丢失 | ① | **L3** | 四路往返后仍在 |
| A8 | 流中途 abort 记成 completed | ① | L1（已有 `MidStreamAbortTest`） | **已修复** |
| B1 | deferred 运行时缺失 | ② | L1 + L2 + **L4** | 挂起/赎回/四类结算齐备，换进程可续 poll |
| B2 | 前端工具 schema 未下发 | ① | L1 + **L3** | wire 上工具定义与 registry 一致 |

> **A8 已完成**：`175e9fa`，`MidStreamAbortTest` 钉住。

---

## 2. L5 差分框架

### 2.1 为什么必须有 L5

L1–L3 全是**自洽测试**：把我们对 `docs/23` 的理解写成断言再跑通。它证明「实现符合设计」，
**完全不证明「设计符合 pi」**。上一轮审计里 `docs/23` 被查出 C6 顺序写反、凭空发明
`tool_result_message` 事件族 —— 都是自洽测试抓不到的。**L5 是唯一的外部锚点。**

### 2.2 管线（五段）

```
剧本(script) ──┬─→ pi-runner        ─→ events.pi.jsonl     ─┐
               └─→ pi-java-runner   ─→ events.java.jsonl   ─┴─→ normalize ─→ diff ─→ triage
```

| 段 | 产物 | 位置 |
|---|---|---|
| 剧本文本 | `conformance/scripts/S*.json` | 两侧**共用同一份**，禁止各自维护 |
| pi-runner | `pi/packages/agent/test/conformance/run.ts` | 用 `agentLoop` + faux provider |
| pi-java-runner | `agent-core/src/test/java/.../conformance/ConformanceRunner.java` | 用 `DriveMode.AUTOMATIC` |
| normalize | `NormalizedFrame` 列表 | 两侧各自实现，规则见 §2.3 |
| diff | 差异帧清单 | 一份脚本，输入两个 jsonl |

### 2.3 归一化规则

**只归一化「独立生成、语义等价」的字段；其余一律逐字比对。**

| 字段 | 处理 | 理由 |
|---|---|---|
| `toolCallId` | 按**首次出现序**改名为 `tc1,tc2,…` | 两侧独立生成，值必然不同 |
| 任意 `id` / `runId` / `entryId` / `messageId` | **删除** | 同上 |
| 时间戳 / `spanId` / duration | **删除** | 不具可比性 |
| `usage` 数值 | 降为 `{"usage":true}` | 模型与 provider 不同 |
| 文本内容 | **逐字比对** | 剧本两侧共用，理应由同一 faux provider 产出相同文本 |
| `args` / `details` | **按 JSON 结构比对**（键集合 + 类型），值逐字 | 键序不同不算差异 |

> 文本逐字比对是刻意的强约束：它把「faux provider 两侧行为是否一致」也纳入 L5 的射程。

### 2.4 分诊规则（**不允许「忽略」**）

每一处差异必须**恰好**落到以下之一，并写入验收报告：

| 级别 | 含义 | 处置 |
|---|---|---|
| **P0** | pi-java 违背 `docs/23`/`docs/26` 的设计 | **修代码**，重跑 L5，不得归档 |
| **P1** | 结构性差异，设计上不追求等价 | 写入 `docs/23 §13` 表（新增行，含 pi 行为 / pi-java 行为 / 理由 / 是否可观察） |
| **P2** | pi 侧无运行时或该层不可比 | 列进 §2.5 不可比清单，改由 L2 覆盖，**必须写明理由** |

**P0 是闭环的**：修完重跑；若重跑后仍差异 ⇒ 说明原判 P0 有误，重新分诊为 P1/P2 并说明依据。

### 2.5 不可比清单（P2 的唯一合法入口）

| # | 帧 / 概念 | 为什么不可比 |
|---|---|---|
| 1 | `agent_settled` | `AgentEvent` 恰好 10 个变体，**其中没有 settled**（`types.ts:428-446`）。pi 的「settled」是 `agent_end` 监听器回收语义（`types.ts:420-426` 注释），不是事件。pi-java 的 `AgentSettled` 是 session 层的补充帧 |
| 2 | 全部 deferred 帧 | pi 只有 spec，`fetch_deferred` 无生产者、`write_deferred` 零生产者 |
| 3 | 记录层（`write_deferred` / `EntryDropped` / `StepAttempt` / `QueueConsumed`） | `harness/` 是 `HarnessNotImplemented` 桩；pi 的 `reducer.ts` 是纯函数、无 wire 帧 |
| 4 | 恢复路径的 turn 帧（有未完成 operation 的 lane） | pi 该路径无运行时可比（`docs/23 §13` 第 6 条） |

---

## 3. 剧本（S1–S8）

每份剧本定义：**输入序列 → faux provider 的脚本化响应 → 期望帧序 → 覆盖项 → 可比性**。

帧序依据 `agent-loop.ts` 实读（`:109-137` 起手、`:165-257` 主循环、`:313-375` 助手消息帧、
`:794` 工具结果帧）。**`msg_start(asst)` 由流 `start` 事件触发**（`:321-325`）；若 provider 未发
`start`，则退化为在 `message_end` 前补发一次（`:355`/`:368`）。

### S1 单轮纯文本

```
agent_start
turn_start
message_start(user)  → message_end(user)
message_start(asst)  → message_update(text_delta)* → message_update(text_end) → message_end(asst, stop)
turn_end(0, toolResults=[])
agent_end
```
覆盖 A2/A3。**可比**。期望 `turn_end` 后**无** `turn_start`（外层 break）。

### S2 单轮单工具

```
agent_start, turn_start, message_start/end(user),
message_start(asst), message_update(toolcall_start|delta|end), message_end(asst, tool_use),
tool_execution_start(tc1), tool_execution_update(*), tool_execution_end(tc1, isError=false),
message_start(toolResult), message_end(toolResult),
turn_end(0, [toolResult]),
turn_start, message_start(asst), …, message_end(asst, stop),
turn_end(1, []),
agent_end
```
覆盖 A1/A2/A4/A6。**可比**。**这是 A1 的核心用例**：工具结果必须在 `turn_end(0)` 之前可见。

### S3 串行三工具（`ToolExecution.Sequential`）

帧形状同 S2，`tool_execution_*` 三组**按源序**连续出现。覆盖 A6（`toolResults` 顺序 == calls 顺序）。

### S4 并行批次 + 被拒调用

一批 `{Bash, Read, <被拒>}`。**被拒调用同样发 `tool_execution_start` + `tool_execution_end(isError=true)`**
（`docs/23` 已订正 C6）。`tool_execution_end` 的批次内**顺序可能不同** —— 见 §8 预判差异。

### S5 `length` 截断

助手消息 `stopReason=length` 且带工具调用 ⇒ 走 `failToolCallsFromTruncatedMessage`（`:206-208`）：
**每个调用**发 `tool_execution_start` + `tool_execution_end(isError=true)`，**不执行**。
断言 `turnHadTools=true`（`docs/23 §4.3` ①）。覆盖 A4。

### S6 轮内 steer

第一轮 `turn_end` 后 `getSteeringMessages` 返回一条用户消息 ⇒ 下一轮**在 `turn_start` 之后**
才发 `message_start/end(user)`（`:172-182`）。断言用户帧位置在 `turn_start` **之后**。

### S7 流中途 abort

覆盖 A8 + `docs/23 §4.4`：

```
agent_start, turn_start, message_start/end(user),
message_start(asst), message_update(text_delta)*, message_end(asst, aborted),
turn_end(0, []),
agent_end
```
断言 `turn_end.message.stopReason == "aborted"`、**无** `agent_settled` 之外的额外帧、
且该助手 entry **不进入**后续请求的上下文。

### S8 follow-up 队列

第一轮结束后 `getFollowUpMessages` 非空 ⇒ 外层 `continue` ⇒ 内层发 **`turn_start`（不重新发 `agent_start`）**
（`:259-266`，`firstTurn` 已为 false）。**这是 `docs/23 §13` 第 4 条的判据**：
pi-java 若在 follow-up 处再发一次 `agent_start`，即 P0。

---

## 4. L4 崩溃恢复实测（②档）

用**可控注入**替代 `kill -9`：崩溃点要精确落在「`write_deferred` 记录已落库、entry 未落库」的窗口内。

```java
/** 在指定 entry 的 append 上抛，模拟该窗口内的崩溃。 */
final class CrashInjectingPersister implements EntryPersister {
    private final EntryPersister delegate;
    private final Set<String> crashOn;          // 命中的 entry id
    private final List<String> appended = new ArrayList<>();

    @Override public void append(String lane, ProvisionedEntry<?> e) {
        if (crashOn.contains(e.id())) {
            throw new SimulatedCrash("crash before appending " + e.id());
        }
        appended.add(e.id());
        delegate.append(lane, e);
    }
}
```

| 场景 | 步骤 | 判据 |
|---|---|---|
| 清偿闭环 | 注入崩溃 → 重开存储 → 走恢复 → 驱动到收敛 | 该 entry **出现在存储里**；`pendingWrites` 为空 |
| 幂等 | 同一 owed entry 连续恢复两次 | 存储中**只有一条**（`persistedEntryIds` 生效，SQLite 不抛 `assertUnusedId`） |
| 不复活 | `dropTrailingErrorAssistant` 之后崩溃 → 恢复 | 被丢弃的 error assistant **没有**被写回（`EntryDropped(RETRY)` 生效） |
| 不重复写 | run 末 flush 与 drain 都跑过 | 无重复 id |
| 挂起等价 | 挂起后换进程恢复 | 从日志重建 `Suspended`，能接着 poll（`docs/26 §B.8`） |

**这是 A5/B1 唯一的端到端证据** —— 它们没有 pi 运行时可比，L1/L2 只能证明自洽。

## 5. L3 载荷往返

同一份载荷走四路，逐路断言 `details` 与 deferred handle 保真：

| 路 | 入口 | 断言 |
|---|---|---|
| JSONL | `RecordJsonCodec` / `JsonlStorage` | `details` 键集合与值一致；handle 完整相等（`docs/26 §B.8`「完整相等」） |
| SQLite | `SqliteSessionRepository` | 同上 |
| Web WS | `docs/15` wire | 帧上 `details` 非 `null` |
| RPC | `pi-java-protocol` | 同上 |

**B2 也在这一层**：wire 上的工具定义集合必须与 `ToolRegistry` 一致（含前端工具）。

---

## 6. 通过判据

| 档 | 通过条件 |
|---|---|
| ① | L5 差分：**零 P0**；每处差异已归档为 P1（写入 `docs/23 §13`）或 P2（在 §2.5 清单内） |
| ② | L4 五个场景全绿；spec 对照表逐条打勾；验收报告写明 spec 基准 commit/日期 |
| ③ | `docs/23 §13` 与 `docs/26 §B.9` 每一条都有「理由 + 是否可观察」；**新增差异必须同批复审** |
| 通用 | `mvn -o clean verify` 零错误零警告、零 checkstyle/SpotBugs 违规、文件 ≤ 500 行、无 `System.out.println` |

**不通过的处置**：P0 ⇒ 修 + 重跑，不归档；②档失败 ⇒ 修或**显式降级为 ③档并说明**；
差异无法分诊 ⇒ 说明 §2.4 规则不覆盖，补规则后重跑（**不得直接忽略**）。

---

## 7. 执行顺序

1. 本文件评审（含 §8 预判差异的确认）
2. 实现 `ConformanceRunner`（pi-java 侧）+ 归一化器 —— **先于** A1–A7 的代码修改
3. 实现 pi 侧 `run.ts`；跑 **S1** 建立基线（此时预期大面积 P0，正是缺陷清单）
4. 按 `docs/23 §11` 实施 A1–A7，**每步重跑 S1–S8**
5. 实施 `docs/26` Part A → L4；Part B → L1/L2 + L4
6. B2 → L3
7. 全档收敛，出验收报告（附两侧 jsonl 与差异归档表）

> **第 2 步先于第 4 步是刻意的**：先有差分框架，才有「改对了」的判据；否则又是自洽测试。

---

## 8. 预判差异（首轮 L5 之前已知，首轮必须逐条确认）

这些是**已经能推断**会出现在首轮 diff 里的项。列出来是为了让首轮结果不意外，并作为 §2.4 分诊的起点。

| # | 预判差异 | 初判 | 依据 |
|---|---|---|---|
| 1 | pi-java 多出 `agent_settled` 帧 | **P2** | `AgentEvent` 10 变体无 settled；pi 的 settled 是 `agent_end` 监听器回收语义 |
| 2 | 助手消息的 `message_start` | **P0 候选** | pi 由流 `start` 事件触发（`agent-loop.ts:321-325`）。而 `docs/23` **三处都没有它**：§7 的 wire 帧形状表只列了 toolResult 与 user 两种（`:453-454`）、§12 手工帧序（`:605-606`）与 §4.3 的 `message_update*` 直接跟在 user 帧之后。**连带影响**：§13 第 1 条「wire 帧序列一致」对助手消息**不成立** —— 该条需重新表述或降级 |
| 3 | 并行批次 `tool_execution_end` 顺序 | **P1** | `docs/23 §13` 第 3 条（pi 完成序 / pi-java 源序，`toolCallId` 关联故不可观察） |
| 4 | `usage` 载体与数值 | **P1** | `docs/23 §13` 第 5 条 |
| 5 | auto-compaction 落在 turn 之内 | **P1** | `docs/23 §13` 第 7 条 |
| 6 | follow-up 处的 `agent_start` 粒度 | **P0 候选** | `docs/23 §13` 第 4 条明说「要严格对齐需把 `agent_end` 也改成每 run 一次」—— §S8 会把它变成必答项 |

> 第 2 项是本文档在写作过程中**新发现**的：`docs/23 §12` 期望的 WS 帧序缺助手
> `message_start`，而 pi 是发的。**这是 `docs/23` 的一处待裁决，不是本文件的结论** ——
> 首轮 S1 的结果说了算。
