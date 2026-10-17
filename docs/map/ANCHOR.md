# pi 锚点（alignment anchor）

> 【背景摘要】本目录 7 份地图（01–07，含 2026-10-12 新建的 07-mcp）里**全部 pi 侧 `file:line` 证据与规模数字，都是对着下面这个 commit 测的**。本文档记录锚点是什么、怎么定的、怎么检测漂移，以及历次换锚的结论。

## 术语

- **锚点（anchor）**：对齐测量时钉死的 pi commit。钉死的原因：pi 是活的，会前进、会删除、会重写；没有锚点，地图里的 `file:line` 就是「某天的某个 pi」，读者无法判断它是否还成立。
- **换锚**：把锚点从旧 commit 推进到新 commit，并重测全部地图。**换锚不是刷新**——新旧锚点之间 pi 可能删掉了地图正在当参照物的子系统。
- **R5 不可达 / 面外**：不进 `bin pi` 主流发布版的子系统（durable、codemode、chord 等），不做对齐、不进分母。见 [`../04-open-items-register.md`](../04-open-items-register.md) §0。
- **工单编号（A16/B233 等）**：[`../04-open-items-register.md`](../04-open-items-register.md) 的条目编号。
- **原 docs/NN**：已删除的过程文档编号，内容在 git 历史可查。

## 当前锚点

```
PI_PIN=ea448f454
PI_PIN_DATE=2026-10-11
```

**2026-10-12：七份地图已全部对 `ea448f454` 逐条重测完毕**（原 A16 销号）。要点：

- 除原 6 图外新建了 `07-mcp.md`。原因：MCP 已成 pi 主流模块（pi `packages/mcp` 3,244 行＋coding-agent MCP 面），而旧图没有它的位置。
- 重测撞出的新语义差异登记为 B233–B238。
- 各图基准头同时改指 pi-java HEAD `edc8749d`。
- `check-drift.sh` 报「✓ 无漂移」自此是字面意义。

**2026-10-11 迷你换锚记录**：`200387122 → ea448f454`（125 提交，跨 v1.0.3/v1.0.4/v1.1.0）。结论：**均匀前进、无结构性删除**。

- protocol/server/telemetry/client/chord/evals 六包逐字节未变。
- 增量集中在 durable/codemode 两个 R5 不可达包。
- 活跃对齐面 `compat.ts`／`api/openai-completions.ts`／`api/anthropic-messages.ts`／`api/pi-messages.ts` 两锚点间零改动。

**⚠️ 上一轮（2026-10-04）是换锚，不是刷新。** 旧锚点 `3390bd936` 之后的 251 个提交里，有一个删掉了 **105,456 行**，包括两份旧地图正在当参照物测的子系统（`session-backends` 整包＋`agent/src/harness/**`）。删除面的裁决在 2026-10-04 已审核（原 `docs/11-pi-reanchor-ruling.md`，已随过程文档清理删除，历史在 git）。重测必须先了解那份裁决，否则会对着已删除的代码判对齐。

## 怎么定的（**别改成 `main` 的 HEAD**）

第一次定锚时找错过：pi 的 `main` 在 2026-08-10 到 2026-09-20 之间**没有被 pull 过**（停在 `936aff009`），而地图测量时实际在 `my-pi` 分支上，基点是 `71dca871b`。

**定锚的通用办法**（下次换锚照做）：

1. 拿一条地图里**形状独特**的证据去比对候选 commit。第一次用的是 `protocol/src` 的文件清单，这次用的是**包结构**。
2. 再用一组 LOC 数字交叉验证。
3. **不要凭 reflog 的时间戳猜**——第一次就是这么错的。

**本次的定锚证据（两条独立）**：

| # | 证据 | 结果 |
|---|---|---|
| ① | `git rev-parse origin/main`（`git fetch` 后） | `200387122ca450d6387f033949423114a270b96c`，提交时间 **2026-10-04** |
| ② | 包结构比对（`git ls-tree --name-only <ref>:packages/`） | 新锚点处 **`session-backends` 不存在**、**`codemode` 与 `mcp` 存在**、`durable` 有 61 个 src 文件——与 `原 docs/11` 的实测逐条对上 |

