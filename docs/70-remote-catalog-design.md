# 70 — 远程模型目录运行时覆盖 / ModelsStore 机制对齐设计

> 对齐基准：pi 锚点 `3390bd93630965a12a0a1a5c36ce890ec22f7e1d`
> 归属：docs/48 §5 行 D「内置模型目录运行时覆盖/重生成机制」（P1）。
> 编制日期：2026-10-03。
>
> **✅ 状态：已闭环（2026-10-03）—— R1–R8 全按建议；实施记录见 §12。**

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

---

## 12. 实施记录

**2026-10-03 全 7 步闭环**（§9 的步骤 3 与 4 合成一次提交，理由见 §12.4-K）。

### 12.1 commit 序列

| 步骤 | commit | 内容 |
|---|---|---|
| 设计 | `fcff3a6` | docs/70 批准（R1–R8 全按建议） |
| 1 | `e92f547` | pi wire DTO（`RemoteModelWire`/`RemoteCostWire`）＋单文件 `FileModelsStore` |
| 2 | `0a3f522` | `Provider.getModels/refreshModels` default ＋ `RefreshModelsContext`/`ModelsPublication`/`CatalogPublisherPort` |
| 4 | `0d04310` | `ModelsStoreEntry.withCheckedAt` ＋ `ModelData.generatedAt`（R5） |
| 3+4 | `b361b6b` | `RemoteCatalogProvider`（全分支）＋ 17 夹具 |
| 5 | `d7ab24c` | `CatalogRefreshCoordinator` ＋ `ModelCatalogRefresh`（两阶段）＋ 装配/接线 |
| 6a | `297060d` | FileModelsStore 的 spotbugs 空指针修（`mvn test` 不跑 spotbugs，见 §12.5） |
| 6b | `711d898` | R4：删每 provider 的 models URL 通道 ＋ 孤儿 `RemoteCatalog`/`CatalogRefreshResult` |
| 6c | `d30c1d3` | 装配层整批共用一个 `HttpClient`（每包装一次新建会让一次刷新拉起 N 个选择器线程）；删未用的单参 `wrap` |
| 7 | `bf83ffa` | §12 回填 ＋ docs/48 §5 行 D ＋ docs/32 B143–B146 |

### 12.2 RED 实测

| 步骤 | RED | 实红 |
|---|---|---|
| 1 | 结构新增 ⇒ 编译红 | 新 API（`FileModelsStore.FILE_NAME`、`RemoteModelWire`）不存在；`git show HEAD:FileModelsStore.java \| grep -c FILE_NAME` = **0**。⚠️ 另有**两条夹具自身的编译错**（`twoProvidersShareOneStoreFile` 未声明 `IOException`、`RemoteModelWireTest` 漏 import `ModelId`）—— 那是夹具笔误、不是实现缺失，不构成 RED 证据 |
| 2 | 结构新增 ⇒ 编译红 | 8 处「找不到符号」（三个新类型 ＋ `Provider.getModels/refreshModels`） |
| 3+4 | **没取到** | 实现与夹具同批写 ⇒ 首跑 **4 红全是夹具错**（3 处 200 响应没给 `Last-Modified`，被 generatedAt 守卫挡掉；1 处把 404 的 overlay 语义当成「空」）⇒ 见 §12.5 第 1 条 |
| 5 | **没取到** | 首跑 **零红**（29/29 一次绿）—— 同上 |
| 6 | 回归为证 | 删除后 ai 1318⇒1312（`RemoteCatalogTest` 6 条）；`ConfigurableProviderRemoteCatalogTest` 80 行随 `modelsUrl` 面删除 |

### 12.3 变异探针（§7.3 的 M1–M6）

每次变异后 `grep` 复核落地，测完回滚、复跑全绿。

