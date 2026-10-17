# 06 会话后端 / 评测 / chord / 防漂移脚本

> 【背景摘要】本图覆盖四块：会话持久化（sqlite 模块与 agent-core 的 JSONL 面）、评测（evals）、chord（R5 不可达）、防漂移脚本（口径外）。核心结论：pi 主流会话持久化只有 JSONL＋in-memory，SQLite 后端无对齐参照物。

## 基准与范围

- 基准：pi @ `ea448f454`（2026-10-11，`v1.1.0-22`）；pi-java @ `edc8749d`（main）。
- 范围：`pi-java-session-backend-sqlite` ＋ `pi-java-agent-core` 的会话持久化面（主流对照 pi `coding-agent/src/core/session-manager.ts`）；`pi-java-evals` ↔ pi `packages/evals`；pi `packages/chord`（R5 不可达）；pi 仓库根 `scripts/` 与本仓 `docs/map/check-drift.sh`（口径外）。
- 判定三档：**对齐** / **缺失** / **存疑**。每条给两侧 `file:line`。
- pi 参照版本：`1.1.0`（`packages/evals/package.json` version 字段）。

---

## 口径：两条铁律

1. **R1「pi 删掉的，pi-java 也删」**——参照物被删除的单元**权重 0、移出分母**。注意不是改判「对齐」：判据是「和 pi 表现一样」，pi 没这个行为了，就没有对齐可言。
2. **R5「只算 `bin pi` 主流可达的」**——判据是从 `bin pi → dist/bundle/cli.js ← src/cli.ts → main()` 这条线可达。不可达者权重 0、不进分母。

### B59（SQLite schema 分叉）裁决：保持「不作缺口」

- 【场景】pi-java 有 SQLite 会话后端，schema 与 pi 曾经的 session-backends 分叉。旧文记「SQLite schema 完全分叉＝全项目单点最大拖累（Σ权重 25，+16.2 pp）」。
- 【决定】不作缺口、移出分母。pi 主流会话持久化**只有 JSONL ＋ in-memory**（`SessionManager`），没有 SQLite 后端；`packages/session-backends` 整包已删，且删除前就**从未被 `bin pi` 主流消费**（R1 ＋ R5）。旧判定**自始即错**。
- 【风险/代价】无影响。[内部，模型不可见] ⚠️ 这**不主张删除 `pi-java-session-backend-sqlite` 模块**——删模块是独立裁决。本文件只如实标注：该模块**无对齐参照物**，其内部改造（见「本轮翻转与改动摘要」）只影响 JSONL 互读等主流单元的判定。

JSONL 主流互读的残余差异（信封扩展键、pi v1/v2 迁移）与 [`03-agent-core.md`](03-agent-core.md) **#73**（JSONL 存储，判「存疑（形状）」）同一口径，以 #73 为上层挂账条目。

---
## 0. 会话持久化各在哪个模块

| 后端 | pi 位置（`ea448f454`） | pi-java 位置（`edc8749d`） |
|---|---|---|
| **JSONL（主流）** | `packages/coding-agent/src/core/session-manager.ts`（`SessionManager`，头 `{type:"session", version:3}`，2,013 行） | 写线＝pi v3 形状：`agent-core/.../session/jsonl/PiV3Wire.java`（头/条目/合成行）；存储 `JsonlSessionStorage.java`；内部头记录仍名 `JsonlV4Header`，旧 `kind:"header"` 文件惰性迁移 |
| **Memory（主流）** | `SessionManager.inMemory()`（同文件 `:1804`） | `agent-core/.../session/memory/MemorySessionStorage.java`（`:31`） |
| **SQLite** | ❌ 无（`session-backends` 已删，从未上 `bin pi`） | `pi-java-session-backend-sqlite/`（2,924 行 main；**参照物不存在**，R1/R5 移出分母） |
| **契约层** | `SessionManager` 的公开方法（无独立 `Storage`/`SessionRepo` 接口） | `agent-core/.../session/SessionStorage.java` ＋ `SessionRepository.java` |

**易混点（保留）**：`pi-java-coding-agent/.../core/session/InMemorySessionRepository.java` **不是**
Memory 后端，它是 coding-agent 的**进程内会话注册表**（`-c/-r/--fork` 用）。真正的 Memory 后端在
`agent-core/.../session/memory/`。

**本轮关键变化**：上一轮重测时 java 落 `{kind:"header",version:4}` 头、条目带 `kind/seq` 信封，
与 pi v3 **双向不可读**。本基准下 java 的**写出线已切成 pi v3 形状**（包 12/14，`PiV3Wire`），
合成行（lane/record/fact）借 pi 原生 `custom`/`session_info`/`label` 条目承载，pi 侧
`parseSessionEntries` 只做 `JSON.parse`（`session-manager.ts:353-368`）⇒ **v3 双向互读已通**，
残余差异见 #16/#17。

---

## 规模（本锚点实测）

