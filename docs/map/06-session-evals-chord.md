# 06 会话后端 / 评测 / chord / 防漂移脚本

> 范围：`pi-java-session-backend-sqlite` ↔ pi `packages/session-backends`；`pi-java-evals` ↔ pi `packages/evals`；
> pi `packages/chord`（整块缺失候选）；pi 仓库根 `scripts/`（整块缺失候选）。
> 判定三档：**对齐** / **缺失** / **存疑**。每条给两侧 file:line。
> pi 参照版本：`v0.85.1`（`packages/*/package.json` version 字段）。

---

## 0. 三种后端各在哪个模块（先解这个）

| 后端 | pi 位置 | pi-java 位置 |
|---|---|---|
| **SQLite** | `packages/session-backends/sqlite-node/src/sqlite/`（`repo.ts` / `storage.ts` / `session.ts` / `session/*.ts`） | `pi-java-session-backend-sqlite/src/main/java/com/pijava/session/sqlite/` |
| **JSONL** | `packages/agent/src/harness/session/jsonl/` | `pi-java-agent-core/src/main/java/com/pijava/agent/session/jsonl/` |
| **Memory** | `packages/agent/src/harness/session/memory.ts` + `in-memory-storage-state.ts` | `pi-java-agent-core/src/main/java/com/pijava/agent/session/memory/` |
| 契约层（`Storage`/`Session`/`SessionRepo`） | `packages/agent/src/harness/session/types.ts` | `pi-java-agent-core/.../agent/session/SessionStorage.java` + `SessionRepository.java` |

**结论**：**三种后端两侧都是 1:1 存在**（不是「只有 SQLite 有」）。pi 把 SQLite 单独成包（`packages/session-backends`），JSONL/Memory 放在 `packages/agent`；pi-java 同形（SQLite 单独模块，JSONL/Memory 在 `agent-core`）。**模块划分一致，按后端对齐可 1:1 展开。**

**另有一处易混点**：`pi-java-coding-agent/.../core/session/InMemorySessionRepository.java`（104 行）**不是** Memory 后端，
它是 coding-agent 的**进程内会话注册表**（`-c/-r/--fork` 用），javadoc 自述 "Phase 4 replaces this with a persistent SessionRepository"。
真正的 Memory 后端在 `agent-core/.../session/memory/`（304 行）。

---

## 规模

> ⚠️ **本节已于 2026-09-20 重测**：pi 从 `71dca871b` 前进 111 个提交到 `3390bd936`。下表 `pi 旧` / `pi 新` 两列给出漂移。

| 模块 | pi 包 / 路径 | pi 旧 | pi 新 | 变化 | java LOC | 新比例 |
|---|---|---|---|---|---|---|
| 会话后端 · SQLite（src） | `packages/session-backends/sqlite-node/src` | 1973 | **1973** | **未变** | 2875 | 1.46× |
| 会话后端 · SQLite（test） | 同上 `test/`（+`benchmark/` 178） | 1909 | **1909** | **未变** | 282 | 0.15× |
| 会话后端 · JSONL（src） | `packages/agent/src/harness/session/jsonl` | 1894 | **1894** | **未变** | 1863 | 0.98× |
| 会话后端 · Memory（src） | `memory.ts`(453) + `in-memory-storage-state.ts`(349) | 802 | **802** | **未变** | 304 | 0.38× |
| 会话契约层（含上述后端） | `packages/agent/src/harness/session`（全目录） | 7108 | **7108** | **未变** | 4194 | 0.59× |
| 评测（src） | `packages/evals/src` | 1964 | **1446** | **−518** | 773 | 0.53× |
| 评测（test） | `packages/evals/test` | 476 | **815** | **+339** | 412 | 0.51× |
| **chord（src）** | `packages/chord/src` | 5822 | **6503** | **+681** | **0** | 0.00× |
| **chord（test）** | `packages/chord/test` | 3553 | **4376** | **+823** | **0** | 0.00× |
| **durable（新包）** | `packages/durable/src` | — | **757** | **全新** | **0** | 0.00× |
| durable（test / docs） | 同包 `test/` 636、`docs/` 3114 | — | **3750** | **全新** | 0 | 0.00× |
| **pico3（新，在 agent 内）** | `packages/agent/src/harness/pico3` | — | **7994** | **全新** | **0** | 0.00× |
| pico3（test） | `packages/agent/test/harness/pico3` | — | **7135** | **全新** | 0 | 0.00× |
| **micro（新，在 coding-agent 内）** | `packages/coding-agent/src/experimental/micro` | — | **1524** | **全新** | **0** | 0.00× |
| **防漂移脚本** | `scripts/`（40 文件，**文件清单未变**） | 8314 | **8325** | **+11** | **0** | 0.00× |

> **pi 的包数 11 → 12**：新增 `packages/durable`（`@earendil-works/pi-durable`，version 0.86.1，依赖 `chord` + `pi-ai`）。
> `packages/agent/src` 从 25305 行涨到 **33353**（+8048，其中 **pico3 占 7994** ⇒ agent 的其余部分零增长）。
> pi 的评测框架仍是**外部 npm 包**：`vitest-evals@0.15.0` + **新增 `@vitest-evals/core@0.15.0`** + **新增 `autoevals@0.3.0`**（`packages/evals/package.json`）。

---

## 重测：pi `71dca871b` → `3390bd936`（111 提交）

> ⚠️ **取证方式**：重测期间发现 `D:/workplaceForai/pi` 的工作树**中途从 `3390bd936` 翻回 `71dca871b`**（`git rev-parse HEAD` 实测为旧提交、`git status` 干净）。
> 因此**所有新状态读取改用 `git show 3390bd936:<path>`**，不依赖工作树；判定口径 / 权重规则 / 范围裁决均未改动。

### 引用漂移总账

| 口径 | 数 | 说明 |
|---|---:|---|
| 从本文解析出的 pi 引用路径 | **73** | 去重后的 `file`（含 `packages/`、`scripts/`） |
| **逐字节未变（引用原样有效）** | **64** | `git diff --quiet 71dca871b..3390bd936 -- <path>` 为空 ⇒ **行号与内容都没动** |
| **变了** | **9** | 见下表 |

| 变了的路经 | 性质 | 影响 |
|---|---|---|
| `packages/agent/src/harness/session/**`（全目录） | — | **逐字节未变** ⇒ 存储后端 / JSONL / Memory / 会话契约的**全部引用原样有效** |
| `packages/session-backends/sqlite-node/src/sqlite/**` | — | **逐字节未变** ⇒ **SQLite schema 与全部行引用原样有效** |
| `packages/chord/src/**`（除 delta） | — | **逐字节未变**（`facets/host.ts`、`services/*`、`node/*`、`context/`、`json.ts`、`api.ts`、`types.ts` 全同） |
| `packages/agent/src/search/index.ts` | — | 未变（`SessionSearchService` 仍在 `:20`） |
| `packages/protocol`、`packages/server`、`packages/client` | — | **整个包未变** |
| `packages/coding-agent/src/core/session-manager.ts` | **实质** | **B55**（+246/−121，367 行变动） |
| `packages/coding-agent/src/modes/interactive/interactive-mode.ts` | 行号 | `/share` 分派 `:3002-3003` → **`:3080-3081`**；handler `:6151` → **`:6279-6280`** |
| `packages/coding-agent/src/modes/interactive/session-share.ts` | 行号 | `shareSession` `:45-46` → **`:57`**；**新增导出** `createShareTrailingEntries`（`:25`） |
| `packages/coding-agent/src/core/slash-commands.ts` | 无漂移 | `/share` 仍在 **`:27`**（`:28` 新增 `bug` 命令，share 行号未动） |
| `packages/chord/src/delta/index.ts` | 行号（内部） | 1267 → **1948**；**20 个导出锚点行号全部未变**，仅 ~6 个内部锚点漂移 |
| `packages/evals/**` | **实质** | 整包重构（见下） |
| `scripts/check-entry-graphs.mjs`、`scripts/local-release.mjs`、`scripts/build-coding-agent-bundle.mjs` | 配置 | 仅新增 `pi-durable` 工作区条目（+1/+1/+17 行）；**门集合未变** |

**只漂行号（判定不变）的引用：10 条** —— chord delta 内部锚点 **7 条**（`:205-227`、`:536-588`、`:435-748`、`:642-648`、`:670-673`、`:982-984`、`:1028-1064`；逐个 `sed -n` 比对确认内容已不同）＋ `/share` **3 条**（`interactive-mode` ×2、`session-share` ×1）。
**另有 5 条 chord delta 锚点连行号都没动**（`:30-36`、`:48-60`、`:764-936`、`:938-1011`、`:1014-1026`），**64 个整文件逐字节未变** ⇒ 本文绝大多数引用一条都不用改。

### B55 重新取证（实质变化）

pi 的 `SessionManager.list` **不再是「同步全量」** —— 现在是**并发 + 可中止 + 增量发布部分结果**：

| 项 | 旧（71dca871b） | 新（3390bd936） |
|---|---|---|
| `SessionListProgress` 类型 | `:768`，签名 `(loaded, total) => void` | **`:795-800`**，签名 **`(loaded, total, partialSessions?: readonly SessionInfo[]) => void`** |
| 增量发布节流 | 无 | **`CURRENT_SESSION_LIST_PUBLISH_INTERVAL = 10`**（`:804`）：`loaded === 1 \|\| loaded % 10 === 0 \|\| loaded === files.length` 时才带 `partialSessions`（`:875`） |
| `listSessionsFromDir` | `:812`，串行 | **`:852-881`**，走 **`buildSessionInfosWithConcurrency`**（`:835`） |
| `AbortSignal` | 无 | `listSessionsFromDir`（`:855`）、`list`（`:1762`）、**`listAll`（`:1779-1804`）** 全部新增 `signal?: AbortSignal`；`signal?.throwIfAborted()`（`:853`、`:884`） |
| `SessionManager.list` | `:1670` | **`:1758-1771`**，且把 `partialSessions` 用 `includeSession` 过滤后再转发（`:1768-1769`） |
| `listAll` | `:1685` | **`:1779-1804`**（重载签名增加 `signal`，参数解析改类型判别 `:1786-1801`） |
| `findMostRecentSession` | — | `:660`（A12 相关，未变语义） |

**结论：B55 判定仍为「缺失」，但差距从 1 项扩到 4 项** —— pi-java 的 `SessionRepository.list` 缺
① 进度回调、② **部分结果增量发布**（`partialSessions`）、③ **`AbortSignal` 中止**、④ **并发扫描**。
`SessionListProgress` 的新签名意味着 pi 的调用方（交互式选择器）能在扫描未完成时**先渲染已加载的会话**；pi-java 只能等全量返回。

### SQLite schema 重新对齐

**pi 的表数没变（仍是 7 张），DDL 逐字节未变** ⇒ **本文「SQLite schema 逐表对齐」18 行判定 100% 不变，零改动**。
证据：`git diff 71dca871b..3390bd936 -- packages/session-backends` 只命中 `CHANGELOG.md` 与 `package.json`（版本号 0.85.1 → 0.86.1）；
`packages/session-backends/sqlite-node/src/sqlite/migrations/001_initial.sql` 与 `migrations.ts`、`repo.ts`、`storage.ts`、`session/*.ts`、`test/repo.test.ts` **全部逐字节未变**。
⇒ 同名 3 表、pi 独有 4 表、java 独有 10 表、触发器 2 条 —— **分叉格局一字未改**。

### `evals` 缩了 518 行：不是删能力，是换架构

`src` 1964 → 1446（−518），但**能力是净增的**。逐文件账：

**删除（−）**：`src/vitest-evals/{artifacts 113, harness-table 204, reporter 124, setup 8, summary 438}` = **887** ·
`src/pi-harness.ts` 302 · `src/providers.eval.ts` 410 · `src/models.eval.ts` 151 · `src/extensions.eval.ts` 127 · `src/docs.eval.ts` 70 · `scripts/run-evals.mjs` 120（在 `scripts/`，不计入 src）。
**新增（+）**：`src/harness.ts` **537** · `src/report.ts` **483** · `src/cli.ts` **192** · `src/docker.ts` **175** · `src/plan.ts` **59** = **1446**（正好等于新 src 总量）。

**去向映射**（能力没丢，换了家）：

| 旧位置 | 新位置 | 证据 |
|---|---|---|
| `vitest-evals/summary.ts`（对照报告） | `src/report.ts` | `summarizeEvalObservations`（`:359`）、`formatEvalComparisonReport`（`:436`）、`EvalComparisonReport`（`:75`）、`PairedMetricSummary`（`:36`）、`EvalSetComparison`（`:43`） |
| `vitest-evals/artifacts.ts`（会话快照） | `src/report.ts` | `PI_SESSION_SNAPSHOT_ARTIFACT = "piSessionJsonl"`（`:9`）、`persistSession`（`:121-134`，落 `session.jsonl`） |
| `vitest-evals/reporter.ts`（落盘） | `src/report.ts` | `readTaskObservation`（`:136`）、`classifyCaseStatus`（`:101`）、`erroredObservation`（`:118`） |
| `vitest-evals/harness-table.ts`（A/B 对照） | `src/report.ts` + `src/plan.ts` | `BlockedPair`（`:58`）、`VariantTotals`（`:62`）；`plan.ts` 的 `DOCUMENTATION_VARIANTS`（`:1`）与 `createTaskPlan`（`:39`） |
| `pi-harness.ts`（真 harness） | `src/harness.ts` | `createPiCodingAgentHarness`（`:471-475`）、`resolveModelSelection`（`:70`）、`applyIsolatedEnvironment`（`:82`）、`verifySystemPrompt`（`:257`）、**新增** `createPiDocumentationEvalHarness`（`:517-523`）、`DOCUMENTATION_EVAL_TOOLS`（`:485`）、`resolveDocumentationVariant`（`:487`） |
| `scripts/run-evals.mjs`（CLI） | `src/cli.ts` | `parseEvalCli`（`:27`）、`compareDiscovery`（`:106`）、`cli`（`:114`） |
| `*.eval.ts` 5 个 | `evals/*.eval.ts` 7 个 + `evals/{acme-server,configured-runtime}.ts` | 见下 |
| — | **`src/docker.ts`（全新）** | `buildImages`（`:38`）、`requireEvalAuthFile`（`:67`）、`createDockerContext`（`:126`）、`discoverCases`（`:137`）＋ `docker/Dockerfile`(44) + `entrypoint.ts`(152) |

**评测用例的变化**：`docs.eval`/`extensions.eval`/`models.eval`/`providers.eval` → 拆成
`documentation-audit.eval.ts`(64) · `extensions.docs.eval.ts`(60) · `models.docs.eval.ts`(40) · `custom-provider.docs.eval.ts`(72) · `openai-provider.docs.eval.ts`(62) · **`tui.docs.eval.ts`(264，全新)** · `smoke.eval.ts`(18)，
外加助手 `acme-server.ts`(154)、`configured-runtime.ts`(120)。
**判分器升级**：`createJudge` 仍在（`tui.docs.eval.ts:13`）＋ **`StructuredOutputJudge`/`ToolCallJudge`**（`extensions.docs.eval.ts:2`）＋ **`autoevals` 的 `Levenshtein`**（`tui.docs.eval.ts:12`）。
**双轨入口**：`package.json` `"eval": "npm run eval:host && npm run eval:docs --"` ⇒ `eval:host`（vitest）+ `eval:docs`（自研 CLI）。

### chord：能力集合未变，只有 delta 内部长大

**关键更正**：`delta/index.ts` 1267 → 1948（+681）**不是新增能力** ——
`overlap`(81)、`isBase`(70)、`TrackerOptions`(108)、`Tracker<T>`(112)、`encoder`(1105)、`decoder`(1198)、`isReplace`(64)、`assertValidOp`(785)、`assertValidWireOp`(828)、`assertSafePath`(891)、`PathError`(922)、`UnsafePathError`(766)、`RESERVED_SEGMENTS`(764)、`apply`(938)、`applyImmutable`(1014)、`track`(435) —— **全部在旧 HEAD 就已存在，且行号一字未变**（逐个 `git show 71dca871b:... | grep -n` 比对）。
+681 行全在 **`decoder()` 之后**（旧 `:1198`→`:1267` 收尾 69 行；新 `:1198`→`:1948` 收尾 750 行），是**内部加固**：
batch 作用域的 arity 省略、base 批次清空路径字典（恢复点语义）、稀疏数组拒绝、`undefined`/`delete`/`defineProperty` 明确抛错、克隆与代理保留量优化。
**⇒ chord 能力清单 21 行判定全部不变。** 只有内部锚点漂了 5 处（见上「只漂行号」）。

