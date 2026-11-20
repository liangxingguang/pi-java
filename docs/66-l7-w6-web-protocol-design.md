# docs/66 — L7 W6：web 面协议事件／timestamp／整表语义／并发契约／会话定位

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 上游：`docs/59` §3 W6（覆盖 map/05 **W8–W12、W16**）、docs/26（web 面）、docs/31（事件面）
- 状态：**2026-11-20 用户授权「写文档后直接实施」**（§6 实施记录）

## 0. 范围与裁决

| 行 | pi 面 | Java 现状 | 本包动作 |
|---|---|---|---|
| W8 | `queue_update`（`{steering[], followUp[]}` 全量快照）、`compaction_start/end`、`auto_retry_start/end` | `AgentEventTranslator` 的 `default` 分支静默吞帧（`:88-90`） | 转发四条（`compaction_*` 两条共用一支语义） |
| W9 | 消息 `timestamp`（Unix **ms**）上 wire | `WebWireJson.messageNode` 从不写（`:62-63` 的有意偏离） | 四种消息都补 `timestamp` |
| W10 | `agent_end.messages` 语义 | 现由全量转录源供、**空则省键**（`:137-147`） | 保留全量源（前端整表替换），**空也写 `[]`** |
| W11 | 宿主消费者并发契约（TUI/RPC/web 三面） | 只有 `PiLaneSink.emit` 的锁内注释 | 文档化＋锁上 javadoc 引本稿 |
| W12 | `transcript()` 读取点清点 | `WebDispatcher:304/320/412` 三处 | 只清点，不改 |
| W16 | `find()` 范围（`--session <id>`） | `find()` 阶梯含跨项目扫描；精确臂已是 header-only（`findById`） | 判定为**结构已满足**，补断言 |

## 1. pi 事实

1. `queue_update`：载荷只有 `steering` / `followUp` 两个**全量数组**（`agent-session.ts:217-221`），全量替换语义（`docs/json.md:121`）；入队/出队/清空三处发射（`:2325/:2342`、`:1146/:1152`、`:2490-2493`）。
2. `compaction_start`：`{reason:"manual"|"threshold"|"overflow"}`；`compaction_end`：`{reason, result?, aborted, willRetry, errorMessage?}`（`:222-233`）。无独立 `compaction_error`。
3. `auto_retry_start`：`{attempt, maxAttempts, delayMs, errorMessage}`；`auto_retry_end`：`{success, attempt, finalError?}`（`:234-235`）。
4. `agent_end.messages`：**本 pass 生成的 newMessages**（`agent-loop.ts:152/263`），空时 pi 也照写（可能是 `[]`）。
5. 消息 `timestamp`：Unix **毫秒**（`docs/message-types.md:5`），全部 AgentMessage 变体都有。
6. 并发契约：pi 只承诺「单线程串行投递、监听器可 await」（`agent.ts:619-621`、`docs/extensions.md:963`），三面共用同一事件漏斗。
7. `findLocalSessionByExactId(id, cwd, sessionDir)`：**项目内 header-only 精确**（`main.ts:243-249`），不读 transcript、不扫全库。

## 2. 设计

### 2.1 W8：转发被吞的四类事件

`JsonEventMapper.toWire(event)`（RPC 面）已实现 pi 逐字线形（小写 `reason`、`result`/`errorMessage`/`finalError` 的省略规则、`compactionEntry` 不出线）。**复用而不复制**：

```java
case AgentSessionEvent.QueueUpdate q -> out.add(wire(q));
case AgentSessionEvent.CompactionStart c -> out.add(wire(c));
case AgentSessionEvent.CompactionEnd c -> out.add(wire(c));
case AgentSessionEvent.AutoRetryStart r -> out.add(wire(r));
case AgentSessionEvent.AutoRetryEnd r -> out.add(wire(r));

private static WebServerMessage wire(AgentSessionEvent e) {
    return new WebServerMessage.AgentEvent(JsonEventMapper.toWire(e));
}
```

- `AgentEvent` 载荷本就是裸 `ObjectNode`（`WebProtocol:347-351`），无需新增协议记录。
- `compaction_end` 的 `compactionEntry`（Java 侧多出的字段）由 `toWire` 的显式投影天然剔除——这正是复用它的理由。

### 2.2 W9：`timestamp` 上 wire

`WebWireJson.messageNode` 增：

```java
var timestamp = switch (m) {
    case Message.UserMessage u -> u.timestamp();
    case Message.AssistantMessage a -> a.timestamp();
    case Message.ToolResultMessage t -> t.timestamp();
    case Message.SystemMessage s -> s.timestamp();
};
if (timestamp != null) node.put("timestamp", timestamp.toEpochMilli());
```

- 单位 ms（pi 同）；`null`（旧会话/兼容构造）⇒ 键省略。
- `client/main.ts:261/286` 直贴消息对象、不做判重 ⇒ 多一个键对前端零行为影响（这是 D-B50 解除的依据）。

### 2.3 W10：`agent_end.messages` 恒写

- **保留全量转录源**：前端是整表替换语义（`client/main.ts:327-339`），若改发本 pass 的 newMessages 会清掉历史——这是 Java 侧**有意**的差异，写入 §3。
- **空表也写键**：删掉"空 ⇒ 省键 + warn"分支，改为恒 `putArray("messages")`；空时仅 `LOG.debug`。

### 2.4 W11：并发契约文档化

