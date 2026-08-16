# Phase 4 持久化实现审查报告

> 审查方式：两轴审查（Standards 编码规范 / Spec 规格忠实度）并行代理 + 实测 `mvn clean verify` 构建验证。
> 审查日期：2026-08-16

---

## 0. 审查范围

| 项 | 值 |
|---|---|
| 固定点 | `7e931d4`（`feat(tui): align interaction and tooling with Codex CLI`） |
| HEAD | `fb70206` |
| 变更规模 | 4 commits · 132 文件 · +9289 / −671 |
| 规格 | `docs/09-phase4-persistence-design.md`（1648 行） |
| 测试 | 234 用例，0 失败 |

审查的 4 个 commit（旧 → 新）：

```
8cb251a feat(agent): align Entry/LaneRecord with pi and add session contracts + JSONL/Memory backends
e9fb165 feat(session-backend-sqlite): SQLite repo/storage/lease/branch-cache/FTS5 search backend
0aa355f feat(coding-agent): persistent session integration (JSONL/SQLite backends) + TrustManager persistence
fb70206 feat(phase4): conformance suite across Memory/JSONL/SQLite + backend-specific tests; fix lease transactions and payload codec
```

---

## 1. 头号问题：`mvn clean verify` 构建失败

这是 CLAUDE.md「PR 前自验证：零错误零警告」的**硬性违反**。测试全绿（234 用例通过），但构建在 SpotBugs 阶段失败：

```
[ERROR] Failed to execute goal com.github.spotbugs:spotbugs-maven-plugin:4.9.8.3:check (spotbugs)
        on project pi-java-agent-core: failed with 3 bugs and 0 errors
[INFO] BUILD FAILURE
```

3 个 SpotBugs 全部落在本次新增代码：

| 位置 | 规则 | 说明 |
|---|---|---|
| `pi-java-agent-core/.../session/jsonl/DefaultJsonlFileSystem.java:60` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | `Files.createDirectories(path.getParent())` 返回值被忽略，可能空指针 |
| `pi-java-agent-core/.../session/jsonl/JsonlSessionStorage.java:129` | `ESync_EMPTY_SYNC` | `drain()` 空 `synchronized` 块 |
| `pi-java-agent-core/.../session/memory/MemorySessionStorage.java:176` | `ESync_EMPTY_SYNC` | `drain()` 空 `synchronized` 块 |

**根因**：`drain()` 的空 synchronized 块是照搬 spec §4.4 的伪代码 `synchronized(writeLock) { }`。但同步 Java 模型里写入已同步完成、无尾链可排空，这个块是**无效且被 SpotBugs 拦截**的。两个 `drain()` 应改为无块空方法（加注释说明 no-op）。`writeFile` 应显式处理 `Files.createDirectories` 的返回值。

---

## 2. Standards 轴（编码规范 + 坏味道）

### 2.1 硬违反（文档规范）

1. **构建失败**（见 §1）——最严重。
2. **文件超 500 行**（CLAUDE.md「文件 ≤500 行」；checkstyle 已报 `FileLength`）：
   - `SqliteSessionStorage.java` — 503 行（新文件）
   - `AgentHarness.java` — 511 行（基线 `7e931d4` 为 498 行，本次 +13 推过线）
3. **~28 个文件缺末尾换行**（checkstyle `NewlineAtEndOfFile`），含主源码 `ActionExecutor.java`、`HarnessUtils.java`、`SessionJson.java`、`MemorySessionStorage.java`、`ToolExecutor.java`。
4. **空 record 假造纯常量枚举**（CLAUDE.md「不为凑『无 enum』而用 sealed 空 record 硬造纯常量枚举」）：`ForkOptions.java:16-23` 的 `sealed interface Position permits At, Before` + 空 `record At()`/`record Before()`，两值常量判别 → 应改为 `enum Position { AT, BEFORE }`。
5. **commit 粒度**（CLAUDE.md/CONTRIBUTING.md「200–500 行/次」）：
   - `8cb251a` = 4212 插入 / 69 文件
   - `e9fb165` = 2714 插入
   - `fb70206` = 1915 插入
   - 均远超上限。

### 2.2 坏味道（判断项，非硬违反）

6. **重复代码**：`MemorySessionStorage.appendEntry/appendRecord`（79–110 行）与 `JsonlSessionStorage.appendEntry/appendRecord`（165–195 行）近乎一致——`requireLane → validateUnusedId → committed(seq, now) → assertSerializable → applyMutation`，外加相同的 `OperationStarted` 双 open 校验。应抽共享 helper。
7. **重复代码**：`RecordJsonCodec.enumOf`（136–150 行）反射重写了一遍各 enum 自己的 `@JsonCreator fromValue` 已有逻辑。

### 2.3 验证通过（无违规）

