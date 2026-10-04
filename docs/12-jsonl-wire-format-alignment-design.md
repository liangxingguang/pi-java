# 12 — JSONL 会话线格式对齐（本仓 v4 ↔ pi v3 双向可读）

**状态：设计稿 —— 待用户审核；审核通过前不写代码**

| | |
|---|---|
| **基准** | pi @ **`200387122`**（2026-10-04）· pi-java @ `491a668` |
| **台账** | **B60**（JSONL 头部与事务行两侧互不可读）· **B61**（`Entry` 面代差）—— 同根因，**同包收** |
| **来源** | `docs/11-pi-reanchor-ruling.md` · `docs/06 §3.4 / §8-1` · `docs/map/06` |
| **判据** | 「**pi 能读本仓写的会话文件，本仓也能读 pi 写的**」—— 双向，不是单向导出 |

---

## 1 为什么做这个包

`docs/06 §8-1` 把它列为**单点最大拖累**（Σ权重 8，占该范围 9.2%，修好 **39.1% → 48.3%**），
且是**用户可见的硬故障**（换机 / 换工具后会话直接读不出来）。`docs/07` 的排期原则第 ③ 条
「**硬故障插队**」直接点名它。

⚠️ **本包的参照物在 2026-10-04 换锚时换过一次**：旧图据以判定的 `{v:4, storageVersion:1}` 是
**已删除的 harness 格式**（`git grep -n storageVersion 200387122 -- 'packages/*/src/**'` **零命中**）。
现在的对照物是 **pi 主流**的 `coding-agent/src/core/session-manager.ts`。**旧图的行内证据一律作废。**

---

## 2 pi 侧的格式（`coding-agent/src/core/session-manager.ts`，逐字）

### 2.1 头（`:41-49`）

```ts
export const CURRENT_SESSION_VERSION = 3;

export interface SessionHeader {
	type: "session";
	version?: number; // v1 sessions don't have this
	id: string;
	timestamp: string;
	cwd: string;
	parentSession?: string;
}
```

写盘（`:1060-1072`，`newSession`）：

```ts
const header: SessionHeader = {
    type: "session",
    version: CURRENT_SESSION_VERSION,   // 3
    id: this.sessionId,
    timestamp,                          // new Date().toISOString()
    cwd: this.cwd,
    parentSession: options?.parentSession,   // undefined ⇒ JSON.stringify 省略该键
};
...
const fileTimestamp = timestamp.replace(/[:.]/g, "-");
this.sessionFile = join(this.getSessionDir(), `${fileTimestamp}_${this.sessionId}.jsonl`);
```

### 2.2 条目（`:57-63` 基线 ＋ `:64-197` 联合）

```ts
export interface SessionEntryBase {
	type: string;
	id: string;
	parentId: string | null;
	timestamp: string;
}
```

**11 个变体**（`:183-197` 的 `SessionEntry` 联合）：

| `type` | 载荷（逐字取自 `:64-182`） |
|---|---|
| `message` | `message: AgentMessage` |
| `thinking_level_change` | `thinkingLevel: string` |
| `model_change` | `provider: string; modelId: string` |
| `usage` | `kind: string; provider: string; model: string; usage: Usage; note?: string` |
| `compaction` | `summary; firstKeptEntryId; tokensBefore; details?; usage?; fromHook?; systemMessage?` |
| `branch_summary` | `fromId; summary; details?; usage?; fromHook?` |
| `custom` | `customType: string; data?: T` |
| `custom_message` | `customType; content: string \| (TextContent\|ImageContent)[]; details?; display: boolean` |
| `context_edit` | `targetId: string; replacement: { content } \| null` |
| `label` | `targetId: string; label: string \| undefined` |
| `session_info` | `name?: string` |

⭐ **`usage` / `label` / `session_info` 在 pi 是**一等条目**，不是旁路表。`context_edit` 是 pi 本轮新增的。
**pi 的会话文件里没有 `lane` / `fact` / `record` 这三种行**，也没有 `seq`。**

