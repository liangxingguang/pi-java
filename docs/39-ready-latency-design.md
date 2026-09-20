# 39 - 包⑫：web `ready` 帧时延（台账 A12 根因）

> **文档约定**（用户 2026-09-19）：一个功能模块一份文档；`docs/32` 是唯一索引。
> 流程：**① 命题表 → ② 逐条代码验证 → ③ 实施稿**；**用户审核 §6 之后才写代码**。
>
> **判据**：分支所有功能都和 pi 表现一样（行为，不是文档、不是 API 形状）。
>
> **本文件当前状态**：根因**已定位（实测）**；§6 已由用户**批准（2026-09-20，「按照推荐实施」= 裁决 A 做、B 做、C 不动）**；
> **已实施并验证**，见 **§8 实施记录**。裁决 C（删你 home 里的存量残渣）**仍未动**。

---

## 1. 这一包解决什么

A12 三次全 reactor 偶发红、记了两轮、**根因一直未定位**（`docs/31:1625`、`:2593`、`docs/34 §10.7`、
包⑪ 又补了一次证据）。本包把它定位到**一条确定性的 O(全部会话文件) 成本**，
并给出与 pi 对齐的修法。

---

## 2. 根因（一句话）

**`ready` 压在 `onOpen` 的同步路径上，而它前面那条链的最后一步，会打开会话根下
「每一个项目目录」里的「每一个 `.jsonl`」。**

调用链（全部本轮读过）：

```
PiWebServer.onOpen:116
  └─ AgentSession.createWeb(args)                       PiWebServer.java:127（同步）
       └─ SessionPersistence.resolvePersistentWeb       AgentSession.java:156
            └─ handle.latest()                          SessionPersistence.java:152
                 └─ repo.list(JsonlSessionListOptions.all())   PersistentSessionRepositories.java:59
                      └─ sessionDirectories(null)       JsonlSessionRepository.java:71 → :226-238
                           = sessionsRoot 下**全部**子目录（＝全部项目）
                      └─ 每个 *.jsonl 一次 fs.readTextLines(path, 1)   JsonlSessionRepository.java:77
   ← createWeb 返回后才 dispatcher.start() → 发 ready    WebDispatcher.java:61-64
```

`readTextLines(path, 1)`（`DefaultJsonlFileSystem.java:41-54`）只读第一行，**成本在「打开文件」本身**。

---

## 3. 证据（全部实测，不是推断）

### 3.1 端到端：`ready` 时延的两臂对照

临时探针 `A12ReadyLatencyProbe`（用完即删），同一 JVM、同一台机、背靠背、预热后各 3 次：

| 臂 | 会话根 | `createWeb` 本体 | `ready` 时延 |
|---|---|---|---|
| **REAL** | 真 `~/.pi-java/agent/sessions`（2125 个 jsonl） | 546 / 489 / 432 ms | **486 / 428 / 442 ms** |
| **EMPTY** | 空临时根 | 31 / 13 / 6 ms | **11 / 10 / 10 ms** |

⇒ **45×**，且 `ready` 与 `createWeb` 几乎相等 ⇒ 时延全在 `createWeb` 里，服务器脚手架可忽略。

### 3.2 拆解：是哪一段

临时探针 `A12PhasesProbe`（用完即删）：

| 测什么 | 实测（3 轮） |
|---|---|
| `list(all())` @ 真根，**2125 个文件** | **1082 / 756 / 608 ms** |
| `list(cwd)` @ 真根，805 个文件 | 266 / 296 / 260 ms |
| `list(all())` @ 空根 | 5 / 0 / 0 ms |
| 打开最新会话 + 读 entries | **1 / 1 / 98 ms**（entries=2） |

⇒ **`list` 就是全部**。折算 **~0.3–0.5 ms/文件** ⇒ 成本是**文件数**的线性函数，与文件大小无关。

### 3.3 文件数从哪来：一个自放大回路

真根 2125 个文件里 **1825 个 ≤300 字节**（内容＝header ＋ 一条 lane 记录，无任何消息）。