| 模块 | pi 路径 / 规模 | java（工作树） | 备注 |
|---|---|---:|---|
| 主流会话持久化 | `coding-agent/src/core/session-manager.ts` **2,013** 行 | jsonl 包 **2,539**（14 文件） | java 数含文件系统抽象/仓库层 |
| SQLite 后端（main / test） | **不存在**（参照包已删） | **2,924 / 308** | R1＋R5 作废 |
| 评测（src） | `packages/evals/src` **1,446** | **785** | pi src 两锚点间逐字节未变 |
| 评测（test / eval 用例） | `test/` **815** ＋ `evals/*.eval.ts` **856** | **412** | — |
| **chord（src / test）** | **8,817 / 9,726** | **0** | R5 不可达 ⇒ 权 0 |
| `durable`（R5 面外参照） | **22,473** | **0** | R5 不可达 ⇒ 权 0 |
| 防漂移脚本 | 根 `scripts/` **82 文件 / 10,445 行** | **0** | 口径外（非产品面） |

> pi 包结构（`git ls-tree ea448f454:packages/`）：`agent` · `ai` · `chord` · `client` · `codemode` ·
> `coding-agent` · `durable` · **`env`** · `evals` · `mcp` · `protocol` · `server` · `telemetry` · `tui`
> （**14 个，无 `session-backends`**；`env` 是 SSH 远程面，R5 面外，不在本图任何分母）。
>
> pi 侧本图引用面在 `200387122..ea448f454` 间的全部改动：`evals/package.json`、`chord/package.json`
> （版本号 → 1.1.0、`shx` 降级 devDep）与 `evals/docker/install-runtime.mjs`（制品打包脚本换用
> `local-package-install`/`package-artifacts`）—— **src 零改动**，本图 pi file:line 全部续用。

---

## 存储后端对齐（主流 `SessionManager`）

判定基准（pi `ea448f454`）：`session-manager.ts` 的 `SessionManager` 公开面（本锚点 2,013 行，
与上一锚点逐字节相同）。java 契约面：`SessionStorage.java`（`:19-93`）＋ `SessionRepository.java`
（`:12-34`）。

