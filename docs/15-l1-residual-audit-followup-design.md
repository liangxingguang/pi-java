# 15 - L1 余项审计续：Anthropic 非 strict 根 schema ＋ 会话资源清理

> 【背景摘要】2026-11-22 对 L1 做第二次逐项复验（`docs/14` 收口后），发现两处未进 246 分母的残余：Anthropic 非 strict 工具的根 schema 没有按 pi 归一化（#114 只验证了三个保留键）；pi 的公开面 `session-resources`（会话资源清理注册表）在 Java 完全没有对位，Anthropic 线程态与 strict 超限记忆的 `clear` 也没有生产调用点。用户 2026-11-22 批准先修这两处；#6（缓存策略模块归属）仍按计划随 L5。
>
> 【锚点与口径】pi 取证一律 `git -C D:/workplaceForai/pi show a00a5048d:<path>`；目标锚点 `a00a5048d`（2026-11-20）。分层判定仍只写 `docs/map/02-ai.md`，本稿只做设计与验收。

## 1. 单元总览

| 序 | 单元 | 现有计分 | 修复后 |
|---|---|---|---|
| A | #114 非 strict 工具根 schema 归一化 | 记为满分，实际只对位了三个保留键 | wire 逐字对齐，保持满分 |
| B | `session-resources` 公开面＋清理接线 | 未进 246 分母（旧锚点起就缺） | 补齐；编号／权重归 P12 |
| C | 线程记账与终局事件的顺序（审计中暴露的并发窗口） | 未登记 | 按 pi 顺序修正 |

> 读数影响：A 把此前「名义满分」变成「实得满分」；B、C 是对既有单元的行为加固，不等于新增分数。L1 计分卡仍是 **245.5/246 ≈ 99.8%**（只余 #6），是否给 B 立新单元、加多少权重由 P12 重判。

## 2. 单元 A：Anthropic 非 strict 根 schema

### 2.1 pi 证据

`packages/ai/src/api/anthropic-messages.ts:1909-1929`：

```ts
const parameters = getJsonSchemaToolParameters(tool, strict);
const schema = parameters as { properties?: unknown; required?: string[]; ... };
// Keep shared definitions so property $refs still resolve, and additionalProperties so the
// model knows whether extra arguments are allowed. Other root keywords stay out:
// Anthropic rejects root anyOf/oneOf/allOf with a 400.
const legacyInputSchema = {
    type: "object" as const,
    properties: schema.properties ?? {},
    required: schema.required ?? [],
    ...(schema.additionalProperties === undefined ? {} : { additionalProperties: schema.additionalProperties }),
    ...(schema.$defs === undefined ? {} : { $defs: schema.$defs }),
    ...(schema.definitions === undefined ? {} : { definitions: schema.definitions }),
};
const inputSchema =
    strict === true
        ? { ...(parameters as Record<string, unknown>), ...legacyInputSchema }
        : legacyInputSchema;
```

要点：

- 非 strict：**只发**白名单六键，其余根键（`title`／`$schema`／`anyOf`／`oneOf`／`allOf` 等）一律不发。
- `properties`／`required` 用 `??` 补 `{}`／`[]`（显式 `null` 也补）。
- 三个可选键用 `!== undefined`：键缺席不发，显式 `null` 照发。
- strict：strictified 参数在前，白名单覆盖其后（已有键原位、缺失键追加）。

### 2.2 Java 落点与改法

`pi-java-ai/src/main/java/com/pijava/ai/protocol/AnthropicToolWire.java`：

- 新增 `legacyInputSchema(Map)`：按 pi 的插入顺序产出六键；`properties`/`required` 用 `== null` 补缺省；三个可选键用 `containsKey`。
- 新增 `mergeLegacyInputSchema(Map)`：`new LinkedHashMap<>(parameters)` 后 `putAll(legacy)`，复刻 JS 展开的键序（已有键原位、新键追加）。
- `toDefinitionJson` 的 schema 选择改为：strict ⇒ `mergeLegacyInputSchema(StrictJsonSchema.convert(...))`；非 strict ⇒ `legacyInputSchema(td.inputSchema())`。

【场景】Java 之前非 strict 直接发原始 schema；pi 发白名单。
【决定】逐字复刻白名单与合并顺序，不保留任何原始根键。
【风险/代价】工具 schema 若在根上带了白名单外的语义键，Java 不再转发（与 pi 相同）；[模型可见] wire 更短且不再触发 Anthropic 根 union 400。[内部，模型不可见]：无新增状态。

### 2.3 夹具

新增 `AnthropicLegacyInputSchemaWireTest`（观测真出站请求体）：

1. 根含 `title`／`$schema`／`anyOf`、缺 `required` ⇒ 只剩六键、`required: []`、无 `title`／`$schema`／`anyOf`。
2. 只有 `type:"object"` ⇒ `properties: {}`、`required: []`、无 `additionalProperties` 键。

## 3. 单元 B：会话资源清理（`session-resources`）

### 3.1 pi 证据