**chord 的消费者新增两个**：`packages/durable`（`package.json` 依赖 `@earendil-works/chord ^0.86.1`）与
`packages/agent/src/harness/pico3`（`chord.ts`、`jsonl.ts`、`memory.ts`、`session.ts`、`types.ts`、`view.ts` 六个文件 import `@earendil-works/chord/delta`）。
⇒ 消费者从 5 个包变 **7 个包**（agent / client / coding-agent / durable / protocol / server / **pico3 所在的 agent**）。

### 防漂移脚本：文件清单与门集合都没变

`scripts/` **40 个文件，文件清单逐条 diff 为空**（`diff <(git ls-tree 71dca871b scripts) <(git ls-tree 3390bd936 scripts)` 无输出）；
`check-*.mjs` 仍是 **7 个**（含 1 个测试）。行数 8314 → 8325（**+11**）。
唯一改动：`check-entry-graphs.mjs:24` 与 `local-release.mjs:13` 各加一行 `packages/durable` 工作区条目 —— **为容纳新包而扩白名单，不是新门**。
`package.json:21` 的 `check` 链仍是 **7 个检查器**（`check:pinned-deps`/`check:runtime-deps`/`check:ts-imports`/`check:entry-graphs`/`check:shrinkwrap`/`check:install-lock:coding-agent`/`check:browser-smoke`）。
**⇒ 脚本表 40 行判定全部不变。**

### 新增子系统（pi 111 提交里长出来的三块，pi-java 零对应物）

| 子系统 | 路径 | LOC（src / test / docs） | 消费者 | 说明 |
|---|---|---|---|---|
| **`durable`**（Pico5） | `packages/durable` | **757** / 636 / **3114**（`pico-v5.md` 2031 行**规范**） | **0**（仅自身 README/package.json） | 「Durable conversation, task, and document runtime」。导出 `MemoryStorage`、`ROOT_CONVERSATION_ID` 与 19 个类型（`ConversationRecord`/`EntryRecord`/`TaskRecord`/`DocumentRecord`/`Input`/`Storage`/`StorageWrite`/`Cursor`/`Page`…）。`Storage` 接口 12 个方法：`commit(writes[],ctx)` 原子批量、`mintId()`、`conversation(id)`、`scanConversations(cursor,limit)`、`entry(id)`、`findLatestHeadMarker(convId, atOrBeforeEntryId)`、`scanEntries(query,cursor,limit)`、`task(id)`、`scanTasks(query,cursor,limit)`、`input(id)`、`inputByRequest(convId, requestId)`、`close(ctx)`。核心不变量（`docs/pico-v5.md:34-52`）：「一次 Session 提交跨 record 与 document **原子**」「可见进度**全部持久**，无易失发布路径」「外部副作用不在 Session 变更事务内运行」 |
| **`pico3`** | `packages/agent/src/harness/pico3` | **7994** / 7135 / — | `micro`（4 文件） | Pico3 内核。最大：`session.ts` 1497、`types.ts` 1038、`harness.ts` 812、`kinds/generation.ts` 641、`scheduler.ts` 486、`kinds/tool.ts` 479、`view.ts` 477。**自带两个存储后端**：`jsonl.ts`(339) `JsonlStorage extends MemoryStorage`（目录 + `main.jsonl` + sticky/task sidecars + fsync + 撕裂尾截断 `:334-338`）与 `memory.ts`(278) `MemoryStorage`。还有 chord 视图桥 `chord.ts`(178)：`attachChordView`/`createPicoConversationService`/`PicoHarnessService` |
| **`micro`** | `packages/coding-agent/src/experimental/micro` | **1524** / — / README 39 | **0**（独立入口 `main.ts`） | 「A small local coding agent built on the experimental Pico3 harness」。`README.md:3-5` 明说「Unlike `mini`, it has **no server, worker, RPC, or client/session process split**. One process owns the model runtime, Pico harness, JSONL storage, and TUI」。会话落 `~/.pi/agent/experimental/micro-sessions/<cwd-hash>/`，**每 session 一个 Pico3 `JsonlStorage` 目录**（`main.jsonl` + sidecars），**用 `proper-lockfile` 做文件系统锁防两进程同占**（`sessions.ts:39-44`，`retries: 0` 直接抛 `Micro session is already open`） |

**⚠️ 三者**：`durable` 与 `micro` **零消费者**、`pico3` 只被 `micro` 消费 ⇒ 都是 **pi 的前瞻性建设，不是已发布能力**。给权重时按「若接上有多重要」计（与 chord 同口径），并单列。

---

## 存储后端对齐（按后端，不按模块）

判定基准：pi `Storage` 接口 = `packages/agent/src/harness/session/types.ts:461-471`（11 个方法）；
pi `Session` 接口 = 同文件 `:587-605`；pi `SessionRepo` = 同文件 `:638-644`。
pi-java 对应 = `pi-java-agent-core/.../agent/session/SessionStorage.java:19-93` + `SessionRepository.java:12-34`。

| # | 权重 | 能力 | 后端 | pi 证据 | java 证据 | 判定 | 台账条目 |
|---|---|---|---|---|---|---|---|
| 1 | 2 | create 建会话 | 三后端 | `session/types.ts:639`（`SessionRepo.create`）；sqlite `sqlite/repo.ts:180` | `SessionRepository.java:18`；sqlite `SqliteSessionRepository.java` | 对齐 | — |
| 2 | 2 | list 列会话 | 三后端 | `session/types.ts:641`；sqlite `repo.ts:250` | `SessionRepository.java:27` | 对齐 | — |
| 3 | 1 | list 带进度回调 `onProgress` ＋ **增量部分结果** ＋ **`AbortSignal`** ＋ **并发扫描** | 三后端 | ⚠️ **重测（`3390bd936`）**：`SessionListProgress` **`:795-800`**，签名 **`(loaded, total, partialSessions?) => void`**；节流常量 `CURRENT_SESSION_LIST_PUBLISH_INTERVAL = 10`（`:804`）；`listSessionsFromDir` **`:852-881`**（走 `buildSessionInfosWithConcurrency` `:835`）；`list` **`:1758-1771`**、`listAll` **`:1779-1804`** 均新增 `signal?: AbortSignal`；`signal?.throwIfAborted()` `:853`/`:884`。旧引用 `:814`/`:1670`/`:1685` 已失效 | **无**（`SessionRepository.list` 是同步全量、无回调、无中止、无并发） | **缺失**（差距由 1 项扩到 **4 项**） | **B55** |
| 4 | 2 | open 打开 + 独占写者 | 三后端 | `session/types.ts:640` | `SessionRepository.java:24` | 对齐 | — |
| 5 | 2 | delete 删会话 | 三后端 | `session/types.ts:642` | `SessionRepository.java:30` | 对齐 | — |
| 6 | 2 | fork（`branch`/`tree` 双 scope） | 三后端 | `session/types.ts:608-637`（`ForkOptions` 判别联合） | `session/ForkOptions.java` | 对齐 | — |
| 7 | 3 | 追加 entry | 三后端 | `session/types.ts:381`（`EntryWrite`）；sqlite `storage.ts:169` | `SessionStorage.java:41`（`appendEntry`） | 对齐 | — |
| 8 | 3 | **批量提交 `commit(writes[])`** | 三后端 | `session/types.ts:461`；sqlite `storage.ts:67`；memory `memory.ts:36` | **无**（只有逐条 append） | **缺失** | — |
| 9 | 3 | **按 id 批量读 entry `getEntries(ids[])`** | 三后端 | `session/types.ts:462`；sqlite `storage.ts:77` | 只有单条 `SessionStorage.java:49`（`getEntry(id)`） | **缺失** | — |
| 10 | 3 | 分支扫描 `scanBranch` | 三后端 | `session/types.ts:463`；sqlite `session/branch-entries.ts:47` | `SessionStorage.java:58`（`findEntriesOnBranch`） | 对齐 | — |
| 11 | 1 | **分支结构扫描 `scanBranchStructure`** | 三后端 | `session/types.ts:464`；sqlite `branch-entries.ts:112` | **无** | **缺失** | — |
| 12 | 3 | entry 扫描 | 三后端 | `session/types.ts:465`（`scanEntries`）；sqlite `session/entries.ts:120` | `SessionStorage.java:52`（`findEntries`） | 对齐 | — |
| 13 | 3 | **标量值 `getValue`/`scanValues`** | 三后端 | `session/types.ts:466-467`；sqlite `session/values.ts:72/80` | **无** | **缺失** | — |
| 14 | 3 | **列表值 `readList`/`appendList`/`deleteList`** | 三后端 | `session/types.ts:468`；sqlite `session/values.ts:121` | **无** | **缺失** | — |
| 15 | 2 | **usage ledger 扫描 `scanUsage`** | 三后端 | `session/types.ts:469`；sqlite `session/usage-ledger.ts:66` | **无** | **缺失** | — |
| 16 | 2 | 会话统计 `getStats` | 三后端 | `session/types.ts:470`；sqlite `session/session-stats.ts:30` | `SessionStorage.java:86` | 对齐 | — |
| 17 | 2 | 会话名 `getName`/`setName` | 三后端 | `session/types.ts:600/603`；载体＝标量值 `pi.session.name`（`session/values.ts:194`） | `SessionStorage.java:72/75`；载体＝`facts` 表 `kind='name'`（`storage/FactRows.java:25`） | **存疑**（载体不同：pi 通用值存储 / java 专用表） | — |
| 18 | 1 | entry 标签 `getLabel`/`setLabel` | 三后端 | `session/types.ts:601/604`；载体＝标量值 `pi.entry.label`（`values.ts:195`） | `SessionStorage.java:78/81`；载体＝`facts` 表 `kind='label'`（`SqliteSessionStorage.java:350`） | **存疑**（同上） | — |
| 19 | 3 | 分支 tip 读取 | 三后端 | **权威**＝标量值 `pi.branch.tip`（`values.ts:158`）；**缓存**＝`branch_meta`（`branch-entries.ts:67`） | `branch_tips` 表（`storage/BranchTipRows.java:15`），无「权威 vs 缓存」二分 | **存疑** | — |
| 20 | 2 | 分支创建 / 移动 | 三后端 | `session/types.ts:596-597`（`createBranch`/`branch`）；sqlite `branch-entries.ts:85/92` | `SessionStorage.java:30/33`（`createLane`/`moveLane`） | **存疑**（命名与粒度不同：pi 有 base_branch/base_seq 分段） | — |
| 21 | 1 | 会话全文搜索 | sqlite | **接口存在、全仓零实现**：`packages/agent/src/search/index.ts:20`（`SessionSearchService`），唯一引用是类型断言 `packages/agent/test/harness/types.test.ts:384` | `SqliteSessionSearch.java:17`（FTS5 真实实现） | **对齐（java 反超）** | H §9.3 line 416 |
| 22 | 2 | schema 迁移 | sqlite | `sqlite/migrations.ts:5` + `migrations/001_initial.sql` | `Migrations.java:21` + `resources/sql/001_initial.sql` | 对齐 | — |
| 23 | **0** | **写者租约 `writer_lease`** | sqlite | **pi 已删除**：`sqlite-node/test/repo.test.ts:368-371` 断言建库后该表**不存在**；`:376` 用例名「ignores a stale writer_lease table from a **pre-WP07 WIP** database」；全仓 `find -iname "*writer-lease*"` 零命中 | `WriterLease.java`、`storage/WriterLeaseRows.java`、`writer_leases` 表、`pi-sqlite-heartbeat` 守护线程（`SqliteSessionStorage.java:50-56`） | **对齐（java 反超/越界）** | **A17** |
| 24 | 1 | 会话日志 `getLog` | 三后端 | **pi 无此 API** | `SessionStorage.java:67`（`getLog`）+ `session/LogItem.java` | **存疑**（java 独有构造） | — |
| 25 | 1 | **lane 记录 `appendRecord`/`findRecords`/`findOpenOperations`** | 三后端 | **pi 无此 API**（pi 的 `Write` 只有 `entry`/`usage`/`value`/`list`，`session/commit.ts:3-29`） | `SessionStorage.java:44/61/64`；`records`/`lanes`/`lane_moves` 表 | **缺失（java 独有）** | — |
| 26 | 1 | **JSONL v3 旧格式读取** | jsonl | `jsonl/codec.ts:18-33`（`type:"session"` + `version:3` + `timestamp` + `parentSession`）＋ `jsonl/legacy-v3.ts`（700 行） | `JsonlCodec.parseHeader:110-113` **要求 `kind:"header"` + `version`** ⇒ 读不了 pi 的 v3 文件 | **缺失** | — |
| 27 | 2 | fork 快照 `snapshot` | sqlite | `sqlite/storage.ts:132` | `SqliteSessionRepository` fork 路径 | 对齐 | — |
| 28 | 2 | close / drain | 三后端 | `session/types.ts:471`（`close`）；memory `memory.ts` `commitQueue` | `SessionStorage.java:89/92`（`drain`/`close`） | 对齐 | — |
| 29 | **0** | 心跳续租 | sqlite | **无**（无租约） | `SqliteSessionStorage.java:62-70`（`scheduleHeartbeat`，TTL 30 s / 心跳 10 s） | **对齐（java 反超）** | **A17** |
| 30 | 2 | **JSONL 头部线格式** | jsonl | `jsonl/types.ts:4-5`（`JSONL_FORMAT_VERSION=4`、`JSONL_STORAGE_VERSION=1`）；`jsonl/codec.ts:35-46` 校验 `v===4` **且** `storageVersion>=1` | `JsonlV4Header.java:24-31`；`JsonlCodec.encodeHeader:85-88` 写 `version`（**不是 `v`**）＋ **无 `storageVersion`** | **缺失（双向不可读）** | — |
| 31 | 3 | **JSONL 事务行 kind 集合** | jsonl | `session/commit.ts:3-29`：`entry` / `usage` / `value` / `list` | `JsonlCodec.java:139-183`：`entry` / `record` / `lane` / `fact` | **缺失（交集只有 `entry`）** | — |
| 32 | 2 | JSONL 文件名编码 | jsonl | `jsonl/repo.ts:40-43`：`${ISO}_${encodeURIComponent(id)}.jsonl` | `JsonlSessionRepository.java:250-253`：`${ISO}_${id}.jsonl`（**不 encode**） | **存疑**（id 含特殊字符时分叉） | — |
| 33 | 2 | JSONL 目录名 | jsonl | `jsonl/repo.ts:36-38`：`--${cwd.replace(/^[/\\]/,"").replace(/[/\\:]/g,"-")}--` | `JsonlSessionRepository.java:244-247`：同形 | 对齐 | — |
| 34 | 1 | JSONL `nextSeq` 高水位 | jsonl | `jsonl/types.ts:16`（`nextSeq?`，snapshot 重写时写） | **无该字段** | **缺失** | — |
| 35 | 1 | 内存后端进程内注册表（`-c/-r`） | — | pi 无对应物（pi 用 JSONL 默认目录 + `findMostRecentSession`） | `coding-agent/.../core/session/InMemorySessionRepository.java:24` | **存疑**（java 独有，见 A12 结论） | **A12**（已结案） |

**行数**：35 行 —— 对齐 16 / 缺失 12 / 存疑 7。
**权重**：**Σ = 66**（2 行权重 0 已排除：`#23` 写者租约、`#29` 心跳续租 ⇒ 计入分母 33 行：对齐 14 / 缺失 12 / 存疑 7）。

---

## SQLite schema 逐表对齐

pi 权威 DDL：`packages/session-backends/sqlite-node/src/sqlite/migrations/001_initial.sql`（122 行，**7 张表**）。
pi-java 权威 DDL：`pi-java-session-backend-sqlite/src/main/resources/sql/001_initial.sql`（117 行，**11 张表**）
＋ 运行时动态创建 2 张：`migrations`（`Migrations.java:39-44`）、`session_search_fts`（`SqliteSessionSearch.java:114-120`，FTS5 虚拟表）
⇒ **pi-java 实际 13 张**。

