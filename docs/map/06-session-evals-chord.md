# 06 会话后端 / 评测 / chord / 防漂移脚本

> 基准：pi-java @ 351d199（main，2026-10-04）；pi @ 200387122（2026-10-04）。
> 范围：`pi-java-session-backend-sqlite` ↔ pi 会话持久化（主流 `coding-agent/src/core/session-manager.ts`）；`pi-java-evals` ↔ pi `packages/evals`；
> pi `packages/chord`（R5 不可达）；pi 仓库根 `scripts/`（口径外）。
> 判定三档：**对齐** / **缺失** / **存疑**。每条给两侧 file:line。
> pi 参照版本：`1.0.2`（`packages/evals/package.json` version 字段）。

---

## 口径（本轮换锚的两条硬约束）

本轮 pi 从 `3390bd936`（2026-09-20）前进到 `200387122`（2026-10-04），251 提交，其中一个提交
`7fd478a2e`（2026-10-01）删掉了 **105,456 行**。裁决见 [`../11-pi-reanchor-ruling.md`](../11-pi-reanchor-ruling.md)，
本文件按下面两条铁律重判：

1. **R1「pi 删掉的，pi-java 也删」** —— 参照物被删除的单元**权重 0、移出分母**（不是改判「对齐」，
   J5：判据是「和 pi 表现一样」，pi 没这个行为了，就没有对齐可言）。
2. **R5「只算 `bin pi` 主流可达的」** —— 判据是从 `bin pi → dist/bundle/cli.js ← src/cli.ts → main()`
   这条线可达。不可达者权重 0、不进分母。

### §3.1 的作废依据（本文件最重的一条）

旧文给「SQLite schema 完全分叉」记了 **全项目单点最大拖累（Σ权重 25，+16.2 pp）**。实测：

```
$ git -C /d/workplaceForai/pi grep -l "session-backends\|sqlite-node" 3390bd936 -- 'packages/*/src/**' 'packages/*/package.json'
3390bd936:packages/session-backends/sqlite-node/package.json     ← 只有它自己
```

⇒ **在旧锚点上 `session-backends` 就已经零消费者**，从没上过 `bin pi` 这条线。那 25 点权重是给一个
**R5 不可达子系统**算的 —— 这不是漂移造成的，是**旧口径漏了可达性检查**，被换锚顺带暴露。
本包把整段 SQLite schema 按 R1（参照物 `7fd478a2e` 整包删除）＋ R5（旧锚点就零消费者）**作废，移出分母**。

⚠️ 后果：`pi-java-session-backend-sqlite` 模块的**参照物已删**。本文件只如实标注，
**不主张删除该模块**（删模块是独立裁决，见裁决 §7 J3）。

### 会话格式换了参照物

旧文判「JSONL v4 双向不可读」的 `{v:4, storageVersion:1}` 是 **harness 的格式**，已随 D2 删除：

```
$ git -C /d/workplaceForai/pi grep -n "storageVersion" 200387122 -- 'packages/*/src/**'   → 零命中
$ git -C /d/workplaceForai/pi grep -n "JSONL_STORAGE_VERSION\|JSONL_FORMAT_VERSION" 200387122 -- 'packages/*/src/**' → 零命中
```

主流 pi 的会话持久化**一直是 JSONL**，定义在 `coding-agent/src/core/session-manager.ts`（v3 头）。
本文件的 JSONL 判定**全部按新的 v3 主流格式重新取证**（见「存储后端对齐」#15–#19）。

---

## 0. 会话持久化各在哪个模块（重写）

| 后端 | pi 位置（`200387122`） | pi-java 位置 |
|---|---|---|
| **JSONL（主流）** | `packages/coding-agent/src/core/session-manager.ts`（`SessionManager`，头 `{type:"session", version:3}`） | `pi-java-agent-core/.../agent/session/jsonl/`（`JsonlCodec` / `JsonlSessionStorage` / `JsonlSessionRepository`，头 `{kind:"header", version:4}`） |
| **Memory（主流）** | `SessionManager.inMemory()`（同文件 `:1804`） | `pi-java-agent-core/.../agent/session/memory/` |
| **SQLite** | ❌ **参照物已删** —— `packages/session-backends` 在 `7fd478a2e` 整包删除（旧锚点即零消费者，从未上 `bin pi`） | `pi-java-session-backend-sqlite/`（**参照物已删**，本包按 R1/R5 移出分母） |
| **契约层** | `SessionManager` 的公开方法（无独立 `Storage`/`SessionRepo` 接口） | `agent-core/.../agent/session/SessionStorage.java` + `SessionRepository.java`（旧文对齐的是**已删除的 harness `session/types.ts`**） |

**结论（重写）**：旧文「三种后端两侧 1:1 存在」**不再成立**。pi 主流只有 **JSONL ＋ in-memory** 两条；
SQLite 从未上主流，参照物已删。pi-java 保留三后端（含 SQLite 模块），但 SQLite 一侧无对齐目标。

**另有一处易混点（保留）**：`pi-java-coding-agent/.../core/session/InMemorySessionRepository.java` **不是** Memory 后端，
它是 coding-agent 的**进程内会话注册表**（`-c/-r/--fork` 用）。真正的 Memory 后端在
`agent-core/.../session/memory/`（`MemorySessionStorage.java`）。

---

## 规模

> ⚠️ **本节已于 2026-10-04 重测**：pi 从 `3390bd936` 前进到 `200387122`（251 提交）。