### 2.3 写路径

```ts
// :1187  常规追加
appendFileSync(this.sessionFile, `${JSON.stringify(entry)}\n`);
```

**惰性建文件**（`:1159-1186`，`#10000`）：setup 条目（模型、思考级别、系统提示）只在内存里，
**直到出现第一条 `user`/`assistant` 消息才落盘**；文件用 `"wx"` 独占创建。

### 2.4 读路径（`:353-368`）

```ts
export function parseSessionEntries(content: string): FileEntry[] {
	const entries: FileEntry[] = [];
	const lines = content.trim().split("\n");
	for (const line of lines) {
		if (!line.trim()) continue;
		try { entries.push(JSON.parse(line) as FileEntry); }
		catch { /* Skip malformed lines */ }        // ← 静默跳过
	}
	return entries;
}
```

装载（`:1084-1100`，`_loadEntries`）：**找 `e.type === "session"` 当头**；找不到就 `newSession()` 并把
剩下的行 `concat` 进去。

### 2.5 迁移（`:287` / `:316` / `:337`）

```ts
function migrateV1ToV2(entries) { /* 补 id/parentId 树结构；firstKeptEntryIndex → firstKeptEntryId */ }
function migrateV2ToV3(entries) { /* message.role: "hookMessage" → "custom" */ }
function migrateToCurrentVersion(entries): boolean {
	const version = header?.version ?? 1;
	if (version >= CURRENT_SESSION_VERSION) return false;
	if (version < 2) migrateV1ToV2(entries);
	if (version < 3) migrateV2ToV3(entries);
	return true;
}
```

⚠️ **pi 的 `version` 是「头缺省即 1」**，且迁移只认 `< 3`。

---

## 3 本仓的格式（逐字，取自一份真实会话文件）

`~/.pi-java/agent/sessions/--D--workplaceForai-pi-java--/2026-10-03T12-50-57-069Z_<id>.jsonl`：

```json
{"kind":"header","version":4,"id":"ffffffff-ffaa-7ffb-ba14-6c6ca8b66d63","createdAt":1791031857069,"cwd":"D:\\workplaceForai\\pi-java"}
{"kind":"lane","seq":1,"lane":"default","leafId":null}
{"kind":"entry","lane":"default","type":"message","id":"91d819a3-…","seq":2,"timestamp":1791031889391,"message":{"role":"system","content":[{"type":"text","text":""}],"timestamp":1791031889257,"sections":{"preamble":"…","tools":"<tools>\n- read: Read"}}}
```

### 3.1 头

`JsonlV4Header.java:19-35` ＋ `JsonlCodec.encodeHeader:82-104`：

```java
public record JsonlV4Header(
    String kind, int version, String id, long createdAtMs, String cwd,
    String parentSessionId, String legacyParentSessionPath, Map<String,Object> metadata) { … }
```

`parseHeader`（`:107-134`）**要求 `kind == "header"`**，且 `version ∈ {3, 4}`。

⚠️ **本仓的 3/4 是自己的沿革，与 pi 的版本号无关** —— `JsonlSessionMetadata.java:18` 明写
「`sourceFormat`: 3 为 legacy 文件、4 为当前」，而那个 legacy 是本仓**自己**的旧 v3（同样 `kind:"header"`，
多一个 `legacyParentSessionPath`）。

### 3.2 行 = 四态包装

`JsonlCodec.encodeMutation:139-192`：

```java
case SessionMutation.Entry m -> { node.put("kind","entry"); if (m.lane()!=null) node.put("lane", m.lane());
                                 node.setAll((ObjectNode) mapper.valueToTree(m.entry())); … }
case SessionMutation.Record m -> { node.put("kind","record"); node.setAll(…); }
case SessionMutation.Lane  m -> { node.put("kind","lane");  … }
case SessionMutation.FactName/FactLabel -> { node.put("kind","fact"); … }
```