⚠️ 本地 pi clone 自 2026-09-20 起**没被 pull 过**（`git rev-parse HEAD` 仍是 `3390bd936`）。所以 `check-drift.sh` 在换锚前报的「✓ 无漂移」是**假绿**——它比的是 local HEAD 与 `PI_PIN`，两个都是旧的。**本轮的漂移一律按 `origin/main` 判。**

## 为什么要钉

判据是「分支所有功能都和 pi 表现一样」。**已经栽过一次**：`docs/phase1-pi-code-mapping.md` 的「~92% 完成度」就是没有锚点的断言。它映射到 pi **已删除**的目录结构，误导了两份后续文档和 `CLAUDE.md`，直到 2026-09-20 才被删。

## 漂移检测

```
bash docs/map/check-drift.sh
```

它做三件事：

1. 报当前 pi HEAD 与 `PI_PIN` 差多少提交。
2. 从 7 份地图里抽出所有被引用的 pi 文件名。
3. 逐个查它是否在 `PI_PIN..HEAD` 里被改过。退出码 1 ＝ 有漂移（可接 CI）。

> ⚠️ **脚本比的是 `HEAD`（工作树），不是 `origin/main`**。换锚期间 `PI_PIN` 已指到 `origin/main`，而本地 `HEAD` 落后，脚本会**倒着报**「当前 HEAD 在锚点之前」。**换锚窗口内以本文件的手工记录为准。**

输出分两栏，**因为地图里的引用写的是 basename**：

| 栏 | 含义 |
|---|---|
| **【确定】** | 该 basename 在 pi 里**唯一** ⇒ 被改就是被改 |
| **【可能】** | 该 basename 有同名文件 ⇒ 可能改的是另一个包的同名文件（上界） |

**⚠️ 已知限制**：`types.ts` 在 pi 里有 **17 个同名文件**、`transcript.ts` 有 3 个。这类引用**按 basename 永远查不准**，只能人工判。**后续新增引用请带足够路径上下文**（如 `jsonl/types.ts` 而非 `types.ts`），否则漂移检测对它们无效。

**2026-09-20 实测教训**：改版前的检测器（不分栏）报 49 条，`01-infra` 那层逐条复核后**真漂移 0 条**——误报全来自同名 basename。**别把本脚本的输出直接当结论，它只圈定范围。**

**⚠️ 取证的第二个坑**：重测期间 `D:/workplaceForai/pi` 的工作树**被切换过两次**（一次是 agent 误跑 `git checkout 71dca871b`，一次是人工）。**凡在本轮直接读工作树取的证，都可能静默拿到旧状态**（`git status` 仍是干净的）。**新状态一律用 `git show <sha>:<path>` 取。**

---

## 漂移记录

| 日期 | 从 | 到 | 提交数 | 状态 |
|---|---|---|---|---|
| 2026-09-11 | `71dca871b` | `3390bd936` | 111 | ✅ 6 份全部重测完毕 |
| **2026-10-04** | **`3390bd936`** | **`200387122`** | **251** | ✅ **6 份全部重测完毕**（裁决见 `原 docs/11`） |
| **2026-10-11** | **`200387122`** | **`ea448f454`** | **125** | ✅ **迷你换锚**：ANCHOR＋台账先行；**2026-10-12 七份地图（含新建 07-mcp）全部重测完毕**，新差异 B233–B238（A16 销号） |

### 2026-10-11 这次漂移的结论（迷你换锚）

**均匀前进，无结构性断裂。** 125 提交跨 v1.0.3 / v1.0.4 / v1.1.0（tag 已 fetch）。主流协议面几乎不动，增量在 durable / codemode / pi-env（SSH 远程）三个 R5 面外域：