| 模块 | pi 包 / 路径 | 旧锚点 | **新锚点** | 变化 | java LOC | 备注 |
|---|---|---|---|---|---|---|
| 会话持久化 · 主流 JSONL | `coding-agent/src/core/session-manager.ts` | 367 | 变更 | **+173 / −31**（204 行变动） | — | v3 格式，见「存储后端」 |
| 会话持久化 · SQLite（src） | `session-backends/sqlite-node/src` | 1973 | **0（整包删除）** | −100% | 2875 | **R1＋R5 作废** |
| 会话持久化 · SQLite（test） | 同上 `test/` | 1909 | **0（删除）** | −100% | 282 | 同上 |
| 会话持久化 · harness JSONL/Memory | `packages/agent/src/harness/session/**` | 7108 | **0（删除）** | −100% | 4194 | **R1 作废** |
| 评测（src） | `packages/evals/src` | 1446 | **1446** | **逐字节未变** | 785 | 仅 2 个非 src 文件改动 |
| 评测（test / evals） | 同包 `test/` ＋ `evals/*.eval.ts` | 815 | 815 | 仅 `documentation-audit.eval.ts` +10/−4 | 412 | — |
| **chord（src）** | `packages/chord/src` | 6503 | **8817** | **+2314（+35.6%）** | **0** | R5 不可达 ⇒ 权 0 |
| **chord（test）** | `packages/chord/test` | 4376 | **9726** | +5350 | **0** | R5 不可达 ⇒ 权 0 |
| **durable（新家）** | `packages/durable/src` | 757 | **17716** | **+23×** | **0** | R5 不可达 ⇒ 权 0 |
| ~~pico3~~ | `packages/agent/src/harness/pico3` | 7994 | **0（删除）** | −100% | 0 | R1 作废 |
| ~~micro / mini~~ | `packages/coding-agent/src/experimental/{micro,mini}` | 1524 | **0（删除）** | −100% | 0 | R1 作废 |
| **防漂移脚本** | `scripts/` | 40 文件 / 8325 | **48 文件 / 9060** | +8 文件 / +735 行 | **0** | 口径外（非产品面） |

> **pi 的包结构（`git ls-tree 200387122:packages/`）**：`agent` · `ai` · `chord` · `client` · `codemode` ·
> `coding-agent` · `durable` · `evals` · `mcp` · `protocol` · `server` · `telemetry` · `tui`（**13 个，无 `session-backends`**）。

---

## 基准漂移（`3390bd936` → `200387122`，本文件引用）

> ⚠️ **取证方式**：pi 本地 clone 仍停在 `3390bd936`（`git rev-parse HEAD` 实测）。**本文全部新状态用
> `git -C /d/workplaceForai/pi show 200387122:<path>` 取，绝不读工作树**。

| 旧文引用的参照物 | 旧出现次数 | 新锚点状态 | 处置 |
|---|---:|---|---|
| `packages/agent/src/harness/**` | 37 | **删除**（`7fd478a2e`，108 文件） | 改指 `coding-agent/src/core/session-manager.ts` 或作废 |
| `packages/session-backends/**` | 8 | **整包删除**（`7fd478a2e`，33 文件） | **作废（R1＋R5）** |
| `packages/agent/src/harness/pico3` | 20 | **删除** | **作废（R1）** |
| `packages/durable` | 24 | 仍存在但 **import 者全在 `experimental/`** ⇒ R5 不可达 | **作废（R5）** |
| `packages/chord` | — | 仍存在，`src` 6503 → **8817**（`delta/` 重构） | 保留，**权 0（R5）** |
| `packages/evals` | — | `src` **逐字节未变** | 判定不变 |

**chord 侧的 `delta/index.ts` 从 1948 行缩到 694 行**（能力拆去新文件），旧文「增长全在 `delta/index.ts` 一处」**不再成立**。

---

## 存储后端对齐（按 R5 主流 `SessionManager` 重判）

判定基准（pi `200387122`）：`packages/coding-agent/src/core/session-manager.ts` 的 `SessionManager` 公开面。
pi-java 对应：`pi-java-agent-core/.../agent/session/SessionStorage.java`（`:19-93`）＋ `SessionRepository.java`（`:12-34`）＋ `Session.java`。

> ⚠️ **旧文那 35 行对齐的是已删除的 harness `session/types.ts`**（`Storage`/`Session`/`SessionRepo` 三接口）。
> 本轮逐条重判：有主流对应物的**改指**；只有 harness/SQLite 才有的**作废**（见文末「作废清单」）。

