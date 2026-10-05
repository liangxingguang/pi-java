# 11 - pi 换锚裁决（`3390bd936` → `200387122`）

**状态：✅ 已裁决并执行（2026-10-04）** —— J1–J5 全按建议：锚点已换、六份地图全部重写，
项目完成度重测为 **52.59%**（见 `docs/06` 与 `docs/map/`；锚点事实在 `docs/map/ANCHOR.md`）。
后续包 12–14 均建立在新锚上。下文保留为裁决原始记录。

| | |
|---|---|
| **旧锚点** | pi `3390bd936`（2026-09-20） |
| **新锚点** | pi **`200387122`**（2026-10-04，`Add [Unreleased] section for next cycle`） |
| **pi-java 旧基准** | `34849a2`（2026-09-20） |
| **pi-java 新基准** | **`351d199`**（2026-10-04，包 10 收口） |
| **漂移** | **251 提交** · 583 个改动 `.ts`（全类 1,203） |

> **为什么必须换锚，而不是「刷新」**：pi 在 2026-10-01 用一个提交删掉了 **10.5 万行** —— 包括
> 两份正在被 `docs/06` 当参照物测的子系统。不先裁决删除面，重测出来的数字会是对着**已删除代码**判的。

---

## 1 结构断裂：`7fd478a2e`（2026-10-01）

```
7fd478a2e  feat(agent): remove the experimental harness from pi-agent-core
341 files changed, 12 insertions(+), 105456 deletions(-)
```

原文（提交信息逐字）：

> pi-agent-core now contains only Agent, the agent loop, the proxy stream, and their types. **Removed the harness,
> sessions and session storage, pico3, harness tools, compaction, skills, prompt templates, telemetry schemas,
> search types**, and the uuidv7 and pi-telemetry re-exports, plus the `./node`, `./harness/*` and
> `./experimental/pico3` subpath exports. **Durable sessions live in `@earendil-works/pi-durable`.**
> **Also removed `packages/session-backends`** and the experimental mini and micro coding-agent frontends.

### 1.1 删除面（逐条带证据）

| # | 被删对象 | 证据 | 规模 |
|---|---|---|---|
| D1 | **`packages/session-backends/` 整包** | `git diff --diff-filter=D … -- packages/session-backends/` 得 33 文件 | 77,640 B |
| D2 | **`packages/agent/src/harness/**`** | 该提交删除 `packages/agent/src/harness/` 下 **108 文件** | 主体 |
| D3 | `packages/agent/src/search/` ＋ `packages/agent/src/node.ts` | 同上，各 1 文件 | — |
| D4 | **`coding-agent/src/experimental/{micro,mini}/`** | 该提交删除 23 个 `coding-agent/src` 文件，逐条核对全在这两个目录 | — |
| D5 | `ai/src/images-models.ts` · `ai/src/image-models.generated.ts` · `ai/src/providers/openrouter-images.ts` | `--diff-filter=D -- packages/ai/src/*` | 3 文件 |
| D6 | `coding-agent/src/modes/interactive/components/daxnuts.ts`（彩蛋） | 同上 | 1 文件 |

`agent` 包 **1,267,980 B → 92,148 B（−93%）**；该包 `src/` 之后只剩
`agent-loop.ts` · `agent.ts` · `index.ts` · `proxy.ts` · `stream-fn.ts` · `types.ts`（`git ls-tree` 实测）。

### 1.2 搬迁面：harness → `durable`（**仍不可达**）

`git diff --diff-filter=R` 抓到实锤重命名：

```
R088  agent/src/harness/env/nodejs.ts        → durable/src/env/node.ts
R068  agent/src/harness/tools/edit.ts        → durable/src/tools/edit.ts
R082  agent/src/harness/tools/image.ts       → durable/src/tools/image.ts
R088  agent/src/harness/tools/path-utils.ts  → durable/src/tools/path-utils.ts
R064  agent/src/harness/tools/write.ts       → durable/src/tools/write.ts
R050  agent/src/harness/utils/truncate.ts    → durable/src/truncate.ts
```

