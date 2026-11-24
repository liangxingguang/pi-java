# 06 会话后端 / 评测 / chord / 防漂移脚本

> 【背景摘要】范围：`pi-java-session-backend-sqlite` ＋ `pi-java-agent-core` 会话持久化面（主流对照 pi `coding-agent/src/core/session-manager.ts`）、`pi-java-evals` ↔ pi `packages/evals`、pi `packages/chord`（R5 不可达）、防漂移脚本（口径外）。
> 当前读数：存储 **44/44**、评测 **49/49**、两域合并 **93/93＝100%**。
> 差异结论：pi 主流会话持久化只有 JSONL＋in-memory，**SQLite 后端无对齐参照物**（B59：R1＋R5 移出分母）；只剩存储 #6／#10 形状不同、#16／#17 残余差异与评测 D 窄差。


> 只保留差异：判定为「对齐」的单元不逐条列，逐条证据见 git 历史；窄差 D 项见 `docs/04` §6.5。
## 基准与范围

- 判定三档：**对齐** / **缺失** / **存疑**。每条给两侧 `file:line`。
- 范围：会话持久化（sqlite 模块与 agent-core 的 JSONL 面）、评测（evals）、chord（R5 不可达）、防漂移脚本（口径外）。

---

## 口径：两条铁律

1. **R1「pi 删掉的，pi-java 也删」**——参照物被删除的单元**权重 0、移出分母**。注意不是改判「对齐」：判据是「和 pi 表现一样」，pi 没这个行为了，就没有对齐可言。
2. **R5「只算 `bin pi` 主流可达的」**——判据是从 `bin pi → dist/bundle/cli.js ← src/cli.ts → main()` 这条线可达。不可达者权重 0、不进分母。

### B59（SQLite schema 分叉）裁决：保持「不作缺口」

- 【场景】pi-java 有 SQLite 会话后端，schema 与 pi 曾经的 session-backends 分叉。旧文记「SQLite schema 完全分叉＝全项目单点最大拖累（Σ权重 25，+16.2 pp）」。
- 【决定】不作缺口、移出分母。pi 主流会话持久化**只有 JSONL ＋ in-memory**（`SessionManager`），没有 SQLite 后端；`packages/session-backends` 整包已删，且删除前就**从未被 `bin pi` 主流消费**（R1 ＋ R5）。旧判定**自始即错**。
- 【风险/代价】无影响。[内部，模型不可见] 不主张删除 SQLite 模块；其独立 schema 不进入 pi 对齐分母。

JSONL 主流互读与 [`03-agent-core.md`](03-agent-core.md) 的会话线形结论使用同一 v3 口径。

---
## 0. 会话持久化各在哪个模块

| 后端 | pi 位置 | pi-java 位置 |
|---|---|---|
| **JSONL（主流）** | `packages/coding-agent/src/core/session-manager.ts`（`SessionManager`，头 `{type:"session", version:3}`，2,036 行） | 写线＝pi v3 形状：`agent-core/.../session/jsonl/PiV3Wire.java`（头/条目/合成行）；存储 `JsonlSessionStorage.java`；内部头记录仍名 `JsonlV4Header`，旧 `kind:"header"` 文件惰性迁移 |
| **Memory（主流）** | `SessionManager.inMemory()`（同文件 `:1804`） | `agent-core/.../session/memory/MemorySessionStorage.java`（`:31`） |
| **SQLite** | ❌ 无（`session-backends` 已删，从未上 `bin pi`） | `pi-java-session-backend-sqlite/`（2,924 行 main；**参照物不存在**，R1/R5 移出分母） |
| **契约层** | `SessionManager` 的公开方法（无独立 `Storage`/`SessionRepo` 接口） | `agent-core/.../session/SessionStorage.java` ＋ `SessionRepository.java` |

**易混点（保留）**：`pi-java-coding-agent/.../core/session/InMemorySessionRepository.java` **不是**
Memory 后端，它是 coding-agent 的**进程内会话注册表**（`-c/-r/--fork` 用）。真正的 Memory 后端在
`agent-core/.../session/memory/`。