| # | 权重 | 能力 | pi 证据（`200387122`） | java 证据 | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 1 | 2 | create 建会话 | `SessionManager.create:1755`／`newSession:1057`（写 header 行 `:1064-1071`） | `SessionRepository.create:18`；`SqliteSessionRepository` | 对齐 | — |
| 2 | 2 | list 列会话（扫描目录 → `SessionInfo[]`） | `static list:1900`／`listAll:1921` | `SessionRepository.list:27` | 对齐 | — |
| 3 | 1 | list 进度回调 ＋ **部分结果增量发布** ＋ `AbortSignal` ＋ **并发扫描** | `SessionListProgress:884-889`（签名 `(loaded,total,partialSessions?) => void`）；节流 `CURRENT_SESSION_LIST_PUBLISH_INTERVAL = 10`（`:893`）；`listSessionsFromDir:941-971`；`signal?.throwIfAborted()` `:879/:911/:946/:971` | **无**（`SessionRepository.list` 同步全量、无回调、无中止、无并发） | **缺失**（4 项） | **B55** |
| 4 | 2 | open 打开会话 | `static open:1766` | `SessionRepository.open:24` | 对齐 | — |
| 5 | 2 | fork 分支会话 | `forkFrom:1815`／`createBranchedSession:1632` | `SessionRepository.fork:33` | 对齐 | — |
| 6 | 2 | 分支创建 / 移动 | `branch:1579`／`resetLeaf:1591`／`branchWithSummary:1600`（树 + leaf 指针） | `SessionStorage.createLane:30`／`moveLane:33`（lane 概念） | **存疑**（粒度不同：pi 单 leaf 树，java lane） | — |
| 7 | 3 | 追加 entry（message / thinking_level_change / model_change / custom / custom_message） | `appendMessage:1204`、`appendThinkingLevelChange:1217`、`appendModelChange:1230`、`appendCustomEntry:1290`、`appendCustomMessageEntry:1339`；落盘 `_persist:1172`（`appendFileSync :1187`） | `SessionStorage.appendEntry:41` | 对齐 | — |
| 8 | 3 | compaction 条目 | `appendCompaction:1260`；`CompactionEntry:91-104`（`summary`/`firstKeptEntryId`/`tokensBefore`/`details?`/`usage?`/`fromHook?`/`systemMessage?`） | `Entry.Compaction:126`（多 `retainedTail`；缺 `fromHook`/`systemMessage`） | **存疑**（列集分叉） | — |
| 9 | 3 | 分支摘要条目 | `branchWithSummary:1600`（entry `:1612`）／`BranchSummaryEntry:106-118`（`fromId`/`summary`/`details?`/`usage?`/`fromHook?`） | `Entry.BranchSummary:145`（缺 `fromHook`） | **存疑** | — |
| 10 | 3 | compaction-aware 上下文投影（含 provenance） | `buildContextEntries:476`／`buildSessionProjection:543`／`SessionProjection:216` | `session/ContextEntries.java` + `MutationReplayer` | **存疑**（形状不同） | — |
| 11 | 3 | **`context_edit` 条目（append-only 上下文改写）** | `ContextEditEntry:175-182`（`targetId`/`replacement`）；`appendContextEdit:1360`；投影 `projectContextEntry:519-538` | **无** | **缺失（新增）** | — |
| 12 | 2 | 会话名 | `appendSessionInfo:1304`／`getSessionName:1318`（`SessionInfoEntry:142-147`，`type:"session_info"`） | `SessionStorage.getName:72`／`setName:75`；载体＝`facts` 表 `kind='name'` | **存疑**（载体不同：pi entry / java 专用表） | — |
| 13 | 1 | entry 标签 | `getLabel:1432`／`appendLabelChange:1441`（`LabelEntry:135-141`，`type:"label"`） | `SessionStorage.getLabel:78`／`setLabel:81`；载体＝`facts` `kind='label'` | **存疑**（同上） | — |
| 14 | 2 | usage 条目 ＋ 会话用量汇总 | `UsageEntry:80-90`（`type:"usage"`）；`appendUsage:1244`；`core/usage-totals.ts` | record `usage` ＋ `SessionStats`（`SessionStorage.getStats:86`） | **存疑**（载体不同） | — |
| 15 | 3 | **JSONL 头部线格式** | `SessionHeader:43-56`＝`{type:"session", version:3, id, timestamp(ISO 串), cwd, parentSession?}`；写点 `:1064-1071`；`CURRENT_SESSION_VERSION = 3`（`:41`） | `JsonlV4Header.java:19-35`＝`{kind:"header", version:4, id, createdAt(epoch ms), cwd, parentSessionId?, legacyParentSessionPath?, metadata?}`；`JsonlCodec.encodeHeader:83-104` | **缺失（双向不可读）** | — |
| 16 | 3 | **JSONL 条目线格式（type 词表 ＋ 基字段）** | 基字段 `SessionEntryBase:57-63`＝`{type, id, parentId, timestamp(ISO 串)}`（**无 `seq`**）；`SessionEntry:183-197` 11 个 type：`message`/`thinking_level_change`/`model_change`/`usage`/`compaction`/`branch_summary`/`custom`/`custom_message`/`context_edit`/`label`/`session_info` | java 行＝`{kind:"entry", type, id, seq, parentId, timestamp(epoch ms), …}`（多 `kind`+`seq`，时间戳类型不同）；`Entry.java:34-42` 7 个 type；另有 `record`/`lane`/`fact` 三类行（`JsonlCodec.encodeMutation:139-192`） | **缺失（词表+信封分叉）** | — |
| 17 | 2 | **JSONL 旧版本读取（v1→v2→v3 迁移）** | `migrateV1ToV2:287`／`migrateV2ToV3:316`／`migrateToCurrentVersion:337`（读旧文件后 `_rewriteFile()` 重写） | `JsonlCodec.parseHeader:107-134` 要求 `kind:"header"` ⇒ 读不了 pi 的 `{type:"session"}` 文件 | **缺失** | — |
| 18 | 2 | JSONL 文件名 / 目录名 | `:1078-1080`＝`${ISO.replace(/[:.]/g,"-")}_${id}.jsonl`（**不 encode**）；`getDefaultSessionDirPath:589-594`＝`--<cwd, /\\:→->--` | `JsonlSessionRepository.sessionFileName:250-253` 同形；`sessionDirectoryName:245-247` 同形 | **对齐**（**改判**：旧文称 pi 用 `encodeURIComponent` 是 harness 遗留错误） | — |
| 19 | 2 | **会话文件惰性创建（首个 user/assistant 消息才落盘）** | `_hasConversation:1166-1170`；`_persist:1172-1191`（setup 条目只留内存，`#10000`） | `JsonlSessionStorage.create:44-46`（create 即写 header 文件） | **缺失（新增）** | — |
| 20 | 1 | 内存后端 | `SessionManager.inMemory:1804` | `MemorySessionStorage`（agent-core）＋ `InMemorySessionRepository`（coding-agent 注册表） | **存疑**（java 两构造 vs pi 单类） | — |

