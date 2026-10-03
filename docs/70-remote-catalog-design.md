# 70 — 远程模型目录运行时覆盖 / ModelsStore 机制对齐设计

> 对齐基准：pi 锚点 `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`
> 归属：docs/48 §5 行 D「内置模型目录运行时覆盖/重生成机制」（P1）。
> 编制日期：2026-10-03。
>
> **✅ 状态：已批准（R1–R8 全按建议，2026-10-03）—— 实施中。**
> 实施记录见 §12。

---

## 0. 范围

**本包对齐 pi 的远程目录覆盖机制**：静态 generated provider 目录在运行时被
pi.dev（或自托管 base）的 per-provider 目录覆盖，目录持久化在 agent 目录、
按 ETag 条件刷新、4 小时新鲜度窗口、启动两阶段（离线恢复 → 后台联网）。

**本包做**：

1. `ModelsStore` 文件实现改为 pi 的**单文件 `models-store.json`** 形状（Record
   keyed by provider），替换现有「每 provider 一文件」实现。
2. Provider 动态协议：`getModels()`（静态 ∪ 动态）＋ 可选
   `refreshModels(RefreshModelsContext)`，对齐 pi `Provider` 接口。
3. `withRemoteCatalog(provider, baseUrl, localGeneratedAt)` 等价包装：恢复缓存、
   TTL、条件 GET、304/404/501/transient 分支、三形态 catalog 解析、
   generatedAt 新鲜度守卫。
4. 世代（generation）发布协议：`publish()` 返回 boolean，过期 refresh 的发布被拒。
5. coding-agent 装配：DefaultProviders 对每个 builtin（radius 等价物除外）包远程
   目录；启动两阶段编排；RPC 模式启动后台刷新（对齐 pi main.ts:924-932）。
6. 配置面：`PI_OFFLINE` 环境变量、`catalogBaseUrl` 选项。

**本包不做（明确排除）**：

- **生成器里「新增全新 provider 变换」**：从 models.dev 重算数据的脚本工作（属
  provider 接入/数据工程，不是运行时机制）。
- **catalog 上传（PUT）**：pi 运行时没有该路径；现有 Java `CatalogPublisher`
  的 PUT 上传是本仓自创、零生产调用 ⇒ 裁决见 R4。
- TUI 内的交互刷新按钮/登录后定向刷新的具体 UI 接线（机制先落；TUI 侧接线跟随
  TUI 包，本包只留可调用的 `refresh()` 编排入口）。
- models.dev 数据抓取脚本（`models-dev-reasoning-options.ts` 等生成期工具）。

---

## 1. pi 事实（锚点逐行取证）

### 1.1 持久化抽象 — `packages/ai/src/models-store.ts`

```ts
export interface ModelsStoreEntry {
	models: readonly Model<Api>;
	lastModified?: number;   // Unix ts from remote Last-Modified header
	checkedAt?: number;      // Unix ts of last completed remote check
	etag?: string;           // opaque validator, verbatim (quotes included)
}

export interface ModelsStore {
	read(providerId: string, options?): Promise<ModelsStoreEntry | undefined>;
	write(providerId: string, entry: ModelsStoreEntry, options?): Promise<void>;
	delete(providerId: string, options?): Promise<void>;
}
```

### 1.2 Provider 动态协议 — `packages/ai/src/models.ts:99-129`

```ts
export interface Provider<TApi extends Api = Api> {
	// ...
	getModels(): readonly Model<TApi>;   // static catalog; dynamic: as of last refreshModels()
	refreshModels?(context: RefreshModelsContext): Promise<void>;
}

export interface RefreshModelsContext {
	credential?: Credential;
	stored?: Readonly<ModelsStoreEntry>;                 // snapshot before this phase
	publish(publication: ModelsPublication): Promise<boolean>;  // generation-checked
	allowNetwork: boolean;                                // false during offline init
	force?: boolean;                                      // bypass freshness TTL
	signal: AbortSignal;
}

export interface ModelsPublication {
	persist?: ModelsStoreEntry | null;   // omit = storage unchanged; null = delete
	update?: () => void;                 // sync in-memory update after persist wins
}
```

### 1.3 远程目录包装 — `packages/coding-agent/src/core/remote-catalog-provider.ts`

常量与 merge：

