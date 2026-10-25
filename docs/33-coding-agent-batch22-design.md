# coding-agent 批 22 设计稿：会话管理尾（W15 web 渐进会话列表）＋自更新终裁（E6-10/B-4/R29）

【背景摘要】本批是 coding-agent 对齐批次的第 22 批（总账 docs/20 §3 序 21——
**计划表唯一剩余行**，收尾批）。三块：① **W15**——会话列表的 onProgress
渐进面在 web 协议暴露（pi `SessionListProgress`＝loaded/total/partialSessions
三参＋AbortSignal；java repo 层批 14 已落富列表，TUI 选择器批 14 已消费，
**余 web 面同步全量**——`WebDispatcher.sessions()` 一次性返回）；② **自更新
终裁**——E6-10/B-4 的 self 通道与 map/05 R29 安装变更警告挂「序 21 一并
评估」（批 15 裁决④/批 19 裁决②）；③ 无其他计划行——本批闭环后
docs/20 §3 计划表清空，剩余工作全是 §3.3 未拍板变量。批 21 闭环后模块账面
404.5/485 ≈ 83.4%。

术语（首现释义）：

- **渐进列表（progressive listing）**：会话扫描分批回传——周期发布点携带
  「已加载会话的活动序快照」（`partialSessions`），调用方边扫边渲染；
  pi 的两档发布间隔：当前项目 10 条/全部 100 条。
- **自更新通道**：pi 的 `update self` 按安装形态分 npm（`npm i -g`）与
  managed（github releases 二进制＋`pruneManagedReleases` 清旧版）两臂；
  未知形态 ⇒ 打不可用说明＋exit 1。
- **安装变更警告（R29）**：pi 检测到运行中的 pi 包在会话期间被升级/替换
  （`detectInstallChange`）后，出错时提示「重启会话」一次。

---

## §0 基线与预期翻动

| 指标 | 前（批 21 闭环） | 后（预期） |
|---|---|---|
| 模块账面（Σ 485，去重） | 404.5 ≈ 83.4% | **406.5 ≈ 83.8%**（+2.0） |
| E6 域（Σ 31） | 30.0 ≈ 96.8% | **31.0 = 100%**（满分域之十一） |
| B 域（Σ 15） | 14.0 ≈ 93.3% | **15.0 = 100%**（满分域之十二） |
| map/05 tui/web（Σ 245） | 121.5 ≈ 49.6% | 122.5 ≈ **50.0%**（W15） |
| 全仓（Σ 1379） | 1,087.0 ≈ 78.8% | **1,090.0 ≈ 79.0%**（+3.0） |

翻动行（3 行，+3.0）：

| 行 | 权重 | 前 | 后 | 任务 |
|---|---:|---|---|---|---|
| W15 web 会话列表 onProgress | 1 | 缺失 | 对齐 | T1 |
| E6-10 `update self/pi` | 2 | 存疑 | 对齐 | T2（终裁：恒不可用臂＝pi 未知形态臂终局） |
| B 域 #4 `update` | 2 | 存疑 | 对齐 | T2（同上——同一行的两表投射） |

挂起解除：**R29** 终裁**不做**（C 类登记，触发条件＝分发/自更新通道落地）——
map/05 行判定改「不做」不计分母变化（map/05 的「不做」惯例＝移出计分表；
R29 w1 缺失 ⇒ 分母 245→244，tui/web 122.5/244 ≈ **50.2%**）。

无新 B 预期；窄差预期 0–1（web 渐进面的协议形是 java 自有面，pi 无对应——
不登记窄差，属 W15 判定内）。

---

## §1 pi 侧事实（取证，锚点 `ea448f454`）

### 1.1 W15——SessionListProgress 与消费序（session-manager.ts:884-943）

```ts
export type SessionListProgress = (
    loaded: number,
    total: number,
    /** Sessions loaded so far, sorted by activity. Present on periodic updates. */
    partialSessions?: readonly SessionInfo[],
) => void;

const MAX_CONCURRENT_SESSION_INFO_LOADS = 10;
const MAX_CONCURRENT_SESSION_DISCOVERY_LOADS = 64;
const CURRENT_SESSION_LIST_PUBLISH_INTERVAL = 10;
const ALL_SESSION_LIST_PUBLISH_INTERVAL = 100;
```

- 语义：每加载一条回调（`loaded/total`）；**周期发布点**（每 INTERVAL 条）
  额外携带活动序 `partialSessions` 快照；`signal.throwIfAborted()` 贯穿。
- TUI 选择器消费（session-selector.ts:76-116）：渐进填充＋进度行。
- **java 现状**：repo 层 `JsonlSessionRepository.listRich(cwd, progress, signal)`
  /`listAllRich`（批 14 已落，回调签名 `onProgress(int, int,
  List<RichSessionInfo>)`——partial 仅发布点非 null，同 pi）；TUI 选择器
  经 `RepositoryHandle.listRich` 消费（E3-9 批 14 对齐）。**web 面**：
  `WebDispatcher.sessions()`（:234-243）走 `listSessionsSummary` 同步全量；
  协议 `getSessions ⇒ sessions` 一次性（WebProtocol.java:86/156/324；
  frontend main.ts:201-206/`sessionsLoading` 标志已在）。