| # | 权重 | 能力 | pi 证据（`ea448f454`） | java 证据（`edc8749d`） | 判定 | 台账 |
|---|---|---|---|---|---|---|
| 1 | 2 | create 建会话 | `SessionManager.create:1755`／`newSession:1057`（写 header 行 `:1064-1071`） | `SessionRepository.create:18` | 对齐 | — |
| 2 | 2 | list 列会话（扫描目录 → `SessionInfo[]`） | `static list:1900`／`listAll:1921` | `SessionRepository.list:27`；`JsonlSessionRepository.list:69` | 对齐 | — |
| 3 | 1 | list 进度回调 ＋ 部分结果增量发布 ＋ `AbortSignal` ＋ 并发扫描 | `SessionListProgress:884-889`；节流 `CURRENT_SESSION_LIST_PUBLISH_INTERVAL = 10`（`:893`）；`listSessionsFromDir:941-971`；`signal?.throwIfAborted()` `:879/:911/:946/:971` | **无**（list 同步全量、无回调、无中止、无并发） | **缺失**（4 项） | **B55** |
| 4 | 2 | open 打开会话 | `static open:1766` | `SessionRepository.open:24` | 对齐 | — |
| 5 | 2 | fork 分支会话 | `forkFrom:1815`／`createBranchedSession:1632` | `SessionRepository.fork:33` | 对齐 | — |
| 6 | 2 | 分支创建 / 移动 | `branch:1579`／`resetLeaf:1591`／`branchWithSummary:1600`（树 + leaf 指针） | `SessionStorage.createLane:30`／`moveLane:33`（lane 概念；落线为 pi `custom` 行，见 #16） | **存疑**（粒度不同：pi 单 leaf 树，java lane） | — |
| 7 | 3 | 追加 entry（message / thinking_level_change / model_change / custom / custom_message / usage / context_edit） | `appendMessage:1204`、`appendThinkingLevelChange:1217`、`appendModelChange:1230`、`appendCustomEntry:1290`、`appendCustomMessageEntry:1339`；落盘 `_persist:1172`（`appendFileSync :1187`） | `SessionStorage.appendEntry:41`；`Entry` 9 变体（`Entry.java:35-44`） | 对齐 | — |
| 8 | 3 | compaction 条目 | `appendCompaction:1260`；`CompactionEntry:91-104`（`summary`/`firstKeptEntryId`/`tokensBefore`/`details?`/`usage?`/`fromHook?`/`systemMessage?`） | `Entry.Compaction:151-167`（多 `retainedTail`；缺 `fromHook`/`systemMessage`） | **存疑**（列集分叉） | — |
| 9 | 3 | 分支摘要条目 | `branchWithSummary:1600`（entry `:1612`）／`BranchSummaryEntry:106-118`（`fromId`/`summary`/`details?`/`usage?`/`fromHook?`） | `Entry.BranchSummary:170-179`（缺 `fromHook`） | **存疑** | — |
| 10 | 3 | compaction-aware 上下文投影（含 provenance） | `buildContextEntries:476`／`buildSessionProjection:543`／`SessionProjection:216` | `ContextEntries.projectEntries:185-214`；`ProjectedEntry`（`:167`）携带 source 条目即 provenance；compaction 裁剪/孤儿 edit 规则同 pi（`:175-214` 注释逐行对 pi） | **对齐（形状不同）** | — |
| 11 | 3 | **`context_edit` 条目（append-only 上下文改写）** | `ContextEditEntry:175-182`（`targetId`/`replacement`）；`appendContextEdit:1360`；投影 `projectContextEntry:519-538` | `Entry.ContextEdit:283-294`；编解码 `EntryJsonCodec:75-109`；投影 `ContextEntries.applyEdit:231-250`；写线补显式 null `PiV3Wire:155-157`；生产者＝压缩/遗漏恢复（`CompactionService`、`RecoveryOmissions`） | **对齐**（**翻转**） | — |
| 12 | 2 | 会话名 | `appendSessionInfo:1304`／`getSessionName:1318`（`SessionInfoEntry:142-147`，`type:"session_info"`） | `SessionStorage.getName:72`／`setName:75`；**JSONL 线已原生落 `session_info`**（`PiV3Wire:209-214`，读回 `:237-238`）；SQLite 仍走 `facts` 表（参照已删） | **对齐**（**翻转**；内部仍 Fact 模型） | — |
| 13 | 1 | entry 标签 | `getLabel:1432`／`appendLabelChange:1441`（`LabelEntry:135-141`，`type:"label"`） | `SessionStorage.getLabel:78`／`setLabel:81`；**JSONL 线原生落 `label`**（`PiV3Wire:217-223`，读回 `:239-241`） | **对齐**（**翻转**） | — |
| 14 | 2 | usage 条目 ＋ 会话用量汇总 | `UsageEntry:80-90`（`type:"usage"`）；`appendUsage:1244`；`core/usage-totals.ts` | `Entry.Usage:207-222`（pi 九键逐字 ＋ runId/entryId 等审计扩展键）；D6 后账随一等 entry 走（`SessionState:325-328`、SQLite `SqliteMutationReplay` D6 注释）；汇总 `SessionStats:13-19` | **存疑**（载体已对齐；汇总桶不同：java cached/uncached/total vs pi input/output/cacheRead/cacheWrite） | — |
| 15 | 3 | **JSONL 头部线格式** | `SessionHeader:43-56`＝`{type:"session",version?:3,id,timestamp(ISO),cwd,parentSession?}`；写点 `:1064-1071`；`CURRENT_SESSION_VERSION = 3`（`:41`）；pi 靠 `e.type==="session"` 找头（`:1086`） | `PiV3Wire.encodeHeader:75-94`（同六键、ISO 毫秒）；读 `:97-121`：pi v3 头 ＋ 本仓旧 `kind:"header"` 头（`JsonlCodec.parseHeader:113-145`） | **对齐**（**翻转**） | — |
| 16 | 3 | **JSONL 条目线格式（type 词表 ＋ 基字段）** | 基字段 `SessionEntryBase:57-63`＝`{type,id,parentId,timestamp(ISO)}`（**无 `seq`**）；`SessionEntry:183-197` 11 个 type | `Entry` 9 变体（`Entry.java:53-61`）＋ label/session_info 走 Fact 族读写；`PiV3Wire.encodeEntryLine:139-158`（去 seq、ISO 时间戳，`lane` 保留为 pi 容忍的扩展键）；合成行＝pi 原生 `custom`（`customType:"pi-java.lane"`/`"pi-java.record.*"`）＋`session_info`/`label`（`:160-245`）；seq 由行号重建 | **存疑（形状）**（**自缺失翻转**：双向可读；合成行借 custom 扩展承载） | map/03 **#73** |
| 17 | 2 | **JSONL 旧版本读取（pi v1→v2→v3 迁移）** | `migrateV1ToV2:287`／`migrateV2ToV3:316`／`migrateToCurrentVersion:337`（读后 `_rewriteFile()`） | **明确不迁 pi v1/v2**（`PiV3Wire.parseHeader:103-109` 对非 v3 报错）；只惰性重写**本仓旧** `kind:"header"`/`kind:"entry|record|lane|fact"` 文件（`JsonlSessionStorage:99-101/301-335`） | **缺失**（刻意非目标：java 从未写过 pi v1/v2） | — |
| 18 | 2 | JSONL 文件名 / 目录名 | `:1078-1080`＝`${ISO.replace(/[:.]/g,"-")}_${id}.jsonl`（**不 encode**）；`getDefaultSessionDirPath:589-594` | `JsonlSessionRepository.sessionFileName:250-253` 同形；`sessionDirectoryName:245-247` 同形 | 对齐 | — |
| 19 | 2 | **会话文件惰性创建（首个 user/assistant 消息才落盘）** | `_hasConversation:1166-1170`；`_persist:1172-1191`（setup 条目只留内存） | `JsonlSessionStorage.create:44-48`（create 即写 header 文件） | **缺失** | — |
| 20 | 1 | 内存后端 | `SessionManager.inMemory:1804` | `MemorySessionStorage:31` ＋ coding-agent 注册表 `core/session/InMemorySessionRepository.java` | **存疑**（java 两构造 vs pi 单类） | — |