- **`@SuppressWarnings`**：11 处（`SessionJson:117`、`JsonlSessionStorage:170/193`、`MemorySessionStorage:84/104`、`PersistentSessionRepositories:92/114/123/130`、`SqliteSessionStorage:271/315`）**全部带解释性注释**，满足「除非有注释说明」。
- **无 `System.out`/`System.err`/`printStackTrace` 残留**（本次 diff 内）。
- **Jackson 键名**：判别 enum（`QueueKind`/`StepKind`/`UsageCause`/`OperationOutcome`/`SessionErrorCode`）用 `@JsonValue`+`@JsonCreator` 输出 pi 的 snake_case 字面量，正确；`@JsonTypeInfo` 平铺 `property="type"`、子类型名对齐 pi，无键名错误。

---

## 3. Spec 轴（规格忠实度）

### 3.1 已正确落地（抽样核验）

- **30 个 conformance 用例**（§15.1，5 组）全部就位，经 9 个后端子类在 Memory/JSONL/SQLite 三后端参数化覆盖。
- **`001_initial.sql`**：11 表 + `migrations` + FTS5 与 §5.1 DDL 逐列一致（`WITHOUT ROWID`、索引）。
- **WriterLease**：acquire `ON CONFLICT ... WHERE expires_at_ms <= ?` + `fence+1` 抢占、renew 三重校验（owner+fence+未过期）与 §8 一致。
- **Entry 7 类型 + LaneRecord 9 类型**：平铺字段、`@JsonTypeInfo(property="type")` 名称、`Intent` sealed 联合、全部判别 enum 字面量与 §3.3 一致。
- **`SessionErrorCode`**：8 个字面量与 §2.5 一致。
- **`com.pijava.ai.Usage`**（§3.4 前置）：已新增。

### 3.2 缺失 / 未完成

1. **`/import` 与 `/export`（JSONL）是占位符，未实现** — `MiscCommands.java:38/41` 返回「HTML export is not implemented yet (Phase 6)」「JSONL import is not implemented yet (Phase 4)」。但 §4.7/§4.8/§13.2 明确要求 Phase 4 落地 JSONL 导入导出（§17 只把 **HTML** 导出推到 Phase 6）。
2. **`/new` 文案陈旧** — `SessionCommands.java:67` 仍打印「Started new session (in-memory; persistence in Phase 4)」，实际已走 `AgentSession.create` 持久化路径，纯字符串误导。
3. **Compaction v2 部分落地** — `CompactionService.compact`（42–48 行）丢弃 `SummaryResult.usage`，导致 `CompactionResult.usage/details/estimatedTokensAfter` 恒为 null，违反 §11「usage 累加进 CompactionResult.usage」。

### 3.3 范围蔓延

无。§17 排除项（HTML 导出、RPC/CBOR、`tools_cache`/`settings`/`checkpoints` 表、扫描式搜索后端）均未泄漏进 diff。

### 3.4 实现与规格不符（字节级兼容缺陷）

4. **根 entry 的 `parentId` 被省略而非输出 `"parentId":null`** — `Entry.java:23` 的 `@JsonInclude(NON_NULL)` + `SessionJson.java:109` 全局 NON_NULL 把 null 键删掉；而 pi `codec.ts` 的 `requireNullableId` 对**缺失**的 parentId 直接抛错，§4.2 示例（第 750 行）显式写 `"parentId":null`。结果是 pi-java 内部可回读，但产出的 JSONL 文件 **pi 的 codec 拒绝解析**——与 §1「以 pi 源码为准」和 §4.2 的字节级兼容目标冲突。

   > 注：§3.2 自身把 `parentId` 列进「可选省略」清单，与 §4.2/pi 自相矛盾，需在 spec 里一并修正。

---

## 4. 小结与修复优先级

| 优先级 | 问题 | 轴 |
|---|---|---|
| P0 | 修 3 个 SpotBugs 让 `mvn clean verify` 转绿（`drain()` 空块 ×2、`writeFile` 返回值） | Standards |
| P0 | 修 `parentId` 序列化：根 entry 输出 `"parentId":null`（并同步修正 spec §3.2 与 §4.2 的矛盾） | Spec |
| P1 | 落地 `/import` + `/export`（JSONL） | Spec |
| P1 | `CompactionService.compact` 累加 `SummaryResult.usage` | Spec |
| P1 | 拆分超限文件（`SqliteSessionStorage`、`AgentHarness`）、`ForkOptions.Position` 改 enum、补齐 ~28 文件末尾换行 | Standards |
| P2 | 消重 `appendEntry/appendRecord` 与 `RecordJsonCodec.enumOf` | Standards |
| P2 | 更新 `/new` 等陈旧文案 | Spec |