- 在 `PiLaneSink.emit` 的锁注释上补一句"契约见 docs/66 §2.4"；本稿登记结论：
  - **单线程串行投递**：所有 `AgentSessionEvent` 经同一漏斗，`emit` 全程 `synchronized`（可重入，消费者内部回调不自锁）；
  - **消费者可阻塞**：TUI（dispatcher 排队）、RPC（逐帧写）、web（`WebSocket` 发送）都在 `emit` 内完成入队，不做重活；
  - **不保证跨 lane 顺序**：多 lane 并发运行时各自的 emit 序列独立。
- 不改代码（现状即契约），只把契约写死，避免后续误加"异步 emit"。

### 2.5 W12：`transcript()` 读取点

| 点 | 用途 | 是否高频 |
|---|---|---|
| `WebDispatcher:304` | `contextInsights`（上下文占用估算） | 请求驱动，非逐帧 |
| `WebDispatcher:320` | `sessionStats`（条数/大小） | 请求驱动 |
| `WebDispatcher:412` | `buildTree` 的 leafId | 请求驱动 |

结论：三处都是**请求驱动**的只读快照，不进事件热路径；不引入缓存。

### 2.6 W16：会话定位的裁决

pi 的 `--session <id>` 走 `findLocalSessionByExactId`（项目内 header-only），Java 的阶梯 ① 已是同一件东西（`repo.findById(cwd, id)`，不读 transcript）；② 项目内前缀；③ 跨项目扫描（**带 global 标**，`--session` 命中时打印提示并要求确认 fork）。

裁决：**保留 ③ 但要求它必须是"显式可感知"的**（现状已满足：`findWithScope.global()` + 提示 + 确认）。不删跨项目能力（用户显式输入 id 的场景下它是净收益），但把「精确臂 header-only、不扫全库」钉进测试。登记 D271 记录这条与 pi 的差异。

## 3. 窄差

| 编号 | 内容 |
|---|---|
| **D271** | `--session <id>` 的跨项目能力：pi 只做项目内精确查找（未命中即报错/新建），Java 保留阶梯 ③（跨项目扫描 + 提示 + 确认 fork）。两者在"项目内命中"路径上行为一致；差异只在跨项目时 Java 多给一次机会。 |
| **D272**（W4 已登记，此处引用） | 扩展对话框 `blocked` 无对位。 |
| **D273** | 技能 collision 的 winner/loser 行尾 ` (source)` 不移植：Java `ResourceDiagnostic.Collision` 没有来源字段（只有路径）。文案其余部分逐字。 |

## 4. 测试计划

1. `AgentEventTranslatorTest`：`queue_update` 线上形状（两个全量数组）；`compaction_start/end`（小写 reason、`result` 省略、**无 `compactionEntry` 键**）；`auto_retry_start/end`（`finalError` 省略）；`agent_end` 空转录 ⇒ `messages: []` 且键存在。
2. `WebWireJsonTest`：四种消息都带 `timestamp`（ms）；`timestamp == null` ⇒ 键缺席；既有哨兵断言反转。
3. `WebInboundModelTest`/集成测试：跑一遍现有 web 集成，确认新事件不破坏前端帧序。
4. `SessionFindLadderTest`：补「精确臂 header-only（不触发 transcript 读）」与「项目内精确优先于跨项目」断言。
5. 验证门：`mvn -pl pi-java-web,pi-java-coding-agent -am test` 全绿。

## 5. 风险

- `JsonEventMapper.toWire` 用的是 RPC 面的 `MAPPER`（含 `Instant` 模块）；web 面此前的手写节点没有这个依赖——但 `toWire` 返回的是 `ObjectNode`，`WebServerMessage.AgentEvent` 原样序列化，序列化器是 web 侧自己的，只影响 tree 结构，不影响时间类型（这些事件无 Instant）。
- `timestamp` 上 wire 会让 `WebWireJsonTest` 的"无 timestamp"哨兵断言反转，属对齐而非回归。
- `agent_end` 恒写空数组后，前端会收到"空表替换"——只有在**真空转录**（新会话首帧失败）时出现，pi 同。

## 6. 实施记录（2026-11-20）

| 文件 | 改动 |
|---|---|
| `web/.../AgentEventTranslator.java` | 五类事件转发（`QueueUpdate`／`CompactionStart`／`CompactionEnd`／`AutoRetryStart`／`AutoRetryEnd`）经 `JsonEventMapper.toWire`（与 RPC 面共用一处形状）；`agent_end` 恒写 `messages` 数组（空也写） |
| `web/.../WebWireJson.java` | `messageNode` 四种消息都补 `timestamp`（`Instant.toEpochMilli`，null 省略） |
| `agent-core/.../PiLaneSink.java` | 锁内注释指向并发契约（docs/66 §2.4） |

**测试**：`pi-java-web` **72 测 0 失败**（新增 3 项：队列快照、compaction 形状含 `compactionEntry` 剔除、auto_retry 的 `finalError` 省略；改写 2 项既有哨兵：`agent_end` 空数组、`timestamp` 存在）；`pi-java-coding-agent` 新增 `SessionFindLadderTest.exactIdLaneStaysInsideTheProject`（W16）。

**实施裁决**：
1. `agent_end.messages` 仍取**全量转录**（pi 发的是本 pass newMessages）：前端是整表替换语义，改发增量会清历史——保留差异（§2.3），只补"空也写键"。
2. `--session <id>` 的跨项目阶梯保留（带 `global` 标与确认提示），登记 D271；精确臂仍 header-only（新增断言钉住）。
3. `mvn -pl pi-java-web test` 需 `-Dfrontend.skip=true`（前端 `npm ci` 依赖网络缓存），Java 侧测试不受影响。