| 变异 | 红集 |
|---|---|
| **M1** TTL 恒假（`isFresh` ⇒ false） | 恰 1：`freshEntrySkipsRequestButForceBypasses` |
| **M2** validator 恒带（去掉 `!models.isEmpty()`） | 恰 1：`sendsValidatorOnlyWhenCachedBodyHasModels` |
| **M3** merge 同 id 只追加不覆盖 | 恰 1：`mergesArrayShapeReplacingSameIdAndAppendingNewId` |
| **M4** 去掉 generatedAt 守卫 | **3**（预测 1）：`generatedAtGuardDropsRemoteNotNewerThanLocal`、`notFoundZeroesLastModifiedAndClearsEtag`、`absentLastModifiedIsStoredAsEpochAndGuardedOut` |
| **M5** 去掉世代检查（publish 恒 true） | 恰 1：`staleGenerationStillPersistsButSkipsUpdateAndReturnsFalse` |
| **M6** 404/501 不置 `lastModified=EPOCH` | 恰 1：`notFoundZeroesLastModifiedAndClearsEtag` |

**M1–M6 无零红**；M4 的红集比预测宽，因为「守卫」同时被三处用例当作判别器（两处直接、一处经 404 的第二相）。

### 12.4 判定与偏离（Ruling）

| # | 判定 | 理由 | 判错的代价 |
|---|---|---|---|
| **A** | `RefreshModelsContext` **不移植** pi 的 `credential` | pi 自己的 `withRemoteCatalog` 从不读它（§1.3 的逐字分支）；本仓无「要凭据才能拉目录」的 provider ⇒ 零读者；带上它会逼协调器接一条凭据解析链 | 出现此类 provider 时要加回一个分量并接解析链 |
| **B** | 不移植 pi 的 `signal: AbortSignal` | 设计 R2 已裁：不支持中途取消，4s attempt timeout 封顶 | 靠 4s 硬超时兜底，长尾不可中断 |
| **C** | `DefaultProviders.streamBlocking` 的模型解析改读 `getModels()`（按 `ModelId` 等值查找），不再 `builtinModels().find()` | 远程 overlay 只挂在 `getModels()` 上；读 `builtinModels()` 会让远端补进来的模型解析不到元数据（退化成 `ModelInfo.minimal`）⇒ 包装等于白做 | 无（这是本包的行为必需项） |
| **D** | `ModelsPublication` 的删除工厂命名 `remove()` | record 分量叫 `delete` ⇒ 访问器已是 `delete()`，Java 不允许仅返回类型不同的重载 | 命名与设计稿 §4.1 草图的字面不同 |
| **E** | `RefreshModelsContext.offline/online` 由 package-private 改 **public** | 夹具在 `com.pijava.coding.agent.core`、被构造对象在 `com.pijava.ai.catalog`；设计稿 §4.1 的草图没写修饰符 | 无 |
| **F** | User-Agent 用本仓既有约定 `pi-java/dev`（`PiHttpClient` 等三处同值），**不**新造 pi 的 `pi/<version> (…)` 形状 | 本仓没有版本源（无 `VERSION` 常量、无资源过滤）；为目录端点单独发明第二种 UA 形状反而不一致 | 若 pi.dev 对 UA 有要求，需要补一个真的版本通道 |
| **G** | **R4 的前提被证伪 ⇒ PUT 与 `CatalogPublisher`/`CatalogModel` 不删** | 「零生产调用」不成立：`AiCli` 把 `pi-ai catalog validate\|merge\|publish` 注册成真子命令，`publish` 就是 PUT 的生产者。设计 §2 表格那行「孤儿：仅自身测试」是**调研错误** | `pi-ai catalog publish` 这条 pi 没有的自创路径继续存在（登记 **B143**） |
| **H** | 两阶段编排里单个 provider 失败**记账而不中断整批** | pi 的 `Promise.all` 会让一个 provider 的 reject 拖垮整批 —— 那个传播形状对本仓的「后台刷新、错误吞掉」没有价值；`ModelCatalogRefresh.Result` 承载逐 provider 的异常 | 与 pi 的失败传播形状不同；调用方若依赖「一个失败即整体失败」需改 |
| **I** | 不设 `isDynamic(p)` 过滤，对列表内**所有** provider 调 `refreshModels` | Java 的 `refreshModels` 是 default no-op（不像 pi 是可选方法），静态 provider 天然无事可做 | 无（等价） |
| **J** | `models-store.json` 默认位置＝**与 `ModelsJsonConfig.defaultPath()` 同一 agent 目录**（`PI_JAVA_CODING_AGENT_DIR` 或 `~/.pi-java/agent`） | 仓库既有的 agent 目录约定；设计 §4.3 的「`AgentDir.resolve(...)`」在本仓没有 `AgentDir` 类 | 无 |
| **K** | §9 的步骤 3 与 4 **合成一次提交** | 它们改的是同一个方法体的分支集合，拆开只会造一个「只有前一半分支」的中间态 | 提交粒度与设计稿的步骤编号不再一一对应 |
| **L** | `ModelData.generatedAt()` 取对齐锚点日期 `2026-09-20`（pi `3390bd9` 的提交日） | 该常量必须 ≤ 远端 `Last-Modified` 才有 overlay；取「今天」的风险是远端目录一概被守卫掉（§4.7 注释已写明刷新模型表时必须同步） | 若内表刷新而常量没跟，新模型进不来 |

