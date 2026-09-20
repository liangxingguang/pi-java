# pi 锚点（alignment anchor）

本目录下 6 份地图的**全部 pi 侧 `file:line` 证据与规模数字，都是对着下面这个 commit 测的**。

```
PI_PIN=3390bd936
PI_PIN_DATE=2026-09-20
```

**6 份地图已于 2026-09-20 全部对该 HEAD 重测完毕**（上一次锚点是 `71dca871b`）。

## 怎么定的（**别改成 `main` 的 HEAD**）

第一次定锚时我找错过：pi 的 `main` 在 2026-08-10 到 2026-09-20 之间**没有被 pull 过**
（停在 `936aff009`，2026-08-09），而地图测量时实际在 `my-pi` 分支上，基点是 **`71dca871b`**（2026-09-11）。

**当时的验证方式**（两个独立证据，都对上了）：
- `71dca871b:packages/protocol/src` = `cbor codec.ts framing.ts index.ts protocol.ts` —— 与地图实测**逐字一致**
  （`936aff009` 处还有 `schemas.ts` 且**没有** `protocol.ts` ⇒ 那个锚点是错的）
- `71dca871b` 处各包 LOC 与地图规模表**逐项相同**：agent 25,305 · ai 24,383 · chord 5,822 ·
  client 1,135 · coding-agent 70,052 · evals 1,964 · protocol 869 · server 1,966 · telemetry 935 · tui 18,107

**定锚的通用办法**（下次换锚照做）：拿一条地图里**形状独特**的证据去比对候选 commit
（这次用的是 `protocol/src` 的文件清单），再用一组 LOC 数字交叉验证。**不要凭 reflog 的时间戳猜** —— 我第一次就是这么错的。

## 为什么要钉

判据是「分支所有功能都和 pi 表现一样」。**pi 是活的** —— 它会前进、会删除、会重写。
没有锚点，地图里的 `file:line` 就是「某天的某个 pi」，读者无法判断它是否还成立。

**已经栽过一次**：`docs/phase1-pi-code-mapping.md` 的「~92% 完成度」就是**没有锚点的断言**，
它映射到 pi **已删除**的目录结构，误导了两份后续文档和 `CLAUDE.md`，直到 2026-09-20 才被删。

## 漂移检测

```
bash docs/map/check-drift.sh
```

它做三件事：① 报当前 pi HEAD 与 `PI_PIN` 差多少提交；② 从 6 份地图里抽出所有被引用的
pi 文件名；③ 逐个查它是否在 `PI_PIN..HEAD` 里被改过。退出码 1 = 有漂移（可接 CI）。

输出分两栏，**因为地图里的引用写的是 basename**：

| 栏 | 含义 |
|---|---|
| **【确定】** | 该 basename 在 pi 里**唯一** ⇒ 被改就是被改 |
| **【可能】** | 该 basename 有同名文件 ⇒ 可能改的是另一个包的同名文件（上界） |

**⚠️ 已知限制**：`types.ts` 在 pi 里有 **17 个同名文件**、`transcript.ts` 有 3 个 —— 这类引用
**按 basename 永远查不准**，只能人工判。**后续新增引用请带足够路径上下文**（如
`jsonl/types.ts` 而非 `types.ts`），否则漂移检测对它们无效。

**2026-09-20 实测教训**：改版前的检测器（不分栏）报 49 条，`01-infra` 那层逐条复核后
**真漂移 0 条** —— 误报全来自同名 basename。**别把本脚本的输出直接当结论，它只圈定范围。**

**⚠️ 取证的第二个坑**：重测期间 `D:/workplaceForai/pi` 的工作树**被切换过两次**
（一次是 agent 误跑 `git checkout 71dca871b`，一次是人工）。**凡在本轮直接读工作树取的证，
都可能静默拿到旧状态**（`git status` 仍是干净的）。**新状态一律用 `git show <sha>:<path>` 取。**

## 漂移记录

| 日期 | 从 | 到 | 提交数 | 状态 |
|---|---|---|---|---|
| 2026-09-20 | `71dca871b` | `3390bd936` | **111** | ✅ **6 份全部重测完毕** |