`durable` 现在是 **61 个 src 文件**（`harness/` · `session/` · `storage/{jsonl,sqlite}/` · `tools/` · `documents.ts` ·
`entries.ts` · `tasks.ts`），即**整个 durable session 内核**。

⚠️ **但它在 R5 口径下仍不可达**：`pi-durable` 的**全部** import 者都在
`coding-agent/src/experimental/**`，而非 experimental 的主源码**一处都不 import**（`git grep` 实测，唯一命中是
`system-prompt.ts:147` 的人话字符串 "a coding agent harness"）⇒ **权重 0，不进分母**。

---

## 2 R1 应用：作废清单（权重 0，移出分母）

裁决依据＝既有 **R1「pi 删掉的，pi-java 也删」**（前例：旧 schemas 协议层 / `SessionHandle` / `writer_leases`）。

| # | 作废对象 | 理由 | 连带 |
|---|---|---|---|
| R1-1 | `session` 地图里全部以 `session-backends` 为参照的单元 | 参照物整包被删（D1） | 见 §3.1 —— **这笔账本来就该作废** |
| R1-2 | `agent-core` 地图里以 `agent/src/harness/**` 为锚的单元 | 参照物被删（D2） | ⚠️ 但**主流副本仍在**，见 §3.2 |
| R1-3 | `coding-agent` 地图里 `micro` / `mini` 的单元 | 参照物被删（D4） | 本就是实验前端，旧图已标「pi 全仓无人 import」 |
| R1-4 | `ai` 地图里 image-models registry 相关单元（若曾登记） | 参照物被删（D5），被 #9948 统一基础设施取代 | 需在重测时核 `ai` 侧新形状 |
| R1-5 | `pico3` / `durable drive` / `harness tools` / effect gate / `run_suspend` / `HarnessEvent` 等单元 | `durable` 不可达（§1.2） | 旧图已把它们记「缺失」并大幅压分 |

---

## 3 两条被这次换锚**证伪**的旧判定

### 3.1 ★ `docs/06 §3.4` 的「SQLite schema 分叉（+16.2pp）」自始即错

旧图把「SQLite schema 完全分叉」记为**全项目单点最大拖累**（Σ权重 25，占该范围 17.6%），并算出
「修好后 36.7% → 52.9%」。**实测**：

```
$ git grep -l "session-backends\|sqlite-node" 3390bd936 -- 'packages/*/src/**' 'packages/*/package.json'
3390bd936:packages/session-backends/sqlite-node/package.json     ← 只有它自己
```

⇒ **在旧锚点上 `session-backends` 就已经零消费者**，从来没上过 `bin pi` 这条线。那 25 点权重是给一个
**R5 不可达子系统**算的，本不该进分母。这不是这次漂移造成的 —— 是**旧口径漏了可达性检查**，
被换锚顺带暴露。

**主流 pi 的会话持久化一直是 JSONL**：`coding-agent/src/core/session-manager.ts` 直接
`appendFileSync(file, JSON.stringify(entry) + "\n")`，头是
`{type:"session", version: CURRENT_SESSION_VERSION, id, timestamp, cwd}`，其中
**`CURRENT_SESSION_VERSION = 3`**（`session-manager.ts:41`）。

⚠️ **且格式本身变了**：旧图据以判定「JSONL v4 双向不可读」的
`{v:4, storageVersion:1}` 是 **harness 的格式**，已随 D2 删除 ——
`git grep storageVersion 200387122 -- 'packages/*/src/**'` **零命中**。⇒ 「v4 硬故障」那条差距
**参照物已不存在**；真状况要按**新的主流格式（v3 头）**重新取证。

### 3.2 ⚠️ `agent-core` 的锚点必须从 `agent/src/harness/*` 改指 `coding-agent/src/core/*`

