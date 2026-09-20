# pi 锚点（alignment anchor）

本目录下 6 份地图的**全部 pi 侧 `file:line` 证据与规模数字，都是对着下面这个 commit 测的**。

```
PI_PIN=71dca871b
PI_PIN_DATE=2026-09-11
```

## 怎么定的（**别改成 `main` 的 HEAD**）

pi 的 `main` 在 2026-08-10 到 2026-09-20 之间**没有被 pull 过**（停在 `936aff009`，2026-08-09）。
地图测量时实际在 `my-pi` 分支上，基点是 **`71dca871b`**（2026-09-11）。

**验证方式**（两个独立证据，都对上了）：
- `71dca871b:packages/protocol/src` = `cbor codec.ts framing.ts index.ts protocol.ts` —— 与地图实测**逐字一致**
  （`936aff009` 处还有 `schemas.ts` 且**没有** `protocol.ts` ⇒ 那个锚点是错的）
- `71dca871b` 处各包 LOC 与地图规模表**逐项相同**：agent 25,305 · ai 24,383 · chord 5,822 ·
  client 1,135 · coding-agent 70,052 · evals 1,964 · protocol 869 · server 1,966 · telemetry 935 · tui 18,107

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

**读法**：报出来的是**上界** —— 「文件被改」不等于「被引的那一行被改」，
但足以说明哪些条目**需要重新取证**。

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
