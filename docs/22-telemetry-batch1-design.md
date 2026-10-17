# 22 telemetry 批 1 设计 —— 契约补全 ＋ InMemory ＋ 一致性套件 ＋ 六条裁决

> 基线：pi `ea448f454`（2026-10-11）；pi-java main（agent-core 批 2 后 `cd1fb902`）。
> 所有 pi 侧 `file:line` 均经 `git -C D:/workplaceForai/pi show ea448f454:<path>` 实读，未读 pi 工作树；
> `git diff --stat ea448f454 HEAD -- packages/telemetry/` 为空（锚点后零漂移）。
> 地图出处：[`map/01-infra.md`](map/01-infra.md) §A（T1–T17）、§C/D/H（C5/C7/D1–D5/H）。
> 前批：[`21-agent-core-batch2-design.md`](21-agent-core-batch2-design.md)（已闭环）。

## 0. 批次卡

| 项 | 值 |
|---|---|
| 范围 | telemetry 包 17 条（T1–T17）全量过一遍 ＋ C5 开/关顺序 ＋ 新发现 ai 层 `telemetryContext` 透传缺口（取证关单） |
| 基线账面 | 17.5/35 = **50.0%**（未加权 7/17 = 41.2%；w3 档 64.3%） |
| 目标账面 | **34/34 = 100%**（分母 35→34：T11 裁决移出；分子 +16.5，逐项见 §8） |
| 重件 | T9 `InMemoryTelemetryContext` 移植 ＋ T10 一致性套件移植（9 用例 / fixture 三挂） |
| 中件 | T5/T6/T7 跨度契约补全（`addEvent`/`setAttributes`/`setStatus`＋`SpanStatus` ADT）；Jsonl 显式状态语义 |
| 小件 | C5 开/关顺序统一（`PiLaneSink.endRequest:216-217` 一处换序） |
| 裁决 | T11 不做（pi 零消费者＋类型机制不可表达）；T4 维持 `Map<String,Object>`；T1/T2 结构性差异维持；T12–T17 扩展确认口径；B241 新登即销（ai `telemetryContext` dormant） |

**本批性质（取证后的一句话）**：pi 的 telemetry 包是一个**休眠契约**——主流代码里没有任何调用点
开启跨度（§1 证据链）；它的全部对齐价值在**契约形状与适配器语义**。因此本批的重件不是
「接通什么」，而是把 pi 的参考实现（memory.ts）与契约钉床（conformance.ts）移植过来，
让 java 侧三个真 adapter（Noop/Jsonl/Otel）与契约的形状差**变红可见**。

---

## 1. 取证总账：pi telemetry 是休眠契约

逐条 grep 证据（pi `ea448f454`，`packages/*/src` ＋ `apps`）：

| 取证问题 | 命令 | 结果 |
|---|---|---|
| 谁在包外 import `@earendil-works/pi-telemetry`？ | `grep -rn "pi-telemetry" packages/*/src apps` | **仅** `packages/ai/src/types.ts:1`（type-only import） |
| 谁调用 `startSpan`？ | `grep -rn "startSpan" packages/ai/src`（coding-agent/agent/tui 同查） | **零命中**（telemetry 包自身除外） |
| 谁用 `defineTelemetrySchema`/`createTypedSpanStarter`？ | `grep -rln ... packages/*/src apps` | **零命中**（telemetry 包自身除外） |
| 谁设置 `telemetryContext` 选项？ | `grep -rn "telemetryContext" packages/*/src` | 仅声明点 `ai/src/types.ts:160` ＋ 透传点 `ai/src/api/simple-options.ts:48`；**零设置点** |
| coding-agent 的 `core/telemetry.ts` 是什么？ | 实读全文 13 行 | 是**安装遥测开关**（`PI_TELEMETRY` env／settings 电话回家），与 telemetry 包无关 |

结论：`bin pi` 主流可达行为面上，telemetry 包的可观察行为是**空**——没有跨度被开启，
`telemetryContext` 选项从未被赋值。pi 侧该包的价值全在**公开契约**（935 LOC，含 339 LOC
testing/ 与 ~280 LOC 类型化词汇）。这决定了两件事：

1. 本批按 R5（行为判据）**不需要**为 java 接任何新线——java 已有的接线（Jsonl 每会话
   落盘、指标、OTel）全是 java 扩展，维持；
2. 地图按**契约面**计分（T1–T17），对齐动作就是把契约缺件补齐、把形状差裁决掉。

---

## 2. T5/T6/T7：跨度契约补全（`addEvent`／`setAttributes`／`setStatus`）

### 2.1 pi 参照（逐字）

**跨度契约三方法**（`telemetry/src/index.ts:18-22`）：