### 1.2 自更新两臂与未知形态臂（package-manager-cli.ts:218/449＋windows-self-update.ts）

pi `update [source|self|pi]`：self/pi ⇒ 按安装形态分派——npm 包 ⇒ `npm i -g`
语义；managed（官方二进制）⇒ releases 下载＋`pruneManagedReleases`（:137，
清旧版）；**未知形态 ⇒ 不可用说明＋exit 1**。java（批 19 裁决②/D115）：
`PackageCommand` self 臂＝恒不可用说明＋exit 1——与 pi 的未知形态臂逐字
同形。本批评估（docs/20 §3 序 21 挂账）：pi-java 的分发形态是**本地 Maven
构建/Native Image**（149MB exe，无远程制品仓）——npm/managed 两臂**永远
不适用** ⇒ pi 的未知形态臂就是 java 的终局行为。

### 1.3 R29 三件套（interactive-mode.ts:2193/2184/:3643＋config.ts detectInstallChange）

pi：会话期间包被升级/替换（package.json 版本/mtime 变化检测）⇒ 出错时
`maybeShowInstallChangeWarning` 提示重启（每会话一次）。java 无 package.json
安装形态、无自更新通道（§1.2 终裁）⇒ 检测前提不成立。批 15 裁决④已把
`maybeSuggestBugReport` 的 install-change 门恒 false（≡ pi 恒 undefined 臂）
落地——本批终裁登记 C 类，触发条件＝分发通道落地后重评。

---

## §2 java 侧现状（取证 2026-10-25，main `ffe6c9b8`）

| 面 | 现状 |
|---|---|
| repo 富列表 | `listRich/listAllRich(cwd, SessionListProgress, AbortSignal)`（批 14；`com.pijava.ai.AbortSignal`） |
| AgentSession | `listSessionsSummary(cwd)` 同步（:2159）——无 progress/signal 重载 |
| web 协议 | `getSessions ⇒ sessions` 一次性；`SessionListItem` 全字段已在 |
| web 前端 | `sessionsLoading` 标志＋`sessionList` 全量替换（main.ts:201-206） |
| self 臂 | `PackageCommand` 恒不可用＋exit 1（批 19，D115 在册） |
| R29 | 门恒 false（批 15 裁决④）——缺 C 类终裁登记 |

---

## §3 任务分解

### T1：W15 web 渐进会话列表（~250 行＋前端＋测试）

- **协议**（WebProtocol.java）：新增 server→client `sessionsProgress
  {loaded, total, sessions: SessionListItem[]}`（周期发布点的活动序快照）＋
  client→server `cancelSessions`；`sessions` 终态消息不变（兼容）。
- **AgentSession**：`listSessionsSummary(cwd, SessionListProgress, AbortSignal)`
  重载——JSONL 臂透传 repo.listRich（RichSessionInfo→SessionSummary 映射在
  进度回调里做，partial 快照同 pi 序）；其它后端退化臂＝一次性全量＋一次
  终态回调（pi 无此后端——java 自有面注记）。
- **WebDispatcher**：`GetSessions` ⇒ 虚拟线程跑富列表；进度回调（仅
  partial 非空的发布点）⇒ 映射发 `sessionsProgress`；完成发 `sessions` 终态；
  `CancelSessions` ⇒ abort；**代次闸**（新一轮 GetSessions 作废旧轮——
  连接级 AtomicLong generation；pi 选择器重开即新扫同族）。发送线程安全：
  `PiWebServer.sendJson` 现状直写 conn（Java-WebSocket 的 send 线程安全，
  既有 `AgentEventTranslator` 已跨线程发——同先例）。
- **前端**（frontend/shared/protocol.ts＋client/main.ts）：`sessionsProgress`
  消息 ⇒ `sessionList = partial; sessionsLoading = true`（picker 边来边渲染）；
  `sessions` 终态 ⇒ loading false（现状不变）。picker 关闭 ⇒ 发
  `cancelSessions`（ AbortSignal 对位）。
- **前端构建**：`npm run build`（frontend/）刷新 assets bundle；无头 Chrome
  验证手法沿用 [[web-frontend-verification]]（假 WS 驱动）。
- 测试：`WebDispatcherSessionsProgressTest`（多文件夹具 ⇒ ≥1 条
  sessionsProgress＋终态全量＋cancel 臂）＋协议 wire 形钉测试。

### T2：自更新终裁（E6-10/B-4 翻行，零代码）

- 裁决①：**终局＝恒不可用臂**（pi 未知形态臂即 java 终局形态；npm/managed
  两臂不适用 java 分发）⇒ E6-10 存疑→对齐、B 域 #4 存疑→对齐；D115 保留
  （文档化偏差登记）。map/04 两行证据改注「终裁」。