**同名表只有 3 个**：`sessions` / `entries` / `branch_entries`。**核实：`docs/32` 全文零处提到 schema 分叉（grep `schema`/`branch_meta`/`scalar_values`/`usage_ledger`/`list_values` 只命中 B8 的无关行）⇒ 本条差距「未登记」。**

| # | 权重 | 表名 | pi 有 | java 有 | 列差异 | 判定 | 证据 |
|---|---|---|---|---|---|---|---|
| 1 | 2 | `sessions` | ✅ `001_initial.sql:9-18` | ✅ `001_initial.sql:1-7` | pi：`id, created_at(INTEGER), parent_session_id, storage_version, metadata, message_count, usage_payload, next_seq`（8 列）<br>java：`id, created_at(TEXT), cwd, parent_session_id, metadata`（5 列）<br>**pi 独有**：`storage_version`/`message_count`/`usage_payload`/`next_seq`（java 拆到 `session_stats`/`session_sequences` 表）<br>**java 独有**：`cwd`（pi 的 SQLite 后端**不存 cwd** —— `session/session-row.ts:57-69` 返回的 metadata 无 cwd 字段）；`created_at` 类型 `TEXT` vs pi `INTEGER` | **缺失/分叉** | pi `sqlite/session/session-row.ts:6-15`；java `storage/SessionRows.java:38` |
| 2 | 3 | `entries` | ✅ `001_initial.sql:20-30` | ✅ `001_initial.sql:12-22` | pi：`session_id, id, parent_id, seq, type, custom_type, timestamp(INTEGER), payload`<br>java：`session_id, seq, id, parent_id, type, timestamp(TEXT), payload`<br>**pi 独有**：`custom_type` 列；`timestamp` 类型 `INTEGER` vs java `TEXT`<br>**java 独有**：`UNIQUE(session_id, seq)` 约束 | **缺失/分叉** | pi `session/entries.ts:52`；java `storage/EntryRows.java:52` |
| 3 | 3 | `branch_entries` | ✅ `001_initial.sql:94-101` | ✅ `001_initial.sql:41-49` | pi：`session_id, branch_id, entry_id, entry_seq, entry_type`<br>java：同上 ＋ **`custom_type`** 列<br>pi 索引 3 个（`ix_be_seq`/`ix_be_type`/`ix_be_entry`），java 索引 4 个 | **存疑**（java 多一列一索引） | pi `001_initial.sql:106-109`；java `001_initial.sql:51-54` |
| 4 | 3 | `scalar_values` | ✅ `001_initial.sql:35-42` | ❌ **无** | pi 的**通用值存储**：`session_id, namespace, key, seq, value`，PK `(session_id, namespace, key)`。承载 pi 的**全部 13 个值命名空间**（`session/values.ts:158-195`）：`pi.branch.tip`/`pi.lane.config`/`pi.lane.state`/`pi.result`/`pi.op.meta`/`pi.op.state`/`pi.op.tool_args`/`pi.op.tool_memo`/`pi.op.preparation`/`pi.pending.entry`/`pi.pending.tool_output`/`pi.session.name`/`pi.entry.label` —— **含整个 durable operation 状态机**。java 把其中 name/label 硬编码进 `facts`，tip 进 `branch_tips`，其余**不持久化** | **缺失** | pi `session/values.ts:33/51/72`；java 无对应文件 |
| 5 | 3 | `list_values` | ✅ `001_initial.sql:44-51` | ❌ **无** | pi 的有序列表值：`session_id, namespace, key, seq, value`，PK `(session_id, namespace, key, seq)`。pi 只用 1 个命名空间：`pi.pending.assistant_frame`（`values.ts:183`） | **缺失** | pi `session/values.ts:121`；java 无 |
| 6 | 2 | `usage_ledger` | ✅ `001_initial.sql:53-64` | ❌ **无** | pi 的用量台账：`session_id, id, seq, entry_id, adjustment, usage, details`，PK `(session_id, id)`，索引 `ix_usage_seq`。**`id` 与 `entries.id` 共享命名空间**（由触发器 `trg_usage_ledger_validate` 强制）。java 只有 `session_stats` 的 5 个汇总实数，**无逐条台账** | **缺失** | pi `session/usage-ledger.ts:14/66`；java `storage/StatsRows.java:17` |
| 7 | 3 | `branch_meta` | ✅ `001_initial.sql:111-119` | ❌ **无** | pi 的分支缓存：`session_id, branch_id, tip_entry_id, tip_seq, base_branch_id, base_seq`，PK `(session_id, branch_id)`，唯一索引 `ix_bm_tip`。`base_*` 承载**分支分段**（`branch-entries.ts:97-111` 的 `readBranchSegmentsNewestFirst` 用它回溯到根，压缩边界 `readNewestCompactionBoundary` 依赖它）。**java 无 base_* 概念** | **缺失** | pi `session/branch-entries.ts:60-70/85/92`；java `storage/BranchTipRows.java` 只有 `(session_id, tip_id, branch_id)` |
| 8 | 3 | `session_sequences` | ❌ **无** | ✅ `001_initial.sql:27-30` | java 独有：`session_id, next_seq`。pi 把 `next_seq` 作为 `sessions` 表的一列（`session/session-sequences.ts:5/11` 直接读写 `sessions.next_seq`） | **对齐（java 拆表）** | pi `session/session-sequences.ts:5`；java `storage/SequenceRows.java:14` |
| 9 | 2 | `session_stats` | ❌ **无** | ✅ `001_initial.sql:32-39` | java 独有：`session_id, message_count, cached_tokens, uncached_tokens, total_tokens, cost_total`。pi 用 `sessions.message_count` + `sessions.usage_payload`（整个 `Usage` JSON 串，`session/session-stats.ts:44`）。**java 把 Usage 摊平成 5 个 REAL 列 ⇒ 丢失 `cacheWrite1h`/`reasoning` 分量**（pi 的 `addUsage` 显式处理这两者，`session-stats.ts:6-13`） | **存疑**（形状分叉 + 可能丢分量） | pi `session/session-stats.ts:30-45`；java `storage/StatsRows.java:17/55` |
| 10 | 3 | `lanes` | ❌ **无** | ✅ `001_initial.sql:56-62` | java 独有：`session_id, lane, leaf_id, open_operation_id`。pi 的 lane 状态是标量值 `pi.lane.state`（`values.ts:161`），不在专门表里 | **缺失（java 独有）** | java `storage/LaneRows.java:34/71` |
| 11 | 1 | `records` | ❌ **无** | ✅ `001_initial.sql:64-83` | java 独有：`session_id, seq, id, lane, run_id, type, op_kind, timestamp, payload` ＋ **6 个索引**。承载 java 的 `LaneRecord` 家族（11 种：`operation_started`/`abort_requested`/`operation_finished`/`step_attempt`/`tool_started`/`tool_finished`/`queue_enqueued`/`queue_cancelled`/`queue_consumed`/`write_deferred`/`usage`，见 `JsonlCodec.java:24-27`）。pi 无 `LaneRecord` 构造 | **缺失（java 独有）** | java `storage/RecordRows.java:37/139` |
| 12 | 1 | `lane_moves` | ❌ **无** | ✅ `001_initial.sql:85-91` | java 独有：`session_id, seq, lane, leaf_id`。lane 移动的 append-only 日志（`LaneRows.java:178`），支持回放到任意 seq | **缺失（java 独有）** | java `storage/LaneRows.java:153/178` |
| 13 | 2 | `facts` | ❌ **无** | ✅ `001_initial.sql:93-100` | java 独有：`session_id, seq, kind, key, value` ＋ 索引。**只用两个 kind**：`'name'`（`SqliteMutationReplay.java:37`）与 `'label'`（`:42`）。语义上对应 pi 的 `pi.session.name` / `pi.entry.label` 两个**标量值** | **缺失（java 独有）** | java `storage/FactRows.java:25/63` |
| 14 | 3 | `branch_tips` | ❌ **无** | ✅ `001_initial.sql:104-110` | java 独有：`session_id, branch_id, tip_id`，PK `(session_id, tip_id)`，`UNIQUE(session_id, branch_id)`。语义上**部分**对应 pi 的 `branch_meta`，但**没有 `base_branch_id`/`base_seq`**（缺分支分段） | **缺失（java 独有）** | java `storage/BranchTipRows.java:15/29/37` |
| 15 | **0** | `writer_leases` | ❌ **无（pi 已删除）** | ✅ `001_initial.sql:112-117` | java 独有：`session_id, owner_id, fence, expires_at_ms`。**pi 明确删掉了它** —— `repo.test.ts:368-371` 断言建库后该表不存在、`:376` 「ignores a stale writer_lease table from a pre-WP07 WIP database」。pi-java 的 javadoc 却写「aligned with pi `storage/writer-leases.ts`」而该文件**不存在**（见「台账纠错」） | **缺失（java 独有）** | java `storage/WriterLeaseRows.java:18`；pi `test/repo.test.ts:368-376` |
| 16 | 2 | `migrations` | ❌ **无** | ✅ `Migrations.java:39-44` | java 独有：`id, applied_at` 迁移台账。pi 的 `migrations.ts:5-8` **只 `db.exec()` 一个 SQL 文件**，**没有任何迁移追踪表** ⇒ pi 的迁移是幂等的、无版本记录 | **缺失（java 独有）** | pi `sqlite/migrations.ts:5`；java `Migrations.java:23/31` |
| 17 | 1 | `session_search_fts` | ❌ **无** | ✅ `SqliteSessionSearch.java:114-120` | java 独有：FTS5 虚拟表（`content='entries'`, `tokenize='trigram remove_diacritics 1'`）＋ 3 个同步触发器（`ai`/`ad`/`au`）＋ `rebuild`。**pi 无任何 FTS5** —— 全仓 `grep -rn "fts5\|MATCH"` 零命中 | **缺失（java 独有）** | java `SqliteSessionSearch.java:111-134`；pi 无 |
| — | 3 | **触发器** `trg_entries_validate` / `trg_usage_ledger_validate` | ✅ `001_initial.sql:69-91` | ❌ **无** | pi 用触发器强制两条跨表不变量：① 父 entry 必须已存在（"missing parent entry"）；② `entries.id` 与 `usage_ledger.id` **共享命名空间**（"duplicate entry or usage id"）。java 两条都没有（无 usage_ledger，父引用靠 `BranchCache` 应用层校验） | **缺失** | pi `001_initial.sql:69-91`；java 无 |

**行数**：18 行（17 张表 + 触发器 1 行） —— 对齐 1 / 缺失 15 / 存疑 2。
**权重**：**Σ = 40**（1 行权重 0 已排除：`#15` `writer_leases` ⇒ 计入分母 17 行：对齐 1 / 缺失 14 / 存疑 2）。
**pi 有 java 无的表：4**（`scalar_values`/`list_values`/`usage_ledger`/`branch_meta`）。
**java 有 pi 无的表：9**（`session_sequences`/`session_stats`/`lanes`/`records`/`lane_moves`/`facts`/`branch_tips`/`writer_leases`/`migrations` ＋ `session_search_fts` = 10）。
**根因**：pi 用**一张通用值表**（`scalar_values` + `list_values`）承载分支 tip、lane 配置/状态、整个 operation 状态机、pending 队列、会话名、entry 标签；
pi-java 把它们拆成 8 张**用途专用表**（`lanes`/`lane_moves`/`records`/`facts`/`branch_tips` …），并额外加了租约、迁移台账、FTS 三张。
**这不是「少了几个表」，是两种存储范式** —— pi 的 `Write` 联合只有 4 个 kind（`entry`/`usage`/`value`/`list`，`session/commit.ts:3-29`），pi-java 的 `SessionMutation` 是 `entry`/`record`/`lane`/`fact`（`JsonlCodec.java:139-183`），**交集只有 `entry`**。

---

## 评测对齐

pi 侧（⚠️ **重测后**）：`packages/evals` 已**重构** —— 框架本体仍是外部 npm 包，但现在有**三个**：
`vitest-evals@0.15.0` + **`@vitest-evals/core@0.15.0`**（新）+ **`autoevals@0.3.0`**（新，LLM 判分器）。
pi 自己的代码从「5 个 eval + 5 个 vitest-evals 集成」变成「**`src/{harness,report,cli,docker,plan}.ts`（1446 行）＋ `evals/*.eval.ts` 7 个 + `docker/`**」。
pi-java 侧：`pi-java-evals`（**自研框架** `EvalSuite`/`EvalCase`/`EvalRunner`/`EvalReporter` ＋ 协议一致性套件）—— **本包零改动**。