**行数**：20 行 —— 对齐 **11** / 缺失 **3** / 存疑 **6**。
**权重**：**Σ = 44**（20 行全部进分母）。
**Σ(w×c) = 25×1.0（对齐）＋ 14×0.5（存疑）＝ 32.0** ⇒ **加权完成度 = 32.0/44 = 72.7%**。
（上一锚点：6 / 6 / 8，21.5/44＝48.9%。翻转明细见文末「本轮翻转清单」。）

---

## ~~SQLite schema 逐表对齐~~ —— **整段作废（R1＋R5，移出分母），B59 销号**

pi 主流没有 SQLite 后端，`packages/session-backends` 已删且删除前从未上 `bin pi`。
原文 18 行（Σ权重 40）不进任何分母；**不主张删 pi-java 模块**。
本基准下该模块的实质变化（002 迁移给合成行补 `parent_id`/`timestamp`、载荷走 `SessionWireShape.toPiTree`、
usage 提为一等 entry）都是**为 pi v3 互读服务**的，其判定效应已计入上表 #11–#16，不再单列为缺口。

---

## 评测对齐

pi 侧（`ea448f454`）：`packages/evals/src` **1,446 行，两锚点间逐字节未变**；`evals/*.eval.ts` 同样未变。
本窗口仅有的非 src 改动：

| 文件 | 改动 | 影响 |
|---|---|---|
| `package.json` | version `1.0.2 → 1.1.0`；内部依赖 `^1.1.0`；`shx` devDep `0.4.0 → 0.3.4` | 版本号，**框架集合不变**（仍 `vitest-evals`＋`@vitest-evals/core`＋`autoevals`） |
| `docker/install-runtime.mjs` | 制品打包换用 `scripts/local-package-install.mjs`＋`scripts/package-artifacts.mjs`（原 `coding-agent-consumer`/`release-packages` 已删） | Docker 装配脚本重构，能力面不变 |

pi-java 侧：自研框架 `EvalSuite`/`EvalCase`/`EvalRunner`/`EvalReporter` ＋ 协议一致性套件。
本窗口 2 个主文件改动，均**不翻判定**：
`ChatApiConformanceSuite.java:156` 跟 B107 删 `StreamRequest` 多余参数；
`ProviderCatalogConformance.java:18/31-35` 内置 provider 计数 18 → **19**。

| # | 权重 | 能力 | pi 证据（`ea448f454`） | java 证据（`edc8749d`） | 判定 |
|---|---|---|---|---|---|
| 1 | 1 | 声明式 eval 定义（`describeEval`） | `evals/documentation-audit.eval.ts:6/43`、`tui.docs.eval.ts:13` | `api/EvalSuite.java:8` | 对齐（形状不同） |
| 2 | 3 | **真 coding-agent harness**（临时目录真会话 + transcript/usage 回读） | `src/harness.ts:471-475`（`createPiCodingAgentHarness`）、`:517-523`（`createPiDocumentationEvalHarness`） | **无**（`api/EvalContext.java:13`） | **缺失** |
| 3 | 2 | **LLM-as-judge 评分** | `tui.docs.eval.ts:12-13`（`Levenshtein`+`createJudge`）、`extensions.docs.eval.ts:2`（`StructuredOutputJudge`/`ToolCallJudge`） | **无**（`api/EvalResult.java:14-17` 只有布尔） | **缺失** |
| 4 | 2 | baseline vs candidate A/B 对照表 + 变体计划 | `src/report.ts:36-75`；`src/plan.ts:1/39` | **无** | **缺失** |
| 5 | 2 | 对照报告汇总（paired metric / correctness lift） | `src/report.ts:359/436` | **无** | **缺失** |
| 6 | 2 | reporter 落产物（`session.jsonl`） | `src/report.ts:123`（`persistSession`）、`:101`（`classifyCaseStatus`） | 部分：`api/EvalReporter.java:15/23`（不落盘） | **缺失** |
| 7 | 2 | 会话 JSONL 快照作为 test attachment | `src/report.ts:9`（`PI_SESSION_SNAPSHOT_ARTIFACT`） | **无** | **缺失** |
| 8 | 2 | 从 run 里查工具调用（`toolCalls`） | `evals/documentation-audit.eval.ts:6/59` | **无** | **缺失** |
| 9 | 3 | eval 运行器 CLI | `src/cli.ts`（`parseEvalCli`/`compareDiscovery`）；`package.json` 双轨入口 | 部分：`runner/EvalRunner.java:63`（`runAll`，纯内存） | **存疑** |
| 10 | 2 | docs 审计 eval | `evals/documentation-audit.eval.ts:10-59`（verdict 含 `inconclusive`） | **无** | **缺失** |
| 11 | 2 | 扩展作者 eval | `evals/extensions.docs.eval.ts:1-40`（走 judge） | 部分：`ExtensionLifecycleTest.java:33` | **存疑** |
| 12 | 2 | 模型作者 eval | `evals/models.docs.eval.ts` + `configured-runtime.ts` | **无** | **缺失** |
| 13 | 2 | provider 集成 eval（本地 HTTP mock server） | `evals/acme-server.ts:1-154` + `custom-provider.docs.eval.ts` + `openai-provider.docs.eval.ts` | 部分：`smoke/ProviderSmokeTest.java:37`（默认 assumption 跳过的真 ping） | **存疑** |
| 14 | 3 | **ChatApi 协议一致性套件（C1–C10）** | **无对应物** | `conformance/ChatApiConformanceSuite.java:24/:33-45` | 对齐（java 反超） |
| 15 | 2 | 简单 smoke eval | `evals/smoke.eval.ts:10-16` | 部分：`smoke/ProviderSmokeTest.java:37` | **存疑** |
| 16 | 3 | eval 框架本体 | **外部 npm ×3**（`vitest-evals`/`@vitest-evals/core`/`autoevals`） | 自研（`evals/api/*.java`） | 对齐（自研替代） |
| 17 | 1 | 框架单测 | `test/*.test.ts`（815 行） | `src/test/...`（412 行，含 conformance/smoke/extension） | 对齐 |
| 18 | 3 | **Docker eval 执行** | `src/docker.ts:38`＋`docker/Dockerfile`＋`docker/entrypoint.ts` | **无** | **缺失** |
| 19 | 2 | **文档变体任务计划** | `src/plan.ts:1`（`DOCUMENTATION_VARIANTS`）、`:39`（`createTaskPlan`） | **无** | **缺失** |
| 20 | 2 | **TUI 文档审计 eval** | `evals/tui.docs.eval.ts:12-13/192/229` | **无** | **缺失** |