三族：
- **entry** 8 型（`ENTRY_TYPES`：`message` / `model_change` / `thinking_level_change` / `active_tools_change` / `compaction` / `branch_summary` / `custom` / `custom_message`）—— ⚠️ `active_tools_change` 是**只读遗留形状**，A3 裁决已定「pi 主线从不发射」，见 `Entry.java:26-37`
- **record** 11 型（`RECORD_TYPES`，审计线：`operation_started` / `tool_started` / `queue_*` / `usage` …）
- **lane** ＋ **fact**（`name` / `label`）

`Entry` 本身（`Entry.java:45-50`）用 `@JsonTypeInfo(property = "type")` —— **判别键已经是 `type`**，
所以 entry 的**内层形状**与 pi 接近，差在外层的 `kind`/`lane`/`seq` 包装与 `timestamp` 类型。

### 3.3 读路径

`JsonlSessionStorage.load:53-102`：**首行必须过 `parseHeader`**（即 `kind:"header"`），否则抛
`JsonlSessionError(path, 1, "is missing a header")`。之后逐行 `parseMutation`，**最后一行 syntax 错 ⇒ 撕尾修复**
（重写有效前缀）⇒ 与 pi 的「静默跳过任意坏行」不同但兼容。

已有自己的 v3→v4 **惰性迁移**（`:290-302`，重写头即可，因为「mutation lines 已经是 v4 形状」）。

---

## 4 差距清单（逐条）

| # | 维度 | pi（`200387122`） | 本仓 | 影响 |
|---|---|---|---|---|
| G1 | 头判别键 | `"type":"session"` | `"kind":"header"` | **互不可读的直接原因** |
| G2 | 版本号 | `version: 3`（`?? 1`） | `version: 3\|4`（**自己的沿革**） | 语义冲突：数字同、所指不同 |
| G3 | 头时间戳 | `timestamp`（ISO 串） | `createdAt`（epoch ms） | pi 读不到 `timestamp` |
| G4 | 父会话键 | `parentSession` | `parentSessionId` ＋ `legacyParentSessionPath` | 键名不同 |
| G5 | 条目行 | **扁平** `{type,id,parentId,timestamp,…}` | 包在 `{kind:"entry",lane,…}` 里 | pi 读到 `type:undefined` |
| G6 | 每行多余键 | 无 | `kind` · `seq` · `lane` | pi 忽略（无害），但**不是 pi 的形状** |
| G7 | 行时间戳 | ISO 串 | epoch ms（long） | pi 侧 `timestamp: string` 类型不符 |
| G8 | 类型集 | **11** 型 | entry **7** 效型（pi 的 `usage`/`label`/`session_info` 在本仓是 record/fact 旁路；`context_edit` **无**） | **缺 4 型** |
| G9 | 额外行族 | 无 | `record`(11) · `lane` · `fact` | **在 pi 无家** |
| G10 | 迁移 | v1→v2→v3 | 只有**自己的** v3→v4 | 读 pi 老文件无路 |

---

## 5 双向不可读的**实测**失败模式

| 方向 | 会发生什么 |
|---|---|
| **pi 读本仓的文件** | `parseSessionEntries` 逐行 `JSON.parse` **全成功**（都是合法 JSON），但 `_loadEntries` 找 `e.type === "session"` **找不到**（本仓写的是 `kind`）⇒ 走 `newSession()` 分支，把 `{kind:"header",…}` / `{"kind":"lane",…}` 等行当作**条目** `concat` 进去 ⇒ **pi 打开本仓的会话会新建一个空会话**，原内容当作无法识别的条目挂着 |
| **本仓读 pi 的文件** | 首行 `parseHeader` 要求 `kind == "header"` ⇒ 拿到 `"type":"session"` ⇒ 抛 `JsonlSessionError(…, "is missing a header")` ⇒ **直接拒绝装载** |

⇒ 两个方向都是**硬失败**，不是「字段丢几个」。

---