```ts
const DEFAULT_CATALOG_BASE_URL = "https://pi.dev";
const REMOTE_CATALOG_ATTEMPT_TIMEOUT_MS = 4_000;
export const REMOTE_CATALOG_REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000;

function mergeModels(baseline, dynamic) {
	const merged = [...baseline];
	for (const model of dynamic) {
		const index = merged.findIndex((entry) => entry.id === model.id);
		if (index >= 0) merged[index] = model; else merged.push(model);
	}
	return merged;
}
```

三形态解析（数组 / `{models:[]}` / 任意对象取 values）：

```ts
function parseCatalog(providerId, value): Model<Api>[] {
	const entries = Array.isArray(value) ? value
		: typeof value === "object" && value !== null && "models" in value && Array.isArray(value.models) ? value.models
		: typeof value === "object" && value !== null ? Object.values(value)
		: undefined;
	if (!entries) throw new Error(`Invalid model catalog for provider "${providerId}"`);
	return entries
		.filter((entry): entry is Model<Api> => typeof entry === "object" && entry !== null && "id" in entry)
		.map((model) => ({ ...model, provider: providerId }));
}
```

generatedAt 守卫（远端不比本地新 ⇒ overlay 不生效）：

```ts
function remoteModels(entry: ModelsStoreEntry | undefined, localGeneratedAt?: number) {
	if (!entry) return [];
	if (localGeneratedAt !== undefined &&
		(entry.lastModified === undefined || entry.lastModified <= localGeneratedAt)) return [];
	return entry.models;
}
```

`withRemoteCatalog` 的 `refreshModels` 全分支（逐字）：

```ts
refreshModels: async (context) => {
	const stored = context.stored;
	const restored = remoteModels(stored, localGeneratedAt)
		.filter((model) => model.provider === provider.id);
	if (!(await context.publish({ update: () => { dynamicModels = restored; } })) return;
	if (!context.allowNetwork || context.signal.aborted) return;
	if (!context.force && stored?.checkedAt !== undefined && stored.lastModified !== undefined &&
		Date.now() - stored.checkedAt < REMOTE_CATALOG_REFRESH_INTERVAL_MS) return;

	// validator only when a cached body backs it, so a 304 can never empty the overlay
	const validator = stored?.models.length ? stored.etag : undefined;
	const url = new URL(`/api/models/providers/${encodeURIComponent(provider.id)}`, catalogBaseUrl);
	const response = await fetchWithRetry(url, {
		headers: { accept: "application/json", "User-Agent": getPiUserAgent(VERSION),
			...(validator ? { "if-none-match": validator } : {}) },
		signal: context.signal,
	}, { attemptTimeoutMs: REMOTE_CATALOG_ATTEMPT_TIMEOUT_MS });
	if (context.signal.aborted) return;
	const checkedAt = Date.now();
	if (response.status === 304 && stored) {
		await context.publish({ persist: { ...stored, checkedAt } });
		return;
	}
	if (response.status === 404 || response.status === 501) {
		await context.publish({ persist: { ...(stored ?? { models: [] }), checkedAt,
			lastModified: 0, etag: undefined } });
		return;
	}
	if (!response.ok) {
		// transient: keep etag so next refresh revalidates instead of downloading
		await context.publish({ persist: { ...(stored ?? { models: [] }), checkedAt } });
		throw new Error(`Model catalog request failed for ${provider.id}: ${response.status}`);
	}
	const refreshed = parseCatalog(provider.id, await response.json());
	const lastModified = Date.parse(response.headers.get("last-modified") ?? "");
	if (context.signal.aborted) return;
	const entry = { models: refreshed, checkedAt,
		lastModified: Number.isNaN(lastModified) ? 0 : lastModified,
		etag: response.headers.get("etag") ?? undefined };
	const published = remoteModels(entry, localGeneratedAt);
	await context.publish({ persist: entry, update: () => { dynamicModels = published; } });
},
```

注意 404/501 的语义：`lastModified: 0` ＋ etag 清除 —— 标记「远端确认无此
provider」，后续 `remoteModels` 对 localGeneratedAt 守卫：`0 <= localGeneratedAt`
恒真 ⇒ overlay 空，且不再带 ETag（无缓存 body）。

### 1.4 世代发布 — `packages/ai/src/models.ts:340-377`