**行数**：20 行 —— 对齐 4 / 缺失 12 / 存疑 4。
**权重**：**Σ = 43** ⇒ **Σ(w×c) = 8（对齐）＋ 4.5（存疑）＝ 12.5** ⇒ **加权完成度 = 12.5/43 = 29.1%**（未变）。
评测缺口的汇总见 `docs/05-module-alignment-map.md` §2.5 / §3.4（台账 `docs/04` 无独立 evals 条目）。

---

## chord（**R5 不可达 ⇒ 权 0，不进分母**）

`ea448f454` 下 `packages/chord`：src **8,817** / test **9,726**，版本 **1.1.0**。
能力面在 `delta/`（`tracker.ts` 2,205 行、`diff.ts` 523、`index.ts` 694 等）与 `services/*`。

**pi-java 有无对应物：零。** 无 chord/facet/replicated state 的业务命中。

**消费者**：`packages/{client,coding-agent,protocol,server,durable}/package.json` 五个包
（＋ `coding-agent/examples/plugins/pi-example-plugin`）；`packages/agent` 不依赖 chord。
这些消费者全在 `bin pi` 主流口径之外（`durable` 不可达；`protocol`/`client`/`server` 按 R5 不进本图分母）
⇒ **整段权 0**。

> 旧图 21 行能力清单不重列（权重 0）。**保留更正**：`/share` 与 chord 无关
> （`modes/interactive/session-share.ts` 零 chord import，本锚点复核仍为 0），它属 coding-agent 命令面；
> pi-java 侧对应 `pi-java-coding-agent/.../core/SessionShare.java`。

---

## 多进程 / 多端子系统（`coding-agent/src/experimental/**`，**R5 不可达 ⇒ 权 0**）

`experimental/` 本锚点仍在：`{durable,vacation,services,plugins}/`、`session-catalog.ts`、
`session-worker.ts`（import `pi-durable`，2 处）、`coordinator.ts`/`server.ts`/`client*.ts` 等。
全部在 `experimental/` ⇒ R5 不可达，旧 C-1~C-4（Σ11）权 0。

pi-java 近似物仍是 `pi-java-server`（单进程、JVM 内、Unix socket 控制面 + 独占租约 + 快照订阅），
无子进程 worker / coordinator / facet-service。

---

## 防漂移脚本（**口径外 —— 非 `bin pi` 产品面**）

pi `scripts/` 是仓库自身构建/发布/校验工具，按 R5 **不进产品加权分母**，作开发流程观察保留。
本锚点 **82 文件 / 10,445 行**（上一锚点 48 / 9,060）。增量两类：
① `scripts/issues/` 工单分诊工具包（约 20 文件，categories + github/triage/register）；
② 制品打包重构：新增 `package-artifacts.mjs`(+test)、`local-package-install.mjs`(+test)、
`coding-agent-smoke.mjs`(+test)、`check-package-install.mjs`、`use-local-packages.mjs`，
删除 `coding-agent-consumer.mjs`(+test)（`evals/docker/install-runtime.mjs` 随迁）。

`check` 链（根 `package.json`，本锚点 8 步）：`biome check --write --error-on-warnings` ＋
`check:pinned-deps` ＋ `check:runtime-deps` ＋ `check:ts-imports` ＋ `check:entry-graphs` ＋
`check:install-lock:coding-agent` ＋ `tsc --noEmit` ＋ `check:browser-smoke`（无 shrinkwrap 门）。

**pi-java 实际存在的漂移预防机制（file:line，本仓工作树）**：