| 层 | 实测（src `.ts`，不含 test） |
|---|---|
| `protocol` / `server` / `telemetry` / `client` / `chord` / `evals` | **逐字节未变**（6 包全 0） |
| `ai` | 26,341 → **27,242**（+901，+3.4%）—— 37 文件；活跃对齐面 `compat.ts`／`openai-completions.ts`／`anthropic-messages.ts`／`pi-messages.ts` **零改动** |
| `agent` | 2,513 → 2,519（+6） |
| `coding-agent` | 84,990 → **86,152**（+1.4%） |
| `tui` | 19,277 → 19,505（+1.2%） |
| `mcp` | 3,167 → 3,244（+2.4%） |
| `durable` | 17,716 → **22,473**（R5 不可达，不进对齐面） |
| `codemode` | 1,678 → 2,597（R5 不可达，依赖 B161） |

**定锚证据（两条独立）**：

| # | 证据 | 结果 |
|---|---|---|
| ① | `git rev-parse origin/main`（`git fetch` 后，2026-10-11） | `ea448f454af474f3e2fe9733b331e17b0662f5d6`，`git describe` ＝ `v1.1.0-22-gea448f454` |
| ② | 规模表交叉验证（LOC 对两锚点实测） | 六个稳定包零变化、ai +901、durable/codemode 增量与 v1.1.0 changelog 叙述一致；无整包删除 |

**ai 层新差异已逐条屏过并登记 `docs/04`**：

- B227（estimate 4→3.5）
- B228（retry 白名单 +3 模式）
- B229（Mistral 停因文案＋超时只盖响应头）
- B230（azure 改名＋Foundry Chat Completions）
- B231（OAuth 刷新取消仍持久化轮转 token）
- B232（目录数据漂移 Haiku 5.5 等）
- B226 补记 Radius gateway catalog 语义。

分类器新 API（`classifier-shared.ts`/`openai-decisions.ts`/llama.cpp）与 codemode/pi-env/durable 同属 R5 面外，不登记。

### 2026-10-04 这次漂移的结论

**是一次结构性断裂，不是均匀前进。** 251 提交 / 583 个改动 `.ts`（全类 1,203），但其中**一个提交就删了 105,456 行**：

```
7fd478a2e  feat(agent): remove the experimental harness from pi-agent-core   (2026-10-01)
341 files changed, 12 insertions(+), 105456 deletions(-)
```

| 层 | 实测 |
|---|---|
| `protocol` / `client` / `telemetry` | **src 零改动**（仅 CHANGELOG/README/package.json 的版本号） |
| `server` | 仅 **5 个** src 文件（`server.ts` · `session-router.ts` · `testing/host.ts` · `transports/unix/preset.ts` · `types.ts`） |
| `evals` | **逐字节未变**（1,446 行，两侧同） |
| **`agent`** | **33,353 → 2,513 行（−92.5%）** —— harness / pico3 / search / node 全删 |
| **`session-backends`** | **整包删除**（旧 1,973 行 src） |
| **`durable`** | **757 → 17,716 行（+23×）** —— harness 与 session storage 的新家，但**仍不可达** |
| `coding-agent` | 73,781 → **84,990**（+15.2%）—— 新增 mcp / codemode / tool-search / virtual-models 等 |
| `ai` | 25,096 → **26,341**（+5.0%） |
| `chord` | 6,503 → **8,817**（+35.6%，`delta/` 加固） |
| `tui` | 18,267 → **19,277**（+5.5%） |
| **新增包** | **`mcp`（3,167）** · **`codemode`（1,678）** —— 两者都在 `bin pi` 主流路径上 |

### 两条旧判定被这次换锚**证伪**

| # | 旧判定 | 实测 |
|---|---|---|
| 1 | `docs/06 §3.4`：「SQLite schema 分叉」＝全项目单点最大拖累（Σ权重 25，+16.2pp） | **自始即错**：`git grep -l "session-backends" 3390bd936 -- 'packages/*/src/**'` **只命中它自己的 `package.json`** ⇒ 它在**旧锚点上就已经零消费者**，从没上过 `bin pi` 这条线。那 25 点权重给了个 R5 不可达的子系统 |
| 2 | `docs/06 §1.3`：「JSONL v4 双向不可读」＝用户可见硬故障 | **参照物已删**：主流 pi 的会话持久化一直是 JSONL，头为 `{type:"session", version:3, id, timestamp, cwd}`（`coding-agent/src/core/session-manager.ts:41` `CURRENT_SESSION_VERSION = 3`）。旧图据以判定的 `{v:4, storageVersion:1}` 是 **harness 的格式**——`git grep storageVersion 200387122 -- 'packages/*/src/**'` **零命中** |