### 2026-09-20 这次漂移的结论

**漂移不均匀** —— 集中在 `ai`（119 文件）与 `coding-agent`（149 文件）两个包，其余层几乎零变化：

| 层 | 实测 |
|---|---|
| `telemetry` / `protocol` / `client` / `server` | **零改动**（580 条引用全部命中，0 漂移） |
| `session-backends`（SQLite） | **逐字节未变** ⇒ 18 行 schema 判定 100% 不变 |
| `agent` 的 `harness/session`、`tools`、`compaction`、`utils`、`env` | **逐文件行数全等** |
| `chord` 除 `delta/` | **逐字节未变**；`delta/` +681 行是**内部加固**（20 个导出锚点行号一字未动） |
| `ai` / `coding-agent` | **主战场** —— 实质判定变化全在这两处 |

**pi 新增三块零对应（10,275 行）**：`durable`(757) · `pico3`(7,994) · `micro`(1,524)。
⚠️ **三者在 pi 自己那边也没接线**：`durable` 全仓无人 import；`pico3` 只被 `micro` 用；
`micro` 全仓无人 import ⇒ **一条自闭合的链，从 CLI 不可达**。**pi 包数 11 → 12。**

**`docs/40` 的总表已按本轮结果更新**（全体加权完成度 **49.96% → 44.31%**）。


## 漂移记录

| 日期 | 从 | 到 | 提交数 | 状态 |
|---|---|---|---|---|
| 2026-09-20 | `71dca871b` | `3390bd936` | **111** | ⚠️ **未重测** —— 见下 |

### 2026-09-20 这次漂移的实测影响

- **49 / 135 个被引用的 pi 文件被改动**（36%）⇒ 这部分条目的 pi 侧行号**未对新 HEAD 复核**。
  （`bash docs/map/check-drift.sh` 实测；⚠️ 这是**上界** —— 文件被改 ≠ 被引的那一行被改。）
- **pi 新增了一个包 `durable`**（757 行，`080160162 feat(durable): move Pico into dedicated package`）
  —— 地图里**零覆盖**。
- **pi 各包 LOC 普遍增长**：`agent` 25,305 → **33,353**（+31.8%）· `coding-agent` 70,052 → **73,781** ·
  `chord` 5,822 → **6,503** · `ai` 24,383 → **25,096**；`evals` 1,964 → **1,446**（缩了）。
  ⇒ **`docs/40` 的规模表与「pi 全库 152,511 行」这句都要更新。**
- **三条已核实的具体失效**（行号漂了，实质未变）：
  - `agent-session.ts` 的 `compact()`：`:1967-1968` → **`:2089-2090`**（第一行仍是 `await this.abort()`）。
    ⚠️ 且 pi 刚发了 `de2de549b fix(coding-agent): close compaction cancellation races`（改该文件 156 行）
    ⇒ **A4 的「缺 abort-first」需要重新取证**。
  - `session-manager.ts` 的 `SessionListProgress`：`:768` → **`:795`**；`listSessionsFromDir`：`:812` → **`:852`**。
    ⚠️ pi 新发了 `dfbf793b7 load session picker progressively`（改 266 行）＋
    `dd01f5b24 speed up recent session discovery`，且 `listAll` 现在带 **`AbortSignal`**
    ⇒ **B55 的「无 onProgress」需要重新取证**。
  - `21b8cc1a4 fix(coding-agent): ignore stale tool image conversions` 改了 TUI 的 `tool-execution.ts`
    —— 与 B17（图片）相关但是**渲染侧**，不是 Anthropic 车道。

### 处置（待办）

1. **重新锚定**：把 6 份地图对 `3390bd936` 重测（6 路并行，同上次），并把 `PI_PIN` 更新到新 HEAD。
   **在此之前，地图里的 pi 侧行号一律按「对 `71dca871b` 有效」读。**
2. **新增包 `durable` 纳入地图**。
3. `docs/40` 的规模表与总量按新 LOC 更新。
4. 台账 `docs/32` 里 A4 / B55 的 pi 侧行号同步更正。