```ts
private async publishProviderModels(providerId, generation, signal, publication) {
	// serialized per provider via publicationChains (queue)
	if (publication.persist === null) await this.modelsStore.delete(providerId, { signal });
	else if (publication.persist !== undefined)
		await this.modelsStore.write(providerId, structuredClone(publication.persist), { signal });
	if (signal.aborted || this.refreshGenerations.get(providerId) !== generation) return false;
	publication.update?.();
	return true;
}
```

### 1.5 两阶段刷新编排 — `models.ts:398-430`

```ts
const refresh = Promise.all(refreshable.map(async (provider) => {
	const { generation, controller } = this.beginProviderRefresh(provider.id);
	const signal = AbortSignal.any([callerSignal, controller.signal]);
	const operation = (async () => {
		// credential resolution ...
		await this.runProviderRefreshPhase(provider, storedCredential, /* allowNetwork */ false, undefined, generation, signal);
		if (!allowNetwork || signal.aborted) return;
		const credential = await this.resolveRefreshCredential(provider, storedCredential, signal);
		if (!credential) return;
		await this.runProviderRefreshPhase(provider, credential, true, options.force, signal);
	})();
	await raceWithAbortSignal(operation, signal);
}));
```

**先离线恢复（allowNetwork=false）⇒ 再联网**：保证无网/慢网时缓存 overlay
立即可用。

### 1.6 实际触发联网刷新的入口

| 入口 | 位置 | allowNetwork |
|---|---|---|
| **RPC 模式启动后台刷新** | `main.ts:924-932` | true（默认），15s 超时，错误吞掉 |
| **交互模式 TUI 初始化后** | `modes/interactive/model-catalog-refresh.ts` → `refreshModelCatalogs` | true，coordinator 合并并发、各调用方独立取消 |
| TUI 认证 provider 后定向刷新 | `interactive-mode.ts:5878-5894` | true，`providers:[id]` |
| 包管理器命令 | `package-manager-cli.ts:590-596` | true，`force:true` |
| **单次 print/CLI** | `main.ts:163` | **false**（`allowModelNetwork:false`） |
| SDK 用户 | sdk.md:380 | 显式 `allowModelNetwork:true` |

### 1.7 远端 catalog 的真实 wire 形状

pi.dev 返回的是 **pi `Model` 完整对象**（types.ts:959-992），字段（TS 形状）：

```
id, name, api, provider, baseUrl, reasoning, thinkingLevelMap?, input[],
cost{input,output,cacheRead,cacheWrite,tiers?}, promptCache?,
contextWindow, maxTokens, samplingParams?, headers?, compat?
```

测试金标（`remote-catalog-provider.test.ts:75-95`）：body 为
`{"dynamic": {id:"dynamic", name:"dynamic", api:"openai-completions", …}}`
（keyed 对象 → Object.values 形态）。

---

## 2. Java 现状（逐文件实测）

| 文件 | 现状 | 接线情况 |
|---|---|---|
| `catalog/ModelsStoreEntry.java` | record（models/lastModified/checkedAt/etag），**形状与 pi 一致** ✅ | 被下面两类使用 |
| `catalog/ModelsStore.java` | 接口 read/write/delete，**形状一致** ✅ | — |
| `catalog/InMemoryModelsStore.java` | Map 实现 ✅ | 测试 + 夹具 |
| `catalog/FileModelsStore.java` | **每 provider 一个文件**（`{dir}/{id}.json`），DTO 经 CatalogModel；默认 `~/.pi-java/agent/catalogs/` | **无生产调用** |
| `catalog/RemoteCatalog.java` | 独立 `ModelCatalog` 实现：单端点 ETag 刷新，200/304/离线回退 | **孤儿：仅自身测试实例化** |
| `catalog/CatalogPublisher.java` | validate/merge/ETag/**HTTP PUT 上传** | **孤儿：仅自身测试** |
| `catalog/CatalogModel.java` | Java 自创 wire DTO（provider/model/capabilities/...） | FileModelsStore 序列化、RemoteCatalog |
| `catalog/ModelCatalog.java` | 静态接口：listModels/find/search | Provider.builtinModels() 返回 |
| `catalog/BuiltinCatalog.java` | 静态 List 封装 | ModelData/各处 |
| `provider/builtin/ModelData.java` | 手写静态数据；**无 generatedAt 时间戳** | builtin provider |
| `provider/Provider.java` | SPI：name/displayName/**builtinModels() 静态**/createApi；**无 getModels/refreshModels** | ProviderRegistry |
| `core/DefaultProviders.java` | loadBuiltin + ServiceLoader + models.json OverrideProvider；**无远程包装、无启动 refresh** | Main/RpcMode/TUI 间接 |
| `rpc/RpcMode.java` | RPC 命令 | 无目录刷新钩子 |
| Main.java | 模式分派（print/rpc/web/text/tui） | 无 PI_OFFLINE/catalogBaseUrl |