```ts
export interface TelemetrySpan extends TelemetryContext {
	addEvent(name: string, attributes?: SpanAttributes): void;
	setAttributes(attributes: SpanAttributes): void;
	setStatus(status: SpanStatus): void;
}
```

**SpanStatus 形状**（`telemetry/src/index.ts:12`）：

```ts
export type SpanStatus = { status: "ok" } | { status: "error"; error?: { name: string; message: string } };
```

**语义钉床一：显式状态赢过自动派生**（`telemetry/src/memory.ts:89-99`）：

```ts
function settleSpan(
	state: InMemoryTelemetryState,
	span: MutableRecordedTelemetrySpan,
	failed: boolean,
	error?: unknown,
): void {
	if (span.settled) return;
	if (failed && !span.explicitStatus) span.status = automaticErrorStatus(error);
	span.settled = true;
	span.endSequence = state.nextEndSequence++;
}
```

**语义钉床二：整包合并、跳过 undefined、失败原子忽略**（`memory.ts:63-69,149-156`）：

```ts
function mergeAttributes(current: SpanAttributes, attributes: SpanAttributes): SpanAttributes {
	const merged = copyAttributes(current);
	for (const [name, value] of Object.entries(attributes)) {
		if (value !== undefined) merged[name] = copyAttributeValue(value);
	}
	return merged;
}
// ...
	setAttributes(attributes) {
		if (recordedSpan.settled) return;
		try {
			recordedSpan.attributes = mergeAttributes(recordedSpan.attributes, attributes);
		} catch {
			// Recording is passive. Ignore malformed or unreadable telemetry payloads.
		}
	},
```

**语义钉床三：settled 后全部 inert**（`memory.ts:126,142,150,158`：`startSpan` 降级 NOOP、
`addEvent`/`setAttributes`/`setStatus` 首行 `if (recordedSpan.settled) return;`）。

**旧裁决翻转说明**：`TelemetrySpan.java:13-19` 现记「`addEvent`/`setStatus` 不移植是裁决而非
疏漏」（原 docs/31 §8.25.3/§8.28）。本批**翻转该裁决**，理由：当年裁决时没有 InMemory 与
一致性套件；如今要移植 T9/T10，契约三方法是它们的前提（套件直接断言 `addEvent` 有序记录、
`setStatus` 显式优先）。翻转后 `recordEvent`（载荷行、payload 门控、线程绑定）与
`addEvent`（小属性、跨度绑定、不门控）**并存分工**，javadoc 重写区分二者。

### 2.2 java 待写

**新文件 `SpanStatus.java`**（ADT，纯常量两变体＋带字段一变体 ⇒ sealed + record）：

```java
package com.pijava.telemetry;

/**
 * pi {@code telemetry/src/index.ts:12} 的跨度状态：
 * {@code {status:"ok"} | {status:"error", error?:{name,message}}}。
 *
 * <p>{@link Error} 的两个组件同 null ≙ pi 的 {@code error} 键缺席
 * （{@code automaticErrorStatus} 对非 Error 拒绝值的产出，memory.ts:86）。</p>
 */
public sealed interface SpanStatus {

    /** pi {@code {status:"ok"}}。 */
    record Ok() implements SpanStatus { }

    /** pi {@code {status:"error", error?...}}；两组件同 null ≙ 无详情。 */
    record Error(String name, String message) implements SpanStatus {
        /** pi 的 {@code error} 对象两键必填 ⇒ 同 null 或同非 null。 */
        public Error {
            if ((name == null) != (message == null)) {
                throw new IllegalArgumentException("name/message must be both null or both set");
            }
        }
    }
}
```

**`TelemetrySpan.java` 契约补三方法**（`addAttribute` 反转成便捷默认——契约主形状与 pi 一致，
单键是扩展便捷；现有 Jsonl/Otel 的 `addAttribute` 覆盖点改覆 `setAttributes`，见 §5/§6）：

```java
    /**
     * pi {@code index.ts:19}：记录一个跨度事件（小属性、跨度绑定、不门控）。
     * settled 后 inert。与 {@link TelemetryContext#recordEvent} 分工：后者是
     * 整载荷行、{@code --trace-payloads} 门控、线程绑定。Default no-op.
     */
    default void addEvent(String name, Map<String, Object> attributes) { }

    /**
     * pi {@code index.ts:20}：整包合并属性（null 值跳过 ≙ pi 跳过 undefined）。
     * settled 后 inert。Default no-op.
     */
    default void setAttributes(Map<String, Object> attributes) { }

    /**
     * pi {@code index.ts:21}：显式状态。显式状态赢过失败时的自动派生
     * （memory.ts:96 {@code !span.explicitStatus}）。settled 后 inert。Default no-op.
     */
    default void setStatus(SpanStatus status) { }

    /** 单键便捷（java 扩展）：转发 {@link #setAttributes}。 */
    default void addAttribute(String key, Object value) {
        if (value != null) {
            setAttributes(Map.of(key, value));
        }
    }
```