java 写出线为 pi v3 形状（包 12/14，`PiV3Wire`）；合成行（lane/record/fact）借 pi 原生
`custom`/`session_info`/`label` 条目承载。pi 侧 `parseSessionEntries` 只做 `JSON.parse`
（`session-manager.ts:353-368`）⇒ **v3 双向互读已通**，残余差异见 #16/#17。

---

## 规模

| 模块 | pi 路径 / 规模 | java（工作树） | 备注 |
|---|---|---:|---|
| 主流会话持久化 | `coding-agent/src/core/session-manager.ts` **2,036** 行 | jsonl 包 **2,539**（14 文件） | java 数含文件系统抽象/仓库层 |
| SQLite 后端（main / test） | **不存在**（参照包已删） | **2,924 / 308** | R1＋R5 作废 |
| 评测（src） | `packages/evals/src` **1,446** | **785** | pi src 两锚点间逐字节未变 |
| 评测（test / eval 用例） | `test/` **815** ＋ `evals/*.eval.ts` **856** | **412** | — |
| **chord（src / test）** | **8,817 / 9,726** | **0** | R5 不可达 ⇒ 权 0 |
| `durable`（R5 面外参照） | **22,473** | **0** | R5 不可达 ⇒ 权 0 |
| 防漂移脚本 | 根 `scripts/` **82 文件 / 10,445 行** | **0** | 口径外（非产品面） |

---

## 存储后端对齐（主流 `SessionManager`）

java 契约面：`SessionStorage.java`（`:19-93`）＋ `SessionRepository.java`（`:12-34`）。

已对齐 18 条（逐条证据见 git 历史）。

**残余差异（#16／#17）**：`lane`／`retainedTail` 为 pi 忽略键（D190）；旧版读取 v1→v2→v3 迁移（D192–D194）。

| # | 权重 | 能力 | pi 证据 | java 证据 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 6 | 2 | 分支创建 / 移动 | `branch:1597`／`resetLeaf:1609`／`branchWithSummary:1618`（树 + leaf 指针） | `SessionStorage.createLane:30`／`moveLane:33`；`TreeCompaction` 走 `navigateTree` 同序 | **对齐（形状不同）**：lane 是 Java 内部扩展，落线 `lane` 键被 pi 忽略 | **D218** |
| 10 | 3 | compaction-aware 上下文投影（含 provenance） | `buildContextEntries:476`／`buildSessionProjection:544`／`SessionProjection:216` | `ContextEntries.projectEntries`＋`ProjectedEntry` | 对齐（形状不同） | — |

**行数**：20 行 —— 对齐 **20** / 缺失 **0** / 存疑 **0**。
**权重**：**Σ = 44**（20 行全部进分母）。
**Σ(w×c) = 44.0** ⇒ **加权完成度 = 44.0/44 = 100%**。

**L4 复核**：落到 #2／#3 的三处：
**L4-R1** `buildSessionInfo` 循环后补检查（pi `:860`，#11956）；**L4-R2** 列表主体外层 catch
（pi `:978-981`／`:2031-2034`，catch 后先转抛 abort 再返回空）；**L4-R3** 候选 stat 失败
（`mtimeOf` 返回 `-1` 哨兵，`buildRichInfo` 按 pi `:806` 再试一次，仍失败则排除）。

**转出行（不重新计入分母）**：map/03 **#77**（恢复会话 active tools，w2）→ B271；
map/03 **#24**（branch summary 生成，w1）→ B272。两者已实施并验证。

---

## ~~SQLite schema 逐表对齐~~ —— **整段作废（R1＋R5，移出分母），B59 销号**

pi 主流没有 SQLite 后端，`packages/session-backends` 已删且删除前从未上 `bin pi`。
原文 18 行（Σ权重 40）不进任何分母；**不主张删 pi-java 模块**。
本基准下该模块的实质变化（002 迁移给合成行补 `parent_id`/`timestamp`、载荷走 `SessionWireShape.toPiTree`、
usage 提为一等 entry）都是**为 pi v3 互读服务**的，其判定效应已计入上表 #11–#16，不再单列为缺口。

---

## 评测对齐