---

## 3. 差距清单

| # | 差距 | pi 对应 | 严重度 |
|---|---|---|---|
| G1 | Provider 无动态协议（getModels/refreshModels） | §1.2 | **结构性** |
| G2 | 无 `withRemoteCatalog` 等价包装（TTL/条件GET/全分支/三形态/守卫） | §1.3 | **核心** |
| G3 | FileModelsStore 形状偏离（每 provider 文件 vs 单文件 Record） | §1.1 | 中 |
| G4 | 无世代 publish（generation 计数、过期返回 false） | §1.4 | 中 |
| G5 | 无两阶段编排（离线恢复 → 联网） | §1.5 | 中 |
| G6 | RPC 启动无后台刷新；无 PI_OFFLINE/catalogBaseUrl 通道 | §1.6 | 中 |
| G7 | **远端 wire 不兼容**：CatalogModel（Java 自创）≠ pi Model wire | §1.7 | **需裁决** |
| G8 | 无 localGeneratedAt（ModelData 无 manifest 时间戳） | §1.3 remoteModels | 小，需裁决 |
| G9 | 孤儿 RemoteCatalog/CatalogPublisher 的处置 | — | 清理 |

---

## 4. 实施设计（Java 草图）

### 4.1 Provider 动态协议（G1）

`Provider.java` 加两个 default，零破坏：

```java
/** 当前生效目录：静态 provider 返回 builtinModels 的列表。 */
default List<ModelInfo> getModels() {
    return builtinModels().listModels();
}

/** 动态 provider 覆盖；静态 provider 不刷新（结构上无 refresh）。 */
default void refreshModels(RefreshModelsContext context) { /* static: no-op */ }
```

`RefreshModelsContext`（新，ai 模块 `catalog` 包）：

```java
public record RefreshModelsContext(
    Optional<Credential> credential,
    Optional<ModelsStoreEntry> stored,
    CatalogPublisherPort publish,   // publication -> boolean（世代检查）
    boolean allowNetwork,
    boolean force) {

    static RefreshModelsContext offline(Optional<ModelsStoreEntry> stored,
                                        CatalogPublisherPort publish) {
        return new RefreshModelsContext(Optional.empty(), stored, publish, false, false);
    }
    static RefreshModelsContext online(Optional<ModelsStoreEntry> stored,
                                       CatalogPublisherPort publish, boolean force) {
        return new RefreshModelsContext(Optional.empty(), stored, publish, true, force);
    }
}

@FunctionalInterface
public interface CatalogPublisherPort {
    boolean publish(ModelsPublication publication);
}

public record ModelsPublication(
    Optional<ModelsStoreEntry> persist,
    boolean delete,
    Runnable update) {
    static ModelsPublication updateOnly(Runnable update) {
        return new ModelsPublication(Optional.empty(), false, update);
    }
    static ModelsPublication persist(ModelsStoreEntry e) {
        return new ModelsPublication(Optional.of(e), false, () -> {});
    }
    static ModelsPublication persistAndUpdate(ModelsStoreEntry e, Runnable update) {
        return new ModelsPublication(Optional.of(e), false, update);
    }
}
```

**中断形状裁决见 R2**：建议不支持中途取消、只靠 HTTP 4s 超时，context 不放
signal（pi 的 AbortSignal 在 Java 启动后台刷新场景非必需）。

### 4.2 远程目录包装（G2）—— `RemoteCatalogProvider.java`

落在 coding-agent（对齐 pi：withRemoteCatalog 在 coding-agent，不在 ai）：