**`NoopTelemetryContext`**：零改动——三方法 default no-op 即 pi 的 frozen noop span
（`noop.ts:11-17` 逐字对应：`addEvent: () => {}, setAttributes: () => {}, setStatus: () => {}`）。

---

## 3. T9：`InMemoryTelemetryContext` 移植

### 3.1 pi 参照（逐字关键段）

**记录形状**（`memory.ts:11-25`）：

```ts
export interface RecordedTelemetryEvent {
	readonly name: string;
	readonly attributes: Readonly<SpanAttributes>;
}

export interface RecordedTelemetrySpan {
	readonly id: number;
	readonly parentId: number | null;
	readonly name: string;
	readonly attributes: Readonly<SpanAttributes>;
	readonly events: readonly RecordedTelemetryEvent[];
	readonly status: SpanStatus;
	readonly settled: boolean;
	readonly endSequence?: number;
}
```

**settled 父跨度的子跨度降级 NOOP**（`memory.ts:126`）：

```ts
	if (parent?.settled) return NOOP_TELEMETRY_CONTEXT.startSpan(options, callback);
```

**自动错误派生（被动检查）**（`memory.ts:78-87`）：

```ts
function automaticErrorStatus(error: unknown): SpanStatus {
	try {
		if (error instanceof Error) {
			return { status: "error", error: { name: error.name, message: error.message } };
		}
	} catch {
		// Error inspection is passive. Fall through to an error status without details.
	}
	return { status: "error" };
}
```

**detached 快照**（`memory.ts:203-218`：`getSpans()` 对每个跨度深拷贝 attributes/events/status，
`endSequence` 未 settle 时**缺席**而非 null）。

### 3.2 java 待写

**`RecordedTelemetryEvent.java`**：

```java
package com.pijava.telemetry;

import java.util.Map;

/** pi {@code memory.ts:11-14}。attributes 为不可变副本。 */
public record RecordedTelemetryEvent(String name, Map<String, Object> attributes) {
    public RecordedTelemetryEvent {
        attributes = Map.copyOf(attributes);
    }
}
```

**`RecordedTelemetrySpan.java`**：

```java
package com.pijava.telemetry;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * pi {@code memory.ts:16-25}。
 *
 * @param parentId     null ≙ pi 的 {@code null}（根跨度）
 * @param endSequence  empty ≙ pi 的键缺席（未 settle）
 */
public record RecordedTelemetrySpan(
    long id,
    Long parentId,
    String name,
    Map<String, Object> attributes,
    List<RecordedTelemetryEvent> events,
    SpanStatus status,
    boolean settled,
    OptionalLong endSequence
) {
    public RecordedTelemetrySpan {
        attributes = Map.copyOf(attributes);
        events = List.copyOf(events);
    }
}
```

**`InMemoryTelemetryContext.java`**（骨架；全量 ~230 行。pi 是单线程无锁，java 多线程
⇒ state 以 `synchronized` 收口——**java 必要偏差**，javadoc 注明）：