0. **锚点漂移检测**（本仓独有）—— `docs/map/check-drift.sh` ＋ `docs/map/ANCHOR.md`：
   `PI_PIN=ea448f454`；脚本比 **pi 本地 HEAD vs PI_PIN** 的提交距离（落后即退出 1），再从 7 份地图抽
   `` `foo.ts:行号` `` 引用的 **basename**，按 pi 内同名文件数分两栏：**【确定】**（basename 唯一）／
   **【可能】**（有同名文件，上界，如 `types.ts` 有 17 个同名）。本锚点实测 HEAD＝PI_PIN，报「✓ 无漂移」。
   限制：只圈范围，行号是否仍命中要人工核。
1. **Checkstyle**（Maven `validate`，`failsOnError=true`）—— 根 `pom.xml:142-171`；
   规则 `LineLength max=120`（`checkstyle.xml:12-13`）、`FileLength max=500`（`:18-19`）。
2. **SpotBugs**（`verify`，`effort=Max`/`threshold=Low`）—— `pom.xml:174-190` ＋ `spotbugs-exclude.xml`。
3. **maven-enforcer** —— `pom.xml:118-133`：`requireJavaVersion [25,26)` ＋ `dependencyConvergence`
   ＋ `banDuplicatePomDependencyVersions`。
4. **L5 跨语言差分一致性框架**（pi-java 独有）—— `pi-java-agent-core/src/test/.../harness/conformance/`
   （`ConformanceTest`/`ConformanceRunner`/`ConformanceScript`/`ConformanceDiff`）：逐剧本与 pi 录制真相比帧。
5. **同族后端分组矩阵** —— `session/ConformanceGroup{1,2,3}Test`（× Memory/Jsonl/Sqlite 后端）。
6. **CI 门** —— `.github/workflows/ci.yml:35` 单步 `./mvnw clean verify --batch-mode`（三平台矩阵）。

---

## 台账纠错

| 条目 | 旧说 | 本锚点实测 | 证据 |
|---|---|---|---|
| **B59 SQLite schema 分叉** | 「全项目单点最大拖累 Σ权重 25、+16.2pp」 | **自始即错，销号**：pi 无 SQLite 后端、参照包已删且从未上 `bin pi` ⇒ R1＋R5 移出分母；模块保留 | 见 §口径/B59 |
| **JSONL v3/v4 互读**（map/03 #73） | 旧文：双向不可读，用户可见硬故障 | **v3 双向互读已通**（包 12/14）：java 写 pi 六键头与无 seq 条目，pi 靠 `type:"session"` 找头、条目只 `JSON.parse`；java 读 pi 头/条目/`session_info`/`label`/`custom`。**残余**：#16 合成行走 `custom` 扩展（存疑，挂 #73）、#17 不迁 pi v1/v2（刻意） | pi `session-manager.ts:1086/353-368`；java `PiV3Wire.java:75-245` |
| **`context_edit` 条目** | 上一轮「新发现，java 零对应」 | **已落地，销号**：条目/编解码/投影/写线 null 键齐，生产者为压缩与遗漏恢复 | `Entry.java:283-294`；`ContextEntries.java:185-250` |
| **会话文件惰性创建** | 上一轮「新发现」 | **仍缺**：java create 即写头 | pi `:1166-1191`；java `JsonlSessionStorage.java:44-48` |
| **A17 写者租约** | 「java 反超/越界」 | **维持**：pi 主流从未有租约 ⇒ `WriterLease`/`WriterLeaseRows`/心跳是自研；`DEFAULT_TTL_MS=30_000`（`SqliteSessionRepository.java:37`），权 0 | 同左 |
| **E6 拆分超限文件** | 记为未结 | **`SqliteSessionStorage` 已解决**（现 **486** 行 <500，`storage/` 子包）；`AgentHarness` 现 **595** 行（E4，仍超） | 工作树 `wc -l` |
| **E7 `appendEntry`/`appendRecord` 重复** | 保留（判断项） | **三处**（JSONL/Memory/SQLite 各一） | 三后端 storage 类 |
| **H §9.3 `/export`/`/import` 占位符** | 台账曾判 OPEN | **CLOSED**：`/export`（HTML/JSONL）与 `/import` 已接线；另有 `/share` | `core/slash/builtin/MiscCommands.java:38/61/76`；`core/SessionShare.java` |
| **`/share` 不是 chord 能力** | 曾列 chord 名下 | **维持更正**：`session-share.ts` 零 chord import（本锚点复核） | pi `session-share.ts`；java `core/SessionShare.java` |

---

## 汇总（未加权）

参与对齐判定的只有存储后端（20 行）＋ 评测（20 行）＝ **40 行**：

| 档 | 存储后端 | 评测 | 合计 |
|---|---|---|---|
| **对齐** | 11 | 4 | **15** |
| **缺失** | 3 | 12 | **15** |
| **存疑** | 6 | 4 | **10** |
| 合计 | 20 | 20 | **40** |

**未加权完成度（按行数）= 15 / 40 = 37.5%**；**按系数（对齐 1 / 存疑 0.5 / 缺失 0）
= (15 + 10×0.5) / 40 = 20 / 40 = 50.0%**。

### 移出分母的整块（R1 / R5 / 口径外）