目录名就是**Maven 模块目录**：`--D--workplaceForai-pi-java-pi-java-tui--`（1314 个）、
`--D--workplaceForai-pi-java-pi-java-coding-agent--`（805 个）。

**写入者已指名**：`PiTuiAppInputTest` 的 10+ 个用例（`:40`、`:95`、`:143`、`:202`、`:245`、
`:295`、`:348`、`:407`、`:575` …）都这样开会话：

```java
try (var session = AgentSession.create(ArgsParser.parse(new String[] {})))   // ← 空 args
```

空 args ⇒ 既无 `--session-dir` 也无 `--no-session` ⇒ `SessionSetup.java:73` 走
`resolvePersistent` ⇒ 无 `-c/-r/--fork` ⇒ `handle.create(user.dir, null)`
⇒ **在开发者真实 home 里落一个真实会话文件**（`user.dir` 在 surefire 下＝模块目录）。

⚠️ **实施时把这份名单重新实测了一遍，两处与初稿不符**（见 §5-R5）：

| 模块 | 真实写入者（实测） | 站点数 |
|---|---|---|
| `pi-java-tui` | `PiTuiAppInputTest`(10)、`PiTuiAppInlineTest`(1)、`PiTuiAppScrollTest`(1)、`PiTuiAppSessionChannelTest`(1) | **13** |
| `pi-java-coding-agent` | `AgentSessionTest`(4)、`SlashCommandTest`(1) | **5** |
| ❌ 初稿说 `SettingsScreenTest`(2) 同形 —— **错** | 它用 `withIsolatedHome` 把 `user.home` 指到临时目录，**本来就不写真实 home** | 0 |

判据不是「args 里有没有 `--session-dir`」，而是**「这轮的 `user.home` 落在哪」**：
`AgentSessionRetryEventOrderTest` / `SessionRunnerRetryContextTest` / `RpcDispatcherTest`
也传空 `--no-session`、看着可疑，但它们同样改了 `user.home` ⇒ 实测**不写**。

⚠️ 注意这条链的非对称：**夹具写但不读**（`create` 不走 `latest()`），
**web 路径读但不写** ⇒ 症状只出现在 web，而病因由夹具滚雪球。

⇒ **回路**：跑一次全量 → 多出上百个文件 → `list(all())` 变慢 → 14 模块负载下越过
`PiWebServerAuthTest` 的 15 s 窗。**非确定性 ✓ 负载敏感 ✓ 随时间恶化 ✓ 早于包① ✓**
—— 台账记的四条特征全部由这一条机制解释。

### 3.4 pi 侧（判据）

| # | pi 事实 | 出处 |
|---|---|---|
| 1 | `continueRecent(cwd, sessionDir?)`：`dir = sessionDir ?? getDefaultSessionDir(cwd)` —— **一个目录** | `session-manager.ts:1589-1597` |
| 2 | `getDefaultSessionDir(cwd)` = `agentDir/sessions/--<编码后的 cwd>--` ⇒ **按项目分目录** | `:476-486` |
| 3 | `findMostRecentSession(sessionDir, cwd?)` 只 `readdirSync` **那一个目录** | `:636-655` |
| 4 | 跨项目的 `listAll` **确实存在**，但只在 `-r` 交互选择器里作为**显式切换**的一档，且带 `onProgress`（**异步**） | `main.ts:412-416`、`:1590-1592` |

⇒ **pi 把「全项目列表」当交互选择器的一档；pi-java 把同一件事当成了「取最近一个会话」的实现。**
这不是「性能优化」问题，是**范围越界**的偏差 —— 它顺带解释了为什么时延会没上限地涨。

---

## 4. 命题表