```java
package com.pijava.telemetry;

// imports 略

/**
 * pi {@code memory.ts:188-219} 的移植：后端中立的参考实现，把跨度记录在进程内存。
 * 每个实例隔离一个录制域（测试 / 独立录制范围各建各的）。
 *
 * <p>语义逐条对 pi：id 自 1 自增；endSequence 自 1 自增、仅 settle 时赋值；
 * settled 后 addEvent/setAttributes/setStatus/close 全 inert；settled 父跨度的
 * startSpan/openSpan 降级 {@link NoopTelemetryContext}（memory.ts:126）；记录被动——
 * 属性读取失败整包原子忽略（memory.ts:149-156）；失败时无显式状态才自动派生
 * error（memory.ts:96）；{@link #getSpans()} 返回 detached 深拷贝快照。</p>
 *
 * <p>java 偏差：pi 单线程无锁；此处 state 用 synchronized 收口，语义不变。</p>
 */
public final class InMemoryTelemetryContext implements TelemetryContext {

    private final Object lock = new Object();
    private final List<MutableSpan> spans = new ArrayList<>();
    private long nextSpanId = 1;
    private long nextEndSequence = 1;

    @Override
    public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
        var span = openSpan(options);
        try {
            return body.apply(span);
        } catch (RuntimeException | Error e) {
            span.settle(true, e);
            throw e;
        } finally {
            span.close(); // 幂等：异常路径已 settle 时此为 no-op（pi settleSpan:95）
        }
    }

    /** 覆写默认：默认实现经 startSpan 会把跨度提前 settle，openSpan 必须真开。 */
    @Override
    public TelemetrySpan openSpan(SpanOptions options) {
        synchronized (lock) {
            var span = new MutableSpan(nextSpanId++, null, options);
            spans.add(span);
            return span;
        }
    }

    /** pi {@code getSpans()}：detached 快照，按跨度开启序。 */
    public List<RecordedTelemetrySpan> getSpans() {
        synchronized (lock) {
            return spans.stream().map(MutableSpan::snapshot).toList();
        }
    }

    /** 内部可变跨度；快照时深拷贝。 */
    private final class MutableSpan implements TelemetrySpan {
        // id / parentId(nullable) / name / LinkedHashMap attributes / events /
        // SpanStatus status = new Ok() / boolean explicitStatus / boolean settled /
        // long endSequence(-1 未赋) —— 对应 memory.ts:32-42

        // startSpan/openSpan：settled ⇒ NoopTelemetryContext.INSTANCE 降级（memory.ts:126）；
        //                     否则建子 MutableSpan(parentId=this.id)，同 §3.2 startSpan 外壳

        // addEvent：settled return；try { events.add(copy) } catch { /* 被动 */ }（:141-148）
        // setAttributes：settled return；try { 整包合并, null 跳过 } catch { /* 被动 */ }（:149-156）
        // setStatus：settled return；status=入参；explicitStatus=true（:157-165）
        // settle(failed, error)：幂等；failed && !explicitStatus ⇒ 自动派生（:89-99）
        //   自动派生 java 版：error instanceof Exception/Error ⇒ Error(类名, getMessage)，
        //   getMessage 读取异常（自定义 Throwable 可炸）⇒ catch ⇒ Error(null,null)（:78-87）
        // close() ⇒ settle(false, null)
    }
}
```

---

## 4. T10：一致性套件移植（C7 触发条件已满足）

### 4.1 C7 翻转论证

map/01 C7 原文：「是否另立 `TelemetryAdapterConformance` 套件（倾向不做），触发条件＝出现
OTel 之外的真 adapter」。现状：`JsonlFileTelemetry` 就是 OTel 之外的真 adapter，且
**每个会话无条件接线**（`AgentSession.java:282-283`）——触发条件在写下 C7 时已满足。
map/01 §2.7 亦自陈：「adapter 一致性套件未做，可把形状差变红」。⇒ **做**。

### 4.2 pi 参照（逐字关键段）

**fixture 三型**（`testing/types.ts:4-18`）：

```ts
/** A fresh adapter instance and normalized snapshot reader owned by one conformance case. */
export interface TelemetryAdapterFixture extends AsyncDisposable {
	readonly context: TelemetryContext;
	getSpans(): Promise<readonly RecordedTelemetrySpan[]>;
}
export type TelemetryAdapterFixtureFactory = () => Promise<TelemetryAdapterFixture>;
export interface TelemetryAdapterConformanceCase {
	readonly group: string;
	readonly name: string;
	run(): Promise<void>;
}
```

注意 `getSpans` 是「**normalized** snapshot reader」——归一化职责在 fixture（Jsonl 的
hex spanId、行序 endSequence 都在 fixture 重建时归一成序号 id）。

**9 用例清单**（`testing/conformance.ts:64-314`，组:名）：