| # | 权重 | 能力 | pi 有（新 `3390bd936`） | java 有 | 判定 | 证据 |
|---|---|---|---|---|---|---|
| 1 | 1 | 声明式 eval 定义（`describeEval(name, {harness}, it => …)`） | ✅ `evals/documentation-audit.eval.ts:6`、`evals/tui.docs.eval.ts:13`（仍来自 `vitest-evals`） | ✅ 但形状不同（`EvalSuite.name()`/`cases()`，`api/EvalSuite.java:8/11`） | **对齐（形状不同）** | pi `evals/evals/documentation-audit.eval.ts:6`；java `evals/api/EvalSuite.java:8-11` |
| 2 | 3 | **真 coding-agent harness**（临时目录起真会话、驱动 prompt、回读 transcript/usage） | ✅ **`src/harness.ts:471-475`**（`createPiCodingAgentHarness`，302→**537** 行）＋ `:70` `resolveModelSelection` 读 `PI_PROVIDER`/`PI_MODEL` ＋ **新增** `:82` `applyIsolatedEnvironment`、`:257` `verifySystemPrompt`、`:517-523` `createPiDocumentationEvalHarness`、`:485` `DOCUMENTATION_EVAL_TOOLS`、`:487` `resolveDocumentationVariant` | **无**（`EvalContext` 只给 `Provider`/`ChatApi`/`AgentHarness`，`api/EvalContext.java:14-27`；无临时目录会话、无 transcript 回读） | **缺失** | pi `evals/src/harness.ts:471-523`；java `evals/api/EvalContext.java` |
| 3 | 2 | **LLM-as-judge 评分** | ✅ **升级**：`createJudge` 仍在（`evals/tui.docs.eval.ts:13`）＋ **`autoevals` 的 `Levenshtein`**（`tui.docs.eval.ts:12`）＋ **`StructuredOutputJudge`/`ToolCallJudge`**（`evals/extensions.docs.eval.ts:2`） | **无**（只有布尔 pass/fail，`api/EvalResult.java:13-17`） | **缺失** | pi `evals/evals/tui.docs.eval.ts:12-13`、`extensions.docs.eval.ts:2`；java `evals/api/EvalResult.java` |
| 4 | 2 | **baseline vs candidate A/B 对照表 + 重复次数** | ✅ **迁到 `src/report.ts`**：`PairedMetricSummary:36`、`EvalSetComparison:43`、`BlockedPair:58`、`VariantTotals:62`、`EvalComparisonReport:75`；变体计划在 `src/plan.ts:1`（`DOCUMENTATION_VARIANTS = ["without_docs","with_docs"]`） | **无** | **缺失** | pi `evals/src/report.ts:36-75`、`src/plan.ts:1` |
| 5 | 2 | **对照报告汇总**（paired metric / correctness lift / 诊断） | ✅ **迁到 `src/report.ts:359`**（`summarizeEvalObservations`）、**`:436`**（`formatEvalComparisonReport`）；483 行 | **无** | **缺失** | pi `evals/src/report.ts:359/436` |
| 6 | 2 | **reporter：harness-run 落产物** | ✅ **迁到 `src/report.ts`**：`readTaskObservation:136`、`persistSession:121-134`（落 `session.jsonl`）、`classifyCaseStatus:101`、`erroredObservation:118` | **部分**：`EvalReporter` 只有进程内回调（`api/EvalReporter.java:14/23`），**不落盘** | **缺失** | pi `evals/src/report.ts:101-136`；java `evals/api/EvalReporter.java:14` |
| 7 | 2 | **会话 JSONL 快照作为 test attachment** | ✅ **迁到 `src/report.ts:9`**（`PI_SESSION_SNAPSHOT_ARTIFACT = "piSessionJsonl"`） | **无** | **缺失** | pi `evals/src/report.ts:9/121-134` |
| 8 | 2 | **从 run 里查工具调用**（`toolCalls(...)`） | ✅ `evals/documentation-audit.eval.ts:6/57` | **无**（java 直接断言 `StreamEvent` 序列） | **缺失** | pi `evals/evals/documentation-audit.eval.ts:6/57` |
| 9 | 3 | eval 运行器 CLI | ✅ **迁到 `src/cli.ts:27`**（`parseEvalCli`）、`:106`（`compareDiscovery`）、`:114`（`cli`）；**双轨入口** `package.json` `"eval": "npm run eval:host && npm run eval:docs --"` | **部分**：`runner/EvalRunner.java:38`（`runAll`，纯内存） | **存疑** | pi `evals/src/cli.ts:27`；java `evals/runner/EvalRunner.java:38` |
| 10 | 2 | **docs 审计 eval**（逐页文档 vs 实现比对） | ✅ `evals/documentation-audit.eval.ts:10-57`（`submitAudit` 工具 + `toolCalls` 断言恰一次） | **无** | **缺失** | pi `evals/evals/documentation-audit.eval.ts:10-57` |
| 11 | 2 | **扩展作者 eval** | ⚠️ **语义变了**：旧「让模型写 `.pi/extensions/hello.ts`」→ 新 `evals/extensions.docs.eval.ts:1-30`「Create and use a tool extension」，走 `createPiDocumentationEvalHarness` + `StructuredOutputJudge`/`ToolCallJudge` | **部分**：`ExtensionLifecycleTest.java:29`（装配后工具/命令可见，**不让模型写扩展**） | **存疑** | pi `evals/evals/extensions.docs.eval.ts:1-30` |
| 12 | 2 | **模型作者 eval** | ✅ `evals/models.docs.eval.ts`（151→**40** 行，判分器抽到 `evals/configured-runtime.ts` 的 `inspectAddedModel`） | **无** | **缺失** | pi `evals/evals/models.docs.eval.ts`、`configured-runtime.ts` |
| 13 | 2 | **provider 集成 eval**（起本地 HTTP mock server） | ✅ **拆成 3 个**：`evals/acme-server.ts:1-154`（mock server 本体）＋ `custom-provider.docs.eval.ts`(72) ＋ `openai-provider.docs.eval.ts`(62) | **部分**：`ProviderSmokeTest.java:38`（真 provider ping）、`ProviderCatalogConformance.java:17` | **存疑** | pi `evals/evals/acme-server.ts:1-154` |
| 14 | 3 | **ChatApi 协议一致性套件**（C1–C10） | **无直接对应物**（pi 无 StreamEvent 序列校验器） | ✅ `ChatApiConformanceSuite.java:30`（C1–C10）、`StreamEventOrderValidator.java:21` | **对齐（java 反超）** | java `evals/conformance/ChatApiConformanceSuite.java:30-44`；pi 无 |
| 15 | 2 | 简单 smoke eval（"Paris" 一问） | ✅ `evals/smoke.eval.ts:10-16`（断 `output`/`errors`/`usage` 对象/`totalTokens>0`） | **部分**：`ProviderSmokeTest.java:39`（只断收到 `StreamDone`） | **存疑** | pi `evals/evals/smoke.eval.ts:10-16` |
| 16 | 3 | eval 框架本体 | **外部 npm ×3**：`vitest-evals@0.15.0` + `@vitest-evals/core@0.15.0` + `autoevals@0.3.0` | 自研（`EvalSuite`/`EvalCase`/`EvalContext`/`EvalResult`/`EvalReporter`/`EvalRunner`，6 接口 130 行） | **对齐（自研替代）** | pi `evals/package.json` devDependencies；java `evals/api/*.java` |
| 17 | 1 | 框架单测 | ✅ `test/{acme-server,comparison,configured-runtime,harness,plan,report}.test.ts`（476→**815** 行） | ✅ `test/conformance/StreamEventOrderValidatorTest.java`（56）、`test/ChatApiConformanceTest.java`（63）、`test/ProviderCatalogTest.java`（26） | 对齐 | 两侧 test 目录 |
| **18** | **3** | **Docker eval 执行**（构建镜像 / 容器内跑 / 隔离 auth 文件） | ✅ **全新**：`src/docker.ts:38`（`buildImages`）、`:67`（`requireEvalAuthFile`）、`:126`（`createDockerContext`）、`:137`（`discoverCases`）＋ `docker/Dockerfile`(44) ＋ `docker/entrypoint.ts`(152) ＋ `docker/install-runtime.mjs`(62) | **无** | **缺失（新增）** | pi `evals/src/docker.ts:38-137`、`evals/docker/*` |
| **19** | **2** | **文档变体任务计划**（`without_docs`/`with_docs` 对照实验的任务编排） | ✅ **全新**：`src/plan.ts:1`（`DOCUMENTATION_VARIANTS`）、`:4`（`DiscoveredEvalCase`）、`:11`（`EvalTask`）、`:21`（`parseDiscoveredCases`）、`:39`（`createTaskPlan`） | **无** | **缺失（新增）** | pi `evals/src/plan.ts:1-59` |
| **20** | **2** | **TUI 文档审计 eval**（264 行，含 Levenshtein 判分与 footer 上下文判据） | ✅ **全新**：`evals/tui.docs.eval.ts:12-13`（`Levenshtein` + `createJudge`）、`:192`（`contextFooterJudge`） | **无** | **缺失（新增）** | pi `evals/evals/tui.docs.eval.ts` |

**行数**：20 行（旧 17 + **新增 3**） —— 对齐 4 / 缺失 12 / 存疑 4。
**权重**：**Σ = 43**（旧 36 + 新增 3 行 7 点；无权重 0 行 ⇒ 计入分母 20 行）。
**净结论（重测后）**：pi 的评测**架构换了、能力净增** —— 删掉的是 `vitest-evals/` 包装层（887 行）与旧 eval 文件，新增的是自研 `harness`/`report`/`cli`/`docker`/`plan` 与 3 项能力（Docker 执行、变体计划、TUI 审计）。
**pi-java 一个都没有对应物**（20 行里 12 缺失 + 4 存疑），本包零改动。
台账 `docs/32:443`「evals 完整测试矩阵」= 唯一相关条目，仍是 OPEN。

---

## 整块缺失：chord

**pi-java 有无对应物：零。** 证据：
- `grep -rli "facet|replicated state|chord|bundle-loader|manifest" --include=*.java .` ⇒ 只命中 `ExtensionManifest.java` 等**扩展包管理**（语义无关，见下）与 `.agents/tamboui-demos/`（第三方演示代码）。
- `grep -rln "Plugin|plugin" --include=*.java pi-java-agent-core/src/main pi-java-coding-agent/src/main` ⇒ **零命中**。
- `grep -rn "chord|Chord" docs/*.md` ⇒ **零命中**（`docs/32` 也没有）⇒ **未登记**。

**pi 侧 LOC（重测后）**：`packages/chord/src` **6503**（旧 5822，**+681**，全部在 `delta/index.ts`），`test/` **4376**（旧 3553，**+823**：新增 `delta-clone.test.ts` 76、`delta-retention.test.ts` 31、`delta-retention.worker.ts` 223、`delta-traversal.bench.ts` 187、`delta.test.ts` +308），合计 **10879**。
**chord 不是 pi 的内部包**：`README.md:3-6` 明说「developed as a standalone package in the Pi monorepo, but it is **not a Pi package**: it does not depend on any other Pi workspace package」。
**chord 的消费者（重测后 7 个包）**：`packages/{agent,client,coding-agent,protocol,server}/package.json` 均声明 `"@earendil-works/chord": "^0.85.1"`（agent `:58`/client `:50`/coding-agent `:52`/protocol `:42`/server `:50`），
**新增** `packages/durable/package.json`（`^0.86.1`）与 `packages/agent/src/harness/pico3/*`（6 个文件 import `@earendil-works/chord/delta`：`chord.ts:2`、`jsonl.ts:18`、`memory.ts:2`、`session.ts:3`、`types.ts:12`、`view.ts:1`）。

**chord 是「不是 Pi 包」的独立运行时**：`README.md:3-6` 明说 standalone、零 Pi 依赖；`PLANNING.md:26-37` 把它列为**硬约束**并**明文禁止**把 Session / Harness / AgentLane / server / TUI 等变成 chord 概念；自检见 `test/boundary.test.ts:11-33`。

**最大三个源文件**：`src/delta/index.ts` **1948**（旧 1267）、`src/facets/host.ts` **906**（未变）、`src/services/consumer.ts` **660**（未变）。

> ⚠️ **重测更正**：`delta/index.ts` 的 +681 行**不是新能力**。逐个 `git show 71dca871b:… | grep -n` 比对确认：
> **20 个导出锚点（`Op`:30 / `WireOp`:48 / `isReplace`:64 / `isBase`:70 / `overlap`:81 / `TrackerOptions`:108 / `Tracker<T>`:112 / `track`:435 / `RESERVED_SEGMENTS`:764 / `UnsafePathError`:766 / `assertValidOp`:785 / `assertValidWireOp`:828 / `assertSafePath`:891 / `PathError`:922 / `apply`:938 / `applyImmutable`:1014 / `Encoder`:1097 / `encoder`:1105 / `Decoder`:1194 / `decoder`:1198）在旧 HEAD 就已存在且行号一字未变**。
> 增长全在 `decoder()` 之后（旧 `:1198`→`:1267` 收尾 69 行 → 新 `:1198`→`:1948` 收尾 750 行），是内部加固：
> batch 作用域的 arity 省略（`encoder():1800-1830`）、base 批次清空路径字典（恢复点语义）、稀疏数组拒绝（`:1268`/`:1322`）、`defineProperty` 抛错（`:1331`）、克隆与代理保留量优化。
> **⇒ 下面 21 行判定全部不变**；仅 5 处内部锚点漂号（见「重测」节）。

| # | 权重 | 能力 | pi 证据（file:line） | 说明 |
|---|---|---|---|
| 1 | 3 | **Facet 定义 + 宿主原子装配**（setup → 解析外部服务 → 图校验 → 装配 provider → 绑定 → 等所有 binding `ready` → 按拓扑序 activate） | `src/api.ts:19-27`（`createFacetHost`）、`src/api.ts:66-68`（`defineFacet`）、`src/facets/host.ts:388-421`、`src/types.ts:225-228` | 返回冻结的 `{services, reload, dispose}`；**setup 必须同步**（`host.ts:379-386` 返回 Promise 即抛错） |
| 2 | 3 | **`FacetEnvironment` 声明面 8 个能力**：`provide` / `provideMany`（返回 `ServiceSpawner`）/ `use` / `observe` / `replicatedState` / `own` / `onActivate` / `onDeactivate` | `src/types.ts:206-223`、`src/facets/host.ts:528-594` | ⚠️ 该类型虽从 chord 导出，但 `packages/*/src` 中**零显式消费者**（只以 `defineFacet({setup(env)})` 的结构化参数出现） |
| 3 | 3 | **依赖图校验**（重复 facet ID / 服务被两方 provide / host 与 facet 争抢 / mode 不匹配 / 找不到 provider / 外部源歧义 / 依赖环） | `src/facets/host.ts:808-880`（含 `topologicalOrder :858-880`）、`:606-627`（外部源歧义） | pi-java 无 |
| 4 | 3 | **拓扑激活 + 逆序销毁 + 错误聚合** | `src/facets/host.ts:412`（按 `#activationOrder`，provider 先于 consumer）、`:753-793`（逆序、幂等、单错抛原错/多错 `AggregateError`）、`:125-142`、`:414-420`（启动失败走 `#terminate()` 全量回收） | pi-java 无 |
| 5 | 2 | **保形 reload（facade 身份不变）** | `src/facets/host.ts:423-511`、`:891-902`（`sameFacetShape`）、`src/api.ts:24`；`PLANNING.md:227` | 要求 ID 集合与 requires/provides 形状不变；候选先 setup+校验再激活，再 `replace` 单例（**不 withdraw**）；cutover 后失败 ⇒ 终止宿主，**无回滚** |
| 6 | 3 | **FacetLoader 四件套** | `src/api.ts:29-64`（`createStaticFacetLoader`/`combineFacetLoaders`）、`src/facets/loader.ts:3-6`（`disposeLoadedFacets`）、`src/node/bundle-loader.ts:84-131`（`createFacetBundleArtifactLoader`）、`:134-169`（`createFacetBundleLoader`） | `combineFacetLoaders` 顺序加载、逆序销毁、后续失败时清理已加载；artifact loader 每次 load 物化到**新临时目录** |
| 7 | 3 | **Service token（局部性 + 保留前缀）** | `src/api.ts:70-82` | `defineService(id,{local:true})` ⇒ 进程本地、永不出现在远程 catalogue；空 ID 与 `$chord.` 前缀被拒（`api.ts:78-80`）；控制面 ID 常量 `src/services/wire.ts:39` |
| 8 | 3 | **singleton / keyed 双模式 + keyed 代际** | `src/types.ts:60`（`ServiceMode`）、`src/types.ts:129-132`（`{key, generation}`）、`src/facets/host.ts:145-217`、`src/services/instances.ts:18-143` | 同 key 关闭后重开即**新 generation**；`InstanceDirectory` 管实例生命周期与每个观察者的可取消任务（`instances.ts:78-138`） |
| 9 | 3 | **`RemoteServiceProvider` 全操作面** | `src/services/provider.ts:77-325` | `catalogue` / `provide` / `withdraw`（保留订阅与 facade，发 `unavailable`）/ `validateReplacement` / `replace`（不中断 facade，发 `replaced`）/ `spawn`（发 `spawned`）/ `invoke`（`Reflect.apply` 并把 `Context` 追加为**尾参** `:233`）/ `subscribe`（先 `#publishPending` 再取快照再入订阅者 ⇒ **竞态无缝隙** `:255-257`）/ `dispose` |
| 10 | 1 | **远程成员分类（方法 vs 复制状态）** | `src/services/provider.ts:544-570`、`:376-383`（`#assertSingletonShape`） | 只接受 **own data property**：函数 ⇒ `method`；带 replicated-state internals 的对象 ⇒ `state`；其余（accessor / 普通字段 / 空成员表）**直接抛 `TypeError`**。替换必须保持成员名/种类表 |
| 11 | 3 | **稳定服务 facade（懒解析、跨替换不变）** | `src/services/handle.ts:9-113`（`ServiceSlot` + `ServiceView`/`ValueView` 双层 Proxy） | 成员懒解析、每次访问过 `assertAccess`；provider 被替换后 facade 仍指向新实现（`test/services.test.ts:262`「keeps singleton facades stable when their provider is replaced」） |
| 12 | 3 | **消费者端 binding（白名单 + rebind + 就绪屏障）** | `src/services/consumer.ts:425-634` | 未 allowlist 抛 `service_not_allowed`；`ready(context)` 循环等所有 singleton/keyed 完成初始快照（`:501-519`）；`rebind(bound, context)` 批量断开/重连（`:521-551`） |
| 13 | 3 | **控制面协议（`$chord.service`）** | `src/services/wire.ts:39-87`（三个控制成员 `catalogue`/`subscribe`/`unsubscribe`）、`src/services/provider.ts:502-538`（`createRemoteServiceEndpoint`，为**一个**消费者托管订阅建立/激活/清理） | 传输无关：chord **不规定** framing / routing / transport / 外层 envelope |
| 14 | 3 | **复制状态：源 + 副本 + 交付语义** | `src/services/state.ts:6-57`（`MutableReplicatedStateImpl`）、`:60-129`（`ReplicatedStateReplica`）、`:132`（`serviceDeliveryContext`）、`src/types.ts:38-48` | 源：`track()` 代理 + `publish(context)`，每次发布 `sequence += 1` 并 flush 一个 `Op[]` 批次；副本：冷启动 `value === undefined`，`hydrate` 必须 base 批次、`update` 序列必须 `+1`，**缺口 ⇒ `clear()` 并抛错**（`state.ts:93-105`）；交付带 `{kind:"hydrate"\|"update", sequence}` |
| 15 | 3 | **线上快照/更新语法 + 严格校验** | `src/services/wire.ts:11-37`、`:113-203`、`:212-222`（`assertKeys`） | 5 种 `WireServiceProviderUpdate`：`state` / `unavailable` / `replaced` / `spawned` / `closed`；快照 = serviceId + mode + instances[]（成员为 `method` 或 `state{sequence, ops}`）。`parseServiceX`（解码词汇，`assertValidOp`）与 `parseWireServiceX`（线上词汇，`assertValidWireOp`）成对 |
| 16 | 1 | **每订阅独立的路径字典编解码** | `src/services/state-codec.ts:60-118`（`createServiceStateEncoder`/`createServiceStateDecoder`，按 `(instance, member)` 建 `StateCodecRegistry`） | `unavailable`/`replaced` **重置字典**，`closed` 摘掉该实例的 codec ⇒ 「每个 client/state 流各持一份 encoder 状态」 |
| 17 | 3 | **Delta 词汇 + track/flush 语义** | `src/delta/index.ts:30-36`（`Op` 六元）、`:48-60`（`WireOp`）、`:435-748`、`:205-227`、`:536-588`；`src/delta/README.md` | `Op` 六元：`r`（唯一整值替换，**只能出现在 index 0**）/`s`/`d`/`a`（字符串追加）/`t`（前截断）/`p`（数组 splice）。`WireOp` 加两种压缩：`["#", id, path]` 在路径**第二次**使用时定义 + 数字 `PathRef` 引用 + 省略路径的短元组（靠 arity 消歧）。`track()` 提供 `state`/`target`/`flush()`/`rebase()`/`discard()`/`dirty` |
| 18 | 3 | **apply / applyImmutable + 路径安全（原型污染防护）** | `src/delta/index.ts:938-1011`（`apply` 原地）、`:1014-1026`、`:1028-1064`（`copyContainers`）、`:764-936` | `applyImmutable` 只复制变化路径上的容器、共享未变子树。防护：`RESERVED_SEGMENTS = {__proto__, constructor, prototype}`、`UnsafePathError`、`PathError`、写用 `Object.defineProperty` 而非赋值（`:982-984`）、数组禁 `delete`/稀疏（`:670-673`/`:642-648`） |
| 19 | 3 | **基础设施三件套：Context / 严格 JSON / 错误码** | `src/context/index.ts:55-116`（`BACKGROUND_CONTEXT`/`TODO_CONTEXT`/`createContextKey`/`withContextValue`/`withAbortSignal`（`AbortSignal.any` 组合）/`withoutAbortSignal`/`withCancel`/`awaitWithContext`）、`src/json.ts:4-50`、`src/types.ts:26-36`、`src/services/errors.ts:1-26` | `awaitWithContext` **只 reject 该等待者，不取消底层 promise**。`isJsonValue` 严格校验（有限数、无环、深度 512、纯对象原型、仅 own enumerable data 属性）；`JsonRepresentation<T>` 编译期派生 wire-safe 类型。8 个稳定错误码：`service_not_allowed`/`not_found`/`mode_mismatch`/`member_not_found`/`member_mismatch`/`instance_not_found`/`stale_instance`/`invalid_value` |
| 20 | 3 | **Node 打包与 bundle 加载** | `src/node/bundle.ts:39-79`、`:176-192`、`src/node/package.ts:30-50`、`:111-154`、`src/node/manifest.ts:1-39`、`src/node/bundle-loader.ts:44-169`、`:192-201`、`src/services/loopback.ts:5-17`、`src/bundler.ts:6/9` | `bundleFacets`：esbuild 逐 entry 打成**独立内容寻址 CJS**（`entryNames: facet-<hash>-[hash]`，`@earendil-works/chord` 强制 external，写临时目录后 `rename` **原子替换**），产出 `chord-facets.json`（format `chord.facet-bundle` **v2** + SHA-256 integrity + `externalImports`）。`bundleFacetPackage` 从 package.json 读 name/version/peerDependencies 与 `chord.facets`/`chord.external`/`chord.sourceMap`，支持 `false` 关闭某约定 entry。加载侧：manifest 校验 + 完整性校验 + `node:vm` `compileFunction` 执行（**不进 Node 模块缓存**），受限 `require` 只放行声明的 external。`createLoopbackServiceTransport` 让 provider 与 binding 同宿主直连、语义不变 |
| 21 | **0** | **对称 RPC peer** | `PLANNING.md:3`（"Symmetric RPC and structural generation replacement **remain planned**"） | pi 自己也**未实现** ⇒ **不算缺口** |