**行数**：20 行 —— 对齐 6 / 缺失 6 / 存疑 8。
**权重**：**Σ = 44**（无权重 0 行 ⇒ 计入分母 20 行）。
**Σ(w×c) = 13×1.0（对齐）＋ 17×0.5（存疑）＋ 0（缺失） = 21.5** ⇒ **加权完成度 = 21.5/44 = 48.9%**。
（旧：33 行 / Σ66 / 36.0 / **54.5%**。行数与分母下降的原因＝15 行 harness-only 单元按 R1/R5 作废、2 行合并，另新增 2 行主流新能力。）

---

## ~~SQLite schema 逐表对齐~~ —— **整段作废（R1＋R5，移出分母）**

> ⚠️ **2026-10-04 换锚作废。** 原文 18 行（Σ权重 40）全部移出分母，**不主张删 pi-java 模块**。
> 作废依据（裁决 §3.1，实测）：
> - **R1**：参照物 `packages/session-backends` 在 `7fd478a2e` **整包删除**（33 文件，77,640 B）。
> - **R5**：`git grep -l "session-backends\|sqlite-node" 3390bd936 -- 'packages/*/src/**' 'packages/*/package.json'`
>   **只命中它自己的 `package.json`** ⇒ **旧锚点即零消费者**，从未上 `bin pi` 这条线。
> - 旧文据以判「v4 硬故障」的 harness 格式也已删（`git grep storageVersion 200387122` 零命中）。
>
> **保留的洞察（供日后重开用）**：旧文记「pi 用一张通用值表（`scalar_values`+`list_values`）承载分支 tip、lane 配置/状态、
> 整个 operation 状态机、pending 队列、会话名、entry 标签；pi-java 拆成 8 张用途专用表 ＋ 租约/迁移台账/FTS 三张」的
> 分叉格局，是**已删除 harness 后端**的形状，不是主流 pi 的形状 —— 主流 pi 根本没有 SQLite 后端。

---

## 评测对齐

pi 侧（`200387122`）：`packages/evals/src` **逐字节未变**（1446 行）。本轮仅 3 个非 src 文件改动：

| 文件 | 改动 | 影响 |
|---|---|---|
| `evals/documentation-audit.eval.ts` | `submitAudit` schema 增 `verdict:"inconclusive"`、删两个 evidence 字段；提示词重写（要求 cite 实现路径/符号） | 评测用例**语义细化**，不提判定；`describeEval:43`、`toolCalls:59` |
| `package.json` | version `0.86.1 → 1.0.2`；移除 `typescript` devDep；`vitest 4.1.9 → 4.1.11` | 依赖版本，**框架集合不变**（仍 `vitest-evals`+`@vitest-evals/core`+`autoevals`） |
| `docker/entrypoint.ts` | `assertWorkspace` 不再要求 `npm-shrinkwrap.json` | 与脚本侧删 shrinkwrap 门一致 |

pi-java 侧：`pi-java-evals`（自研框架 `EvalSuite`/`EvalCase`/`EvalRunner`/`EvalReporter` ＋ 协议一致性套件）—— **本包零改动**。

| # | 权重 | 能力 | pi 证据 | java 证据 | 判定 |
|---|---|---|---|---|---|
| 1 | 1 | 声明式 eval 定义（`describeEval`） | `evals/documentation-audit.eval.ts:6/43`、`tui.docs.eval.ts:13` | `api/EvalSuite.java:8-14` | 对齐（形状不同） |
| 2 | 3 | **真 coding-agent harness**（临时目录真会话 + transcript/usage 回读） | `src/harness.ts:471-475`（`createPiCodingAgentHarness`）、`:517-523`（`createPiDocumentationEvalHarness`） | **无**（`api/EvalContext.java:13-22`） | **缺失** |
| 3 | 2 | **LLM-as-judge 评分** | `tui.docs.eval.ts:12-13`（`Levenshtein`+`createJudge`）、`extensions.docs.eval.ts:2`（`StructuredOutputJudge`/`ToolCallJudge`） | **无**（`api/EvalResult.java:14-17` 只有布尔） | **缺失** |
| 4 | 2 | baseline vs candidate A/B 对照表 + 变体计划 | `src/report.ts:36-75`；`src/plan.ts:1/39` | **无** | **缺失** |
| 5 | 2 | 对照报告汇总（paired metric / correctness lift） | `src/report.ts:359/436` | **无** | **缺失** |
| 6 | 2 | reporter 落产物（`session.jsonl`） | `src/report.ts:123`（`persistSession`）、`:101`（`classifyCaseStatus`） | 部分：`api/EvalReporter.java:15/23`（不落盘） | **缺失** |
| 7 | 2 | 会话 JSONL 快照作为 test attachment | `src/report.ts:9`（`PI_SESSION_SNAPSHOT_ARTIFACT`） | **无** | **缺失** |
| 8 | 2 | 从 run 里查工具调用（`toolCalls`） | `evals/documentation-audit.eval.ts:6/59` | **无** | **缺失** |
| 9 | 3 | eval 运行器 CLI | `src/cli.ts`（`parseEvalCli`/`compareDiscovery`）；`package.json` 双轨入口 | 部分：`runner/EvalRunner.java:38`（纯内存） | **存疑** |
| 10 | 2 | docs 审计 eval | `evals/documentation-audit.eval.ts:10-59` | **无** | **缺失** |
| 11 | 2 | 扩展作者 eval | `evals/extensions.docs.eval.ts:1-40`（走 judge） | 部分：`ExtensionLifecycleTest.java:33` | **存疑** |
| 12 | 2 | 模型作者 eval | `evals/models.docs.eval.ts` + `configured-runtime.ts` | **无** | **缺失** |
| 13 | 2 | provider 集成 eval（本地 HTTP mock server） | `evals/acme-server.ts:1-154` + `custom-provider.docs.eval.ts` + `openai-provider.docs.eval.ts` | 部分：`ProviderSmokeTest.java:37` | **存疑** |
| 14 | 3 | **ChatApi 协议一致性套件（C1–C10）** | **无对应物** | `evals/conformance/ChatApiConformanceSuite.java:24/36-45` | 对齐（java 反超） |
| 15 | 2 | 简单 smoke eval | `evals/smoke.eval.ts:10-16` | 部分：`ProviderSmokeTest.java:37` | **存疑** |
| 16 | 3 | eval 框架本体 | **外部 npm ×3**（`vitest-evals`/`@vitest-evals/core`/`autoevals`） | 自研（`evals/api/*.java`） | 对齐（自研替代） |
| 17 | 1 | 框架单测 | `test/*.test.ts`（815 行） | `test/conformance/*` 等 | 对齐 |
| 18 | 3 | **Docker eval 执行** | `src/docker.ts:38`＋`docker/Dockerfile`＋`docker/entrypoint.ts` | **无** | **缺失** |
| 19 | 2 | **文档变体任务计划** | `src/plan.ts:1`（`DOCUMENTATION_VARIANTS`）、`:39`（`createTaskPlan`） | **无** | **缺失** |
| 20 | 2 | **TUI 文档审计 eval** | `evals/tui.docs.eval.ts:12-13/192/229` | **无** | **缺失** |