| # | 组 | 名 | pi 行 | java 可表达性 |
|---|---|---|---|---|
| 1 | callback lifecycle | admits once synchronously and preserves the result | :65-85 | ✓ 全移植 |
| 2 | callback lifecycle | preserves synchronous and asynchronous rejection values | :87-140 | ✓ 同步化：异常对象身份 `assertSame`；`Promise.reject(undefined)` 子场景不可表达（java 无空异常）；unreadable error ⇒ 自定义 `Throwable`（`getMessage` 抛）验 `Error(null,null)` 派生 |
| 3 | status | uses last explicit status without automatic overwrite | :142-182 | ✓ 全移植（显式 ok 后抛 ⇒ 仍 ok；显式 error 后 reject ⇒ 保显式） |
| 4 | recording | merges attributes and records ordered events | :184-204 | ✓ 全移植（undefined 值 ⇒ java 直接不放键） |
| 5 | recording | ignores failed attribute calls atomically | :206-218 | ✓ 炸弹 `Map`（`entrySet()` 抛） |
| 6 | recording | makes calls after settlement inert | :220-244 | ✓ 全移植（settled 后 setAttributes/addEvent/setStatus inert；late-child 经降级仍执行回调返 7、不记录） |
| 7 | parentage | records nested and concurrent child relationships | :246-272 | ✓ 同步化改写：父跨度内 `openSpan` 开 first（不关）→ `startSpan` 跑完 second → `firstChild.close()` ⇒ 断言不变式相同：`second.endSequence < first.endSequence < parent.endSequence`、parentId 链 |
| 8 | passivity | suppresses unreadable telemetry payload failures | :274-299 | △ 部分：炸弹 Map 作 `setAttributes`/`addEvent` 参数 ✓；**unreadable `SpanOptions` 子场景不可表达**——java `SpanOptions` 紧凑构造器 `Map.copyOf` 在**调用点**就触发读取，炸弹到不了 adapter；裁决：该子场景为 TS Proxy 特有，登记不移植 |
| 9 | passivity | ignores failed status calls atomically | :301-313 | △ 部分：炸弹 `SpanStatus` 不可表达（sealed record 组件访问器不会抛）；保留其核心断言「显式 status 调用失败后，自动派生仍生效」由用例 3 覆盖 ⇒ 本用例合并进用例 3，不独立存在 |

⇒ java 套件落地 **7 用例 / 5 组**（1–7 全量，8 截半并入 recording 组，9 并入 status 组）；
两个不可表达子场景在套件 javadoc 登记（理由：TS Proxy 特有 / sealed record 值语义）。

### 4.3 java 待写

**放置**：主源集 `com.pijava.telemetry.testing` 包（pi 把 `src/testing/` 当发布 API，
`dist/testing/` 可查）；套件本体**不依赖 JUnit**（runner-independent，与 pi 同），
断言失败抛 `AssertionError`；JUnit 挂载（`@TestFactory` → `DynamicTest`）在测试源集。

**`testing/TelemetryAdapterFixture.java`／`TelemetryAdapterFixtureFactory.java`／
`TelemetryAdapterConformanceCase.java`**：

```java
package com.pijava.telemetry.testing;

/** pi {@code testing/types.ts:5-9}。getSpans 是归一化快照读取器。 */
public interface TelemetryAdapterFixture extends AutoCloseable {
    TelemetryContext context();
    List<RecordedTelemetrySpan> getSpans() throws Exception;
    @Override default void close() throws Exception { }
}
```

```java
package com.pijava.telemetry.testing;

/** pi {@code testing/types.ts:12}。 */
@FunctionalInterface
public interface TelemetryAdapterFixtureFactory {
    TelemetryAdapterFixture create() throws Exception;
}
```

```java
package com.pijava.telemetry.testing;

/** pi {@code testing/types.ts:14-18}。 */
public record TelemetryAdapterConformanceCase(String group, String name, ThrowingRunnable run) {
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
```

**`testing/TelemetryAdapterConformance.java`**（骨架；7 用例全量在实现稿）：

```java
package com.pijava.telemetry.testing;

/**
 * pi {@code testing/conformance.ts:60-315} 的移植：回调式遥测适配器契约的
 * runner 无关一致性用例。7 用例 / 5 组；两个 TS Proxy 特有子场景不移植（javadoc 登记）。
 */
public final class TelemetryAdapterConformance {

    private TelemetryAdapterConformance() { }

    public static List<TelemetryAdapterConformanceCase> createTelemetryAdapterConformance(
            TelemetryAdapterFixtureFactory factory) {
        return List.of(
            callbackLifecycleAdmitsOnce(factory),
            callbackLifecyclePreservesRejections(factory),
            statusLastExplicitWins(factory),
            recordingMergesAttributesAndOrderedEvents(factory),
            recordingAtomicAttributeFailure(factory),
            recordingSettlementInert(factory),
            parentageNestedChildren(factory)
        );
    }
    // 各用例为 private static 工厂方法，断言用 AssertionError / 等值比较，
    // 与 pi 的 node:assert/strict 逐条对应
}
```

**fixture 三挂**（测试源集）：

| fixture | 实现要点 |
|---|---|
| InMemory | `context = new InMemoryTelemetryContext()`；`getSpans = context::getSpans`（pi `conformance.test.ts:5-12` 同款） |
| Jsonl | `JsonlFileTelemetry.create(@TempDir)`；`getSpans` = **读回 JSONL 重建**：span_start/span_end 行序 ⇒ 序号 id 与 endSequence；parentSpanId(hex) ⇒ 父序号 id；`attrs` 合并 ⇒ attributes；kind=event 且带 `attrs` 键的行 ⇒ events；status 串 ⇒ SpanStatus |
| OTel | `OpenTelemetrySdk`＋`InMemorySpanExporter`（`opentelemetry-sdk-testing` 已是测试依赖，`pom.xml:51-52`）；`getSpans` 由 exporter 的 finishedSpans 重建（parentage/endSequence 由 spanContext 与时间序归一） |

