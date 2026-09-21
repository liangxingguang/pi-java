# 29 — L5 差分验收报告（`docs/28 §5` 第 3 步）

> **状态**：第 3 步**通过**（零 P0）。本文是 `docs/23c §6` ① 档要求的验收报告。
> **注意**：`docs/23c §6` ① 把 P1 归档指向 `docs/23 §13`，而 `docs/23` 已于 `85d0f10` 删除。
> 该归档目标由本文 §5 取代 —— 后续 P1 一律追加到本文 §5 的表里。
> **更新（`docs/31 §8` 第 10 步）**：剧本已增至 10 个且**全部严格**逐帧通过 —— 唯一的
> P1-1 随 `ToolRunner` 拆成 `prepare`/`execute` 两相而结案，放宽规则与守卫已删除。
> §1–§3 保留验收当时的原貌；§4/§5 中两处对 pi 的错误描述（「所有 start 早于任何 end」
> 的结构保证、中止批次「每个已 start 都补 end」）已按 `agent-loop.ts:487-560` 重读修正。
>
> **更新（`docs/31 §8.23.7`，2026-09-16）**：剧本已增至 **13 个（S1–S13）**，全部严格逐帧
> 通过（连跑 7 轮绿）。包 B（工具批次真并发）发现 `S4`/`S12` 原先「等延迟下 end 同序」是
> **两侧运行时的偶然**而非不变量，剧本格式因此加了 `delayMs`（见 §8.23.7 第 1 条）：
> **后文凡描述 S4/S12 的次序，均以 `docs/31 §8.23.7` 的现状为准。**
>
> **补充（`docs/31 §8.23.8`）**：为什么「等延迟下同序」是偶然、三类帧各自服从哪种顺序、
> 以及 pi 侧 `S13` 如何以「end 反源序」自证 —— 逐条图表化记于该节。该节的落点索引列到
> 文件与行号，复核顺序规则时**从那里进**，不必回到本文。
>
> **再更新（`docs/31 §8.24`，包 C，2026-09-16）**：剧本再增至 **14 个（S1–S14）**。
> 新增 `S14`「流式更新与并发批次的交错」（剧本格式加可选字段 `updateEveryMs`），
> 补上此前结构上跑不到的盲区；旧 13 个录制重录后**逐字节不变**。`S4/S12` 的次序描述
> 仍以 §8.23.7 为准。

## 1. 结论

| 项 | 结果 |
|---|---|
| 剧本通过数 | **8 / 8**（S1–S8） |
| 逐帧完全一致 | **7 / 8**（S1、S2、S3、S5、S6、S7、S8 逐字节相同） |
| 有差异 | 1（S4，差异 **2 行**，已归档为 P1-1） |
| **P0** | **0** |
| P1 | 1 |
| P2 | 0（本次未新增；`docs/23c §2.5` 的四条清单不变） |

差分过程**发现并修复了一个真实缺陷**（P0，见 §4）：并行批次的 `tool_execution_start` /
`tool_execution_end` 发射次序与 pi 不符。修完后重跑，S4 只剩 2 行次序差异，且该差异是结构性的。

## 2. 基准与复现环境

| 项 | 值 |
|---|---|
| pi 基准 | tag **`v0.85.1`** = `d981de1229ef899957bbe968bc8dcda02a21f477`（提交日期 2026-09-05） |
| pi 检出 | `D:\workplaceForai\pi-v0.85.1`（`git worktree`，detached HEAD；**不动用户的 `my-pi`**） |
| pi-java 基准 | 分支 `agent-core-pi-loop`，起点 `49ab4fb` |
| 运行日期 | 2026-09-13 |
| Node | v24.19.0 |

对齐到 **release tag** 而非 pi HEAD，依据 `docs/27`。实测 `packages/agent/src/agent-loop.ts`
与 `types.ts` 在 `v0.85.1` 与 `my-pi` HEAD 之间**逐字节相同**，因此这个选择不影响被移植的代码。

复现 pi 侧真相：

```bash
cd <pi 检出>/packages/agent
CONFORMANCE_SCRIPTS=<pi-java>/conformance/scripts \
CONFORMANCE_OUT=<pi-java>/conformance/pi-out \
npx vitest --run --config vitest.conformance.config.ts test/conformance/run.test.ts
```

复现 Java 侧并比对（连同 8 个剧本一起跑）：