公开面 `packages/ai/src/session-resources.ts:1-25`（`index.ts:34` 导出、`package.json` 有 `./session-resources` 子路径）：

```ts
export type SessionResourceCleanup = (sessionId?: string) => void;
const sessionResourceCleanups = new Set<SessionResourceCleanup>();

export function registerSessionResourceCleanup(cleanup: SessionResourceCleanup): () => void {
    sessionResourceCleanups.add(cleanup);
    return () => { sessionResourceCleanups.delete(cleanup); };
}

export function cleanupSessionResources(sessionId?: string): void {
    const errors: unknown[] = [];
    for (const cleanup of sessionResourceCleanups) {
        try { cleanup(sessionId); } catch (error) { errors.push(error); }
    }
    if (errors.length > 0) throw new AggregateError(errors, "Failed to cleanup session resources");
}
```

注册者与调用点：

- `anthropic-messages.ts:304-306`：`registerSessionResourceCleanup((sessionId) => { if (sessionId) threadState.delete(sessionId); else threadState.clear(); })`。
- `strict-tool-limits.ts:48-51`：同形，清 `sessionsOverStrictToolLimits`。
- `coding-agent/src/core/agent-session.ts:1501`：`dispose()` 末尾 `cleanupSessionResources(this.sessionId)`。

### 3.2 Java 落点与改法

1. 新增 `pi-java-ai/src/main/java/com/pijava/ai/api/SessionResources.java`：
   - `SessionResourceCleanup` 函数式接口（`cleanup(String sessionId)`）。
   - `register(...)` 返回注销 `Runnable`；`ConcurrentHashMap.newKeySet()` 存钩子。
   - `cleanup(String)` 逐个调用、单个失败不阻断其余、最后抛 `IllegalStateException("Failed to cleanup session resources", first)`，其余错误记 suppressed；`cleanup()` 无参清全部。
2. 登记两个既有状态（对齐 pi 的模块加载时登记）：
   - `StrictToolLimitMemory` 静态块 `SessionResources.register(StrictToolLimitMemory::clear)`。
   - `AnthropicThreadState` 静态块 `SessionResources.register(AnthropicThreadState::clear)`。
3. 宿主接线：`SessionDiscoveryFork.closeWith` 末尾调
   `SessionResources.cleanup(owner.sessionId())`。`close()`／`closeForReplacement`／`closeForFork` 三条臂都经过这里，等价 pi 的 `dispose()` 覆盖会话替换与进程退出。

【场景】Java 的 `clear` 只有测试调用；pi 在会话结束时统一释放。
【决定】把 pi 的注册表原样补进 ai，宿主只调一个入口；不把清理散落到各车道。
【风险/代价】`cleanup` 是进程级静态注册表，测试须自行注销钩子；单个钩子抛错时仍执行其余钩子（与 pi 相同）。[内部，模型不可见]。[模型可见] 面：会话替换后不再继承旧会话的 thread／strict 状态。

### 3.3 夹具

- `SessionResourcesTest`：按会话传递 id、注销生效、无参传 `null` 清全部、抛错不阻断其余且聚合。
- `StrictToolLimitMemoryTest` / `AnthropicThreadStateTest`：经 `SessionResources.cleanup("s")` 释放状态。
- `AgentSessionCacheWarmingTest.closeCleansSessionResources`：`session.close()` 以当前 sessionId 触发清理钩子。

## 4. 单元 C：线程记账必须先于终局事件

审计 B 时，`AnthropicThreadsWireTest` 的第二轮偶发重开线程。根因：

- `streamBlocking` 的消费端看到 `done` 就返回；
- Java 原来先 `submitTail`（发 done）再 `record`（写线程态）；
- 第二轮请求可能在 `record` 的 `put` 之前开始，读到空状态 ⇒ 发 `create`。

pi 的顺序是 `recordThreadReply`（`anthropic-messages.ts:1151`）**先于** `push {type:"done"}`（`:1166`）。改法：把 `AnthropicThreadRequests.record(...)` 移到 `AnthropicStreamSupport.submitTail(...)` 之前；判据（`sentThread != null`、`!stop.errored`、非 pending/error）不变，`reply` 仍取 `builder.snapshot()`。模型可见面（请求体）与 pi 一致；内部修复一个并发窗口。

## 5. 验收

| 命令 | 期望 |
|---|---|
| `mvn test -pl pi-java-ai -am` | **2,253 测全绿**（新增 7 例） |
| `mvn test -pl pi-java-coding-agent -am` | 全链全绿（含新增 `closeCleansSessionResources`） |

## 6. 提交切分

| 序 | 内容 | 模块 |
|---|---|---|
| 1 | 本设计稿 | docs |
| 2 | `feat(ai): align non-strict Anthropic tool schemas`（含 wire 夹具） | ai |
| 3 | `feat(ai): add session resource cleanup registry`（含注册与夹具） | ai |
| 4 | `fix(coding-agent): clean session resources on close` | coding-agent |
| 5 | 读数与台账回填 | docs |

不新增 B／D 号：两项都是已批准的直接落地；`session-resources` 的单元编号与权重归 P12。