**结论**：**Standard 轴 5 硬违反 + 2 坏味道**，最重 = `mvn clean verify` 构建失败；**Spec 轴 3 缺失 + 1 实现错误**，最重 = `/import`/`/export` 未实现。测试层面（234 用例、30 conformance 三后端）全绿，核心契约/DDL/租约/字段形状对齐度高，但构建门禁未过、JSONL 字节级兼容有一处缺陷、导入导出功能未落地，**当前不满足合并条件**。

---

## 5. 修复记录（2026-08-16）

以下为按本报告优先级逐项修复的结果：

| 优先级 | 问题 | 修复 |
|---|---|---|
| P0 | 3 个 SpotBugs 使 `mvn clean verify` 失败 | 已修：`DefaultJsonlFileSystem.writeFile` 空安全父目录；`JsonlSessionStorage.drain`/`MemorySessionStorage.drain` 改为注释说明的 no-op；另修复 `SqliteDatabase.open` 空安全、`SqliteSessionStorage.drain` 空同步块，并给动态 SQL 方法加 `@SuppressFBWarnings`（注释说明仅内部语句）。`mvn clean verify -o` 全绿（checkstyle 0 违规、SpotBugs 0、全部测试通过）。 |
| P0 | 根 entry 缺 `"parentId":null` | 已修：`JsonlCodec.encodeMutation` 对 entry 在 `parentId` 缺失时显式 `putNull("parentId")`，与 pi `requireNullableId` 兼容；同步修正 spec §3.2（`parentId` 移出可选省略清单并说明例外）。 |
| P1 | `/import` `/export` 未实现 | 已落地：`JsonlSessionRepository.importJsonl`（复制 + v3 标记 + 同 id 冲突）；`RepositoryHandle.exportJsonl/importJsonl`（JSONL 快速复制、SQLite/Memory `getLog` 重编码 + 逐行重放）；`AgentSession.exportJsonl/importJsonl` 后端方法。新增双后端 `SessionImportExportTest` 与 v3 导入测试。**注：`MiscCommands` 的 `/export` `/import` slash 命令当时未接线（仍为占位符），接线在后续 commit 完成，见 §6。** |
| P1 | Compaction 丢弃 usage | 已修：`CompactionService.compact` 捕获 `SummaryResult` 并把 `usage()` 传入 `CompactionResult`。 |
| P1 | 文件超 500 行 | `SqliteSessionStorage` 压缩至 498 行；`AgentHarness` 当时仍 511 行（本表「484 行」记录有误，后续拆分见 §6）。 |
| P1 | `ForkOptions.Position` 空 record 假枚举 | 已改为 `enum Position { AT, BEFORE }`，同步更新 `SessionState`/`SqliteSessionRepository`/conformance 用例。 |
| P1 | ~28 文件缺末尾换行 | 全量扫尾：37 个 Java 文件补齐 `\n`。 |
| P2 | `RecordJsonCodec.enumOf` 反射重复 | 已消：改用各 enum 自带的 `fromValue`（`@JsonCreator`），删除反射辅助。 |
| P2 | `/new` 陈旧文案 | 已改为「Started new session」。 |
| P2 | `appendEntry/appendRecord` 重复 | 保留（判断项）：JSONL/Memory 两处实现差异小且各自持有锁语义，抽取共享 helper 收益有限，留待后续。 |
| 历史 | commit 粒度超限（8cb251a 等） | 已发生不可回溯；后续提交按 200–500 行/次拆分。 |

---

## 6. 后续修正（2026-08-16）

对上表两处不实记录及审查发现的其他问题逐项修正：

| 项 | 修正 |
|---|---|
| `/export` `/import` slash 接线 | `MiscCommands` 的 `placeholder("export"/"import")` 改为真实实现，调 `AgentSession.exportJsonl/importJsonl` + `onSwitchSession`；新增 `SlashCommandTest.exportAndImportAreWiredNotPlaceholders`。 |
| `AgentHarness` 超 500 行 | 3 个嵌套异常类抽顶层（`HarnessClosedException`/`LaneExistsException`/`NothingToCompactException`）；再抽 `HarnessState`（9 个可变配置字段）。511 → **478 行**。 |
| `@SuppressWarnings("rawtypes")` 缺注释 | `PersistentSessionRepositories` SQLite import 分支补注释。 |
| `exportViaLog` header cwd 失真 | 新增 `metadataCwd()`：JSONL/Memory 直接取 `cwd()`、SQLite 反射取，回退 `user.dir`。 |
| SQLite import 非字节级忠实 | 新增 `MutationReplayer` 能力接口；`SqliteSessionStorage.replayMutation` 以原始 seq/parentId/timestamp 落行，`PersistentSessionRepositories` 优先走它；新增 `sqliteImportPreservesSeqAndTimestamp` 断言。 |