**挂载**（测试源集，每 adapter 一个类）：

```java
@TestFactory
Collection<DynamicTest> conformance() {
    return TelemetryAdapterConformance.createTelemetryAdapterConformance(JsonlFixture::new)
        .stream()
        .map(c -> DynamicTest.dynamicTest(c.group() + " / " + c.name(), () -> {
            try (var fixture = new JsonlFixture()) {
                c.run().run(); // 用例体经 fixture 断言
            }
        }))
        .toList();
}
```

（实现时把 fixture 注入用例：`TelemetryAdapterConformanceCase.run()` 内部自建自关 fixture，
与 pi 的 `createCase` 一致——`conformance.ts:12-26` 的 `await using fixture`。）

---

## 5. JsonlFileTelemetry 改造（显式状态语义 ＋ 契约三方法）

现状三处差距（对照 §2.1 钉床）：

1. **无显式状态**：`startSpan` catch 里 `js.markError()` 无条件置 error
   （`JsonlFileTelemetry.java:123-127`）——pi 是 `!span.explicitStatus` 才派生。
2. `addAttribute` 单键覆盖（`:312-316`）——主形状应为 `setAttributes` 整包。
3. 无 `addEvent`。

java 待写（改动点，`JsonlFileTelemetry.java`）：

```java
    // JsonlSpan 字段加：
    private SpanStatus explicitStatus;   // null ≙ 未显式设置（pi explicitStatus 旗标）

    @Override
    public void setStatus(SpanStatus status) {
        if (ended) {
            return;                      // pi memory.ts:158 settled inert
        }
        explicitStatus = status;         // 后写赢（pi conformance "status" 用例）
    }

    @Override
    public void setAttributes(Map<String, Object> attributes) {
        if (ended) {
            return;
        }
        try {
            attributes.forEach((k, v) -> {
                if (v != null) {
                    lateAttrs.put(k, v); // 整包合并、null 跳过（memory.ts:63-69）
                }
            });
        } catch (RuntimeException e) {
            // 记录被动：炸弹 Map 整包忽略（memory.ts:153-155）。
            // 注：forEach 中途失败非原子——lateAttrs 已写入部分键。
            // ⇒ 实现时先拷贝到临时 map 再 putAll，保证原子（pi 语义）。
        }
    }

    @Override
    public void addEvent(String name, Map<String, Object> attributes) {
        if (ended) {
            return;
        }
        try {
            var line = baseLine("event", traceId);
            line.put("spanId", spanId);
            line.put("name", name);
            line.put("attrs", sanitize(attributes)); // 小属性键，区别于 recordEvent 的 payload
            writeLine(line);
        } catch (RuntimeException e) {
            // 记录被动（memory.ts:145-147）
        }
    }

    // startSpan catch 改显式优先：
    } catch (RuntimeException | Error e) {
        if (span instanceof JsonlSpan js && js.explicitStatus == null) {
            js.markError();              // pi memory.ts:96：无显式状态才自动派生
        }
        throw e;
    }

    // close()：status 取值 explicitStatus != null ? wire(explicitStatus) : status 字段；
    //   wire: Ok ⇒ "ok"；Error ⇒ "error"（行格式 status 串不变，向后兼容 trace 消费者）；
    //   Error 带详情时加 errorName/errorMessage 两键（java 扩展行格式，javadoc 登记）。
```

行格式裁决（**grill 点**）：`addEvent` 与 `recordEvent` 同为 `kind:"event"` 行，以
`attrs`（小属性、跨度绑定、恒记录）vs `payload`（整载荷、线程绑定、门控）区分，
不引入新 kind——避免既有 trace 消费脚本面对新词汇。`addEvent` 行恒带 `spanId`。

---

## 6. C5：开/关顺序统一

map/01 C5：`JsonlFileTelemetry.startSpan:133-134` ＝ 先 `popCurrent()` 再 `close()`；
`PiLaneSink.endRequest:226-227`（现 `:216-217`）＝ 先 `close()` 再 `popCurrent()` ⇒ 相反。

**裁决：统一为「先 pop 后 close」**（JsonlFileTelemetry 侧不动）。理由：close 期间
（span_end 行写出）本线程若发生 `recordEvent`，先 pop 时它无归属（可检测的缺席），
后 pop 时它会错挂到**已闭**跨度上（静默错归属）——与 `:73-76` ThreadLocal 设计注释
「把错归属变成可检测的缺席」同一原则。