```bash
JAVA_HOME="D:/soft/jdk/graalvm-jdk-25" \
  D:/soft/apache-maven-3.9.9/bin/mvn -o -am -pl pi-java-agent-core test -Dtest=ConformanceTest
```

## 3. 差分机器的构成

`docs/28 §5` 第 3 步写作「用 `docs/23c §2` 的 L5 差分跑 S1–S8」，但这台机器此前**不存在**
（`com.pijava.agent.session` 下的 `Conformance*` 是存储后端一致性测试，同名不同物）。
按 `docs/23c §7`「先建框架，再改代码」，本次先补齐五个部件：

| # | 部件 | 位置 |
|---|---|---|
| 1 | 共用剧本 | `conformance/scripts/S1.json` … `S8.json` |
| 2 | pi 侧 runner | `conformance/pi/run.test.ts` + `vitest.conformance.config.ts` |
| 3 | pi 侧真相（基线产物） | `conformance/pi-out/S*.pi.jsonl` |
| 4 | pi-java 侧 runner + 归一化器 | `pi-java-agent-core/src/test/java/com/pijava/agent/harness/conformance/` |
| 5 | 差分器 + 放宽规则 | `ConformanceDiff` |

两点设计约束值得记录：

- **剧本显式写出流式分块**（`"chunks": [...]`），不由启发式推断。否则两侧要各自实现同一套
  分块规则，差异会来自分块而非被测对象。缺省即「整段一次成型、零 delta」。
- **归一化**（`docs/23c §2.3`）把 `toolCallId` 按首次出现次序改名 `tc1,tc2,…`，丢弃
  id / timestamp / usage，文本逐字比较，对象键序抹平。两侧用同一套规则 —— Java 侧产出与 pi
  侧**键序相同的单行 JSON**，差分因此退化成逐行字符串比较。

## 4. 差分发现的缺陷（P0，已修）

### 4.1 并行批次：start 与 end 交错

`PiLoopTools.executeParallel` 原先在同一个循环里发出
`start₁ → end₁ → start₂ → end₂ → …`，而 pi 的并行分支是**三段**时序
（本节初版对 pi 的描述有误，已按 `agent-loop.ts:487-560` 重读修正）：

- 准备循环按**源序**交替发 start 与准备（`:497-545`）；准备相当场失败的调用
  （拒绝 / 未找到 / 参数非法 / 已中止）**在准备循环内**就收尾 end（`:505-517`）——
  它的 end 因此排在批次后续的 start **之前**；
- 拿到执行票的调用打包成延迟任务，end 由任务自己在完成时发（`:520-541`），
  中止检查也发生在任务执行时（`:521-524`）；
- 批次收束（`Promise.all`，`:547`）后，结果消息按**源序**补发（`:549-554`）。

「所有 start 都早于任何 end」**不是**该模式的保证 —— 它只在批次里没有 immediate
调用、也没中止时成立（S4 的录制里被拒调用恰是源序最后一个，才有初版误判的余地）。
更值得注意的是：`PiLoopTools` 的类 javadoc 本来就写着「所有 start 先按**源序**发出，
end 随各自完成」——**代码与自己的文档相反**，这条从第 2 步起就潜伏着。

**验证过它确实会被抓到**：把该文件回退到修复前版本重跑，S4 以 5 帧错位失败
（`start₁,end₁,start₂,end₂,…` vs `start₁,start₂,start₃,end₃,end₁,end₂`）；恢复修复后 9/9 绿。
回归哨兵：`PiLoopTest.parallelBatchEmitsEveryStartBeforeAnyEnd`。

**中止路径**（差分未覆盖）：批次开始前 `signal` 已中止时，pi 只为**第一个**调用发帧
—— start → 准备 → end → `break`（`:514-516` / `:542-544`），后续调用既无 start 也无 end。
本节初版把 pi 误读成「每个已 start 的调用都补一个 `Operation aborted` 错误 end」，
当时的实现与哨兵跟着钉住了这个自创形状。两相拆分时按 pi 真实形状改回
（`PiLoopTest.abortedParallelBatchFramesOnlyTheFirstCallLikePi`）。

## 5. P1 归档（已结案）