| 子系统 | 参照规模（`ea448f454`） | 旧行数 | 旧 Σw | 处置 |
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
| 存储后端 | **44（20 行）** | **32.0** | **72.7%** | 70.0%（14/20） |
| ~~SQLite schema~~ | **0（作废）** | 0 | — | — |
| 评测 | **43（20 行）** | **12.5** | **29.1%** | 30.0%（6/20） |
| **本范围（R5 可达）** | **87（40 行）** | **44.5** | **51.1%** | **50.0%（20/40）** |

（上一锚点：87 / 34.0 / **39.1%** ／ 未加权 40.0%。本轮 **+12.0 pp 全部来自判定翻转**：
JSONL v3 写线改造与 `context_edit` 落地；分母与权重未动。评测面零变化。
⚠️ `docs/05` §2.5/§3.4 的总表数字（39.1%）尚未随本图回填，是已知的下游待同步项。）

### 权重 0（排除出分母）的行

| 域 | 行 | 为什么 0 |
|---|---|---|
| 存储后端 | 写者租约 / 心跳续租 | pi 主流从未有租约 ⇒ 非目标（java 自研，A17） |
| SQLite schema | 全部 18 行 | 参照物不存在（R1）＋从未主流（R5） |
| chord | 全部 | R5 不可达 |
| 多进程 | 全部 | R5 不可达 |
| harness-only / `durable` / pico3 / micro | 全部 59 行 | R1（删除）/ R5（不可达） |
| 防漂移脚本 | 全部 | 非 `bin pi` 产品面 |

### 关键差距的加权影响（重算）

> 上一锚点「JSONL 格式分叉 +9.2 pp」**大部分已消解**：#15 头格式翻对齐、#16 翻存疑（双向已可读）。
> 真正剩余的：

| 差距 | 涉及行（权重） | 现状 Σ(w×c) | 若修复后 | 对本范围（分母 87） |
|---|---|---:|---:|---|
| **JSONL 残余**：#16 信封扩展键（3）＋ #17 不迁 pi v1/v2（2） | Σw **5** | 1.5 | 5 | 44.5→48.0 ⇒ **51.1% → 55.2%（+4.0 pp）** |
| **会话文件惰性创建**（#19） | Σw **2** | 0 | 2 | 44.5→46.5 ⇒ **51.1% → 53.4%（+2.3 pp）** |
| 两条同时修 | Σw 7 | 1.5 | 7 | 44.5→50.0 ⇒ **51.1% → 57.5%（+6.3 pp）** |

**读法**：
- 评测面（29.1%）现在是本范围的主要低分面：12 条缺失（真 harness / judge / A-B 报告 / Docker 等），
  其中高权 3 条（真 harness、运行器 CLI、Docker、框架外协议套件除外）。
- 存储面只剩 **3 条缺失**：B55（w1，大目录扫描体验）、pi v1/v2 迁移（w2，刻意非目标）、惰性落盘（w2）。
- **#17 是否算缺口可再议**：java 从未产生过 pi v1/v2 文件，当前实现选择显式报错而非静默新建。

---

## 存疑清单（10 个判定单元，8 行）

| # | 能力 | 存疑点 |
|---|---|---|
| S1 | 分支创建/移动（#6） | pi 单 leaf 树（`branch`/`resetLeaf`），java lane（`createLane`/`moveLane`）⇒ 语义粒度不同（落线借 `custom` 行） |
| S2 | compaction 条目（#8） | java 多 `retainedTail`、缺 `fromHook`/`systemMessage` ⇒ 列集分叉 |
| S3 | 分支摘要条目（#9） | java 缺 `fromHook` |
| S4 | usage 汇总（#14） | 条目载体已对齐（一等 `Entry.Usage`）；`SessionStats` 桶形 vs pi `UsageTotals`（input/output/cacheRead/cacheWrite） |
| S5 | JSONL 条目信封（#16） | 双向可读；java 多 `lane` 扩展键，合成行借 `custom`/`customType` 承载，seq 内存重建（map/03 #73） |
| S6 | 内存后端（#20） | pi 单类 `SessionManager.inMemory` vs java Storage ＋ coding-agent 注册表两构造 |
| S7 | eval 运行器（评测 #9） | pi 落 `.eval/<ISO>_<uuid>/` 产物目录，java `EvalRunner.runAll` 纯内存 |
| S8 | 扩展/provider/smoke eval（评测 #11/#13/#15） | pi 走真 harness + judge；java 是装配可见性 / 真 provider ping（默认跳过） |

（评测 4 条存疑合并为 S7/S8 两行叙述；计 4 个判定单元。）

---

## 缺失清单（15 条）

### A. 存储后端（3 条）

| # | 能力 | pi 证据（`ea448f454`） |
|---|---|---|
| M1 | list 进度回调 + 部分结果增量发布 + `AbortSignal` + 并发扫描 | `session-manager.ts:884-893/941-971`（B55） |
| M2 | pi v1/v2 旧会话迁移（java 刻意不做） | `session-manager.ts:287/316/337`（java `PiV3Wire:103-109` 报错） |
| M3 | 会话文件惰性创建（首个 user/assistant 消息才落盘） | `session-manager.ts:1166-1191`（java `JsonlSessionStorage:44-48` 建即写头） |