**行数**：21 行 —— 除 #21（pi 也未实现）外，**#1–#20 全部 20 条缺失**。
**权重**：**Σ = 55**（1 行权重 0 已排除：`#21` 对称 RPC peer ⇒ 计入分母 20 行）。

### chord 的**消费方**（pi 侧）

**依赖声明**：`packages/{agent,client,coding-agent,protocol,server}/package.json` 均声明 `"@earendil-works/chord": "^0.85.1"`（分别 `:58`/`:50`/`:52`/`:42`/`:50`）。
**⚠️ `packages/tui` 完全不消费 chord** —— `grep -rn "chord\|Chord" packages/tui` **0 命中**（其依赖只有 `get-east-asian-width` + `marked`）。

**两档**，差别很大：

**① 通用原语档（真·生产负载）** —— 只吃 `JsonValue`/`isJsonValue`/`Context`/`BACKGROUND_CONTEXT`/`JsonRepresentation`：
- `packages/agent/src/harness/agent-harness.ts:1`（`JsonRepresentation`）
- `packages/agent/src/harness/context.ts:1`、`:11`（`Context`/`ContextKey`）
- `packages/agent/src/harness/session/types.ts:1`、`:10`（`JsonValue` 并 re-export）
- `packages/protocol/src/codec.ts:1`（`isJsonValue`，**真实运行时依赖**）、`src/protocol.ts:1`（仅 type）
- `packages/server/src/server.ts:1-10`、`src/connection.ts:1`、`src/session-router.ts:2`、`src/types.ts:1`、`src/errors.ts:1`、`src/testing/client.ts:3`、`src/testing/host.ts:1`
- `packages/client/src/client.ts:1-18`（**一次 import 15 个符号**）、`src/types.ts:1`

**② 完整运行时档（全在 `experimental/`，共 9192 行）** —— 吃 facet / service / replicated state / bundler / node loader：
- `experimental/services/models-provider.ts:1/18/130`（`defineFacet`/`Facet`/`MutableReplicatedState` + `env.replicatedState`）
- `experimental/services/worker.ts:48-78`（**facet 宿主**：`createStaticFacetLoader([...]).load()` → `createFacetHost({facets:[...]})`）、`:118-126`（每接入一个客户端分一个 scoped `createRemoteServiceEndpoint(provider)`）
- `experimental/services/{agent-controller,agent-controller-provider,connection,server,sessions,plugins,slash-commands*,transcript*,presentation-ui}.ts`
- `experimental/plugins/bundled.ts:1/7`（`combineFacetLoaders`/`FacetLoader` + `@earendil-works/chord/node`）
- `experimental/plugins/package.ts:4/9`（`bundleFacetPackage` + `chord/node`）
- `experimental/session-worker-manager.ts:4-13`（`decodeServiceControlCall`/`parseServiceProviderUpdate`/`ServiceCall`/`ServiceProviderUpdate`）、`:206-236`（`#attachClient` 造 `RoutedSessionAttachment` 代理 `ServiceCall`）
- `experimental/session-worker.ts:459-462`（出站更新先过 chord 边界：`isJsonValue` → `parseServiceProviderUpdate`）
- `experimental/client-runtime.ts:2/52/71-79`、`client-tui.ts:10-11`、`client-tui-chat.ts:20`
- `experimental/server.ts:5-6`（`Context` + `FacetBundleArtifact`）、`:135-140`（`createRemoteServiceEndpoint(provider).invoke(...)`）
- `packages/client/src/client.ts:447-448`（`createClientServiceTransport`，把路由客户端目标适配成 chord `RemoteServiceTransport`）

**③ `/share` 与 chord 无关**：`coding-agent/src/core/slash-commands.ts:27` 定义，`interactive-mode.ts:3002-3003` 分派，实现 `modes/interactive/session-share.ts:45-46`（**零 chord import**）。chord 侧的 `SlashCommands` 服务只注册 `reload`/`model`/`thinking`/`compact`（`services/slash-commands-provider.ts:104/123/173/206`），**没有 share**。

**④ 5 个 replicated state 暴露单元**（都在 `experimental/services/`）：

| 服务 | 声明 | 单元 |
|---|---|---|
| `Transcript`（`pi.transcript`） | `transcript.ts:12/15` | 主 lane 转录快照（docblock `:10` 自述「replicated through Chord's operation stream」） |
| `Models`（`pi.models`） | `models.ts:27` | 目录 + 配置 |
| `SessionDirectory` | `sessions.ts:23` | 会话列表 + revision |
| `ServerServiceSource` | `connection.ts:28` | 连接状态 |
| `SessionServiceSource` | `connection.ts:33` | attach/detach 状态 |

生产端创建点：`services/server.ts:37-40/44-49`、`services/connection.ts:110/125-129/186/189/206`、`models-provider.ts:18/130`、`transcript-provider.ts:22/97`；示例插件 `examples/plugins/pi-example-plugin/src/contract.ts:9` + `src/session.ts:8`。

### 多进程 / 多端架构（chord 支撑的那个大子系统）

pi 的 `packages/coding-agent/src/experimental/`（9192 行，顶层 18 个文件 + `mini/`13 + `plugins/`2 + `services/`13）里有一整套**三层多进程会话架构**，chord 是它的地基：

| 层 | 文件 | LOC | 角色 / 关键行 |
|---|---|---|---|
| Tier1 | `coordinator.ts` | 612 | 同时开 public + control 两个 socket（`:298-299`、`:319-322`）；public 连接被**字节管道**接到当前 server（`:450` `createConnection(currentServer.endpoint)`，`:456-458` 双向 pipe）；`:187` `ensureCoordinator` 按需 spawn |
| Tier2 | `experimental/server.ts` | 798 | `:521` `startServer`；`:610-617` 生成 `control-<id>.sock` / `server-<id>-<nonce>.sock`，`ensureCoordinator` 后 `new CoordinatorConnection(...)` + `new SessionWorkerManager(...)` |
| Tier3 | `session-worker.ts` | 884 | `:874` `runSessionWorkerProcess(args)`；`:350` `createConnection(address)` 连回 coordinator 的**控制 socket**；`:355` `createJsonLineMessages(socket)` 换行分隔 JSON；`:357` 首帧 `register_peer`；env 契约 `:68-71`（`PI_SESSION_WORKER_CONTROL_ADDRESS/TOKEN/SESSION_KEY_BASE64/PEER_ID`） |
| 管理 | `session-worker-manager.ts` | 859 | `:97` docblock「owned by one replaceable server process」；`:476-482` `spawnInternalProcess("session-worker", ...)`；`:180-190` **一 session 一 worker** |
| 进程 | `process.ts` | 105 | `:8` `type InternalProcessRole = "coordinator" \| "server" \| "session-worker"`；`:41` `spawnInternalProcess`；`:55-70` `spawn(process.execPath, [...], {detached:true, stdio:"ignore", windowsHide:true})` ⇒ **stdio 被显式忽略**，传输只能是 socket；`:96` worker 脚本路径 |
| 中继 | `radius-relay.ts` | 724 | `:103` Radius WebSocket 中继；子协议 `pi-session-relay.host.v1`/`.client.v1`（`:11-12`） |
| 传输 | `client/src/unix.ts` | — | `:16` `.sock`；`:34` `discoverUnixServers()`；**`:35` Windows 直接抛错** ⚠️ |
| 平行实验 | `experimental/mini/`（13 文件） | — | **不用 chord**：`mini/worker/run.ts:1-7` docblock 自述「speaks JSON over its **stdio pipes**」，自带 `defineService` shim 于 `mini/shared/protocol.ts:56` |

**pi-java 的对应物**：`pi-java-server`（563 行，`PiServer.java:1`/`PiServerService.java:13`/`PiSessionRuntime.java:1`/`UnixSocketListener.java:1`/`SessionLockedException.java:1`）
—— 一个**单进程、JVM 内、Unix socket 上的会话控制面 + 独占租约 + 快照订阅**（`PiServerService.java:9-10` 自述「会话控制面 + 独占租约 + 快照订阅，没有 entry 级存储方法」）。
**它没有子进程 session worker、没有 coordinator、没有 facet/service/replicated-state。**

| # | 权重 | 缺失能力 | pi 证据 | java |
|---|---|---|---|---|
| C-1 | 3 | 会话 worker 子进程模型（一 session 一 worker，走控制 socket 换行 JSON） | `session-worker.ts:350/355/357/874`、`process.ts:8/24/96`、`session-worker-manager.ts:180-190/476-482` | ❌（`pi-java-server` 全在进程内） |
| C-2 | 3 | 协调进程 + 三层进程拓扑（coordinator / server / session-worker）+ 字节管道转发 | `coordinator.ts:298-299/319-322/450/456-458`、`server.ts:521/610-617` | ❌ |
| C-3 | 2 | facet 包（第三方插件包）安装/打包/加载 | `experimental/plugins/package.ts:4/9`、`chord/src/node/package.ts:30`、`chord/src/node/bundle-loader.ts:192-201` | ❌ |
| C-4 | 3 | 服务目录 / 订阅的跨进程 RPC + 客户端传输适配 | `experimental/services/connection.ts:60-66/353`、`session-router.ts:2`、`client/src/client.ts:447-448` | ❌ |

**行数**：4 行 —— 全部缺失。**权重**：**Σ = 11**（无权重 0 行 ⇒ 计入分母 4 行）。

---

## 整块缺失：pi 111 提交新增的三块（`durable` / `pico3` / `micro`）

**pi-java 有无对应物：零。** 证据：`grep -rli "pico\|durable\|micro-session" --include=*.java .` 零命中；`packages/` 12 个包中 pi-java 只对应 11 个（缺 `durable`）。
**三者均无已发布消费者**（`durable` 0、`micro` 0、`pico3` 仅被 `micro` 用）⇒ 是 pi 的**前瞻性建设**。权重按「若接上有多重要」计（与 chord 同口径）。

### `durable`（Pico5 存储契约 + MemoryStorage）—— `packages/durable`

`docs/pico-v5.md`（**2031 行规范**）＋ `pico-v5-chord-usage.md`(384) ＋ `pico-v5-handoff.md`(341) ＋ `chord-delta-findings.md`(358)。
核心不变量（`docs/pico-v5.md:34-52`）：「一次 Session 提交跨 record 与 document **原子**」「可见进度**全部持久**，无易失发布路径」「外部副作用不在 Session 变更事务内运行」「条目与 ID 提交后永不复用」。

| # | 权重 | 能力 | pi 证据（`3390bd936`） | java | 判定 |
|---|---|---|---|---|---|
| D-1 | 3 | **原子批量提交**（跨 conversation/entry/task/input 四类记录一个事务） | `src/types.ts:305-306`（`commit(writes, ctx)`）、`:292-297`（`StorageWrite` 四变体） | ❌ | **缺失** |
| D-2 | 2 | **全局记录 ID 铸造** `mintId()` | `src/types.ts:309` | ❌ | **缺失** |
| D-3 | 2 | conversation 查询/扫描（fork 链） | `src/types.ts:312/315`（`conversation`/`scanConversations`） | ❌ | **缺失** |
| D-4 | 3 | **entry 查询/扫描 + `findLatestHeadMarker`**（可见范围下界） | `src/types.ts:318/322/329` | ❌ | **缺失** |
| D-5 | 2 | task 记录（持久状态机 `TaskState<S,R>`）查询/扫描 | `src/types.ts:164/205/334/337` | ❌ | **缺失** |
| D-6 | 2 | input 记录 + `inputByRequest`（会话域 host 去重键） | `src/types.ts:344/347` | ❌ | **缺失** |
| D-7 | 3 | **document**（chord ops 表示的可变 JSON + 偶发完整 base） | `docs/pico-v5.md:44`；`src/types.ts:219/258`（`DocumentRecord`/`DocumentCreate`） | ❌ | **缺失** |
| D-8 | 2 | `MemoryStorage` 实现（detached in-memory） | `src/memory-storage.ts:112`（372 行） | ❌ | **缺失** |

