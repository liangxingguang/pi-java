# 29 — L5 差分验收报告（`docs/28 §5` 第 3 步）

> **状态**：第 3 步**通过**（零 P0）。本文是 `docs/23c §6` ① 档要求的验收报告。
> **注意**：`docs/23c §6` ① 把 P1 归档指向 `docs/23 §13`，而 `docs/23` 已于 `85d0f10` 删除。
> 该归档目标由本文 §5 取代 —— 后续 P1 一律追加到本文 §5 的表里。

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
`start₁ → end₁ → start₂ → end₂ → …`，而 pi 的并行分支是**两相**的：

- 准备循环里把**全部** `tool_execution_start` 按源序发出（`agent-loop.ts:547`）；
- 随后 `Promise.all` 按各自**完成序**发 `tool_execution_end`（`:550-553`）。

「所有 start 都早于任何 end」因此是该模式的**结构保证**，不是时序巧合。更值得注意的是：
`PiLoopTools` 的类 javadoc 本来就写着「所有 start 先按**源序**发出，end 随各自完成」——
**代码与自己的文档相反**，这条从第 2 步起就潜伏着。

**验证过它确实会被抓到**：把该文件回退到修复前版本重跑，S4 以 5 帧错位失败
（`start₁,end₁,start₂,end₂,…` vs `start₁,start₂,start₃,end₃,end₁,end₂`）；恢复修复后 9/9 绿。
回归哨兵：`PiLoopTest.parallelBatchEmitsEveryStartBeforeAnyEnd`。

**顺带对齐的中止路径**（差分未覆盖，声明在此以免被误当作差分发现）：原有的并行分支在
`signal` 已中止时「发一个调用的帧就 break」，剩下的调用既无 start 也无 end。按 pi 的
`prepareToolCall` 优先返回 immediate 错误结果（`:655-661`）改为**每个已 start 的调用都补一个
`Operation aborted` 错误 end**。哨兵：`PiLoopTest.abortedSignalFailsEveryToolCallWithoutExecutingIt`。

## 5. P1 归档

| # | pi 行为 | pi-java 行为 | 理由 | 是否可观察 |
|---|---|---|---|---|
| P1-1 | 并行批次内 `tool_execution_end` 按**完成序**发出；被 `beforeToolCall` 拦下的调用在准备循环内立即收尾（`:534-542`），其 end 排在所有真正执行过的调用**之前** | end 按**源序**发出（完成序恒等于源序） | `PiLoop.ToolRunner` 是**单相同步端口**，没有 pi `prepareToolCall` 的「immediate（未执行）vs prepared（已执行）」二分，同步实现里没有「完成序」这个概念 | **是** —— 但在并行批次**内部**，且只影响 end 的相对次序；`start` 全部早于任何 `end` 这一结构保证两侧一致 |

对应代码：`ConformanceDiff.Relaxation.PARALLEL_TOOL_END_ORDER`，仅对 S4 生效。
该放宽被 `ConformanceTest.declaredRelaxationsAreStillEffective` 强制要求**仍然生效** ——
一旦失效（例如将来把端口改成两相）测试会红，提醒删除豁免而不是让它长期留存。

## 6. 本次差分**未覆盖**的范围

以下都跑不到，因此**不能**从「S1–S8 全绿」推出它们等价：

| 未覆盖项 | 原因 |
|---|---|
| `tool_execution_update` | 剧本不产生流式工具中间结果，无帧可比 |
| thinking 内容块 | 剧本未含 thinking 块 |
| `prepareNextTurn` / `shouldStopAfterTurn` | 八个剧本里两者恒为 `null` |
| `terminate` 语义（**every** 而非 any，`:589-591`） | 无剧本置 `terminate: true` |
| `usage` / token 记账 | 按 `docs/23c §2.3` 归一化时**有意丢弃**，L5 结构上不覆盖 |
| 车道 / 记录日志 / 持久化 | 有意排除：本次直连 `PiLoop`，对标 `agentLoop`，非 pi 的 harness 层 |
| 工具批次中途 abort | 剧本不覆盖；改由 §4.1 末尾的单测兜底 |
| 多轮 steer 与 follow-up 的组合 | S6/S8 各只覆盖一种、各只有一轮 |

## 7. 对 `docs/28 §5` 第 3 步判据的回应

> 判据：「零 P0；差异按 P1/P2 归档」

- **零 P0**：达成。唯一的 P0（§4.1）已修并重跑验证。
- **差异归档**：1 条 P1（§5），0 条 P2。
- **通用判据**：`mvn -o -am -pl pi-java-agent-core test` **427/427 绿**；checkstyle 零违规
  （既有 7 条 warning 均为改动前就存在的文件）；新增文件全部 ≤ 500 行；无 `System.out.println`。

⇒ `docs/28 §5` 第 4 步（删除旧状态机）的**推进条件已满足**。按用户裁决，删除仍然**最后**做。

## 8. 对用户 pi 检出的副作用（可回退）

| 位置 | 动作 | 可回退性 |
|---|---|---|
| `D:\workplaceForai\pi` | `npm ci --ignore-scripts`（308 包 / 282MB） | `node_modules/` 在 `.gitignore` 内；跟踪文件未改 |
| `D:\workplaceForai\pi-v0.85.1` | 新建 `git worktree`（detached @ `v0.85.1`） | `git worktree remove` 即可；`my-pi` 分支未动 |

pi 自身的一处配置坏死在此记录：`packages/agent/vitest.config.ts` 的 alias 表早于
`@earendil-works/pi-ai/utils/*` 子路径导入，**在 `v0.85.1` 和 `my-pi` HEAD 上都跑不了它自己的
`agent-loop.test.ts`**。本次用独立配置文件绕过（补上 `/utils/(.+)$` 别名后其自测 23/23 绿），
**没有**改动 pi 的配置文件。