**P1-1（S4 的 end 次序）— 已关闭，规则已删除。** 曾经唯一的放宽规则
`PARALLEL_TOOL_END_ORDER` 存在，是因为 `PiLoop.ToolRunner` 是单相同步端口，
没有 pi `prepareToolCall` 的「immediate（准备相定局）vs prepared（执行完成）」二分，
被拒调用的 end 因此排不到准备循环里去。端口拆成 `prepare` / `execute` 两相后
（对齐 `agent-loop.ts:607-675` / `:677-764`），该二分与「immediate 在准备循环内收尾」
一并落地：规则文件、`ConformanceTest.RELAXATIONS` 表与
`declaredRelaxationsAreStillEffective` 守卫全部删除，**十个剧本自该步起全部严格
逐帧比较**。若将来要新增放宽规则，放回流程见 `ConformanceDiff` 的 javadoc
（归类 + 理由 + 等效性守卫，缺一不可）。

## 6. 本次差分**未覆盖**的范围

以下都跑不到，因此**不能**从「S1–S8 全绿」推出它们等价：

| 未覆盖项 | 原因 |
|---|---|
| `tool_execution_update` | 剧本不产生流式工具中间结果，无帧可比 |
| thinking 内容块 | 剧本未含 thinking 块 |
| ~~`prepareNextTurn` / `shouldStopAfterTurn`~~ | ~~八个剧本里两者恒为 `null`~~ —— **`prepareNextTurn` 已由 S9 覆盖**，见 §8；`shouldStopAfterTurn` 仍是盲区 |
| `terminate` 语义（**every** 而非 any，`:589-591`） | 无剧本置 `terminate: true` |
| ~~`usage` / token 记账~~ | **本行两度过时**：按 `docs/23c §2.3`「有意丢弃」——❌ 3a（`docs/31 §8.19`）起 usage 已**进帧**（但两侧恒零 ⇒ 差分空转，即 `docs/42` A1）；**2026-09-22 包H1 步 7 起由 S15 实质覆盖**（全部分量＋可选键＋小数 cost 逐字节差分），见 §10 |
| 车道 / 记录日志 / 持久化 | 有意排除：本次直连 `PiLoop`，对标 `agentLoop`，非 pi 的 harness 层 |
| 工具批次中途 abort | 剧本不覆盖；改由 §4.1 末尾的单测兜底 |
| 多轮 steer 与 follow-up 的组合 | S6/S8 各只覆盖一种、各只有一轮 |

## 7. 对 `docs/28 §5` 第 3 步判据的回应

> 判据：「零 P0；差异按 P1/P2 归档」

- **零 P0**：达成。唯一的 P0（§4.1）已修并重跑验证。
- **差异归档**：1 条 P1（§5），0 条 P2。—— P1-1 其后已**结案**（放宽规则删除，S4 起严格），见 §5。

## 8. 追加：S9（2026-09-13，`docs/31 §7` 第 6 条）

`docs/31` 实施时发现 `PiLoop` 的 `NextTurnUpdate` 少一个 `context` 字段 —— 压缩的落地通道。
补上之后需要一条差分剧本守住它，于是新增 **S9**：`prepareNextTurn` 非 `null`，整体替换
上下文（消息 + 系统提示）并切模型。**9 / 9 通过，S9 与 pi 逐字节相同。**

**一个必须记下来的坑**：归一化后的帧**只有 agent 事件**，请求消息本身从不进帧（剧本的流是
假的，不看参数）。所以「上下文被整体替换」这个后果在帧里**完全不可观察** —— 只加一条
`prepareNextTurn` 非 `null` 的剧本，它会在钩子根本没接上时照样通过。

因此给共享剧本格式加了一个字段 `echoRequest`（两侧 runner 各约 6 行，逐字相同）：它把本次
请求的形状（消息数 / 模型 / 系统提示）编进首个文本块。S9 第二轮回显
`[n=1 model=openai/switched sys=SYS2]` —— 替换生效时为 `n=1`，未生效时为 `n=3`。

**反向实验**：把 Java 侧 `driver::prepareNextTurn` 换成 `null`，S9 立刻失败；其余剧本不受影响。

**S9 仍不覆盖**：`AgentLoopTurnUpdate.thinkingLevel` —— 它不出现在任何帧上，差分**结构上**
验证不了，所以没有写进剧本（写进去只会让人以为已覆盖）。
- **通用判据**：`mvn -o -am -pl pi-java-agent-core test` **427/427 绿**；checkstyle 零违规
  （既有 7 条 warning 均为改动前就存在的文件）；新增文件全部 ≤ 500 行；无 `System.out.println`。