| # | 命题 | 出处（本轮读） | 判定 |
|---|---|---|---|
| P1 | `ready` 在 `createWeb` 之后才发 | `PiWebServer.java:127`、`WebDispatcher.java:61-64` | **已核** |
| P2 | `latest()` 走 `all()` ⇒ 全部项目目录 | `PersistentSessionRepositories.java:58-61`、`JsonlSessionRepository.java:226-238` | **已核** |
| P3 | 每个 `.jsonl` 被打开一次（只读首行） | `JsonlSessionRepository.java:77`、`DefaultJsonlFileSystem.java:41` | **已核** |
| P4 | 成本 ≈ 文件数 × 0.3–0.5 ms | §3.2 实测 | **已核** |
| P5 | pi 的「取最近一个」只看一个项目目录 | `session-manager.ts:1589/483/636` | **已核** |
| P6 | 夹具在真实 home 落盘 | `PiTuiAppInputTest.java:40` 等 + 1825 个 ≤300 B 残渣 | **已核** |
| P7 | `resolvePersistentWeb` 手上已有 `cwd`（`:151`），只是没传给 `latest()` | `SessionPersistence.java:151-152` | **已核** |
| P8 | 另两个调用点同形：`-c`（`:168`）、`find`（`:52`） | grep `.latest()` / `all()` 全部调用点 | **已核** |

---

## 5. 被推翻的命题（含我自己写错过的那条）

| # | 原先的说法 | 实测 | 后果 |
|---|---|---|---|
| **R1** | 台账 A12 行里我写的「`ready` 时延＝`AgentSession.createWeb` 全量耗时（**settings 载入＋模型目录＋会话恢复**）」 | **错**：settings/providers 是毫秒级；730 ms 里 **~430 ms 是 `list(all())` 的文件打开**，打开最新会话只占 1–98 ms | §7 要改台账那一句 |
| **R2** | 「负载把某个固定成本放大了 10 倍」（隐含） | **半错**：放大的是**一个随文件数单调增长**的成本 ⇒ 它不是「偶发噪声」，是**会一直变坏**的确定性成本 | ⇒ 修法必须同时止血（不再写进真 home） |
| **R3** | 「打开那个 710 KB / 306 KB 的大会话是主因」 | **错**：最新会话是 213 B 的残渣，打开 + 读 entries 只 1–98 ms | 不用去优化会话加载 |
| **R4** | 「A12 未定位根因，可能是环境问题」 | **错**：机制完全确定，`EMPTY` 臂 10 ms 是它不存在时的样子 | 不需要「加超时」那种掩盖 |
| **R5** | §3.3 初稿的两条：「会话残渣目录只有 tui(1314) 与 coding-agent(805)」；「`SettingsScreenTest`(2) 与 `PiTuiAppInlineTest`(1) 同形」 | **半错**：① 还有第三个（`--D--workplaceForai-pi-java--`，6 个）；② 写入者名单实测为 **tui 4 个类 13 站点 + coding-agent 2 个类 5 站点**，而 `SettingsScreenTest` **不在其中**（它改了 `user.home`） | §3.3 已就地更正；**修法判据随之更正为「这轮的 `user.home` 落在哪」**，否则会去修一个本来就干净的夹具、又漏掉两个真写入者 |
| **R6** | 「`latest()` 的跨项目扫描只是**性能**问题」 | **错**：它是**行为偏差** —— 项目 A 里 `-c` / web `ready` 会恢复**项目 B** 的会话。证据是实施时撞出来的：改了范围后 `SessionResumeFoldTest` / `SessionResumeCompactionTest` **编译期红**，因为它们的夹具用字面量 `"cwd"` 建会话，**只有在越界扫描下才碰巧找得到** | 两处夹具改用与 resume 路径同一个 cwd；**这条红是范围改动的第一份牙**（见 §8.2） |

---

## 6. 实施稿（**待用户审核**）

### 6.0 三个裁决点