`ANCHOR.md` 早已记过：「`harness/compaction`、`harness/messages`、`harness/session`、`harness/tools` 在主流
`coding-agent/src/core/*` 有**独立副本** ⇒ 仍算主流，锚点应改指 `core/*`」——**当时未执行**。
这次 harness 被删，改指**从「应该做」变成「不做就没有参照物」**。

实测支持：主流 `coding-agent/src/core/` 里独立存在
`agent-session.ts` · `session-manager.ts` · `compaction/` · `tools/` · `messages.ts` · `system-prompt.ts`，
且 `core/system-prompt.ts:147` 那唯一一处 "harness" 字样**只是提示词里的人话**，不是依赖。

---

## 4 新增面：R5 复判

### 4.1 两个**新包** —— 都在主流路径上

| 包 | 规模 | 可达性证据 | 判定 |
|---|---|---|---|
| **`mcp`** | 18 个 src（`client` · `transports/{stdio,streamable-http,in-memory}` · `oauth/*` · `protocol/*`） | `coding-agent/package.json` 依赖 `@earendil-works/pi-mcp`；`coding-agent/src/config.ts` ＋ `src/extensions/mcp/*`（13 文件）import | **主流可达 ⇒ 进分母** |
| **`codemode`** | 10 个 src（`runtime/{host,worker,protocol,prelude-source}` · `wasm` · `declarations`） | 同上，依赖 `pi-codemode`；`src/extensions/codemode/*`（6 文件）＋ `config.ts` import | **主流可达 ⇒ 进分母** |

### 4.2 `durable` —— **不进分母**

见 §1.2：61 个 src 文件、规模 +26×，但 import 者全在 `experimental/` ⇒ R5 不可达。

### 4.3 其它新增（主流，进各自的域）

| 域 | 新增 | 载体 |
|---|---|---|
| coding-agent core | `virtual-models.ts`（Virtual models #10035）· `nested-tool-calls.ts` · `mcp-servers.ts` | `coding-agent/src/core/` |
| coding-agent ext | `extensions/tool-search/{index,tool}.ts` | 新扩展面 |
| ai | `api/{typesafe,cloudflare-workers-ai,llama-cpp-classify,system-one-shared}.ts`（长尾 provider，**须 R2 复判**）· `auth/oauth/{callback-server,openai-chatgpt}.ts` · `utils/model-operations.ts` | `ai/src/` |
| chord | `delta/{diff,draft,tracker,revision-validator,apply-immutable-trusted}.ts` | `chord/src/`（旧图已判 chord 不可达，本次仍权 0） |
| tui | `colors.ts` · `oklab.ts` · `wheel-scroll.ts` · 主题 token 系统 | `tui/src/` |
| 构建 | TypeScript 7 + 裸 node 跑源码；Nix flake | 基建，`01-infra` 复判 |

### 4.4 基建层（`01-infra`）漂移极小

| 包 | src 改动 | 结论 |
|---|---|---|
| `protocol` | **0**（仅 CHANGELOG/README/package.json） | 旧判定**全部保留** |
| `client` | **0**（仅 CHANGELOG/package.json） | 同上 |
| `telemetry` | **0**（仅 CHANGELOG/package.json） | 同上 |
| `server` | **5**：`server.ts` · `session-router.ts` · `testing/host.ts` · `transports/unix/preset.ts` · `types.ts` | 需逐条复核这 5 个 |

---

## 5 逐份地图的影响预估（重测前的范围界定）