⇒ `docs/28 §5` 第 3 步判据达成。（第 4 步「删除旧状态机」此后已**撤销** —— 复核发现旧状态机
不是死代码，`PiLaneEngine` 复用了 `ActionExecutor` 的 `run`/`runContinue`/`finishRun`，
`peekAction` 也仍在 live 路径上被调用；见 `docs/28 §2.4` 与 §5。）

## 8. 对用户 pi 检出的副作用（可回退）

| 位置 | 动作 | 可回退性 |
|---|---|---|
| `D:\workplaceForai\pi` | `npm ci --ignore-scripts`（308 包 / 282MB） | `node_modules/` 在 `.gitignore` 内；跟踪文件未改 |
| `D:\workplaceForai\pi-v0.85.1` | 新建 `git worktree`（detached @ `v0.85.1`） | `git worktree remove` 即可；`my-pi` 分支未动 |

pi 自身的一处配置坏死在此记录：`packages/agent/vitest.config.ts` 的 alias 表早于
`@earendil-works/pi-ai/utils/*` 子路径导入，**在 `v0.85.1` 和 `my-pi` HEAD 上都跑不了它自己的
`agent-loop.test.ts`**。本次用独立配置文件绕过（补上 `/utils/(.+)$` 别名后其自测 23/23 绿），
**没有**改动 pi 的配置文件。

## 9. 差分之外发现的缺陷（记录，未修）

`docs/28 §5` 第 5 步要求「`records` 发射点接到新循环上」。核对时发现该子句**已由第 2 步的接线
满足**（见 §9.1），而补上「新循环日志是否仍可折叠」这一空白覆盖后，**找到一个两条路径共有的
既有缺陷**（§9.2）。它**不属于 L5 差分发现**，在此显式声明，以免与 §4.1 混为一谈。

### 9.1 第 5 步第一子句的核对（已满足）

`LaneRecord` 全部 11 个变体在新驱动路径上都有发射点：

| 变体 | 发射点（新路径） |
|---|---|
| `OperationStarted` / `OperationFinished` | `ActionExecutor.run` / `finishRun`（`PiLaneEngine` 起手收口复用） |
| `StepAttempt` / `UsageRecord` | `PiLaneSink.emitAssistantRecords` |
| `ToolStarted` / `ToolFinished` | `PiLaneSink.emitToolRecords` |
| `QueueConsumed` | `PiLaneEngine.emitQueueConsumed` |
| `QueueEnqueued` / `QueueCancelled` | `QueueManager`（会话层队列 API） |
| `WriteDeferred` | `HarnessUtils.recordDeferredWrite`（`PiLaneSink.append` 调用） |
| `AbortRequested` | `AgentHarness` 的 abort 路径 |

### 9.2 缺陷：`deferredWriteIds` 使两条折叠派生在真实日志上恒为空

**症状**：对**真实**日志调用 `LaneStateFolder.fold`，`toolBatch()` 把**每条**工具调用都标成
`missing`（`resultEntryId == null`）；同理，任何由 step 产生的 `error` 条目都不会被判为
`terminalFailure()`。

**根因**：`HarnessUtils.recordDeferredWrite` 对**运行期追加的每一条 entry** 都发一条
`WriteDeferred`（`LaneOperationFold.deferredWriteIds` 因此收下全部运行期 entry id，含每个工具
结果），而两条派生都用 `!deferredWriteIds.contains(entryId)` 作排除条件 —— 排除条件把每个候选
都滤掉，派生恒为空。

**两条路径都有**：把同一折叠施加在旧步进链（`peekAction`/`executeAction`）产生的日志上，同样
复现 `resultEntryId=null`。缺陷自 Phase 21 起就存在。

**为何一直没有暴露**：`ToolBatchFoldTest` / `TerminalFailureFoldTest` 的记录集是**手搓的最小集**
（只给目标条目配一条 `WriteDeferred`），而 `LaneStateFoldTest` / `DeferredWriteFoldTest` 虽然折叠
真实日志，却**从不断言 `toolBatch()` / `terminalFailure()`**。本次新增
`PiLaneEngineTest.recordLogOfANewLoopToolRunFoldsBackToTheLiveLane` 是第一个折叠真实工具轮日志的
用例，它把这个缺陷照了出来（该用例因此**刻意不破口**这两个派生，只在注释里指向本节）。