```java
/** 包装静态 provider：getModels 合并动态 overlay；refreshModels 走 §1.3 全分支。 */
public final class RemoteCatalogProvider implements Provider {

    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(4);
    static final Duration REFRESH_INTERVAL = Duration.ofHours(4);

    private final Provider delegate;
    private final String catalogBaseUrl;
    private final Optional<Instant> localGeneratedAt;
    private volatile List<ModelInfo> dynamic = List.of();

    private RemoteCatalogProvider(Provider d, String base, Optional<Instant> genAt) { ... }

    public static Provider wrap(Provider d, String base, Optional<Instant> localGeneratedAt) {
        return new RemoteCatalogProvider(d, base, genAt);
    }

    @Override public String name() { return delegate.name(); }
    @Override public List<ModelInfo> getModels() { return merge(delegate.getModels(), dynamic); }

    @Override public void refreshModels(RefreshModelsContext ctx) {
        var stored = ctx.stored();
        var restored = remoteModels(stored, localGeneratedAt).stream()
            .filter(m -> m.id().provider().equals(delegate.name())).toList();
        if (!ctx.publish().accept(ModelsPublication.updateOnly(() -> dynamic = restored))) return;
        if (!ctx.allowNetwork()) return;
        if (!ctx.force() && isFresh(stored.orElse(null))) return;
        // ... 条件 GET、304/404/501/!ok/200 分支（逐行照 §1.3）
    }
}
```

HTTP 用 JDK `java.net.http.HttpClient`（R3）：

```java
HttpRequest.Builder rb = HttpRequest.newBuilder()
    .uri(URI.create(catalogBaseUrl + "/api/models/providers/"
        + URLEncoder.encode(delegate.name(), StandardCharsets.UTF_8)))
    .timeout(ATTEMPT_TIMEOUT)
    .header("accept", "application/json")
    .header("User-Agent", PiUserAgent.get());
validator.ifPresent(v -> rb.header("If-None-Match", v));
HttpResponse<String> response = http.send(rb.build(), BodyHandlers.ofString());
```

分支（全照 pi，文案见 §6）：

- **304**：`publish(persist(stored.withCheckedAt(now)))`，dynamic 不动。
- **404/501**：`persist(models=[](stored), lastModified=Instant.EPOCH(=0), etag=null)`。
- **其他非 2xx**：`persist(... checkedAt=now, 保留 etag)`，**throw**
  `Model catalog request failed for {id}: {status}`。
- **200**：`parseCatalog` → entry（Last-Modified RFC1123 解析、ETag 头）；
  `remoteModels(entry)` 守卫后
  `publish(persistAndUpdate(entry, () -> dynamic=published))`。

`Instant.EPOCH` 对应 pi 的 `0`；守卫 `!entry.lastModified().isAfter(localGeneratedAt)`
⇒ EPOCH 恒不晚于任何 local ⇒ overlay 空。

三形态解析与 pi 同：

```java
List<JsonNode> entries = switch (root) {
    case ARRAY -> elements;
    case OBJECT when root.has("models") && root.get("models").isArray() -> ...;
    case OBJECT -> root.properties().values().stream()
        .filter(JsonNode::isObject).toList();
    default -> throw new IllegalStateException(
        "Invalid model catalog for provider \"" + id + "\"");
};
```

### 4.3 FileModelsStore 单文件（G3）

重写 `FileModelsStore`（原实现无生产调用者 ⇒ 直接换形）：

```java
public final class FileModelsStore implements ModelsStore {
    // 单文件 models-store.json：Map<providerId, Entry>
    // entry 的 models 经 §4.4 wire 映射（不再是 CatalogModel）
    private final Path file;
    private final ObjectMapper json = Json.mapper();

    @Override public Optional<ModelsStoreEntry> read(String providerId) {
        return Optional.ofNullable(loadMap().get(providerId));
    }
    @Override public synchronized void write(String id, ModelsStoreEntry e) {
        var map = loadMap(); map.put(id, e); save(map);
    }
    @Override public synchronized void delete(String id) { ... }
}
```

**文件锁**：pi 用 AuthStorage 的跨进程文件锁。Java coding-agent 单进程，
synchronized 足够（R6）。默认路径改 `AgentDir.resolve("models-store.json")`。

### 4.4 远端 wire 映射（G7 —— 需 R1 裁决）

**建议：新建 pi Model wire 的 Java DTO，宽松解析**，而不是 CatalogModel：