| 地图 | 引 `harness` | 引 `session-backends` | 引 `pico3` | 引 `durable` | 处置 |
|---|---:|---:|---:|---:|---|
| `01-infra` | — | — | — | — | 只复核 `server` 的 5 个文件 |
| `02-ai` | — | — | — | — | 删 image registry 相关；核 3 个新 provider 的 R2 归属 |
| `03-agent-core` | **43** | — | **27** | **14** | **改动最大** —— 锚点全面改指 `coding-agent/src/core/*`；harness/pico3/durable 单元作废 |
| `04-coding-agent` | 1 | — | — | — | 删 micro/mini；**新增 mcp · codemode · tool-search · virtual-models · nested-tool-calls** 五块 |
| `05-tui-web` | — | — | — | 1 | 核 `colors/oklab/wheel-scroll` ＋主题系统 |
| `06-session-evals-chord` | **37** | **8** | **20** | **24** | **次大** —— SQLite 整段作废（§3.1）；JSONL 改按 v3 主流格式重取；`evals` 复核 |

---

## 6 重测方法（审核通过后执行）

**取数纪律**（照 `ANCHOR.md`，血泪换来的）：

1. **一律 `git show <sha>:<path>` 取证，不读工作树** —— 工作树被切过两次，`git status` 干净也会骗人。
2. **定锚通用办法**：拿一条形状独特的证据比对候选 commit ＋ 一组 LOC 交叉验证；**不凭 reflog 时间戳猜**。
3. **新锚点已用两条独立证据锁定**：① `git rev-parse origin/main` → `200387122`；② 包结构（`durable` 61 文件 /
   `session-backends` 不存在 / `codemode`·`mcp` 存在）与 `git ls-tree` 逐条对上。
4. **引用要带足路径上下文**（`jsonl/types.ts` 而非 `types.ts`）—— 旧图按 basename 匹配，`types.ts` 在 pi 里有 17 个同名。
5. **跑 pi 夹具前先** `cd packages/ai && node scripts/generate-models.ts --strict`（`providers/data/` 被 gitignore）。

**次序**：

| 步 | 动作 | 产出 |
|---|---|---|
| 1 | 本文档审核通过 | 裁决落定 |
| 2 | 更新 `ANCHOR.md`：`PI_PIN=200387122`、新 LOC 表、新漂移记录 | 锚点生效 |
| 3 | **6 路并行重测**，各自对 `200387122` ＋ pi-java `351d199` 重判 | 6 份新 `docs/map/*.md` |
| 4 | 重算 `docs/06` 总表（含 §5.1 作废面、§5.2 整块缺失） | 新的全项目数字 |
| 5 | 同步 `docs/05`（作废条目、行号更正）与 `docs/07`（补全计划） | 台账一致 |

---

## 7 待裁决点

| # | 问题 | 我的建议 |
|---|---|---|
| J1 | 新锚点取 `200387122`（`origin/main` 当前 tip）？ | **是**。它包含断裂提交，且是最新发布线。 |
| J2 | `durable` 是否维持权 0？ | **是**。R5 判据是「`bin pi` 可达」，它的 import 者全在 `experimental/`。⚠️ 但它规模已 +26×，若哪天进主流，这是一整块新大陆。 |
| J3 | `session-backends` 作废后，pi-java 的 `pi-java-session-backend-sqlite` 模块怎么办？ | **本次只标「参照物已删」，不删模块**。删模块是独立裁决（涉及 35 个测试与整条存储链）。 |
| J4 | §3.1 证伪了旧图的 25 点权重，`docs/06` 的历史数字要不要加勘误注？ | **要**。在 `docs/06 §3.4` 原处加「⚠️ 2026-10-04 换锚证伪」横幅，不删原文。 |
| J5 | 旧图判定的「缺失」项，若参照物被删 ⇒ 是否直接从分母消失（而非改判「对齐」）？ | **消失**。判据是「和 pi 表现一样」，pi 没有这个行为了，就没有对齐可言。 |

---

## 8 本裁决**不做**什么

- 不改任何 pi-java 生产代码。
- 不删任何 pi-java 模块（J3）。
- 不在本步重测 —— 重测是审核通过后的第 3 步。
- 不追溯修改 `docs/06`/`docs/07` 的历史数字（只在 J4 那处加勘误横幅）。