### B. 评测（12 条）

| # | 缺失项 | pi 证据 |
|---|---|---|
| M4 | 真 coding-agent harness | `src/harness.ts:471-523` |
| M5 | LLM-as-judge | `tui.docs.eval.ts:12-13`、`extensions.docs.eval.ts:2` |
| M6 | A/B 对照表 + 变体计划 | `src/report.ts:36-75`、`src/plan.ts:1/39` |
| M7 | 对照报告汇总 | `src/report.ts:359/436` |
| M8 | reporter 落产物 | `src/report.ts:101/123` |
| M9 | 会话 JSONL 快照 attachment | `src/report.ts:9` |
| M10 | `toolCalls` 查工具调用 | `documentation-audit.eval.ts:6/59` |
| M11 | docs 审计 eval | `documentation-audit.eval.ts:10-59` |
| M12 | 模型作者 eval | `models.docs.eval.ts` + `configured-runtime.ts` |
| M13 | Docker eval 执行 | `src/docker.ts:38` + `docker/*` |
| M14 | 文档变体任务计划 | `src/plan.ts:1-59` |
| M15 | TUI 文档审计 eval | `tui.docs.eval.ts:12-13/192/229` |

---

## 本轮翻转清单与 java 改动摘要（`351d199..edc8749d`）

本窗口 pi-java 本图两模块共 **12 个主源码文件**改动（sqlite 10 ＋ evals 2）＋ 1 个测试文件。
另外，存储面多条翻转的承重代码落在 `pi-java-agent-core`（jsonl 包新增 `PiV3Wire`/`SessionWireShape`、
`Entry` 增 9 变体、`ContextEntries` 投影），java 侧取证读工作树，一并计入。

**判定翻转（5 缺失/存疑 → 对齐，1 缺失 → 存疑）**：

| 行 | 旧判定 | 新判定 | 承重改动 |
|---|---|---|---|
| #10 上下文投影 | 存疑 | **对齐** | `ContextEntries.projectEntries/applyEdit:185-250` |
| #11 `context_edit` | 缺失 | **对齐** | `Entry.ContextEdit:283-294` ＋ 投影/编解码/生产者 |
| #12 会话名 | 存疑 | **对齐** | FactName 落 pi 原生 `session_info` 线（`PiV3Wire:209-214`） |
| #13 entry 标签 | 存疑 | **对齐** | FactLabel 落 pi 原生 `label` 线（`PiV3Wire:217-223`） |
| #15 JSONL 头 | 缺失 | **对齐** | `PiV3Wire.encodeHeader:75-94`（pi 六键） |
| #16 JSONL 条目信封 | 缺失 | **存疑（形状）** | 去 seq/kind、ISO 时间戳、合成行借 `custom`；双向可读 |

**12 个主源码文件改了什么**：

| 文件 | 改动 | 判定影响 |
|---|---|---|
| `resources/sql/002_pi_identity.sql`（新，10 行）＋ `Migrations.java:16-17` | 002 迁移：`records`/`lane_moves`/`facts` 补可空 `parent_id`/`timestamp`（pi `SessionEntryBase:57-63` 形状；旧行 NULL 按 epoch 读） | #16 合成行能链进 pi 的树 |
| `storage/EntryRows.java:34` | 载荷序列化改走 `SessionWireShape.toPiTree`（tool→toolResult、tool_use→toolCall、toolUseId→toolCallId 深走重写） | SQLite 载荷与 pi 同词表 |
| `storage/{Fact,Lane,Record}Rows.java` | 三族行全部贯通 `parentId`/`timestamp`（INSERT/SELECT/record 分量） | #16 |
| `SqliteMutationReplay.java` | 合成行写库带父级/时间戳；**D6：usage 账改读一等 `Entry.Usage`**，删 `LaneRecord.UsageRecord` 账路径 | #14（载体对齐的一部分） |
| `SqliteSessionStorage.java`（463→486 行） | `getLog` 重建父级/时间戳；新增 `laneHead()`（合成行挂最近条目）＋ `textToInstant()`（旧行 epoch）；createLane/moveLane/setName/setLabel/appendRecord 挂 `main` 叶；D6 usage 同上 | #12/#13/#16 |
| `SqliteSessionRepository.java:234-256` | fork 拷贝时合成行父级＝拷贝进来的最后一条（tipId/tipTs） | #16（fork 互读） |
| `SqliteCodecs.java` | 删 `LaneRecord.UsageRecord` 的 runId 分支（该型随 D6 退役） | 无 |
| `test/.../SqliteLeaseAndSearchTest.java` | ＋1 测试：SQL 层 type 扫描能捞 usage 行且 runId 审计键读回（B168） | 测试基建 |
| `evals/.../ChatApiConformanceSuite.java:156` | 跟 B107 删 `StreamRequest` 多余参数 | 无 |
| `evals/.../ProviderCatalogConformance.java:18/31-35` | 内置 provider 计数 18 → 19 | 无 |