**Σ权重 = 19**（无权重 0 行）。

### `pico3`（Pico3 内核 + 自带两个后端）—— `packages/agent/src/harness/pico3`

| # | 权重 | 能力 | pi 证据（`3390bd936`） | java | 判定 |
|---|---|---|---|---|---|
| P-1 | 3 | 会话/条目/任务契约 | `session.ts`（**1497**）、`types.ts`（**1038**） | ❌ | **缺失** |
| P-2 | 3 | **`JsonlStorage`**（目录 + `main.jsonl` + sticky/task sidecars + fsync + 撕裂尾截断） | `jsonl.ts:58`（`extends MemoryStorage`）、`:73` `open`、`:195` `commit`、`:281` `truncate`、`:334-338`（尾截断） | ❌ | **缺失** |
| P-3 | 3 | **`MemoryStorage`**（pico3 版，278 行） | `memory.ts:25` | ❌ | **缺失** |
| P-4 | 2 | `Harness` + kinds（generation/tool/collapse/job/post-tools/frames/plugin/entries） | `harness.ts`（**812**）、`kinds/generation.ts`（641）、`kinds/tool.ts`（479）、`kinds/collapse.ts`（274）、`kinds/job.ts`（187）、`kinds/post-tools.ts`（165） | ❌ | **缺失** |
| P-5 | 2 | 调度与背压（`scheduler.ts` 486 / `bounded.ts` 100） | `scheduler.ts`、`bounded.ts:1`（`Bounded`） | ❌ | **缺失** |
| P-6 | 3 | **chord 视图桥**（把会话视图经 chord 复制出去） | `chord.ts:1-178`（`attachChordView`/`createPicoConversationService`/`PicoHarnessService`/`ChordViewBridge`） | ❌ | **缺失** |
| P-7 | 2 | 系统段声明 | `system.ts:376`（`defineSystemSection`/`systemSections`/`SystemSection`） | ❌ | **缺失** |
| P-8 | 1 | `membrane.ts`（草稿不逃逸事务） | `membrane.ts`（123 行） | ❌ | **缺失** |

**Σ权重 = 19**（无权重 0 行）。

### `micro`（建在 Pico3 上的本地编码代理）—— `packages/coding-agent/src/experimental/micro`

`README.md:3-5` 自述「Unlike `mini`, it has **no server, worker, RPC, or client/session process split**. One process owns the model runtime, Pico harness, JSONL storage, and TUI」。

| # | 权重 | 能力 | pi 证据（`3390bd936`） | java | 判定 |
|---|---|---|---|---|---|
| M-1 | 2 | 会话目录布局 + `--continue` 取该 cwd 最新 | `sessions.ts:22`（`selectSession`）、`:15-17`（`cwdKey` sha256 前 24 位）、`README.md:18`（`~/.pi/agent/experimental/micro-sessions/<cwd-hash>/`） | ❌ | **缺失** |
| M-2 | 2 | **文件系统锁**（一 session 一进程，`retries: 0` 直接抛） | `sessions.ts:39-44`（`lockfile.lock(path, {realpath:false, retries:0})` ⇒ `Micro session is already open`） | ❌ | **缺失** |
| M-3 | 2 | Pico3 `JsonlStorage` 落盘（`main.jsonl` + sidecars） | `README.md:19-20`；`runtime.ts`（526）、`tui.ts`（567） | ❌ | **缺失** |

**Σ权重 = 6**（无权重 0 行）。

### 三块合计

**Σ权重 = 44**（durable 19 + pico3 19 + micro 6）；**Σ(w×系数) = 0.0**（**44 行全部缺失**）⇒ **加权完成度 0.0%**。

---

## 整块缺失：防漂移脚本

**pi-java 有无对应物**：**没有 `scripts/`、没有 `tools/`、没有 ArchUnit / PMD / japicmp / forbidden-apis**
（`grep -rn "archunit" --include=pom.xml .` 零命中）。
pi `scripts/` 共 **40 个文件 / 8325 行**（旧 40 / 8314，**+11**；**文件清单逐条 diff 为空、`check-*.mjs` 仍是 7 个**），其中 **8 个是真·漂移校验器**（+1 个 `--dry-run` 校验模式 +1 个 lockstep 断言），4 个是它们的测试，其余 26 个是构建/发布/剖析/分析/库/一次性复现。
**重测唯一改动**：`check-entry-graphs.mjs:24` 与 `local-release.mjs:13` 各加一行 `packages/durable` 工作区条目（为容纳新包扩白名单，**不是新门**）；`package.json:21` 的 `check` 链仍是 7 个检查器。

| # | 权重 | 脚本 | 校验什么 | java 有对应物 |
|---|---|---|---|---|
| 1 | 0 | `agent-treeshake-smoke-entry.ts` | 夹具（非检查器）：只 import `Agent`+`createModels`+`anthropicProvider` | ❌ |
| 2 | 0 | `auto-pi.sh` | 开发者 wrapper：`pi` 符号链接到本地 bundle | 🔶 近似 `run.cmd` |
| 3 | 0 | `browser-smoke-entry.ts` | 夹具：浏览器面导出全 import 一遍，供 bundler 检出 Node-only 泄漏 | ❌ |
| 4 | 0 | `build-binaries.sh` | 本地复刻跨 6 平台出包 | 🔶 近似 `dist.sh` |
| 5 | 0 | `build-coding-agent-bundle.mjs` | esbuild 单文件 bundle + 外部包白名单 | ❌（pi-java 用 shade/jlink） |
| **6** | **1** | **`check-browser-smoke.mjs`** | **漂移门**：浏览器面必须能打成 browser/esm；treeshake entry 不得含 `compat.ts`/`models.generated.ts`/`providers/all.ts`（`:66-75`）；只允许 1 个 provider catalog 且必须是 `anthropic.json`（`:87`）；5 个 AI SDK 只允许 `@anthropic-ai/sdk`（`:100-108`） | ❌ |
| **7** | **1** | **`check-entry-graphs.mjs`** | **漂移门**：按 `package.json#exports` 走值导入图，逐 entry 强制 `maxFiles` 预算与 `forbid` 路径（`BUDGETS :33-43`）；`export *` 桶文件回归即失败（`:136-139`） | ❌ |
| **8** | **1** | **`check-lockfile-commit.mjs`** | **漂移门（仅 pre-commit，不在 CI）**：`package-lock.json` 被 staged 即阻断，除非 `PI_ALLOW_LOCKFILE_CHANGE=1` 或改动只落在工作区元数据（`:92`） | ❌（根 `package-lock.json` 是 `{"packages":{}}` 空壳） |
| **9** | **2** | **`check-pinned-deps.mjs`** | **漂移门**：递归所有 `package.json`，外部依赖必须精确版本（`:5`）；内部包与 `workspace:`/`file:`/`git+` 豁免（`:29-31`） | 🔶 部分：Maven 版本本身即精确；集中式 `<properties>` 版本表 `pom.xml:42-64`。**但 `pi-java-web/src/main/frontend/package.json:11-17` 的 7 个依赖全用 `^`** |
| **10** | **2** | **`check-runtime-deps.mjs`** | **漂移门**：TS 程序级分析 —— 值导入/`require`/动态 `import()` 的裸模块名必须在 runtime deps 中声明（`:27`）；拒绝「被 tsconfig 排除却被 import 拉回」的文件（`:82`） | ❌ |
| 11 | 0 | `check-runtime-deps.test.mjs` | 10 号的自测 | ❌ |
| **12** | **0** | **`check-ts-relative-imports.mjs`** | **漂移门**：源码相对 import 不得以 `.js` 结尾 | ❌（Java import 无扩展名，**不适用** ⇒ 权重 0） |
| 13 | 0 | `coding-agent-consumer.mjs` | 打包 10 个发布包 → 仓库外隔离目录装 tarball → 跑真实 consumer 冒烟 | ❌ |
| 14 | 0 | `coding-agent-consumer.test.mjs` | 13 号单测 | ❌ |
| 15 | 0 | `cost.ts` | 本地分析：按会话目录汇总花费 | ❌ |
| 16 | 0 | `create-source-archive.sh` | 确定性源码归档 | ❌ |
| 17 | 0 | `diff-model-catalog.mjs` | 开发工具：HEAD vs worktree 的 model catalog diff | ❌ |
| 18 | 0 | `edit-tool-stats.mjs` | 本地分析：Edit 工具统计（834 行） | ❌ |
| **19** | **1** | **`generate-coding-agent-install-lock.mjs`** | **漂移门（`--check`）**：从根 lockfile 生成 `install-lock/`，`--check` 验证磁盘与重生成一致 | ❌ |
| **20** | **1** | **`generate-coding-agent-shrinkwrap.mjs`** | **漂移门（`--check`）**：同上，产物为 `npm-shrinkwrap.json` | ❌ |
| 21 | 0 | `generate-thinking-capabilities.mjs` | 从 catalog 生成 thinking 档位 JSON（只被 17 号调用） | ❌ |
| 22 | 0 | `local-release.mjs` | 本地发布演练 | ❌ |
| 23 | 0 | `package-workspaces.mjs` | 库：递归找 workspace 包目录 | ❌ |
| 24 | 0 | `profile-coding-agent-node.mjs` | 性能剖析：TUI/RPC 启动耗时 + CPU profile | ❌ |
| 25 | **1** | `publish-model-catalog.mjs` | **校验 + 发布**：`--dry-run` 校验（`REQUIRED_PROVIDERS`、`MINIMUM_MODEL_COUNT=500`，`:24-25`） | 🔶 部分：`CatalogPublisher.validate()`（**运行时**校验，非仓库漂移门） |
| 26 | 0 | `publish-release-announcement.mjs` | 发布：写 verified-release 标记 | ❌ |
| 27 | 0 | `publish-release-announcement.test.mjs` | 26 号单测 | ❌ |
| 28 | 0 | `publish.mjs` | 发布：`npm publish` 所有 public 包 | ❌ |
| 29 | 0 | `read-tool-stats.mjs` | 本地分析：Read 工具统计（505 行） | ❌ |
| 30 | 0 | `release-notes.mjs` | 从 CHANGELOG 抽 release notes | ❌ |
| 31 | 0 | `release-packages.mjs` | 库：列非 private 工作区包 | ❌ |
| 32 | 0 | `release.mjs` | 发布编排（步骤表 `:9-19`） | ❌ |
| 33 | 0 | `repro-5893-wsl-bash.mjs` | 一次性复现（issue #5893） | ❌ |
| 34 | 0 | `session-context-stats.mjs` | 本地分析：会话上下文占用 | ❌ |
| 35 | 0 | `session-transcripts.ts` | 本地分析：抽 transcript 切块 | ❌ |
| 36 | 0 | `stats.ts` | 本地分析：token/花费汇总 | ❌ |
| **37** | **2** | **`sync-versions.js`** | **校验 + 同步**：验证所有非 private 包版本 **lockstep**（`:29-37`，不一致 exit 1） | 🔶 部分：Maven 单一 parent 版本 `pom.xml:11` + `pi-java-bom` —— **结构性保证，无校验脚本** |
| 38 | 0 | `sync-versions.test.mjs` | 37 号单测 | ❌ |
| 39 | 0 | `tool-stats.ts` | 本地分析：HTML 工具统计报告 | ❌ |
| 40 | 0 | `update-source-imports-to-ts.sh` | 一次性 codemod：`.js` → `.ts` | ❌ |

**行数**：40 行。**权重**：**Σ = 12**（31 行权重 0 已排除：非漂移门的构建/发布/剖析/分析/库/夹具/测试/一次性脚本 + `#12` 语义不适用 ⇒ 计入分母 **9 行**：`#6`/`#7`/`#8`/`#9`/`#10`/`#19`/`#20`/`#25`/`#37`）。

### pi-java 实际存在的漂移预防机制（file:line）

1. **Checkstyle**（Maven `validate` 阶段，`failsOnError=true`）— `pom.xml:130-159`，规则集 `checkstyle.xml`（10 个 module），其中两条是**编码规范的机器化**：`LineLength max=120`（`checkstyle.xml:12`）、`FileLength max=500`（`checkstyle.xml:18-19`，对应 CLAUDE.md「文件 ≤ 500 行」）。
2. **SpotBugs**（`verify` 阶段，`effort=Max`/`threshold=Low`/`maxRank=15`）— `pom.xml:161-181` + `spotbugs-exclude.xml`。
3. **maven-enforcer** — `pom.xml:105-127`：`requireJavaVersion [25,26)` + `dependencyConvergence` + `banDuplicatePomDependencyVersions`。**pi-java 唯一形似「依赖一致性校验」的东西**。
4. **L5 跨语言差分一致性框架**（**pi-java 独有，pi 侧无对应物**）— `pi-java-agent-core/src/test/java/com/pijava/agent/harness/conformance/`（7 文件 1204 行）。`ConformanceTest.java:44-68` 对 S1–S14 逐剧本跑 pi-java、写 `conformance/java-out/S*.java.jsonl`，与 pi 侧录制真相 `conformance/pi-out/S*.pi.jsonl` **逐帧严格比对**，差异非空即 fail（`:58-64`）。pi 侧真相由 `conformance/pi/run.test.ts` + `vitest.conformance.config.ts` 在 pi 检出（tag `v0.85.1`）生成（`ConformanceTest.java:20-31` javadoc）。**这是一套比 pi 自己的任何 `check-*.mjs` 都更强的漂移检测器** —— 但它是**测试**，不是独立门脚本。
5. **同族后端分组矩阵** — `ConformanceGroup1/2/3Test` × `Memory`/`Jsonl`/`Sqlite` 三个 `*ConformanceBackend` = 9 个测试类，把「同一契约在三种后端表现一致」变成 CI 断言。
6. **CI 门本身** — `.github/workflows/ci.yml:35` 单步 `./mvnw clean verify --batch-mode`（ubuntu/windows/macos 三平台矩阵 `:21`）⇒ 上述 1–5 全是 CI 门。

**pi-java 缺的那几类**（pi 有、java 无，且**语义上适用**）：
- 入口图 / 打包体积预算（#7）
- 生成产物与磁盘一致性 `--check` 重生成比对（#19/#20）
- 版本 lockstep 断言（#37）
- 依赖精确版本钉（#9，`pi-java-web` 前端用 `^`）
- 源码级 runtime dep 声明校验（#10）
- lockfile staged 阻断（#8）

**语义上不适用的**（不算缺口）：#12（TS 相对 import 扩展名，Java 无扩展名）。

---

## 台账纠错