**行数**：20 行 —— 对齐 4 / 缺失 12 / 存疑 4。
**权重**：**Σ = 43** ⇒ **Σ(w×c) = 12.5** ⇒ **加权完成度 = 12.5/43 = 29.1%**（未变）。
台账 `docs/05:443`「evals 完整测试矩阵」= 唯一相关条目，仍 OPEN。

---

## chord（**R5 不可达 ⇒ 权 0，与旧裁决一致**）

> ⚠️ **本轮更新**：chord `src` 6503 → **8817**（+35.6%）、`test` 4376 → **9726**。**增长不是单点** ——
> `src/delta/index.ts` 反而从 **1948 缩到 694**（能力拆去新文件）。新增：
> `delta/diff.ts`(523) · `delta/tracker.ts`(2205) · `delta/apply-immutable-trusted.ts`(128) ·
> `delta/revision-validator.ts`(75) · `delta/draft.ts`(10)；`services/*`（consumer/instances/provider/state/state-codec/state-internals/wire）、`api.ts`、`json.ts`、`types.ts` 均改。
> **旧文「21 行判定全部不变、仅 5 处内部锚点漂号」已失效**：本轮是 `delta/` 的**整块重构**，不是内部加固。

**pi-java 有无对应物：零。** `grep -rn "chord\|Chord" docs/*.md`、`grep -rli "facet|replicated state|chord" --include=*.java .` 均无业务命中。

**消费者（`200387122`）**：`packages/{client,coding-agent,protocol,server,durable}/package.json` 声明 `@earendil-works/chord`
（＋ `coding-agent/examples/plugins/pi-example-plugin`）。**`packages/agent` 已不再依赖 chord**（harness 删除，旧文的 7 包 → 5 包）。

**R5 裁决**：chord 的消费者全在 `bin pi` 主流之外（`durable` 不可达、`protocol`/`client`/`server` 在 R5 口径下也不进分母）
⇒ **整段权 0、不进分母**（`docs/07 §7.2`「已取消（R5）：chord 地基」）。

> 旧文 21 行能力清单与「多进程架构」的逐条 `file:line` **本轮不重列**（权重 0，且参照面已随 `7fd478a2e` 变动）。
> 需要时按 `200387122` 重取。**唯一保留的更正**：`/share` 与 chord 无关（`modes/interactive/session-share.ts` 零 chord import），
> 它属 coding-agent 的命令面。

---

## 多进程 / 多端子系统（`coding-agent/src/experimental/**`，**R5 不可达 ⇒ 权 0**）

> ⚠️ **本轮更新**：`experimental/{mini,micro}` 已被 `7fd478a2e` 删除；新增 `experimental/{durable,vacation}/`
> 与 `session-catalog.ts`（`48dd1e2f0`「port the experimental client/server to **pi-durable**」）；
> `session-worker.ts` 现 import `pi-durable`。**该子系统全部在 `experimental/` ⇒ R5 不可达**（`docs/07 §7.2`：
> 「已取消（R5）：多进程子系统」）⇒ **整段权 0、不进分母**。
>
> pi-java 的近似物仍是 `pi-java-server`（单进程、JVM 内、Unix socket 控制面 + 独占租约 + 快照订阅），
> 无子进程 worker / coordinator / facet-service。旧文 C-1~C-4（Σ11）随 R5 移出分母。

---

## ~~整块缺失：pi 新增的三块（`durable` / `pico3` / `micro`）~~ —— **整段作废**

| 块 | 新锚点状态 | 处置 |
|---|---|---|
| `pico3`（`agent/src/harness/pico3`） | **删除**（`7fd478a2e`） | **R1 作废** |
| `micro`／`mini`（`coding-agent/src/experimental/*`） | **删除**（`7fd478a2e`） | **R1 作废** |
| `durable`（`packages/durable`） | 仍存在（+23×，17716 行），但 **import 者全在 `coding-agent/src/experimental/**`** ⇒ R5 不可达 | **R5 作废** |

> 旧文给这三块记了 **44 行 / Σ权重 44**（durable 19 + pico3 19 + micro 6），**全部移出分母**。
> `durable` 是 harness 与 session storage 的新家（D2 的 `R088/R068/...` 重命名目标），但**没上过 `bin pi`**。
> ⚠️ 若哪天 durable 进主流，这是一整块新大陆（裁决 §7 J2）。

---

## 防漂移脚本（**口径外 —— 非 `bin pi` 产品面**）

> ⚠️ **口径说明**：pi `scripts/` 是仓库自身的构建/发布/校验工具，**不是 `bin pi` 可达的功能面**，按 R5
> **不进产品加权分母**。本节作为开发流程观察保留（旧文曾把它算进「整块缺失」，本轮移出）。