### 12.5 教训

1. **夹具写在实现之后 ⇒ 首跑的红/绿都不构成证据**（「零红」的第 6 种成因）。步骤 3+4 的首跑 4 红全是**夹具**错（守卫 vs 缺 `Last-Modified`、404 的 overlay 语义），步骤 5 首跑零红。两处都只能靠 §12.3 的变异探针补证 —— 与 `docs/58` 的第 (6) 形态同源。
2. **`mvn test` 不跑 spotbugs**（它绑在 `verify`）⇒ 「模块测试全绿」≠ 可提交。本包因此多出一个 `fix(ai)` commit（`297060d`）。
3. **`mvn spotbugs:check` 的 `default-cli` 用 `target/classes`**：换掉源码但不重编译，取证无效。判定 `StrictSampling` 那条是否既有，第一次只 `cp` 源码就跑 ⇒ 结论不算数，补了 `compile` 才作数（见 §12.6）。
4. **`git rm` 之后不能再 `git add <同一路径>`**（`pathspec did not match any files`），而已经 staged 的删除会被**下一条不带 pathspec 的 `commit` 一并收走** —— 本次让 `fix(ai)` 那次提交吞了 3 个删除，用 `git reset --soft HEAD~2` 重做。
5. **404/501 的 overlay 语义**：同一次 `refreshModels` 调用里，404 分支**只 persist 不带 update** ⇒ 本次调用内 `dynamic` 仍是离线相恢复的那份（pi 亦如此）。「overlay 空」是**下一次**刷新的效果。设计稿 §7.1-4 的措辞「overlay 空」不够精确，夹具已按实测口径写（并断言第二相归空）。
6. **收尾那次 `-am` 回归里 agent-core 的 `ConformanceTest[14]`（S14）假红**（该用例 96.07 s、报帧序不一致；隔离复跑 1.6 s 15/15 绿）。agent-core 本包**一行未动**，同一份代码在前一次全 reactor `mvn -o test`（14/14 SUCCESS）里是绿的 ⇒ 负载敏感。登记 **B146**，不在本包修。

### 12.6 回归与门禁