```java
/** pi.dev Model wire（types.ts:959）的宽松映射；Java 只用 ModelInfo 需要的子集。 */
public record RemoteModelWire(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("contextWindow") int contextWindow,
    @JsonProperty("maxTokens") int maxTokens,
    @JsonProperty("reasoning") boolean reasoning,
    @JsonProperty("input") List<String> input,
    @JsonProperty("cost") RemoteCostWire cost,
    @JsonProperty("thinkingLevelMap") Map<String, String> thinkingLevelMap
    // api/baseUrl/headers/samplingParams/promptCache/compat: 忽略
) {
    ModelInfo toModelInfo(String providerId) {
        return new ModelInfo(
            ModelId.of(providerId, id),
            name != null ? name : id,
            capabilitiesFrom(input, reasoning),
            contextWindow, maxTokens, /*deprecated*/ false,
            new PricingInfo(cost != null ? cost.input() : 0,
                            cost != null ? cost.output() : 0),
            thinkingMapFrom(thinkingLevelMap));
    }
}

record RemoteCostWire(double input, double output,
                      double cacheRead, double cacheWrite) {}
```

ObjectMapper 配置 `FAIL_ON_UNKNOWN_PROPERTIES=false`（pi 字段超集，前向兼容）。

`id` 是纯模型名（pi Model.id 不含 provider；pi parseCatalog 用
`{...model, provider: providerId}` 覆盖）。

### 4.5 世代发布（G4）—— `CatalogRefreshCoordinator`

```java
public final class CatalogRefreshCoordinator {
    private final Map<String, Integer> generations = new HashMap<>();
    private final Map<String, Object> locks = new HashMap<>();
    private final ModelsStore store;

    public int begin(String providerId) {
        return generations.merge(providerId, 1, Integer::sum);
    }

    /** pi publishProviderModels：先持久化、世代仍匹配才跑 update、返回是否接受。 */
    public boolean publish(String providerId, int generation,
                            ModelsPublication publication) {
        synchronized (lock(providerId)) {
            if (publication.delete()) store.delete(providerId);
            publication.persist().ifPresent(e -> store.write(providerId, e));
            if (generations.get(providerId) != generation) return false;
            publication.update().run();
            return true;
        }
    }
}
```

### 4.6 两阶段编排与触发（G5/G6）—— `ModelCatalogRefresh`

```java
public final class ModelCatalogRefresh {

    /** 两阶段：先全部离线恢复，再（allowNetwork 时）全部联网。 */
    RefreshResult refresh(List<Provider> providers, boolean allowNetwork, boolean force) {
        var errors = new LinkedHashMap<String, RuntimeException>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var phase1 = new ArrayList<Future<?>>();
            for (Provider p : providers) if (isDynamic(p))
                phase1.add(executor.submit(() -> runPhase(p, false, false)));
            for (Future<?> f : phase1)
                try { f.get(); } catch (ExecutionException e) { recordError(errors, e); }
            if (!allowNetwork) return new RefreshResult(errors);
            var phase2 = new ArrayList<Future<?>>();
            for (Provider p : providers) if (isDynamic(p))
                phase2.add(executor.submit(() -> runPhase(p, true, force)));
            for (Future<?> f : phase2)
                try { f.get(); } catch (ExecutionException e) { recordError(errors, e); }
        }
        return new RefreshResult(errors);
    }

    private void runPhase(Provider p, boolean allowNetwork, boolean force) {
        int generation = coordinator.begin(p.name());
        var ctx = allowNetwork
            ? RefreshModelsContext.online(store.read(p.name()),
                pub -> coordinator.publish(p.name(), generation, pub), force)
            : RefreshModelsContext.offline(store.read(p.name()),
                pub -> coordinator.publish(p.name(), generation, pub));
        p.refreshModels(ctx);
    }
}
```

**装配点 DefaultProviders**（对齐 model-runtime.ts:183-190）：

```java
static ProviderRegistry defaultProviders(ProviderRefreshOptions opts) {
    var registry = ... // 现有装配（builtin + ServiceLoader + models.json）
    // models.json OverrideProvider 之后，对每个非排除 provider 包远程目录
    registry.replaceAll((id, p) -> isExcluded(id) ? p
        : RemoteCatalogProvider.wrap(p, opts.catalogBaseUrl(), builtinGeneratedAt(id)));
    return registry;
}
```