**pi-java 有无对应物**：**没有 `scripts/`、没有 ArchUnit / PMD / japicmp**（`grep -rn "archunit" --include=pom.xml .` 零命中）。

pi `scripts/` **48 文件 / 9060 行**（旧 40 / 8325）。本轮变化：

- **删除**：`generate-coding-agent-shrinkwrap.mjs`（386 行）—— `package.json` 的 check 链不再有 shrinkwrap 门，改由 `tsc --noEmit` ＋ `biome check` 覆盖。
- **新增**：`model-catalog-protocol.ts`(260)＋`.test.ts`(120)、`update-model-catalog-pin.mjs`(115)＋`.test.mjs`(149)、`npm-audit.mjs`(83)、`smoke-test-codemode-binary.mjs`(178)、`durable-browser-smoke-entry.ts`(8)、`biome/model-type-comparison.grit`(16)。
- `check` 链（`package.json:20`）仍是 ~8 步：`biome check` ＋ `check:pinned-deps` ＋ `check:runtime-deps` ＋ `check:ts-imports` ＋ `check:entry-graphs` ＋ `check:install-lock:coding-agent` ＋ `tsc --noEmit` ＋ `check:browser-smoke`。

**pi-java 实际存在的漂移预防机制（file:line）**：

1. **Checkstyle**（Maven `validate`，`failsOnError=true`）—— `pom.xml:139-168`；规则含 `LineLength max=120`（`checkstyle.xml:12`）、`FileLength max=500`（`checkstyle.xml:18`）。
2. **SpotBugs**（`verify`，`effort=Max`/`threshold=Low`）—— `pom.xml:171-190` ＋ `spotbugs-exclude.xml`。
3. **maven-enforcer** —— `pom.xml:115-136`：`requireJavaVersion [25,26)` ＋ `dependencyConvergence` ＋ `banDuplicatePomDependencyVersions`。
4. **L5 跨语言差分一致性框架**（**pi-java 独有**）—— `pi-java-agent-core/src/test/.../harness/conformance/`：S1–S14 逐剧本与 pi 录制真相逐帧比对。
5. **同族后端分组矩阵** —— `ConformanceGroup{1,2,3}Test` × `{Memory,Jsonl,Sqlite}`。
6. **CI 门** —— `.github/workflows/ci.yml:35` 单步 `./mvnw clean verify`（三平台矩阵）。

---

## 台账纠错

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **⚠️ 证伪：SQLite schema 分叉** | 旧文记「全项目单点最大拖累 Σ权重 25、+16.2pp」（`docs/05` 正文未登记） | **自始即错** —— 参照物旧锚点即零消费者、新锚点整包删除 ⇒ 那段是给 R5 不可达子系统算的分，**移出分母** | 见本文件 §「口径」 |
| **⚠️ 证伪：JSONL v4 双向不可读** | 旧文记 pi 写 `{v:4, storageVersion:1}`、java 写 `{version:4}` ⇒ 双向不可读 | **参照物已删**；真状况：pi 主流写 `{type:"session", version:3}`，java 写 `{kind:"header", version:4}` ⇒ **仍双向不可读**，但**理由换了**（头判别字面量 + 字段名/类型 + 条目词表，见 #15–#17） | `session-manager.ts:44-51/1064-1071`；`JsonlV4Header.java:19-35` |
| **A17 写者租约** | 「java 反超/越界」 | **维持**。pi 主流从未有租约（旧 harness 删物）⇒ java 侧 `WriterLease`/`WriterLeaseRows`/心跳是**自研**；`SqliteSessionRepository.DEFAULT_TTL_MS=30_000`（`:37`）。台账归条目不变，但仍**权 0** | `SqliteSessionRepository.java:37` |
| **E6 拆分超限文件** | 记为未结 | **`SqliteSessionStorage` 已解决**（现 463 行，已拆 `storage/` 子包）；只剩 `AgentHarness`（**544 行** = E4） | `SqliteSessionStorage.java` 463 行；`AgentHarness.java` 544 行 |
| **E7 `appendEntry`/`appendRecord` 重复** | 保留（判断项） | **三处**（JSONL/Memory/SQLite） | `JsonlSessionStorage.java`、`MemorySessionStorage.java`、`SqliteSessionStorage.java` |
| **H §9.3 `/export`/`/import` 占位符** | 台账判「应为 OPEN」 | **台账错，应为 CLOSED** —— `/export`（HTML/JSONL）与 `/import` **已接线**；pi-java 另有 `/share` | `MiscCommands.java:38/61/76` |
| **⚠️ 新发现（未登记）：JSONL 头/行格式分叉（v3 主流 vs java v4）** | `docs/05` 零处 | 见 #15–#17：判别字面量、version、时间戳类型、`seq` 字段、条目词表、信封 `kind` 全不同 ⇒ **java 读不了 pi v3；pi 也读不回** | pi `session-manager.ts:41-55/183-196`；java `JsonlCodec.java:83-104/139-192` |
| **⚠️ 新发现（未登记）：`context_edit` 条目** | `docs/05` 零处 | pi 新增 append-only 上下文改写入能力，java 零对应 | `session-manager.ts:175-182/1360` |
| **⚠️ 新发现（未登记）：会话文件惰性创建** | `docs/05` 零处 | pi 首个 user/assistant 消息才落盘（`#10000`），java create 即写 header | `session-manager.ts:1166-1190` |
| **⚠️ 更正：旧文列的新会话文件多数不新** | 任务线索称 `session-export.ts`/`export-html/*`/`session-picker.ts`/`session-share.ts` 为主流新增 | **实测多数在旧锚点即已存在**（`git cat-file -e 3390bd936:<path>` 全 YES，`git log 3390..200387122` 无提交）；**唯一新增的 `experimental/session-catalog.ts` 在 `experimental/` ⇒ R5 不可达** | `git log --oneline 3390bd936..200387122 -- <path>` |
| **⚠️ 更正：`/share` 不是 chord 能力** | 曾把 `/share` 列 chord 名下 | **不成立**（维持旧更正）—— `session-share.ts:50/57` 零 chord import；pi-java 侧对应 `SessionShare.java` | `session-share.ts:1-13` |