| # | 裁决点 | 我的建议 | 备选 |
|---|---|---|---|
| **A** | **扫描范围**：`latest()` 改成按**当前项目 cwd** 取（对齐 pi 的 `continueRecent`） | **做**。一处签名小改（`latest()` → `latest(cwd)`），直接消掉 45× 时延，且修的是**行为偏差**（现在 `-c` 在项目 A 可能恢复项目 B 的会话） | 只在 web 路径特判（留着 `-c` 的偏差）—— 不推荐，两处不同形 |
| **B** | **夹具卫生**：TUI 那 13 处 `create(new String[]{})` 改成 `@TempDir` 的 `--session-dir` | **做**。否则回路的**输入端**还在，文件还会涨（修 A 只是让每次扫描更便宜，不阻止垃圾增长） | 改 `--no-session`（更简，但会让夹具跑在**内存会话**上＝与生产不同路，正是 A16 那类问题） |
| **C** | **存量清理**：真 home 里那 1825 个 ≤300 B 残渣删不删 | **建议你点头再删**。识别规则：位于 `~/.pi-java/agent/sessions/--D--workplaceForai-pi-java-pi-java-*--/`（＝Maven 模块目录，不是你实际用的 cwd）且 ≤300 B。⚠️ 这是**你的数据目录**，我不会未经许可动手 | 不删（那就只靠 A＋B 止住增长，存量 1825 个仍值 ~0.6 s；按 A 修完它们**不再被扫到**，实际影响≈0） |

> 裁决 C 与本包的正确性无关：**A 修完后这些文件不会再被任何路径打开**。它只是「要不要顺手把垃圾清了」。
>
> **用户裁决（2026-09-20）：「按照推荐实施」** ⇒ **A 做、B 做**；
> **C 不动** —— 我的「建议」本身就是「你点头再删」，所以这句话不构成对数据目录的授权，
> 存量 1830 个残渣**一个都没删**（见 §8.5）。

### 6.1 分步（每步一个可独立编译的模块 ⇒ 一次提交）

| 步 | 模块 | 内容 |
|---|---|---|
| **1** | `pi-java-coding-agent` | `RepositoryHandle.latest()` → `latest(String cwd)`（JSONL 用 `JsonlSessionListOptions(cwd)`、SQLite 用既有 `sqliteListOptions(cwd)`）；`SessionPersistence.resolvePersistentWeb`/`resolvePersistent` 把手上已有的 `cwd` 传进去 |
| **2** | `pi-java-tui`＋`pi-java-coding-agent` | 夹具改用 `@TempDir` 的 `--session-dir`（裁决 B）：**实测 18 站点** —— tui 13（§3.3 表）＋ coding-agent 5（同表；**初稿漏了这一个模块**） |
| **3** | `docs/39` ＋ `docs/32` | 实施记录 ＋ **A12 结案** ＋ 更正 §5-R1 那句错记述 ＋ 新登记 B53/B54（§6.4） |

### 6.2 测试计划（**先红**；本项目不加计时断言）

| # | 夹具 | 钉什么 | 有牙吗 |
|---|---|---|---|
| ① | `LatestSessionScopeTest`（agent-core）：注入一个**计数的** `JsonlSessionRepoFileSystem` 装饰器（`JsonlSessionRepository(Path, fs)` 构造器已是公开的注入口），临时根下造 3 个项目目录 × 2 个会话 | `latest(cwd)` 打开的**文件数 == 本项目目录里的文件数**（2），不是 6 | ✅ 确定性计数，**不是**计时 |
| ② | 同文件或用例：本项目的会话较**旧**、别的项目的会话较**新** | 恢复出的是**本项目**那个 | ✅ 旧行为会恢复别人的（既慢又错） |
| ③ | `SessionPersistence` 侧：temp 根 + 当前 cwd 一个会话 + 另一项目一个**更新**的会话 | `resolvePersistentWeb` 选本项目那个 | ✅ |
| ④ | 变异探针：把 `latest(cwd)` 改回 `all()` | ①②③ 红 | **红集跑完实测记录，不预测** |

### 6.2b 实测（不是预测）

| 探针 | 实测结果 |
|---|---|
| **④ 变异**：`latest(cwd)` 退回 `repo.list(all())` | 跑**整个 coding-agent 模块**：`Tests run: 266, Failures: 3` ⇒ **红集 = `LatestSessionScopeTest` 全部 3 条，其余 263 条全绿**。断言原文见 §8.2（① 打开 **6** 个文件而非 2 个；② 返回了 `c2` 而非 `a2`；③ 恢复出的是别的项目的会话） |
| **① 计数探针的口径** | 变异下 `readFileNames()` 实测拿到 **6 个文件名**（3 个项目各 2）⇒ 它数的确实是「打开了几下文件」，不是别的 |
| **夹具先红（R6 那条）** | 范围改动落地、夹具未改时：`SessionResumeFoldTest`/`SessionResumeCompactionTest` **编译期就红**（`latest()` 已无此重载）—— 这暴露的是**夹具依赖越界扫描**（§5-R6），不是范围改动错了 |