## 6 设计裁决点

> 每条给**推荐**，理由一行。审核时逐条裁决。

### D1 —— 以后写哪一侧的形状？〔推荐：**写 pi 的 v3 形状**〕

本仓改为写 `{type:"session",version:3,…}` ＋ 扁平行 ＋ ISO 时间戳。理由：判据是「pi 能读本仓的文件」，
pi 不会为本仓改；且 pi 的 `version:3` 是它的**当前版本**，pi 读到即不回写不迁移，最不易出意外。

⚠️ **与 D2 的耦合**：本仓**旧文件也是 `version:3`**（但 `kind:"header"`）⇒ 版本号**不能**用来区分新旧。
区分只能靠**判别键**（`type` vs `kind`）。这是本包唯一的「脏」点，必须写进 `JsonlCodec` 的注释与测试。

### D2 —— 本仓的三族额外行（`record` / `lane` / `fact`）怎么办？〔推荐：**映射 ＋ 收编**〕

| 本仓 | 目标 |
|---|---|
| `fact` `name` | **pi 原生 `session_info {name?}`**（语义一对一） |
| `fact` `label` | **pi 原生 `label {targetId,label?}`**（语义一对一） |
| `lane`（含 `leafId`） | **`custom` 条目**，`customType:"pi-java.lane"`，`data:{lane, leafId}` |
| `record` ×11 | **`custom` 条目**，`customType:"pi-java.record.<kind>"`，`data:{…}` |

理由：pi 的 `custom` 条目**本就是**给扩展存私有的持久状态用的（`:112-122` 的注释逐字这么说），且
**不参与 LLM 上下文**（`buildSessionContext` 忽略 `custom`）—— 正好是本仓审计行的性质。
⚠️ **`record` 落成 `custom` 会让它们进 pi 的 `parentId` 链**（`_buildIndex` 把它们当普通条目），
这会改变 pi 侧的 leaf 语义。**备选 D2′：`record` 干脆不落线**（它们是审计线，见 `docs/30` 的裁决 ——
记录发射仍有效但折叠链已删）。**这一条请重点裁。**

### D3 —— `context_edit` 要不要一起补？〔推荐：**本包只做线格式，`context_edit` 另立**〕

它是 pi 本轮**新增的整条能力**（追加式改写历史条目的上下文贡献），落线之外还有投影语义
（`buildSessionProjection`）。塞进本包会把包撑成两件事。登记为 **B61 的剩余面**。

### D4 —— 旧文件怎么迁移？〔推荐：**读时惰性重写，照 `migrateV3ToV4` 的先例**〕

`JsonlSessionStorage.load` 已经有一套「装载时发现旧格式 ⇒ 重写」的机制（`:290-302`）。
新增一条：首行判别键是 `kind` ⇒ 走 `migratePiJavaV4ToPiV3`（重写头 ＋ 逐行重新编码）。
不新增迁移工具、不改文件名（文件名两侧已经一致，见 `docs/map/06`）。

### D5 —— 本仓的「v4」这个名字怎么办？〔推荐：**保留 `JsonlV4Header` 类型名，只改它的编码**〕

改名会牵动 20+ 个引用点，而它现在描述的是「本仓的第四代头」这件事本身仍成立。
但在**编码**处写清楚：落线的形状是 **pi v3**。术语上区分「内部类型名」与「落线形状」。

### D6 —— 是否顺带对齐 `Entry` 的缺型（`usage` 从 record 提为 entry）？〔推荐：**提**〕

`usage` 在 pi 是**一等 entry**、在本仓是 `record`。D2 若采纳「record → custom」，`usage` 会变成
`custom`，**与 pi 的形状差得更远**。⇒ 建议 `usage` **单独提为一等 entry**（与 D2 的其余 10 个 record 分开处置）。
这一条与 D2 一起看。

---

## 7 实施任务与测试（RED-first）