---

## 汇总（未加权）

> ⚠️ **已随 pi `200387122` 重算**。SQLite schema（18 行）整段作废，故**参与对齐判定的只有存储后端 + 评测**。

按**能力单元**逐行统计（存储后端 20 行 + 评测 20 行 = **40 行**）：

| 档 | 存储后端 | 评测 | 合计 |
|---|---|---|---|
| **对齐** | 6 | 4 | **10** |
| **缺失** | 6 | 12 | **18** |
| **存疑** | 8 | 4 | **12** |
| 合计 | 20 | 20 | **40** |

**未加权完成度（按行数）= 10 / 40 = 25.0%**；**按系数（对齐 1 / 存疑 0.5 / 缺失 0）= (10 + 12×0.5) / 40 = 16 / 40 = 40.0%**。

### 移出分母的整块（R1 / R5）

| 子系统 | 旧规模 | 旧行数 | 旧 Σw | 处置 |
|---|---|---:|---:|---|
| SQLite schema | `session-backends` 1973 src（**删**） | 18 | 40 | **R1（参照物删）＋R5（旧锚点零消费者）** |
| chord | 8817 src（仍在，R5 不可达） | 21 | 55 | **R5** |
| 多进程子系统 | `experimental/**`（R5 不可达） | 4 | 11 | **R5** |
| `durable`/`pico3`/`micro` | 删除（pico3/micro）＋不可达（durable） | 44 | 44 | **R1 / R5** |
| 防漂移脚本 | `scripts/` 9060（非产品面） | — | 12 | **口径外** |

---

## 加权汇总

> ⚠️ **本表已于 2026-10-04 随 pi `200387122` 重算**。旧值括注在「旧」列。

**权重规则**：权重 = 用户可观察影响 × 频率（**3** = 每轮对话都走；**2** = 每次会话走；**1** = 低频/边缘；**0** = 非目标，排除出分母）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重（旧） | Σ权重（新） | Σ(w×系数)（旧→新） | 加权完成度（旧→新） | 未加权（按系数） |
|---|---:|---:|---|---:|---:|
| 存储后端 | 66（33 行） | **44（20 行）** | 36.0 → **21.5** | 54.5% → **48.9%** | 50.0%（10/20） |
| ~~SQLite schema~~ | 40（17 行） | **0（作废）** | 5.5 → **0** | 13.8% → — | — |
| 评测 | 43（20 行） | **43（20 行）** | 12.5 → **12.5** | 29.1% → **29.1%**（未变） | 30.0%（6/20） |
| **本范围（R5 可达）** | 149（70 行） | **87（40 行）** | 54.0 → **34.0** | 36.2% → **39.1%** | **40.0%（16/40）** |

**本范围合计**：Σ权重 **87** ／ Σ(w×c) **34.0** ／ **加权完成度 = 34.0/87 = 39.1%**。
　　（旧：149 / 54.0 / **36.2%** ⇒ **+2.9 pp**，**全部来自分母缩小**：SQLite schema 40 点移出 + 存储后端移除 15 行 harness-only 单元。）

**全部计入（R5 口径下 = 本范围）**：Σ权重 **87** ／ Σ(w×c) **34.0** ／ **39.1%**。
　　（旧「全部计入」= 271 / 56.5 / **20.8%** ⇒ 旧值含 chord 55 + 多进程 11 + 三块 44 + 脚本 12 = 122 点不可达/删除权重；本轮按 R5 全部移出。）

> **重读**：旧文 20.8% 是**对着不可达/已删除子系统**算的低分。剔除后本范围升到 **39.1%**。
> 升降**只由分母与判定口径驱动**，不是 pi-java 干了什么。**存储后端是唯一实质降分处**（54.5% → 48.9%），
> 降因＝新公式把 15 行 harness-only「缺失」单元移出，同时补入 2 行主流新能力（`context_edit`、惰性落盘）均缺失。

### 权重 0（排除出分母）的行

| 域 | 行 | 为什么 0 |
|---|---|---|
| 存储后端 | 写者租约 / 心跳续租（旧 #23/#29） | pi 主流从未有租约（旧 harness 删物）⇒ 非目标 |
| SQLite schema | 全部 18 行 | **参照物整包删除（R1）＋旧锚点即零消费者（R5）** |
| chord | 全部 | R5 不可达 |
| 多进程 | 全部 | R5 不可达 |
| `durable`/`pico3`/`micro` | 全部 44 行 | R1（删除）/ R5（不可达） |
| 防漂移脚本 | 全部 | 非 `bin pi` 产品面 |

### 两条关键差距的加权影响（重算）

> ⚠️ **旧文「SQLite schema 分叉」那条（+9.3pp）作废** —— 参照物删除，**它不再是差距**。真正剩余的两条：

| 差距 | 涉及行（权重） | 现状 Σ(w×c) | 若修复后 | 对本范围（分母 87）的影响 |
|---|---|---:|---:|---|
| **JSONL 格式分叉（v3 vs v4）** | `存储#15`(3) `#16`(3) `#17`(2) = **Σw 8** | 0 | 8 | 34.0→42.0 ⇒ **39.1% → 48.3%（+9.2 pp）** |
| **`context_edit` 上下文改写** | `存储#11`(3) = **Σw 3** | 0 | 3 | 34.0→37.0 ⇒ **39.1% → 42.5%（+3.4 pp）** |
| 两条同时修 | Σw 11 | 0 | 11 | 34.0→45.0 ⇒ **39.1% → 51.7%（+12.6 pp）** |