**当前无生产影响**：`AgentHarness.restoreFromRecords` 消费的字段是
`idle / runId / stepIndex / newestOwn / 三个队列 / pendingWrites`，**不含** `toolBatch()` 与
`terminalFailure()` —— 两者在 `src/main` 里零消费者。

**不修的理由（裁决 2026-09-13：退休）**：修它需要先判定「是排除条件过宽，还是
`recordDeferredWrite` 过宽」，而 `LaneOperationFold` javadoc 引用的判据
（`pi reducer.ts:479-486` / `:614-640` 等）**指向一份 pi 已删除的文件**（详见 §9.3）。两个方向都能
让现有测试变绿，靠读代码无法裁决。

裁决为**退休整条折叠链**，因此本缺陷**不修** —— 修它等于加固一段要删的代码。实施蓝图见
**`docs/30`**。新增的那个折叠用例随之改为断言恢复后的可驱动性（`docs/30 §5` Step 2）。

### 9.3 相关背景：折叠模型的 pi 参照已不存在

`docs/28 §5` 第 5 步要求让 `LaneOperationFold` / `LaneStateFolder` 退休。核对参照实现时确认：

- `v0.85.1` 的 `packages/agent/src/harness/runtime/reducer.ts` 只有 **232 行**，是**实时事件
  归约器**（`reduceLaneSnapshot`，把 `HarnessEvent` 归约进 lane 快照），**不是**日志折叠器。
- `deferredWrite` / `WriteDeferred` / `deriveToolBatch` / `reduceLaneState` 在 `v0.85.1` **与**
  用户 `my-pi` 检出上**全仓零命中**。
- π 侧 `harness.md:1317` invariant 5 明文禁止该模型：
  *"No read on a hot path may fold history or infer state from an absent value — no value history exists to fold."*

即：`LaneOperationFold` 是一套**仅存在于 pi-java** 的构造，其 javadoc 的行号引用指向 pi 已删除的
667 行旧 `reducer.ts`。退休与「跟 legacy 还是跟 harness」的裁决耦合，见 `docs/28` 的后续步骤。

## 10. 追加：S15 —— 剧本加 usage（2026-09-22，包H1 步 7 / `docs/42` 裁决 C）

A1 的终结：**usage 第一次被差分实质观察**。剧本模型加可选 `usage`（字段名＝pi 的
`Usage` 键名，pi 侧零翻译；缺省 ⇒ 两侧同零，S1–S14 一字不动）；S15 单轮文本回合挂
**全分解**：四分量非零（100/20/40/10）、可选键在场（`cacheWrite1h=6`/`reasoning=8`
⇒ 钉「非空才带」与 pi 侧 stringify 丢 undefined 的对偶）、`totalTokens=170`、
cost 五字段全取**二分小数**。

**二分小数是刻意的**：JS `JSON.stringify` 与 Java `Double.toString` 的小数格式在
`<1e-3` 分叉（`0.00002` vs `2.0E-5`），二分小数（0.125、0.03125…）的最短往返表示
两侧逐字一致 ⇒ 差分测的是**接线与键集**，不是两门语言浮点打印的方言差异。

**锚点重验**（原 §2 的 worktree 已被清理，重建）：`git worktree add --detach
D:/workplaceForai/pi-v0.85.1 v0.85.1` ＋ `npm ci --ignore-scripts` ⇒ 重生成 S1–S14
**剥 CRLF 后与已提交基线 14/14 逐字节相同**（孪生改动行为守恒、环境无漂移），
S15 基线同批落盘。L5 现为 **15/15**。

**探针实测红集**（细节在 `docs/42 §10`）：桩恒零→恰 S15 红；`usageOf` 丢
`cacheWrite1h`→恰 S15 红；`num()` 整数归一失效→**15/15 全红** —— usage 渲染被
每一个剧本观察，形状守护从此不是摆设。

**新登记（剧本夹具盲区，未修）**：裁决 C 预告的「thinking 不对称」已核实 ——
**pi 侧桩对 thinking 块不发任何生命周期事件**（`conformance/pi/run.test.ts` 的
forEach 只有 text/toolCall 分支，thinking 只进 `final` 的内容），而 Java 桩发
`thinking_start`/`thinking_end` 帧 ⇒ **含 thinking 的剧本结构上必红**。与 usage
正交（`usage.reasoning` 只是数字），S15 不依赖它。要补 thinking 断面，需先给
pi 侧桩补 thinking 事件（孪生同路，`docs/38` 口径）⇒ 独立裁决项。