- 核销检查：pi 本锚点新增 `pruneManagedReleases`（managed 臂子件）——同属
  不适用面，终裁一并覆盖（注记）。

### T3：R29 终裁（不做，C 类登记）

- 裁决②：java 无安装形态可检测（无 package.json、无自更新通道〔裁决①〕）
  ⇒ 三件套不做；批 15 的恒 false 门即终局。docs/04 §4 C 类登记＋触发条件
  （分发通道落地重评）；map/05 R29 行 缺失→出分母（「不做」惯例移出计分表，
  分母 245→244）。

---

## §4 不做项

| 项 | 理由 |
|---|---|
| pi `pi update` 的 npm/managed 真实通道 | 裁决①：无分发载体 |
| R29 检测件（jar mtime 监视等替代形） | 裁决②：自造形态非 pi 语义，无通道则无场景 |
| TUI 选择器渐进（已批 14 落地）／repo 层（批 14） | 已在 |

## §5 窄差登记

无新窄差预期（web 渐进协议形属 java 自有面的判定内实现；D115 保留）。

## §6 裁决

| # | 裁决 | 理由 |
|---|---|---|
| ① | `update self/pi` 终局＝pi 未知形态臂（恒不可用＋exit 1）；E6-10/B-4 翻对齐 | pi-java 无 npm/managed 分发形态（Native Image 149MB 本地制品，无远程仓）——pi 的未知形态臂即终局 |
| ② | R29 不做（C 类），触发条件＝分发通道落地 | 检测前提（package.json 安装形态）不存在；恒 false 门已是 pi 等价臂 |
| ③ | web 渐进协议新增 `sessionsProgress`/`cancelSessions`，终态 `sessions` 不变 | 兼容存量前端；pi 的 AbortSignal⇒cancelSessions 消息对位 |
| ④ | 代次闸取代连接级单飞（新 GetSessions 作废旧轮） | pi 选择器重开即新扫的同族语义；无闸则乱序覆盖 |

## §7 测试计划

| 测试 | 覆盖 |
|---|---|
| `WebDispatcherSessionsProgressTest` | N 会话文件 ⇒ 终态 `sessions` 全量＋序正确；进度消息字段形（loaded/total/items）；cancel 后不再发（代次闸）；新一轮 GetSessions 作废旧轮 |
| `WebProtocolWireTest`（随既有协议测试簇） | `sessionsProgress`/`cancelSessions` 线格式钉死 |
| 前端 | protocol.ts 类型＋main.ts 处理臂；无头 Chrome 冒烟（渐进臂至少一条 progress 再终态） |
| 回归 | `mvn clean verify` 全绿 |

## §8 对账表

| 行 | 前 | 后 | 证据（实施后回填） |
|---|---|---|---|
| W15 | 缺失 | 对齐 | T1 落地：`WebSessionsProgressTest` 3 用例绿（渐进帧＋终态/取消恢复＋代次闸/线格式）＋`SessionListProgressOverloadTest` 3 用例绿 |
| E6-10 | 存疑 | 对齐 | T2（裁决①）——map/04 行改注终裁 |
| B#4 | 存疑 | 对齐 | T2（裁决①）——同一裁决的两表投射 |
| R29 | 缺失（挂起） | 出分母（不做） | T3（裁决②）——docs/04 §4 C 类登记＋map/05 行出分母 |

## §9 闭环回填

### 实施记录（2026-10-25 闭环）

- **任务全落**：T1（协议两件＋AgentSession 重载＋WebDispatcher 虚拟线程＋代次闸
  ＋前端渐进渲染/取消）＋T2/T3（终裁零代码）。测试 2 件 6 用例全绿；前端
  `vite build` 过（bundle 随 frontend-maven-plugin 在包构建期重建）。
- **撞坑与修真**：① 夹具自身会话落盘进同根列表（计数 +1 两测试各修一次）；
  ② 测试 await 轮询丢弃非匹配帧 ⇒ 进度帧假红（改为 seen 收集）；
  ③ 无 `-am` 定向测试拾 .m2 旧 jar ⇒ NoSuchMethodError 逃出
  `catch (RuntimeException)` 静默死线程（表现为超时无终态帧）——
  **定向测试恒带 `-am`**（批 13 坑的变体，第三批撞上）。
- **verify 史**：定向（W15 六用例＋T1 编译链）绿＋最终全量 clean verify
  （见闭环 commit 注记）。
- **评审**：无独立评审轮（用户指令：设计稿落地后直接实施）。
- 回填落位：docs/04 §4 批 22 段＋C 类 R29 行；docs/05 §1 总表/§2.4/§2.5；
  map/04 E6-10/B#4 两行＋E6/B 汇总＋域表＋合计＋注记；map/05 W15/R29 两行
  ＋R/W 族行＋合计＋注记；docs/20 §1/§2/§3（序 21 划线＋计划表清空注记）。