pi-java 侧：自研框架 `EvalSuite`/`EvalCase`/`EvalRunner`/`EvalReporter` ＋ 协议一致性套件。

已对齐 19 条（逐条证据见 git 历史）。

| # | 权重 | 能力 | pi 证据 | java 证据 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 1 | 1 | 声明式 eval 定义（`describeEval`） | `evals/documentation-audit.eval.ts:44`、`tui.docs.eval.ts:262` | `api/EvalSuite.java`（`harnessCases`/`judges`/`judgeThreshold`） | 对齐（形状不同） | D185 |
| 6 | 2 | reporter 落产物（`session.jsonl`） | `src/report.ts:122-134`（`persistSession`）、`:101`（`classifyCaseStatus`）、`:147-157`（兄弟用例过滤） | `report/EvalObservation.java`／`CaseStatus.java`＋`cli/ArtifactLayout.java`＋`HarnessRun.sessionFile`；单臂模型下无需 fullName 选择 | **对齐**（形状不同） | D229 |
| 14 | 3 | **ChatApi 协议一致性套件（C1–C10）** | **无对应物** | `conformance/ChatApiConformanceSuite.java` | 对齐（java 反超） | — |
| 16 | 3 | eval 框架本体 | **外部 npm ×2**（`vitest-evals`/`@vitest-evals/core`；`autoevals` 本锚点已删） | 自研（`evals/api/*.java`） | 对齐（自研替代） | D185 |
| 20 | 2 | **TUI 文档审计 eval** | `evals/tui.docs.eval.ts:23-37/207-260`（context-footer oracle） | `contents/TuiDocsEval.java`（Java 文档问答＋Levenshtein 0.75）＋`fixtures/RecordingFooterOracle.java`＋`tui/component/StatusBar.java`（B293：内置 `percent%/window (auto)` 状态行，pi oracle 同形） | **对齐（形状不同）** | D183/D189 |

**行数**：24 行 —— 对齐 **24** / 缺失 **0** / 存疑 **0**。
**权重**：**Σ = 49** ⇒ **Σ(w×c) = 49.0（对齐）＝ 49.0** ⇒ **加权完成度 = 49.0/49 ＝ 100%**。

---

## chord（**R5 不可达 ⇒ 权 0，不进分母**）

`packages/chord`：src **8,817** / test **9,726**，版本 **1.1.0**。
能力面在 `delta/`（`tracker.ts` 2,205 行、`diff.ts` 523、`index.ts` 694 等）与 `services/*`。

**pi-java 有无对应物：零。** 无 chord/facet/replicated state 的业务命中。

**消费者**（`a00a5048d` 全包检索）：`apps/{p2,p3}`、`packages/durable`、`packages/env` 的 `package.json`；
`packages/agent` 与 `packages/coding-agent` 均不依赖 chord。
这些消费者全在 `bin pi` 主流口径之外 ⇒ **整段权 0**。

> 旧图 21 行能力清单不重列（权重 0）。**保留更正**：`/share` 与 chord 无关
> （`modes/interactive/session-share.ts` 零 chord import，本锚点复核仍为 0），它属 coding-agent 命令面；
> pi-java 侧对应 `pi-java-coding-agent/.../core/SessionShare.java`。

---

## 多进程 / 多端子系统（`coding-agent/src/experimental/**`，**R5 不可达 ⇒ 权 0**）

`experimental/` 本锚点仍在：`{durable,vacation,services,plugins}/`、`session-catalog.ts`、
`session-worker.ts`（import `pi-durable`，2 处）、`coordinator.ts`/`server.ts`/`client*.ts` 等。
全部在 `experimental/` ⇒ R5 不可达，旧 C-1~C-4（Σ11）权 0。

---

## 防漂移脚本（**口径外 —— 非 `bin pi` 产品面**）

pi `scripts/` 是仓库自身构建/发布/校验工具，按 R5 **不进产品加权分母**，作开发流程观察保留。

**pi-java 实际存在的漂移预防机制**：`docs/map/check-drift.sh` 锚点漂移检测、Checkstyle、SpotBugs、
maven-enforcer、L5 跨语言差分一致性框架（`harness/conformance/`）、同族后端分组矩阵
（`session/ConformanceGroup{1,2,3}Test`）、CI 门（`.github/workflows/ci.yml` 单步 `./mvnw clean verify`）。