- **全 reactor `mvn -o test`**（cleanup 之后）：**14/14 SUCCESS** —— telemetry 31 ／ ai 1312 ／ agent-core 527 ／ sqlite 35 ／ coding-agent 319（307 ⇒ 319）／ TUI（4:42）／ protocol／client／server／web（52 s）／evals／dist；
- cleanup 使 ai 1318 ⇒ **1312**（删 `RemoteCatalogTest` 的 6 条）；
- ⚠️ 收尾那次 `-am` 回归里 agent-core 的 `ConformanceTest[14]`（S14）**假红**，隔离复跑 15/15 绿（§12.5-6，登记 B146）；
- 本包新增 spotbugs 违规 **0**（`FileModelsStore` 两处已修）；
- ⚠️ **`mvn clean verify` 在收尾时是红的**，原因是**既有**的 `StrictSampling.resolveStrict` `NP_BOOLEAN_RETURN_NULL`（`spotbugs-exclude.xml` 当时为空）。已用「换回 HEAD 源码 ＋ **重新编译** ＋ 再跑」复核：同样报 —— 与本包无关（登记 **B144**）。本包因此**未能**跑到 §10.4 的全 reactor `verify` 全绿，如实记录。⇒ 包闭环后按用户裁决**已修**（加排除条目，见 `docs/32 B144`）；此后全 reactor `mvn -o verify -DskipTests` **14/14 SUCCESS**，主干自 2026-08-17 引入 spotbugs 以来第一次 `verify` 全绿。
- checkstyle：ai 与 coding-agent 0 新违规。

### 12.7 门禁对照（§10）

| # | 门槛 | 结果 |
|---|---|---|
| 1 | RED 先于实现 | 步骤 1/2 编译红；步骤 3+4/5 **未取到**（§12.2 + §12.5-1），由 M1–M6 变异补证 |
| 2 | GREEN | 目标夹具全绿：`RemoteCatalogProviderTest` 17 、`CatalogRefreshCoordinatorTest` 5 、`ModelCatalogRefreshTest` 4 、`DefaultProvidersRemoteCatalogTest` 3 、`ProviderDynamicProtocolTest` 2 、`FileModelsStoreTest` 5 、`RemoteModelWireTest` 3 、`ModelDataGeneratedAtTest` 2 |
| 3 | 变异 M1–M6 记录红集 | §12.3（无零红） |
| 4 | 回归 ai/coding-agent（-am）全绿；checkstyle 0 新违规 | ✅（spotbugs 见 §12.6 的既有项） |
| 5 | 无调试残留；改动文件 ≤500 | ✅（最大新增文件 `RemoteCatalogProvider.java` 302 行、`RemoteCatalogProviderTest.java` 428 行） |
| 6 | §12 与 docs/48 行 D 在收尾提交中回填 | ✅ |

### 12.8 新登记

| # | 内容 | 处置 |
|---|---|---|
| **B143** | **`pi-ai catalog` 是本仓自创的 CLI 面（`validate`/`merge`/`publish`，`publish`＝HTTP PUT 上传），pi 无对应物**；R4 原判「零生产调用」被证伪 | 保留（见 §12.4-G）。待裁决：整条命令删掉，还是维持为自建工具链 |
| **B144** | **`mvn clean verify` 在 ai 模块因既有 `StrictSampling.resolveStrict` 的 `NP_BOOLEAN_RETURN_NULL` 失败**（`spotbugs-exclude.xml` 为空；换源重编译复核为既有） | 待裁决：`Optional<Boolean>` 化（改公开 API）／加 exclude 条目／维持红。⚠️ 在修好前，任何包的「`mvn clean verify` 零错误」自验证都**物理上做不到** |
| **B145** | **TUI 启动/登录后的定向目录刷新未接线**（设计 R8 明示随 TUI 包）—— 今天只有 RPC 模式启动后台刷新；`pi-ai` CLI 与 print 模式不刷（pi 的 `main.ts:163` 也是 `allowModelNetwork:false`，口径一致） | 随 TUI 包；编排入口 `ModelCatalogRefresh` 已可调用 |

### 12.9 未覆盖

- **不覆盖 404/501 的「跨进程」语义**：`FileModelsStore` 用 `synchronized`（R6 裁决，单进程）—— 多进程同时刷新同一 agent 目录没有锁。
- **`pi-ai catalog` 那条工具链与新远程目录机制并存**：两套 wire（`CatalogModel` vs `RemoteModelWire`）今天都活着；本包**没有**把它们合流（见 B143 的裁决）。