### 处置

1. ✅ **换锚裁决**——`原 docs/11-pi-reanchor-ruling.md`（2026-10-04，J1–J5 全按建议；文档已删，历史在 git）。
2. ✅ **6 份地图重测**——已对顶部新 `PI_PIN` ＋ pi-java `351d199` 全部重写。
3. ✅ **`docs/06` 总表重算**——全项目 **44.31% → 52.59%**（分母 1,423 → 1,273），含 §5.1 作废面与 §5.2 不可达面。（52.59% 是**换锚当刻**值；此后 ⑧/⑨/⑩ 与 `原 docs/40` 的回填只改判定不改分母 ⇒ 现为 **53.50%**，最新读数见 `docs/05` §1。）
4. ✅ **`docs/05`**（B59/B60/B61/B62 改写＋新增 B158–B165）／**`docs/07`**（补全包清单按 R5 重排）**已同步**。

**2026-10-04 收尾**：本地 pi clone（`D:\workplaceForai\pi`）已快进到 `200387122`（工作树干净、`--ff-only` 成功）⇒ `check-drift.sh` 恢复一致（报「✓ 无漂移」）。**此前那次「✓ 无漂移」是假绿**（本地 HEAD 与 `PI_PIN` 都还停在旧的 `3390bd936`，脚本比的是工作树 HEAD 而不是 `origin/main`）。

**2026-10-12 收尾**：七份地图逐条重测完成（pi `ea448f454` / pi-java `edc8749d`），新建 07-mcp，新差异 B233–B238 已入台账；`check-drift.sh` 的「✓ 无漂移」自此名副其实。

---

## 规模表（src `.ts` 行数，不含 test）

| 包 | `3390bd936` | `200387122` | **`ea448f454`** | 本轮 Δ |
|---|---:|---:|---:|---:|
| `coding-agent` | 73,781 | 84,990 | **86,152** | +1.4% |
| `ai` | 25,096 | 26,341 | **27,242** | +3.4% |
| `tui`（＋app 层） | 18,267 | 19,277 | **19,505** | +1.2% |
| `durable` | 757 | 17,716 | **22,473** | +26.9%（R5 面外） |
| `chord` | 6,503 | 8,817 | **8,817** | 0 |
| `mcp` | — | 3,167 | **3,244** | +2.4% |
| `agent` | 33,353 | 2,513 | **2,519** | +6 |
| `server` | 1,966 | 1,958 | **1,958** | 0 |
| `codemode` | — | 1,678 | **2,597** | +54.8%（R5 面外） |
| `evals` | 1,446 | 1,446 | **1,446** | 0 |
| `client` | 1,135 | 1,135 | **1,135** | 0 |
| `telemetry` | 935 | 935 | **935** | 0 |
| `protocol` | 869 | 869 | **869** | 0 |
| `session-backends` | 1,973 | 0（删除） | **0** | — |

**pi-java 侧同口径（`src/main/java` 行数）**，供 `docs/05` §6 对照：

| 模块 | 行数 | 模块 | 行数 |
|---|---:|---|---:|
| `pi-java-ai` | 24,141 | `pi-java-session-backend-sqlite` | 2,875 |
| `pi-java-agent-core` | 17,599 | `pi-java-web`（pi 无对应） | 1,862 |
| `pi-java-coding-agent` | 11,188 | `pi-java-telemetry` | 787 |
| `pi-java-tui` | 6,707 | `pi-java-evals` | 785 |
| | | `pi-java-protocol` | 748 |
| | | `pi-java-server` | 563 |
| | | `pi-java-client` | 374 |