---

## 汇总（未加权）

参与对齐判定的只有存储后端（20 行）＋ 评测（24 行）＝ **44 行**：

| 档 | 存储后端（新锚点） | 评测（新锚点） | 合计 |
|---|---|---|---|
| **对齐** | 20 | 24 | **44** |
| **缺失** | 0 | 0 | **0** |
| **存疑** | 0 | 0 | **0** |
| 合计 | 20 | 24 | **44** |

**未加权完成度（按行数）= 44 / 44 = 100%**；**按系数（对齐 1 / 存疑 0.5 / 缺失 0）
= 44 / 44 = 100%**（存储后端单独 100%，见下节；评测单独 100%，见上表，B293 后）。

### 移出分母的整块（R1 / R5 / 口径外）

| 子系统 | 参照规模 | 旧行数 | 旧 Σw | 处置 |
|---|---|---:|---:|---|
| SQLite schema | 参照包不存在（已删、从未主流） | 18 | 40 | **R1＋R5（B59 销号）** |
| chord | 8,817 src（R5 不可达） | 21 | 55 | **R5** |
| 多进程子系统 | `experimental/**`（R5 不可达） | 4 | 11 | **R5** |
| harness-only 存储单元 | 旧 harness `session/types.ts`（R1 已删） | 15 | — | **R1**（并入现行 20 行表重判） |
| `durable` / pico3 / micro | `durable` 22,473 行 R5 不可达；pico3/micro R1 已删 | 44 | 44 | **R1 / R5** |
| 防漂移脚本 | `scripts/` 10,445（非产品面） | — | 12 | **口径外** |

---

## 加权汇总

**权重规则**：权重 = 用户可观察影响 × 频率（**3** = 每轮对话都走；**2** = 每次会话走；**1** = 低频/边缘；
**0** = 非目标，排除出分母）。**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重 | Σ(w×系数) | 加权完成度 | 未加权（按系数） |
|---|---:|---:|---:|---:|
| 存储后端（新锚点） | **44（20 行）** | **44.0** | **100%** | 100%（20/20） |
| ~~SQLite schema~~ | **0（作废）** | 0 | — | — |
| 评测（新锚点） | **49（24 行）** | **49.0** | **100%** | 100%（24/24） |
| 合并快照（两域均新锚点） | **93（44 行）** | **93.0** | **100%** | **100%（44/44）** |

### 权重 0（排除出分母）的行

| 域 | 行 | 为什么 0 |
|---|---|---|
| 存储后端 | 写者租约 / 心跳续租 | pi 主流从未有租约 ⇒ 非目标（java 自研，A17） |
| SQLite schema | 全部 18 行 | 参照物不存在（R1）＋从未主流（R5） |
| chord | 全部 | R5 不可达 |
| 多进程 | 全部 | R5 不可达 |
| harness-only / `durable` / pico3 / micro | 全部 59 行 | R1（删除）/ R5（不可达） |
| 防漂移脚本 | 全部 | 非 `bin pi` 产品面 |

## 存疑清单（0 个判定单元；S7 已随 B293 销号）

| # | 能力 | 存疑点 |
|---|---|---|
| S7 | TUI 文档审计（评测 #20） | pi 用 context-footer oracle（`42.2%/272k` 状态行）；**B293 已落地**：L7 `StatusBar` 显示同形 `percent%/window (auto)`（`SessionSnapshot` 携带 `ContextUsage`/`autoCompact`），#20 改判「对齐（形状不同）」。eval 仍用 Java 文档问答＋Levenshtein 0.75 替代 pi 的代码修改任务（D183／D189 保留）。oracle 形态未变（`BUILT_IN_CONTEXT_PATTERN` `:13-15`、内联 `levenshteinSimilarity` `:19-30`） |

> #6 已改判「对齐（形状不同）」并登记 D218；S1 销号。
> #20 已随 B293 改判「对齐（形状不同）」；评测域 24 行全部对齐，**没有剩余缺口**。