### 6.3 明确不做（含理由）

| # | 不做 | 理由 |
|---|---|---|
| 1 | **加超时 / 重试 / 提前发 `ready`** | 那是掩盖：`ready` 提前发会改客户端的就绪语义（`ready` 现在蕴含「会话已建好」），且 pi 没有这种形状 |
| 2 | 缓存 `list` 结果 / 建索引文件 | pi 没有；`findMostRecentSession` 每次都老实 readdir。加了就是**自造契约**，日后必分叉 |
| 3 | 把 `list` 改成异步 / 并发读 | pi 的 `listAll` 有并发与 `onProgress`，但那是**交互选择器**的车道；本包要修的是「范围」，不是「把越界扫描做快」 |
| 4 | 动 `find(idOrPrefix)` | 见 §6.4-B53：改它会动 `--session <id>` 的跨项目语义，需单独裁决 |

### 6.4 新登记（本包顺带发现，**不在本包修**）

| # | 登记 | 出处 | 为什么另办 |
|---|---|---|---|
| **B53** | `PersistentSessionRepositories.find()`（`:52`）**同样走 `all()`** ⇒ `--session <id>` / `--session-id` 也是 O(全部会话文件)；pi 的对应物 `findLocalSessionByExactId(id, cwd, sessionDir)` 是**项目内** | `:47-55`、`main.ts:432` | 改它＝改 `--session` 的**跨项目查找语义**（用户可能真靠它找别的项目的会话）⇒ 行为裁决，单独一包 |
| **B54** | **`--no-session` 在 web 路径被完全忽略**：`resolvePersistentWeb` 的 `args` 形参**一句话都没用**，所以 `--mode web --no-session` 仍会建持久会话（`PiWebServerAuthTest` 正是这么传的） | `SessionPersistence.java:149-162` | `--mode web` 的「总是持久化」是设计选择（刷新/重连不丢历史，见其 javadoc）；但 `--no-session` 与它冲突时谁赢**没定义** ⇒ 要你裁 |
| **B55** | pi 的会话列表 API 带 `onProgress`（异步、可报进度），pi-java 的 `list` 是同步全量 | `session-manager.ts:812`、`:772` | 交互式选择器（TUI/RPC）未对照过，属另一条车道 |

### 6.5 未覆盖（如实登记）

- **不测真实 provider 的会话**：与 A12 无关。
- **不加「`ready` 在 X 毫秒内」这类断言**：计时断言在负载下必 flake，本项目既有纪律是不写（`docs/31 §8.23` 的教训）；本包用的是**文件打开次数**这个确定性代理量。
- **不承诺别的 flake 一起消失**：A12 是「症状家族」的**已证机制**，但台账里另两条同症状记录（`docs/31:1625`、`:2593`）**没有当时的文件数快照**，我无法回溯证明它们同因；本包只能保证「这条机制被消除」。

---

## 7. 下一步

**已批准（2026-09-20）并实施完毕** ⇒ 见 §8。

---

## 8. 实施记录（2026-09-20）

### 8.1 落地的改动