**读法**：
- **JSONL 格式分叉是当前单点最大拖累**（Σ权重 8，占本范围 87 的 9.2%）—— 它是**用户可见的硬故障**（换机/换工具后会话直接读不出），不是形状偏差。
- **`context_edit` 是 pi 新增的整条能力**，java 零对应。
- **B55（list 进度/部分结果/中止/并发）权重只有 1，但决定「扫描大目录时交互式选择器能否边扫边显示」**。

---

## 存疑清单（10 条）

| # | 能力 | 存疑点 |
|---|---|---|
| S1 | 分支创建/移动（#6） | pi 是单 leaf 树（`branch`/`resetLeaf`），java 是 lane（`createLane`/`moveLane`）⇒ 语义粒度不同 |
| S2 | compaction 条目（#8） | java 多 `retainedTail`、缺 `fromHook`/`systemMessage` ⇒ 列集分叉 |
| S3 | 分支摘要条目（#9） | java 缺 `fromHook` |
| S4 | 上下文投影（#10） | pi `buildSessionProjection` 带 provenance，java `ContextEntries` 形状不同 |
| S5 | 会话名（#12） | pi `session_info` entry vs java `facts` 表 `kind='name'` |
| S6 | entry 标签（#13） | pi `label` entry vs java `facts` 表 `kind='label'` |
| S7 | usage（#14） | pi `usage` entry vs java record `usage` + `SessionStats` 摊平 |
| S8 | 内存后端（#20） | pi 单类 `SessionManager.inMemory` vs java 两构造（Storage + 注册表） |
| S9 | eval 运行器（#9 评测） | pi 落 `.eval/<ISO>_<uuid>/` 产物目录，java `EvalRunner` 纯内存 |
| S10 | 扩展/provider/smoke eval（#11/#13/#15 评测） | pi 走真 harness + judge；java 是装配可见性 / 真 provider ping |

---

## 缺失清单（18 条）

### A. 存储后端（6 条）

| # | 能力 | pi 证据（`200387122`） |
|---|---|---|
| M1 | list 进度回调 + 部分结果增量发布 + `AbortSignal` + 并发扫描 | `session-manager.ts:884-889/893/941-971`（B55） |
| M2 | `context_edit` 条目 + 投影 | `session-manager.ts:175-182/1360/519-538` |
| M3 | JSONL 头部线格式（`{type:"session",version:3}`） | `session-manager.ts:43-56/1064-1071` |
| M4 | JSONL 条目线格式（11 type 词表 + 基字段无 `seq`） | `session-manager.ts:57-63/183-197` |
| M5 | JSONL 旧版本读取（v1→v2→v3 迁移） | `session-manager.ts:287/316/337` |
| M6 | 会话文件惰性创建（首个 user/assistant 消息才落盘） | `session-manager.ts:1166-1190` |

### B. 评测（12 条）

| # | 缺失项 | pi 证据 |
|---|---|---|
| M7 | 真 coding-agent harness | `src/harness.ts:471-523` |
| M8 | LLM-as-judge | `tui.docs.eval.ts:12-13`、`extensions.docs.eval.ts:2` |
| M9 | A/B 对照表 + 变体计划 | `src/report.ts:36-75`、`src/plan.ts:1/39` |
| M10 | 对照报告汇总 | `src/report.ts:359/436` |
| M11 | reporter 落产物 | `src/report.ts:101/123` |
| M12 | 会话 JSONL 快照 attachment | `src/report.ts:9` |
| M13 | `toolCalls` 查工具调用 | `documentation-audit.eval.ts:6/59` |
| M14 | docs 审计 eval | `documentation-audit.eval.ts:10-59` |
| M15 | 模型作者 eval | `models.docs.eval.ts` + `configured-runtime.ts` |
| M16 | Docker eval 执行 | `src/docker.ts:38` + `docker/*` |
| M17 | 文档变体任务计划 | `src/plan.ts:1-59` |
| M18 | TUI 文档审计 eval | `tui.docs.eval.ts:12-13/192/229` |

---

## 作废 / 改指 / 新增 明细（供台账同步）

### 作废（移出分母）

| 来源 | 行数 | 依据 |
|---|---:|---|
| 存储后端 harness-only | 15 | R1（harness `session/types.ts` 删）：delete 会话 / `commit(writes[])` / `getEntries(ids[])` / `scanBranchStructure` / `getValue·scanValues` / `readList·appendList·deleteList` / `scanUsage` / branch tip 二分 / 全文搜索 / schema 迁移 / `getLog` / lane records / fork snapshot / close·drain / `nextSeq` |
| SQLite schema | 18 | R1（整包删）+ R5（旧锚点零消费者） |
| `durable`/`pico3`/`micro` | 44 | R1（pico3/micro 删）+ R5（durable 不可达） |
| chord / 多进程 / 脚本 | 21 / 4 / — | R5 / 非产品面 |

### 改指（保留判定，换参照）

存储后端 **18 行**由已删除的 `harness/session/types.ts` **改指** `coding-agent/src/core/session-manager.ts`（见 #1–#20）。
**评测 0 行**（`src` 逐字节未变）。
**chord 引用**本轮不重取（权 0）。

### 新增（主流新能力）

| # | 能力 | 证据 |
|---|---|---|
| 1 | `context_edit` 条目 + 上下文投影 | `session-manager.ts:175-182/1360/543`（`e466db0fec` canonical session context boundaries） |
| 2 | 会话文件惰性创建（首个用户消息才落盘） | `session-manager.ts:1166-1190`（`ff72faba2`，#10000） |