java 待写（`PiLaneSink.endRequest` 一处换序，注释引 C5）：

```java
            ctx.telemetry().popCurrent(llmSpan); // C5：先 pop 后 close，与 JsonlFileTelemetry.startSpan 对称
            llmSpan.close();
            llmSpan = null;
```

全库扫查结论：push/pop 配对仅 `PiLaneSink:196,216-217` 一处；`PiLaneSink:528` 的
tool 跨度只有 close 无 push（不参与 C5）。✓

---

## 7. 裁决（四条）

### 7.1 T11 类型化跨度词汇：不做（移出分母）

pi 侧 935 LOC 中 ~280 LOC（`index.ts:26-354`）是 TS **编译期**类型机制
（`InferStartAttributes`／`ExactTelemetryAttributes`／`UnionToIntersection` 等条件类型）。
运行时残核仅 `bindTypedSpanStarter`（`index.ts:324-343`）= `startSpan({name, attributes},
callback)` 的薄包装。证据：§1 表——pi 全库**零消费者**；Java 无法表达该类型级推断；
R5：`bin pi` 不可达。⇒ 移出分母（35→34），map/01 T11 注「裁决不做（docs/22 §7.1）」。

### 7.2 T4 `AttributeValue` 闭集：维持 `Map<String,Object>`（转对齐）

pi 的闭集（`index.ts:1`：`string | number | boolean | 三类数组`）是 TS 类型层约束；
运行时 pi 不做任何校验（`copyAttributeValue` 只浅拷数组，`memory.ts:50-52`）。行为面上
java 的 `Map<String,Object>` 生产值域与 pi 相同（JSON 序列化同值等价）。引入密封
`AttributeValue` 类型要改全部生产者调用点，**零行为收益**。⇒ 裁决维持，T4 转对齐。

### 7.3 T1/T2 结构性差异：维持现状（转对齐）

- 同步 `Function` 回调 vs pi 的 `Promise`：java 无事件循环，同步回调行为等价
  （pi 套件用例 1 本身就要求「synchronously admitted」，`conformance.ts:79`）。
- `AutoCloseable.close()` vs pi 的 startSpan-owns-settlement：java 需要 `openSpan`
  跨线程跨度（`PiLaneSink` llm.request 跨请求起止），close 幂等＝pi settle 幂等
  （`memory.ts:95`）。两差异已在 `TelemetryContext.java:13-26` javadoc 登记（原 docs/31
  §8.28），本批维持并补「证据已随文档清理入 git」注。⇒ T1/T2 转对齐。

### 7.4 T12–T17 java 扩展：扩展·确认（计满分留分母）

T12 `openSpan`／T13 `recordEvent`+`pushCurrent`/`popCurrent`／T14 指标／T15 `with`／
T16 `JsonlFileTelemetry`／T17 `OtelTelemetryContext` 均为 pi 无对照物的 java 自主
可观察性建设（可观察性 9/9 已闭环）。判据是行为：pi 无此行为面，不存在「差距」。
计分口径：**留在分母、计对齐**——不移出分母是为了 drift 检测（pi 若将来新增对照物
——如官方 exporter——条目原位可重测翻红）。行为正确性由本批一致性套件（fixture 三挂）
＋ 既有 31 测试钉住。⇒ 六条转「扩展·确认（对齐）」，map/01 判定列改注。

### 7.5 B241（新登即销）：ai 层 `telemetryContext` 透传

pi `ai/src/types.ts:159-160` 声明 `ProviderRequestOptions.telemetryContext?`，
`simple-options.ts:48` 透传——但 pi 全库**零设置点**（§1 表），是 dormant 公共 API
选项；R5 行为不可达。pi-java ai 无对应字段。⇒ 取证关单不做，台账登记 B241 防重复
取证；map/02 无需变动（该面从未入分母）。

---

## 8. 账面与回填