| 条目 | 台账说什么 | 实际 | 证据 |
|---|---|---|---|
| **A17 的测试归属** | 「`SqliteConformanceGroup1Test.concurrentLaneWritesSerializeWithDistinctSequences`」（`docs/32:189`），并给「第 147 行」 | **行号准确**（`createLane("right", null)` 确在 147 行）；但**方法本体不在 sqlite 模块** —— 它在 `pi-java-agent-core/src/test/java/com/pijava/agent/session/ConformanceGroup1Test.java:143`（抽象一致性套件），`pi-java-session-backend-sqlite/.../SqliteConformanceGroup1Test.java` **只有 11 行**、是绑定 SQLite 后端的子类。⇒ 登记无误，**归属可更精确**（复现时要在 agent-core 找方法体） | `ConformanceGroup1Test.java:143`；`SqliteConformanceGroup1Test.java:1-11` |
| **A17 的其余断言** | 「不是 A12」「与包⑫ 无因果」「该模块不依赖 coding-agent」「reactor 里先于 coding-agent」 | **全部成立**。`pi-java-session-backend-sqlite/pom.xml` 依赖只有 `pi-java-agent-core`/`sqlite-jdbc`/`slf4j-api`/`spotbugs-annotations`/`junit`/`assertj` | `pi-java-session-backend-sqlite/pom.xml:22-51` |
| **A17 的「待取证」** | 「1990 s 这个量级对应哪个超时（`busy_timeout`？租约 TTL？）」 | **本次补一条线索**：`SqliteSessionRepository.DEFAULT_TTL_MS = 30_000`（30 s）、心跳 10 s、`PRAGMA busy_timeout=5000`（5 s）。**三者都不等于 1990 s** ⇒ 1990 s 更可能是 **maven-surefire 的 fork 等待/整个 fork 超时**，不是 SQLite 层超时。仍**未定位**，不修 | `SqliteSessionRepository.java:37`（`DEFAULT_TTL_MS=30_000`）、`:316`（`busy_timeout=5000`）、`SqliteSessionSearch.java:98` |
| **E6「拆分超限文件（`SqliteSessionStorage`、`AgentHarness`）」** | 记为未结（`docs/32:297`） | **`SqliteSessionStorage` 部分已解决**：`docs/09b:117` 记的是当时的超限，`docs/09b §5`（2026-08-16）已「压缩至 498 行」，**现为 463 行**（已拆出 `storage/` 子包 10 个文件）。只剩 `AgentHarness`（502 行，= E4）。⇒ E6 应改为「仅 `AgentHarness`」或与 E4 合并 | `SqliteSessionStorage.java` 463 行；`docs/09b-phase4-persistence-review.md:117` 与 §5 |
| **E7「`appendEntry`/`appendRecord` 在 JSONL/Memory 两处重复」** | 记为判断项，保留（`docs/32:298`） | **条数少算**：实际是**三处**（JSONL/Memory/**SQLite**） | `JsonlSessionStorage.java:170/187`、`MemorySessionStorage.java:79/94`、`SqliteSessionStorage.java:264/289` |
| **H §9.3「`/import` 与 `/export`（JSONL）是占位符，未实现」**（`docs/32:412`） | 扫描标 CLOSED，台账判「应为 OPEN」 | **台账这一格错了 —— 应为 CLOSED**。`/export`（HTML/JSONL 双路）与 `/import` **已接线**：`MiscCommands.java:38`（`registry.register(simple("export", "Export session as HTML or JSONL", …))`）、`:61`（`simple("import", "Import session from JSONL", …)`） | `MiscCommands.java:38/61` |
| **H §9.3「根 entry 的 `parentId` 被省略而非输出 `"parentId":null`」**（`docs/32:415`） | 记为 OPEN | **已修（陈旧）**：`JsonlCodec.java:149-152` 显式 `node.putNull("parentId")`，注释直引 pi 的 `requireNullableId`。`docs/09b §5` 亦记「已修」 | `JsonlCodec.java:149-152` |
| **H §9.3「多进程读并发压测」**（`docs/32:417`） | 记为缺口 | **仍成立**：全仓无 `多进程`/`multiProcess`/`concurrentRead` 测试 | `grep -rln "多进程\|multiProcess\|concurrentRead" --include=*.java .` 零命中 |
| **H §9.3「JSONL 扫描式搜索后端（按需）」**（`docs/32:416`） | 记为缺口 | **口径需更正**：pi **没有任何搜索实现**（`packages/agent/src/search/index.ts:20` 只有接口，唯一引用是类型断言）。⇒ 这不是「pi 有 java 无」，而是 **pi-java 自研了 FTS5 搜索、还想再补一个 JSONL 扫描后端**。**属 java 侧自设目标，非 pi 对齐缺口** | `packages/agent/src/search/index.ts:20-27`；`test/harness/types.test.ts:384` |
| **⚠️ 新发现（未登记）：SQLite schema 分叉** | `docs/32` **零处**提到 | pi 7 张表 / java 11(+2) 张、**同名只有 3 个**、pi 的 `scalar_values`/`list_values`/`usage_ledger`/`branch_meta` 全缺、java 多出 9 张专用表 | 见上「SQLite schema 逐表对齐」；`grep -n "schema\|branch_meta\|scalar_values\|usage_ledger\|list_values" docs/32-open-items-register.md` 只命中 B8 无关行 |
| **⚠️ 新发现（未登记）：JSONL v4 头部双向不可读** | `docs/32` 零处 | pi 写 `{v:4, storageVersion:1, …}` 且 `codec.ts:35-46` 强校验 `v===4 && storageVersion>=1`；java 写 `{version:4, …}` 无 `storageVersion` ⇒ **pi 读不了 java 文件，java 读不了 pi 文件**。事务行 kind 交集只有 `entry` | pi `jsonl/types.ts:4-5`、`codec.ts:35-46`、`commit.ts:3-29`；java `JsonlV4Header.java:24-31`、`JsonlCodec.java:85-88/139-183` |
| **⚠️ 新发现（未登记）：9 处 javadoc 引用不存在的 pi 文件** | `docs/32` 零处 | `pi-java-session-backend-sqlite` 主源码 17 处「aligned with pi `X`」里，**9 处 X 不存在**：`branch-cache.ts`（`BranchCache.java:12`）、`branch-tips.ts`（`BranchTipRows.java:8`）、`storage/facts.ts`（`FactRows.java:8`）、`storage/lanes.ts`（`LaneRows.java:10`）、`storage/records.ts`（`RecordRows.java:16`）、`storage/sessions.ts`（`SessionRows.java:13`，pi 实为 `session/session-row.ts`）、`writer-leases.ts`/`storage/writer-leases.ts`（`WriterLeaseRows.java:8`、`WriterLease.java:8`）、`search-backend.ts`（`SqliteSessionSearch.java:13`）。**`WriterLease.java:8` 最严重**：pi 明文删掉了 writer_lease（`repo.test.ts:368-376`） | `find D:/workplaceForai/pi -iname "<name>"` 逐个验证，全部 ABSENT |
| **⚠️ 新发现（未登记）：chord 整块零对应、零文档** | `docs/32` 零处；`grep -rn "chord\|Chord" docs/*.md` 零命中 | pi 侧 **10879** 行（src 6503 + test 4376，**重测后**；旧 9375），是 **7 个** pi 包的真依赖 | 见「整块缺失：chord」 |
| **⚠️ 新发现（未登记）：pi 新增三块（`durable` / `pico3` / `micro`）零对应、零文档** | `docs/32` 零处；`grep -rli "pico\|durable" --include=*.java .` 零命中 | **重测新增**：`packages/durable`（757 src + 3114 行规范）、`packages/agent/src/harness/pico3`（7994 src）、`packages/coding-agent/src/experimental/micro`（1524）＝ **10275 行，全部无对应物**。pi 包数 **11 → 12** | 见「整块缺失：pi 111 提交新增的三块」 |
| **⚠️ 取证坑：pi 工作树中途回退（未登记）** | — | 重测期间 `D:/workplaceForai/pi` 的工作树**从 `3390bd936` 翻回 `71dca871b`**（`git rev-parse HEAD` 实测旧提交、`git status` 干净）⇒ 直接读工作树会**静默拿到旧状态**。本文件全部新状态读数改用 **`git show 3390bd936:<path>`**。建议后续复测一律走 `git show`，别信工作树 | `git rev-parse HEAD` 两次读数不一致 |
| **⚠️ 新发现（未登记）：防漂移脚本整块零对应** | `docs/32` 零处 | pi `scripts/` 40 文件 / **8325** 行（重测后；旧 8314，文件清单未变），其中 8 个真漂移门；pi-java 无 `scripts/`/`tools/`/ArchUnit | 见「整块缺失：防漂移脚本」 |
| **⚠️ 更正：`/share` 不是 chord 能力** | 任务线索把 `/share` 列在 chord 名下 | **不成立**：pi 的 `/share`（`slash-commands.ts:27` → `interactive-mode.ts:3002-3003` → `session-share.ts:45-46`）**零 chord import**；chord 侧的 `SlashCommands` 服务只注册 `reload`/`model`/`thinking`/`compact`（`services/slash-commands-provider.ts:104/123/173/206`）。⇒ `/share` 该挂 **coding-agent 的 slash 命令面**，不是 chord 面 | `session-share.ts:1-13`（import 列表无 chord）；`slash-commands-provider.ts:104/123/173/206` |
| **⚠️ 更正：pi 的 `experimental/mini/` 不用 chord** | 若把 `experimental/` 整块算作 chord 消费面会多算 | `mini/`（13 文件）**自带 RPC + stdio**：`mini/worker/run.ts:1-7` docblock 自述「speaks JSON over its **stdio pipes**」，`mini/shared/protocol.ts:56` 自带 `defineService` shim。⇒ 它是**平行实验**，与 chord 无关 | `mini/worker/run.ts:1-7`；`mini/shared/protocol.ts:56` |
| **⚠️ 环境风险（未登记）：pi 的跨进程传输在 Windows 直接抛** | `docs/32` 零处 | `client/src/unix.ts:35` Windows 抛错；`process.ts:55-70` spawn 带 `windowsHide:true`。pi-java 的开发环境是 Windows 11 ⇒ **该子系统在 Windows 上本就不可用**，做对齐时要考虑可移植性 | `client/src/unix.ts:34-35`；`process.ts:55-70` |

---

## 汇总（未加权）

> ⚠️ **已随 pi `3390bd936` 重算**（评测 17→20 行；整块缺失新增三块 44 行）。

按**能力单元**逐行统计（存储后端 35 行 + schema 18 行 + 评测 20 行 = **73 行** 参与对齐判定）：

| 档 | 存储后端 | SQLite schema | 评测 | 合计 |
|---|---|---|---|---|
| **对齐** | 16 | 1 | 4 | **21** |
| **缺失** | 12 | 15 | 12 | **39** |
| **存疑** | 7 | 2 | 4 | **13** |
| 合计 | 35 | 18 | 20 | **73** |

**未加权完成度（按行数）= 21 / 73 = 28.8%**；**按系数（对齐 1 / 存疑 0.5 / 缺失 0）= (21 + 13×0.5) / 73 = 27.5 / 73 = 37.7%**。

**整块缺失（不参与上面 73 行，单列）**：
| 子系统 | pi LOC | java LOC | 能力行数 |
|---|---|---|---|
| chord | 10879（src 6503 / test 4376；旧 9375） | **0** | 21 行（**20 缺失** + #21 对称 RPC 是 pi 自己也没实现的 planned 项） |
| 多进程会话架构（`coding-agent/src/experimental`） | 9192（未变） | 0（`pi-java-server` 563 行为单进程近似物） | 4 行 |
| **`durable` / `pico3` / `micro`（新增）** | **10275**（757 + 7994 + 1524） | **0** | **44 行（全部缺失）** |
| 防漂移脚本（`scripts/`） | 8325（40 文件） | **0** | 40 行（其中 8 行是真漂移门） |

**若把整块缺失并入总计（按行数）**：对齐 21 / 缺失 39+20+4+44+40=147 / 存疑 13 / 合计 **182** ⇒ **11.5%**。

**范围外但顺带量到的规模**：pi `packages/agent/src/harness/session` 7108 行 ↔ java `agent-core/.../agent/session` 4194 行（0.59×，**两侧均未变**）；
pi `packages/evals/src` 1446 ↔ java `pi-java-evals/src/main` 773（0.53×）。

---

## 加权汇总

> ⚠️ **本表已于 2026-09-20 随 pi `3390bd936` 重算**。旧值括注在「旧」列。判定口径 / 权重规则 / 范围裁决未改动；F9 已裁「chord 与多进程要做」⇒ 其权重照常计入（不再有「待裁」）。

**权重规则**：权重 = 用户可观察影响 × 频率（**3** = 每轮对话都走 / 默认路径；**2** = 每次会话走 / 常用命令；**1** = 低频 / 边缘 / 纯内部；**0** = 非目标，**排除出分母**）。
**完成系数**：对齐 = 1.0 ／ 存疑 = 0.5 ／ 缺失 = 0。

| 域 | Σ权重（旧） | Σ权重（新） | Σ(w×系数) | 加权完成度（旧→新） | 未加权完成度（按系数） |
|---|---:|---:|---:|---|---:|
| 存储后端（分母 33 行） | 66 | **66** | 36.0 | 54.5% → **54.5%**（未变） | 53.0%（17.5/33） |
| SQLite schema（分母 17 行） | 40 | **40** | 5.5 | 13.8% → **13.8%**（未变） | 11.8%（2/17） |
| 评测（分母 **17→20** 行） | 36 | **43** | 12.5 | 34.7% → **29.1%** | 30.0%（6/20） |
| **小计（本范围）** | 142 | **149** | **54.0** | 38.0% → **36.2%** | **36.4%（25.5/70）** |
| chord（分母 20 行） | 55 | **55** | 0.0 | 0.0% → **0.0%**（未变） | 0.0% |
| 多进程（分母 4 行） | 11 | **11** | 0.0 | 0.0% → **0.0%**（未变） | 0.0% |
| 防漂移脚本（分母 9 行） | 12 | **12** | 2.5 | 20.8% → **20.8%**（未变） | 16.7%（1.5/9） |
| **新增三子系统（分母 44 行）** | — | **44** | **0.0** | — → **0.0%** | 0.0% |
| **整块缺失小计** | 78 | **122** | **2.5** | 3.2% → **2.0%** | 1.9%（1.5/77） |

**模块合计（全部计入，含 chord＋多进程＋新增三块）**：Σ权重 **271** ／ Σ(w×c) **56.5** ／ **加权完成度 = 56.5/271 = 20.8%**（未加权 18.4% = 27/147）
　　（旧：220 / 56.5 / **25.7%** ⇒ **−4.9 pp**）
**模块合计（不含 chord＋多进程，但仍含新增三块与脚本）**：Σ权重 **205** ／ Σ(w×c) **56.5** ／ **加权完成度 = 56.5/205 = 27.6%**
**本范围（仅存储后端＋schema＋评测）**：Σ权重 **149** ／ Σ(w×c) **54.0** ／ **36.2%**

> **重测影响**：总分从 25.7% 掉到 **20.8%（−4.9 pp）**，**全部来自 pi 新增的 44 点权重**（`durable` 19 + `pico3` 19 + `micro` 6），它们**一分不得**；
> 本范围自身只降 1.8 pp（38.0% → 36.2%），且**降因是评测新增 3 行 7 点权重**（Docker 执行 / 变体计划 / TUI 审计），不是既有判定变差 ——
> **存储后端与 SQLite schema 的加权完成度一字未动**（pi 的 schema 与三后端逐字节未变）。

### 权重 0（排除出分母）的行 —— 共 35 行（未变）

| 域 | 行 | 为什么 0 |
|---|---|---|

| 域 | 行 | 为什么 0 |
|---|---|---|
| 存储后端 | `#23` 写者租约 `writer_lease` | **已裁决「pi 删掉的，pi-java 也删」**（pi `repo.test.ts:369` 断言该表不存在）⇒ 非目标 |
| 存储后端 | `#29` 心跳续租 | 同上（租约的附属机制，随租约一并删除） |
| SQLite schema | `#15` `writer_leases` | 同上 |
| chord | `#21` 对称 RPC peer | pi 自己也**未实现**（`PLANNING.md:3` "remain planned"）⇒ 非目标 |
| 防漂移脚本 | 30 行非漂移门（`#1`–`#5`、`#11`、`#13`–`#18`、`#21`–`#24`、`#26`–`#36`、`#38`–`#40`） | 构建 / 发布 / 剖析 / 本地分析 / 库 / 夹具 / 测试 / 一次性脚本，不是漂移校验器 |
| 防漂移脚本 | `#12` `check-ts-relative-imports.mjs` | 是**真漂移门**，但校验「TS 相对 import 不得以 `.js` 结尾」—— **Java 无扩展名 import，语义不适用** ⇒ 非目标 |

### 权重 3 的单元清单（每轮对话都走 / 默认路径）—— 共 50 条（旧 42 + 新增 8）

| 域 | # | 单元 | 判定 |
|---|---|---|---|
| 存储后端 | 7 | 追加 entry | 对齐 |
| 存储后端 | 8 | **批量提交 `commit(writes[])`** | **缺失** |
| 存储后端 | 9 | **按 id 批量读 `getEntries(ids[])`** | **缺失** |
| 存储后端 | 10 | 分支扫描 `scanBranch` | 对齐 |
| 存储后端 | 12 | entry 扫描 | 对齐 |
| 存储后端 | 13 | **标量值 `getValue`/`scanValues`** | **缺失** |
| 存储后端 | 14 | **列表值 `readList`/`appendList`/`deleteList`** | **缺失** |
| 存储后端 | 19 | **分支 tip 读取** | **存疑** |
| 存储后端 | 31 | **JSONL 事务行 kind 集合** | **缺失** |
| schema | 2 | **`entries`** | **缺失/分叉** |
| schema | 3 | **`branch_entries`** | **存疑** |
| schema | 4 | **`scalar_values`** | **缺失** |
| schema | 5 | **`list_values`** | **缺失** |
| schema | 7 | **`branch_meta`** | **缺失** |
| schema | 8 | `session_sequences` | 对齐 |
| schema | 10 | **`lanes`** | **缺失** |
| schema | 14 | **`branch_tips`** | **缺失** |
| schema | — | **触发器 `trg_entries_validate` / `trg_usage_ledger_validate`** | **缺失** |
| 评测 | 2 | **真 coding-agent harness** | **缺失** |
| 评测 | 9 | **eval 运行器 CLI** | **存疑** |
| 评测 | 14 | ChatApi 协议一致性套件 | 对齐 |
| 评测 | 16 | eval 框架本体 | 对齐 |
| **评测（新增）** | **18** | **Docker eval 执行** | **缺失** |
| chord | 1,2,3,4,6,7,8,9,11,12,13,14,15,17,18,19,20 | 17 条（Facet 装配 / FacetEnvironment / 图校验 / 拓扑激活 / Loader / token / 双模式 / Provider 面 / facade / binding / 控制面 / 复制状态 / wire 语法 / Delta / apply / 基础设施 / Node 打包） | **全缺失** |
| 多进程 | C-1 / C-2 / C-4 | worker 子进程 / 三层拓扑 / 跨进程服务 RPC | **全缺失** |
| **新增三块** | **D-1 / D-4 / D-7** | **durable**：原子批量提交 / entry 查询扫描+head marker / document | **全缺失** |
| **新增三块** | **P-1 / P-2 / P-3 / P-6** | **pico3**：会话契约 / `JsonlStorage` / `MemoryStorage` / chord 视图桥 | **全缺失** |

**权重 3 且非对齐 = 44 条**：本范围 **17 条**（存储 `#8`/`#9`/`#13`/`#14`/`#31` 缺失 + `#19` 存疑；schema `#2`/`#4`/`#5`/`#7`/`#10`/`#14`/`触发器` 缺失 + `#3` 存疑；评测 `#2` 缺失 + `#9` 存疑 + **新增 `#18` Docker 缺失** ⇒ 缺失 14 + 存疑 3）＋ chord 17 条 ＋ 多进程 3 条 ＋ **新增三块 7 条**（`durable` D-1/D-4/D-7、`pico3` P-1/P-2/P-3/P-6，**全缺失**）。

**「最该先修」= 本范围那 17 条**（旧 16 + 评测新增 `#18` Docker）—— 其中 **14 条是纯缺失**（拿 0 分），3 条是**存疑**（拿半分）。
**新增三块的 7 条权重 3** 属前瞻性建设（零消费者），不计入「最该先修」。

### 两条关键差距的加权影响

| 差距 | 涉及行（权重） | 现状 Σ(w×c) | 若修复后 Σ(w×c) | 对「全部计入」总分的影响 | 对「不含 chord＋多进程」总分的影响 |
|---|---|---:|---:|---|---|
| **SQLite schema 分叉** | `schema#1`(2) `#2`(3) `#4`(3) `#5`(3) `#6`(2) `#7`(3) `#10`(3) `#14`(3) `触发器`(3) = **Σ权重 25** | 0 | 25 | 56.5→81.5 ⇒ **20.8% → 30.1%（+9.3 pp）** | 56.5→81.5 /205 ⇒ **27.6% → 39.8%（+12.2 pp）** |
| **JSONL v4 双向不可读** | `存储#30`(2) `#31`(3) = **Σ权重 5** | 0 | 5 | 56.5→61.5 ⇒ **20.8% → 22.7%（+1.9 pp）** | 56.5→61.5 /205 ⇒ **27.6% → 30.0%（+2.4 pp）** |
| 两条同时修 | Σ权重 30 | 0 | 30 | 56.5→86.5 ⇒ **20.8% → 31.9%（+11.1 pp）** | 56.5→86.5 /205 ⇒ **27.6% → 42.2%（+14.6 pp）** |

> ⚠️ **重测后分母变了**（220 → 271：新增三块 44 点 + 评测 7 点）⇒ 同样两条差距的**百分点贡献被稀释**（旧 +11.3 pp / +2.3 pp → 新 **+9.3 pp / +1.9 pp**）。
> 但**绝对权重未变**（schema 分叉仍 25 点、JSONL 仍 5 点），且 **SQLite schema 分叉仍是单点最大拖累**（占全部分母 271 的 **9.2%**、占本范围 149 的 **16.8%**）。

**读法**：
- **SQLite schema 分叉是单点最大拖累** —— 权重 25，**一分不得**，一项吃掉 9.3 pp。
- **JSONL v4 不可读权重只有 5，但性价比最高** —— 权重 5 换 1.9 pp（每点权重 0.39 pp，是 schema 分叉的 **1.6 倍**效率），且它是**用户可见的硬故障**（换机/换工具后会话直接读不出来），不是形状偏差。
- **新增三块（`durable`/`pico3`/`micro`）是新增的第二大拖累** —— 44 点权重全缺失；但它们**零消费者**，属前瞻性建设，修复紧迫度低于上两条。

---

## 存疑清单（全部 13 条）

| # | 能力 | 存疑点 |
|---|---|---|
| S1 | 会话名 `getName`/`setName` | 载体不同：pi 通用标量值 `pi.session.name`（`values.ts:194`）vs java `facts` 表 `kind='name'`（`SqliteMutationReplay.java:37`）—— 行为等价性未逐一核 |
| S2 | entry 标签 `getLabel`/`setLabel` | 同上，pi `pi.entry.label`（`values.ts:195`）vs java `facts` `kind='label'`（`SqliteSessionStorage.java:350`） |
| S3 | 分支 tip 读取 | pi 有「权威（scalar value）vs 缓存（branch_meta）」二分（`values.ts:158` + `branch-entries.ts:67`），java 只有一张 `branch_tips` 表 ⇒ 一致性语义未核 |
| S4 | 分支创建 / 移动 | pi 的 `createBranch` 带 `base_branch_id`/`base_seq` 分段（`branch-entries.ts:85/92`），java 的 `createLane`/`moveLane` 无 base 概念 ⇒ 压缩边界行为可能不等价 |
| S5 | 会话日志 `getLog` | java 独有构造（`SessionStorage.java:67`），pi 无对应 API ⇒ 是否必要未核 |
| S6 | JSONL 文件名编码 | pi `encodeURIComponent(id)` vs java 裸 id（`JsonlSessionRepository.java:250`）⇒ id 含特殊字符时路径分叉 |
| S7 | 内存后端进程内注册表 | java 独有（`InMemorySessionRepository.java:24`），pi 无对应物（A12 已就「跨项目扫描」结案，但该构造本身是否该保留未裁） |
| S8 | `session_stats` 摊平 | java 用 5 个 REAL 列（`StatsRows.java:17`）代替 pi 的 `usage_payload` JSON 串 ⇒ **可能丢 `cacheWrite1h`/`reasoning` 分量**（pi 的 `addUsage` 显式保留，`session-stats.ts:6-13`） |
| S9 | `branch_entries` 列差异 | java 多 `custom_type` 列 + 多一个索引 ⇒ 行为等价性未核 |
| S10 | eval 运行器脚本 | pi 的 `run-evals.mjs` 落 `.eval/<ISO>_<uuid>/` 产物目录（`:12-16`），java 的 `EvalRunner` 纯内存 ⇒ 可复现性/归档能力不等 |
| S11 | 扩展作者 eval | pi 让模型**真写** `.pi/extensions/hello.ts` 再判（`extensions.eval.ts:16-34`）；java `ExtensionLifecycleTest.java:29` 只验装配可见性 |
| S12 | provider 集成 eval | pi 起本地 HTTP mock server 走 capability 发现 + NDJSON 流（`providers.eval.ts:1-70`）；java 是目录级检查 + 真 provider ping |
| S13 | smoke eval 断言强度 | pi 断 5 项（`output`/`errors`/`usage.provider`/`usage.model`/`totalTokens>0`，`smoke.eval.ts:10-14`）；java 只断收到 `StreamDone`（`ProviderSmokeTest.java:39`） |

> ⚠️ **原稿误列 15 条**：S14（`list` 无 `onProgress`）与 S15（`commit(writes[])` 的崩溃语义）在能力表里的判定是**缺失**、不是存疑 ⇒ 已删除，内容并入「缺失清单」的 `M1`/`M2`。故本清单实为 **13 条**（= 存储后端 7 + schema 2 + 评测 4）。

---

## 缺失清单（全部 39 条 + 整块 109 条）

### A. 存储后端（12 条）

| # | 能力 | pi 证据 |
|---|---|---|
| M1 | `list` 的 `onProgress` 进度回调 | `coding-agent/src/core/session-manager.ts:814/1670/1685` |
| M2 | 批量提交 `commit(writes[])`（单事务原子性） | `session/types.ts:461`；sqlite `storage.ts:67/164` |
| M3 | 按 id 批量读 `getEntries(ids[])` | `session/types.ts:462`；sqlite `storage.ts:77` |
| M4 | 分支结构扫描 `scanBranchStructure` | `session/types.ts:464`；sqlite `branch-entries.ts:112` |
| M5 | 标量值 `getValue`/`scanValues` | `session/types.ts:466-467`；sqlite `session/values.ts:72/80` |
| M6 | 列表值 `readList`/`appendList`/`deleteList` | `session/types.ts:468`；sqlite `session/values.ts:121` |
| M7 | usage ledger `scanUsage`（逐条用量台账） | `session/types.ts:469`；sqlite `session/usage-ledger.ts:66` |
| M8 | lane 记录 API（pi 侧本就无此构造；java 的 `records`/`lanes` 是独有） | `session/commit.ts:3-29`（pi 的 `Write` 只有 4 个 kind） |
| M9 | JSONL v3 旧格式读取 | `jsonl/codec.ts:18-33`；`jsonl/legacy-v3.ts`（700 行） |
| M10 | JSONL 头部线格式兼容（`v` + `storageVersion`） | `jsonl/types.ts:4-5`；`jsonl/codec.ts:35-46` |
| M11 | JSONL 事务行 kind 兼容（`usage`/`value`/`list`） | `session/commit.ts:3-29` |
| M12 | JSONL `nextSeq` 高水位字段 | `jsonl/types.ts:16` |

### B. SQLite schema（15 条）

| # | 缺失项 | pi 证据 |
|---|---|---|
| M13 | `scalar_values` 表（通用值存储，承载 13 个命名空间含整个 operation 状态机） | `001_initial.sql:35-42`；`session/values.ts:158-195` |
| M14 | `list_values` 表（`pi.pending.assistant_frame`） | `001_initial.sql:44-51`；`values.ts:183` |
| M15 | `usage_ledger` 表（逐条用量台账 + 与 entries 共享 id 命名空间） | `001_initial.sql:53-64`；`session/usage-ledger.ts:14` |
| M16 | `branch_meta` 表（含 `base_branch_id`/`base_seq` 分支分段） | `001_initial.sql:111-119`；`branch-entries.ts:97-111` |
| M17 | `sessions.storage_version` 列（版本闸：`:57-61` 新版/旧版双向报错） | `001_initial.sql:13`；`session-row.ts:57-61` |
| M18 | `sessions.message_count` / `usage_payload` / `next_seq` 列（java 拆成 `session_stats`/`session_sequences` 两表） | `001_initial.sql:15-17` |
| M19 | `entries.custom_type` 列 | `001_initial.sql:26`；`session/entries.ts` |
| M20 | `created_at` / `timestamp` 的 `INTEGER` 类型（java 用 `TEXT`） | `001_initial.sql:11/27` |
| M21 | 触发器 `trg_entries_validate`（父 entry 必须已存在） | `001_initial.sql:69-82` |
| M22 | 触发器 `trg_usage_ledger_validate`（entry/usage id 共享命名空间） | `001_initial.sql:84-91` |
| M23 | `sessions.cwd` 在 pi 侧不存在（java 独有列） | `session-row.ts:57-69`（返回的 metadata 无 cwd） |
| M24 | pi 无 `migrations` 追踪表（`migrations.ts:5-8` 只 exec 一个文件） | `sqlite/migrations.ts:5-8` |
| M34 | `sessions` 表列集分叉（8 列 vs 5 列，见 schema 表 `#1`） | `001_initial.sql:9-18` |
| M35 | `entries` 表列集分叉（缺 `custom_type`、时间类型 `INTEGER` vs `TEXT`，见 `#2`） | `001_initial.sql:20-30` |
| M36 | `branch_tips` 缺 `base_branch_id`/`base_seq` ⇒ 无分支分段（见 `#14`） | `branch-entries.ts:97-111` |

### C. 评测（12 条）

| # | 缺失项 | pi 证据（`3390bd936`） |
|---|---|---|
| M25 | 真 coding-agent harness（临时目录真会话 + transcript/usage 回读） | `evals/src/harness.ts:471-523` |
| M26 | LLM-as-judge 评分（`createJudge` + `StructuredOutputJudge`/`ToolCallJudge` + `autoevals`） | `evals/evals/tui.docs.eval.ts:12-13`；`extensions.docs.eval.ts:2` |
| M27 | baseline vs candidate A/B 对照表 + 变体计划 | `evals/src/report.ts:36-75`；`src/plan.ts:1/39` |
| M28 | 对照报告汇总（paired metric / correctness lift） | `evals/src/report.ts:359/436` |
| M29 | reporter 落产物（`session.jsonl`） | `evals/src/report.ts:121-136` |
| M30 | 会话 JSONL 快照作为 test attachment | `evals/src/report.ts:9` |
| M31 | 从 run 查工具调用（`toolCalls`） | `evals/evals/documentation-audit.eval.ts:6/57` |
| M32 | docs 审计 eval（逐页文档 vs 实现） | `evals/evals/documentation-audit.eval.ts:10-57` |
| M33 | 模型作者 eval（让模型往 models.json 写条目） | `evals/evals/models.docs.eval.ts` + `configured-runtime.ts` |
| **M37** | **Docker eval 执行**（构建镜像 / 容器内跑 / 隔离 auth） | `evals/src/docker.ts:38/67/126/137` + `evals/docker/*` |
| **M38** | **文档变体任务计划**（`without_docs`/`with_docs`） | `evals/src/plan.ts:1-59` |
| **M39** | **TUI 文档审计 eval**（264 行） | `evals/evals/tui.docs.eval.ts:12-13/192` |

### D. 整块缺失：chord（20 条能力 + 4 条多进程 + 44 条新增三块）

见上「整块缺失：chord」表 —— 能力 **#1–#20 全部缺失**（#21 对称 RPC 是 pi 自己也没实现的 planned 项，`PLANNING.md:3`）。
多进程 C-1 ~ C-4 全部缺失。
**新增（重测）**：`durable` D-1~D-8（Σ权重 19）、`pico3` P-1~P-8（Σ权重 19）、`micro` M-1~M-3（Σ权重 6）—— **44 条全部缺失**，见「整块缺失：pi 111 提交新增的三块」。

### E. 整块缺失：防漂移脚本（8 条真漂移门）

`check-browser-smoke.mjs`(#6) / `check-entry-graphs.mjs`(#7) / `check-lockfile-commit.mjs`(#8) /
`check-pinned-deps.mjs`(#9) / `check-runtime-deps.mjs`(#10) / `check-ts-relative-imports.mjs`(#12，**语义不适用**) /
`generate-coding-agent-install-lock.mjs`(#19) / `generate-coding-agent-shrinkwrap.mjs`(#20)；
外加 `sync-versions.js`(#37) 的 lockstep 断言 与 `publish-model-catalog.mjs`(#25) 的 `--dry-run` 校验模式。
其余 26 个是构建/发布/剖析/本地分析/库/一次性脚本，**不算能力缺口**。