顺序：**先 models.json 合并（内层）再包远程（外层）**。

**RPC 后台刷新（G6）**：RpcMode 启动后（对齐 main.ts:924-932）：

```java
if (!opts.offline()) {
    Thread.startVirtualThread(() -> {
        try { catalogRefresh.refresh(dynamicProviders, true, false); }
        catch (Exception ignored) { }   // pi: .catch(() => {})
    });
}
```

`PI_OFFLINE`：`boolean offline = envPresent("PI_OFFLINE")`；
`catalogBaseUrl`：settings 字段＋缺省 `https://pi.dev`（R7）。

### 4.7 localGeneratedAt（G8 —— R5 裁决）

**建议 ModelData 常量**：

```java
/** 静态目录生成时间（对齐 pi modelDataManifest.generatedAt）。
 *  更新手写数据时同步；测试钉「常量存在且为有效 Instant」。 */
public static Instant generatedAt() { return GENERATED_AT; }
```

备选 empty（守卫失效，远端恒覆盖；pi 有守卫，建议照做）。

---

## 5. 裁决项（请回复 R 项决议）

| # | 裁决 | 建议 |
|---|---|---|
| **R1** | **远端 wire 方向**：新建 pi Model wire DTO（真实兼容 pi.dev）vs 沿用 CatalogModel（只对齐机制、不互通 pi.dev） | **新建 pi wire DTO**（§4.4）；store 与远端统一一种形状，CatalogModel 删除 |
| **R2** | **取消/中断形状**：AtomicBoolean 取消标志 vs 不支持中途取消（仅 HTTP 4s 超时） | **不支持中途取消**（cancelled 恒 false；4s attempt timeout 已封顶） |
| **R3** | **HTTP 客户端**：JDK `java.net.http.HttpClient` vs 现有 PiHttpClient | **JDK HttpClient**（timeout 天然、每协调器一个实例） |
| **R4** | **孤儿处置**：RemoteCatalog 删；CatalogPublisher 的 PUT 上传删；validate/merge 是否保留 | **RemoteCatalog 删**；**PUT 删**（pi 无、零生产）；实施前 grep 全部调用点，无调用则整文件连测试删 |
| **R5** | **localGeneratedAt**：ModelData 常量（手工维护）vs 不传（守卫失效） | **ModelData 常量**（§4.7），测试钉住 |
| **R6** | **models-store.json 跨进程锁**：synchronized vs 文件锁 | **synchronized**（单进程；对齐 settings 现有写法） |
| **R7** | **catalogBaseUrl 通道**：settings 字段＋缺省 pi.dev vs 再加 CLI 旗标 | **settings 字段＋缺省 pi.dev**；不加 CLI 旗标（pi 也无） |
| **R8** | **TUI 接线范围**：本包只做 RPC 后台刷新＋编排入口 vs TUI 启动/登录刷新一并做 | **只做 RPC 后台刷新＋留 `ModelCatalogRefresh` 入口**；TUI 接线随 TUI 包 |

---

## 6. 错误文案（逐字对齐）

| 场景 | 文案 |
|---|---|
| 坏 catalog（非数组/对象） | `Invalid model catalog for provider "<id>"` |
| transient 非 2xx | `Model catalog request failed for <id>: <status>` |
| HTTP 发送失败 | JDK HttpClient 异常（message 含 URL）；pi 走 fetchWithRetry 包装 |

304 无文案（静默更新 checkedAt）；404/501 静默持久化标记。

---

## 7. 测试计划（RED 先于实现）

### 7.1 新夹具

1. **GET 请求形状**（RecordingHttpServer 模拟 pi.dev）：路径
   `/api/models/providers/openai`；头含 accept、User-Agent；有缓存 body 带
   If-None-Match、空 body **不带**。
2. **200 三形态**：数组 / `{models:[]}` / `{key:Model}` ⇒ getModels 合并
   （static 保留、同 id 覆盖、新 id 追加），持久化 Last-Modified/ETag。