| 步 | 模块 | 改动 |
|---|---|---|
| **1** | `pi-java-coding-agent` | ① **生产**：`RepositoryHandle.latest()` → `latest(String cwd)`（JSONL 走 `JsonlSessionListOptions(cwd)`、SQLite 走既有 `sqliteListOptions(cwd)`）；`SessionPersistence.resolvePersistentWeb`/`resolvePersistent` 把手上已有的 `cwd` 传进去；`AgentSession.latestSession()`（**无调用者的死代码**）跟进同一语义。<br>② **测试**：新夹具 `LatestSessionScopeTest`（3 条）；`SessionResumeFoldTest`/`SessionResumeCompactionTest` 的夹具 cwd 改成本模块目录（§5-R6）；`AgentSessionTest`(4)/`SlashCommandTest`(1) 改用 `@TempDir --session-dir`。<br>⚠️ **一条超出「纯修」的生产改动**（同 A13 的做法，请知悉）：`PersistentSessionRepositories` 加了一条**包私有**重载 `jsonl(Path, JsonlSessionRepoFileSystem)`，唯一用途是让夹具能注入**计数**文件系统（= 本包要的确定性代理量）。原有 `jsonl(Path)` 原样委托，**生产行为零变化**。 |
| **2** | `pi-java-tui` | 13 个夹具站点改用 `@TempDir --session-dir`（4 个类；`PiTuiAppScrollTest` 用**静态** `@TempDir`，因为会话在其静态助手 `start()` 里建） |
| **3** | `docs/39`＋`docs/32` | 本记录 ＋ A12 结案 ＋ §5-R1/R5/R6 更正 ＋ B53/B54/B55 登记 |

### 8.2 牙：变异探针的**实测**红集

**唯一变异点**：`latest(cwd)` 退回 `repo.list(JsonlSessionListOptions.all())`（＝复现原缺陷）。

跑**整个 `pi-java-coding-agent` 模块**：`Tests run: 266, Failures: 3, Errors: 0` ⇒

- 红集＝`LatestSessionScopeTest` **全部 3 条**，**其余 263 条一条不红**。
- 三条红的断言原文（说明它红在**机制**上，不是红在噪音上）：
  - ① `latestOpensOnlyThisProjectsSessionFiles` —— 期望只打开本项目 2 个文件，实际打开 **6 个**（3 个项目全扫了）。
  - ② `latestPrefersThisProjectsSessionOverANewerOneElsewhere` —— 期望 `a2`，实际返回别的项目的 `c2`。
  - ③ `resumeAttachesToThisProjectsSessionNotANewerOneElsewhere` —— 恢复出的 entry id 属于**别的项目**的会话。

**另一份牙（不是探针，是改动本身撞出来的）**：范围改动落地而夹具未跟进时，
`SessionResumeFoldTest`/`SessionResumeCompactionTest` **编译期红** —— 它们用字面量 `"cwd"` 建会话，
只在越界扫描下才碰巧被 `latest()` 找到。这是 §5-R6（范围越界＝行为偏差）的第一手证据。

### 8.3 端到端：改前 / 改后（同一台机、同一探针，用完即删）

| 量 | 改前 | 改后 | 倍数 |
|---|---|---|---|
| **REAL 臂 `ready` 时延**（`user.dir`＝pi-java-web 模块目录） | 486 / 428 / 442 ms | **20 / 16 / 14 ms** | **~30×** |
| REAL 臂 `createWeb` 本体 | 546 / 489 / 432 ms | 10 / 21 / 10 ms | ~40× |
| EMPTY 臂（空会话根）`ready` | 11 / 10 / 10 ms | 14 / 11 / 13 ms | 不变 |
| `list(all())` @ 真根（2135 文件） | 1082 / 756 / 608 ms | 1123 / 862 / 645 ms（**本包没动它**） | —— |
| `list(cwd)` @ 真根（coding-agent 目录 815 文件） | 266 / 296 / 260 ms | 283 / 235 / 224 ms | —— |
| `handle.latest(cwd)` @ 真根 | （旧签名＝上面的 `all()`） | 255 / 255 / 234 ms | —— |

**读法**：`ready` 从 ~450 ms 掉到 ~16 ms，**与空根同档**（14/11/13）⇒ A12 那 15 s 窗口的成因被消除。
REAL 臂在网络模块目录下只剩 1 个会话文件（见 §8.4），所以它≈EMPTY。

### 8.4 夹具卫生：真实 home 的文件数（实测，不是推断）

判据＝**跑一轮相关测试，看真实 home 的文件数变不变**：