> 每步先写夹具、**看着它红**，再改实现。取值面一律用 `RecordingHttpServer` 之外的**文件读取**观测
> （本包是文件格式，直接读盘即可）。

| 步 | 内容 | 先红夹具 |
|---|---|---|
| 1 | `JsonlCodec`：新增 **pi v3 编码**（头 ＋ 扁平行），`parseHeader` 认 `type=="session"` | `PiV3HeaderWireTest`：断言本仓写出的首行 `type=="session"`、`version==3`、`timestamp` 是 ISO 串 —— 现状红 |
| 2 | `JsonlCodec.parseMutation` 认**扁平行**（无 `kind` 包裹） | `PiV3EntryWireTest`：喂一行 pi 风格的 `{"type":"message","id":…,"parentId":null,"timestamp":"…","message":{…}}` ⇒ 现状红（`has unknown mutation kind`） |
| 3 | `Entry` 落线：去 `kind`/`seq`/`lane` 包装、`timestamp` 转 ISO | `PiV3RoundTripTest`：写→读→逐字段相等，且**读出的 JSON 键集与 pi 的 `SessionEntryBase` 逐字相同** |
| 4 | `fact` → `session_info` / `label` 一等 entry | `PiV3FactsTest` |
| 5 | `usage` record → 一等 entry（D6） | `PiV3UsageEntryTest` |
| 6 | `lane` / 其余 `record` → `custom`（D2，或按裁决不落线） | `PiV3ExtensionRowsTest` |
| 7 | 装载：判别键分派 ＋ `migratePiJavaV4ToPiV3`（D4） | `PiJavaV4MigrationTest`：喂一份**真实的本仓 v4 文件**，断言迁移后首行是 `type:"session"` 且条目数不变 |
| 8 | **双向验收**：pi 侧的 `migrateSessionEntries` / `_loadEntries` 语义用夹具复刻（无法直接跑 pi） | `PiV3InteropTest`：断言「本仓写的文件里，**每一行都能被 pi 的 `parseSessionEntries` 解析且头能命中 `type==="session"`**」 |

**测试纪律**（本仓教训）：① 跨实现夹具**按键取值**，不按字节比（键序不同）；② 负向断言要**钉字段名**；
③ 变异探针每改一处**先 grep 复核落地**（CRLF 已栽 6 次）。

---

## 8 验收标准

1. `pi-java` 写出的会话文件：首行 `{"type":"session","version":3,…}`；其后每行都是 pi `SessionEntry` 的
   合法形状；**`git grep 'storageVersion'` 类历史包袱零残留**。
2. 本仓能装载 pi 写的一份**真实录制**会话文件（夹具里放一份 pi 侧的样例，逐行注明出处）。
3. **既有 3,000+ 个本仓 v4 会话**能被惰性迁移且**条目数、顺序、id 全部不变**（迁移只改编码，不动数据）。
4. 全 reactor `mvn -o clean verify` 绿；`docs/05` 的 **B60/B61 结案**。

---

## 9 不做与已知风险

- **不改文件名/目录名** —— 两侧现已一致（`docs/map/06` 实测：`${ISO}-化}_${id}.jsonl`、`--cwd--`）。
- **不动 `context_edit` 的投影语义**（D3）—— 本包只做线格式。
- **不做 pi 的 v1/v2 迁移** —— 那是 2026 年前的格式，本仓从没写过；只在读到时给出**明确错误**而非静默新建。
- ⚠️ **风险**：D2 的 `custom` 方案会让本仓的审计行进入 pi 的 `parentId` 链，可能扰动 pi 的分支/叶语义。
  若 D2′（不落线）成立，这个风险消失，代价是 pi 侧看不到本仓的审计线 —— **这正是 D2 要裁的点。**
- ⚠️ **风险**：本包是**跨 3 个模块**的改动（`agent-core` 的 codec/storage ＋ `coding-agent` 的仓储 ＋ `ai` 的
  `Message` 时间戳）。落地时**串行**跑，避免共享 `target` 假绿。