3. **304**：dynamic 不变、checkedAt 更新、不重下。
4. **404/501**：lastModified=EPOCH、etag 清除；overlay 空。
5. **5xx/网络错误**：throw `Model catalog request failed…`；etag 保留。
6. **TTL**：4h 内零请求；force=true 发请求。
7. **generatedAt 守卫**：Last-Modified ≤ local ⇒ dynamic 空。
8. **世代 publish**：旧 refresh 的 publish 返回 false、update 不跑。
9. **两阶段**：allowNetwork=false ⇒ 零请求、缓存 overlay 立即恢复。
10. **FileModelsStore 单文件**：两 provider ⇒ 一个文件 Map 两键；round-trip。
11. **pi wire 映射**：完整 Model（含 tiers/compat 未知字段）解析不报错。
12. **DefaultProviders 装配**：非排除 builtin 被包；排除项不包。
13. **PI_OFFLINE**：env 设置 ⇒ RPC 零请求。

### 7.2 RED 安排

- 结构新增走编译红；行为分支 stash 实现取真红；触发面（RPC 后台）单独钉。
- RED 实红数填 §12，不预设计数。

### 7.3 变异探针（GREEN 后）

- **M1**：TTL 窗口检查删除（恒刷新）⇒ TTL 用例红。
- **M2**：ETag validator 恒带 ⇒ 空 body 请求头断言红。
- **M3**：merge 改同 id 追加（不覆盖）⇒ 覆盖用例红。
- **M4**：generatedAt 守卫删除 ⇒ 守卫用例红。
- **M5**：世代检查删除（publish 恒 true）⇒ 世代用例红。
- **M6**：404/501 不置 lastModified=0 ⇒ 404 分支用例红。

零红则登记「变异体语义等价」或补夹具。

---

## 8. 文件大小约束

- 新增/修改文件 ≤500；新逻辑优先新类。
- `Provider.java` 只加两个短 default；`DefaultProviders` 包装逻辑放进
  `RemoteCatalogProvider`/helper。
- 删孤儿反而减行；FileModelsStore 重写后应短于现状 104 行。

---

## 9. 实施步骤（每步全相关模块绿）

1. **pi wire DTO ＋ ModelsStore 单文件**：RemoteModelWire/RemoteCostWire、
   重写 FileModelsStore ＋ 测试；CatalogModel 步骤 6 清理。
2. **动态协议**：Provider getModels/refreshModels default＋context/publication/port。
3. **RemoteCatalogProvider**：wrap＋merge＋三形态＋守卫；GET 与 200 先红→绿。
4. **全分支**：TTL、304、404/501、transient；条件 ETag。
5. **协调器＋两阶段**：CatalogRefreshCoordinator＋ModelCatalogRefresh；
   DefaultProviders 装配；RpcMode 后台刷新＋PI_OFFLINE。
6. **清理**：删孤儿与 CatalogModel（若全无调用）＋测试；全模块回归＋checkstyle。
7. **文档闭环**：§12、docs/48 §5 行 D、台账更新。

跨模块：ai 改 Provider SPI（default 零破坏）→ coding-agent 接线；
回归 `mvn -pl pi-java-ai test` ＋ coding-agent `-am`；checkstyle。

---

## 10. 验收门槛

1. RED：旧实现下目标测试失败；结构不可红处说明并用变异证明有牙。
2. GREEN：目标夹具＋集成测试通过。
3. 变异：M1–M6 记录红集；零红须登记或补夹具。
4. 回归：ai/coding-agent（-am）全绿；checkstyle 0 新违规。
5. 无调试残留；改动文件 ≤500。
6. §12 与 docs/48 行 D 在收尾提交中回填。

---

## 11. 风险与备注

- **网络不确定性**：后台刷新必须真“后台”（虚拟线程、错误吞掉、不阻塞 RPC 启动），
  4s 是硬上限；启动主流程不等它（pi 用 `void`）。
- **pi wire 前向兼容**：pi Model 字段会增长，Mapper 必须忽略未知。
- **wrap 顺序**：models.json OverrideProvider 必须在远程包装内层；顺序错会让远端
  与用户配置互相错位覆盖。
- **radius 排除**：pi 对 radius 不包；Java 排除清单按实际动态 provider 调整。
- **G7 是最大形状风险**：若 R1 选 CatalogModel 方向，本包只对齐机制、不获得
  pi.dev 真实目录，功能价值大打折扣，故强烈建议 pi wire 方向。

<!-- §12 实施记录：批准并实施后回填（commit 序列、RED 实测、变异红集、偏差、台账） -->