| 运行 | 改前 | 改后 |
|---|---|---|
| `-pl pi-java-coding-agent`（`AgentSessionTest`＋`SlashCommandTest`） | **+5** 个文件/轮（4×213 B ＋ 1×265 B） | **+0** |
| `-pl pi-java-tui`（4 个夹具类，17 用例全绿） | +13 个文件/轮（**这就是 1314 那堆的来源**） | **+0** |

⇒ 回路的**输入端**关掉了。两个模块的相关用例全绿（coding-agent 25/25、tui 17/17）。

### 8.5 裁决 C：**你的数据目录一个文件都没动**

存量（`~/.pi-java/agent/sessions/`，2026-09-20 实测）：

| 项目目录 | 文件数 |
|---|---:|
| `--D--workplaceForai-pi-java-pi-java-tui--` | 1314 |
| `--D--workplaceForai-pi-java-pi-java-coding-agent--` | 815 |
| `--D--workplaceForai-pi-java--` | 6 |
| `--D--workplaceForai-pi-java-pi-java-web--` | 1（**新增**：修完范围后 web 才第一次有自己的会话；改前它总是恢复别人的） |

**未删任何文件**。A 修完后它们不再被 `--web` 路径扫到；B 修完后它们不再增长。
要清理是独立的一次动作，等你点头。

### 8.6 未覆盖（如实登记）

- **不承诺别的 flake 一起消失**（同 §6.5）：台账里另两条同症状记录（`docs/31:1625`、`:2593`）没有当时的文件数快照，我回溯不了。本包能保证的是**这条机制被消除**。
- **没有加任何计时断言**：夹具用的是「打开了几下文件」这个确定性计数。
- **`docs/32` 的 A 类计数是陈旧的**（记 11，实测 16 行 / 未结 13）—— 本次复核后更正为 13，见 A12 结案行。
- **B53（`find()` 仍走 `all()`）、B54（`--no-session` 在 web 路径被忽略）、B55（pi 的 list 带 `onProgress`）** 均**未修**，只登记。
- **裁决 C 未执行**（§8.5）。

### 8.7 全量验证：**没跑绿，原因与本包无关**（如实记录）

`mvn clean verify`（14 模块）在 **`pi-java-session-backend-sqlite`** 停下：

```
SqliteConformanceGroup1Test.concurrentLaneWritesSerializeWithDistinctSequences
  Time elapsed: 1990 s  <<< ERROR!  SessionError: SQLite session writer lease was lost
  at SqliteSessionStorage.lostWriterError(:426) ← enqueueWrite ← SqliteDatabase.transaction
  ← Session.createLane(Session.java:66) ← ConformanceGroup1Test:147
```

**为什么可以判定与包⑫ 无关**（三条独立证据）：

1. **依赖方向**：本包改动只在 `pi-java-coding-agent`（生产＋测试）与 `pi-java-tui`（测试）；
   `session-backend-sqlite` 依赖 `agent-core`，**不依赖 coding-agent**。
2. **reactor 次序**：它 **FAILURE** 时，`Coding Agent` / `TUI` 还是 **SKIPPED** —— 我的模块**根本没被编译**。
3. **隔离复跑**：`-pl pi-java-session-backend-sqlite -am -Dtest=SqliteConformanceGroup1Test`
   ⇒ **16/16 绿、1.609 s**。

⇒ 症状（写事务抛租约丢失、挂在第 147 行**线程还没起**的地方、1990 s）与 A12 是**两回事**，
已登记为 **A17**（`docs/32`），按同一纪律**先定位再修，不先加超时**。

**本包自己的验证**改成按模块做（见 §8.2/§8.4 的实测），另跑一次**受影响子树的 `clean verify`**
（`-pl pi-java-coding-agent,pi-java-tui,pi-java-web -am`，不含 sqlite）—— 结果记在下面。

> ⚠️ 一条纪律备忘：**「全量绿」与「本包绿」是两件事**。包⑪ 的教训是「0 条新红 ≠ 守卫已对齐」，
> 这次的教训是「全量红 ≠ 本包红」——**先看红在哪个模块、那个模块在不在你的依赖路径上**。