| 条目 | 权重 | 现判定 | 现得分 | 新判定 | 新得分 | Δ |
|---|---|---|---|---|---|---|
| T1 startSpan | 3 | 存疑（形状） | 1.5 | 对齐（§7.3） | 3 | +1.5 |
| T2 TelemetrySpan | 3 | 存疑（形状） | 1.5 | 对齐（§7.3） | 3 | +1.5 |
| T3 SpanOptions | 3 | 对齐 | 3 | 对齐 | 3 | 0 |
| T4 AttributeValue | 1 | 存疑（形状） | 0.5 | 对齐（§7.2） | 1 | +0.5 |
| T5 addEvent | 1 | 缺失 | 0 | 对齐（§2） | 1 | +1 |
| T6 setAttributes | 2 | 存疑（形状） | 1 | 对齐（§2） | 2 | +1 |
| T7 setStatus | 2 | 缺失 | 0 | 对齐（§2） | 2 | +2 |
| T8 NOOP | 3 | 对齐 | 3 | 对齐 | 3 | 0 |
| T9 InMemory | 1 | 缺失 | 0 | 对齐（§3） | 1 | +1 |
| T10 conformance | 1 | 缺失 | 0 | 对齐（§4） | 1 | +1 |
| T11 typed schema | 1 | 缺失 | 0 | **移出分母**（§7.1） | — | 0 |
| T12 openSpan | 3 | 存疑（扩展） | 1.5 | 扩展·确认（§7.4） | 3 | +1.5 |
| T13 recordEvent 等 | 2 | 存疑（扩展） | 1 | 扩展·确认（§7.4） | 2 | +1 |
| T14 指标 | 3 | 存疑（扩展） | 1.5 | 扩展·确认（§7.4） | 3 | +1.5 |
| T15 with | 2 | 存疑（扩展） | 1 | 扩展·确认（§7.4） | 2 | +1 |
| T16 JsonlFile | 3 | 存疑（扩展） | 1.5 | 扩展·确认（§7.4） | 3 | +1.5 |
| T17 Otel | 1 | 存疑（扩展） | 0.5 | 扩展·确认（§7.4） | 1 | +0.5 |
| **合计** | **35→34** | | **17.5** | | **34** | **+16.5** |

**50.0% → 100%**（未加权 7/17 → 16/16）。

闭环回填（记忆：banner 是活状态，两处＋地图）：

1. `docs/map/01-infra.md`：T 表判定列逐条更新＋§C/D/H 表（C5/C7/H 三行销号注
   「docs/22 闭环」）＋§分权重档表重算＋规模行（java 侧 LOC 增长）；
2. `docs/05-module-alignment-map.md`：telemetry 行 50.0%→100%、§2.7 小节改写、
   总表联动（telemetry 计入总表，权重 +16.5）；
3. `docs/04-open-items-register.md`：banner 加批 1 闭环段＋B241 登即销一行；
4. `TelemetryContext.java`／`TelemetrySpan.java` javadoc：删「不移植」裁决段，
   改引本稿 §2.1 翻转说明。

---

## 9. 实施序列（commit 粒度）与验证

| # | commit | 内容 | 规模 |
|---|---|---|---|
| 1 | `feat(telemetry): span contract completion — addEvent/setAttributes/setStatus + SpanStatus ADT` | §2：SpanStatus 新文件；TelemetrySpan 三方法＋addAttribute 反转；Noop 维持；Jsonl/Otel 覆盖点换向（行为不变，纯契约） | ~150 行 |
| 2 | `feat(telemetry): InMemoryTelemetryContext port of pi memory.ts` | §3：三新文件＋单测（pi `telemetry.test.ts` 行为面＋快照 detached 用例 `conformance.test.ts:23-45`） | ~350 行 |
| 3 | `feat(telemetry): adapter conformance suite (7 cases / 5 groups) with InMemory+Jsonl+OTel fixtures` | §4：testing 包四文件＋Jsonl 改造（§5 显式状态/setAttributes/addEvent，套件转红的前提）＋三 fixture＋三挂载类 | ~550 行 |
| 4 | `fix(agent-core): unify pop-before-close order in PiLaneSink.endRequest (C5)` | §6 一处换序＋注释 | ~5 行 |
| 5 | `docs(telemetry): batch-1 closure — map/01 + 总表 + 台账 banner + javadoc 回填` | §8 全部回填 | 文档 |

验证（PR 前自验证口径）：

- commit 3 落地时：三 adapter × 7 用例 全绿；**先用红->绿自证**（Jsonl 改造前跑套件
  必红：显式状态用例 3 红、addEvent 用例 4 红——证明套件真把形状差变红）；
- `mvn clean verify` 零错误零警告；agent-core／coding-agent 全量回归（契约改动波及
  Jsonl/Otel 覆盖点）；
- 无 `System.out.println` 残留；checkstyle 过。

风险：

- **commit 1 波及面**：`TelemetrySpan` 加 default 方法不破外部实现；但 Jsonl/Otel 的
  `addAttribute` 覆盖点换向 `setAttributes` 若漏改，单键便捷会静默丢属性 ⇒ commit 1
  自带「两 adapter 现有测试全绿」为门禁（JsonlFileTelemetryTest 18 测 /
  OtelTelemetryContextTest 8 测已钉属性行为）。
- **Jsonl 行格式新增 `attrs` event 行**：无现存 java 调用点调 `addEvent` ⇒ 不改变
  既有会话 trace 输出（只在新 API 被调用时产行）。
