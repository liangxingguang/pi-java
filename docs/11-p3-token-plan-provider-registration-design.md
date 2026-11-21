# 11 - P3 余项：Qwen／Xiaomi Token Plan Provider 注册设计

> 【背景摘要】本文是 `docs/09` 换锚计划的 **P3 余项**设计稿：把目标锚点 `a00a5048d` 新增的 Qwen／Xiaomi Token Plan provider 注册进 Java，并补上 pi 的多环境变量键回落链。
>
> **状态：设计待审，尚未实施。** 用户批准后才写代码；闭环后结论回写 `docs/04`、`docs/05` 与 `docs/map/02`，本文按 `docs/04` §7 规则删除。
>
> 术语释义：
> - **Token Plan**：QwenCloud／小米 MIMO 的预付费套餐网关，按区域（国际／中国／AMS／SGP）分域名与密钥。
> - **环境变量键回落链**：一个 provider 可读多个候选环境变量，按顺序取第一个非空值（pi `envApiKeyAuth`）。
> - **分片（shard）**：`pi-java-ai/src/main/resources/catalog/<provider>.json`，由 `catalog-generator/` 从固定上游快照生成。
> - **模型可见面**：模型读到的提示、工具声明、结果与报错；其余算【内部，模型不可见】。

## 1. 范围

本包只做 `docs/09` P3 余项中的 provider 注册与鉴权键：

| 编号 | 事项 |
|---|---|
| S1 | 新增 `qwen-token-plan`（国际团队版） |
| S2 | 更新 `qwen-token-plan-cn`（显示名、baseUrl、密钥链） |
| S3 | 新增 `qwen-token-plan-individual` |
| S4 | 新增 `qwen-token-plan-individual-cn` |
| S5 | 新增 `xiaomi-token-plan-ams` |
| S6 | 新增 `xiaomi-token-plan-sgp` |
| S7 | 生成 5 个新分片，更新 manifest／归因／测试计数 |
| S8 | 补 5 个 provider 的默认模型表 |

不做：classifier／图像生成面（这两类 provider 在 pi 目录里没有对应条目，按 R5 保持出分母）；不扩 R2 长尾 provider。

## 2. pi 事实清单（锚点 `a00a5048d`）

### 2.1 Provider 定义

| provider | 显示名 | baseUrl | 密钥候选（按序） | 证据 |
|---|---|---|---|---|
| `qwen-token-plan` | Qwen Token Plan Team | `https://token-plan.maas.qwencloudapi.com/compatible-mode/v1` | `QWEN_TOKEN_PLAN_TEAM_API_KEY`, `QWEN_TOKEN_PLAN_API_KEY` | `packages/ai/src/providers/qwen-token-plan.ts:8-17` |
| `qwen-token-plan-cn` | Qwen Token Plan Team CN | `https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1` | `QWEN_TOKEN_PLAN_TEAM_CN_API_KEY`, `QWEN_TOKEN_PLAN_CN_API_KEY` | `packages/ai/src/providers/qwen-token-plan-cn.ts:8-17` |
| `qwen-token-plan-individual` | Qwen Token Plan Individual | `https://token-plan.maas.qwencloudapi.com/compatible-mode/v1` | `QWEN_TOKEN_PLAN_INDIVIDUAL_API_KEY`, `QWEN_TOKEN_PLAN_API_KEY` | `packages/ai/src/providers/qwen-token-plan-individual.ts:8-17` |
| `qwen-token-plan-individual-cn` | Qwen Token Plan Individual CN | `https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1` | `QWEN_TOKEN_PLAN_INDIVIDUAL_CN_API_KEY`, `QWEN_TOKEN_PLAN_CN_API_KEY` | `packages/ai/src/providers/qwen-token-plan-individual-cn.ts:8-17` |
| `xiaomi-token-plan-ams` | Xiaomi Token Plan AMS | `https://token-plan-ams.xiaomimimo.com/v1` | `XIAOMI_TOKEN_PLAN_AMS_API_KEY` | `packages/ai/src/providers/xiaomi-token-plan-ams.ts:8-12` |
| `xiaomi-token-plan-sgp` | Xiaomi Token Plan SGP | `https://token-plan-sgp.xiaomimimo.com/v1` | `XIAOMI_TOKEN_PLAN_SGP_API_KEY` | `packages/ai/src/providers/xiaomi-token-plan-sgp.ts:8-12` |

密钥链逐字证据：`packages/ai/src/env-api-keys.ts:84-90`（Qwen 四个 provider 的数组形式）、`:121-124`（Xiaomi 两个新区域）。同一函数把单值与数组统一成数组（`:127-128`）。

### 2.2 模型来源与过滤

| provider | models.dev 源键 | 过滤规则 | 证据 |
|---|---|---|---|
| `qwen-token-plan` | `alibaba-token-plan` | `skipDeprecated`；无白名单 | `packages/ai/catalog/providers/qwen-token-plan.ts:52-57` |
| `qwen-token-plan-individual` | `alibaba-token-plan` | 白名单 `INDIVIDUAL_MODEL_IDS`（13 个 id）＋ `skipDeprecated` | `catalog/providers/qwen-token-plan-individual.ts:9-28` |
| `qwen-token-plan-individual-cn` | `alibaba-token-plan-cn` | 同一白名单＋ `skipDeprecated`；中国域名 | `catalog/providers/qwen-token-plan-individual-cn.ts:7-9` |
| `xiaomi-token-plan-ams` | `xiaomi-token-plan-ams` | `skipDeprecated` | `catalog/providers/xiaomi-token-plan-ams.ts:5-7` |
| `xiaomi-token-plan-sgp` | `xiaomi-token-plan-sgp` | `skipDeprecated` | `catalog/providers/xiaomi-token-plan-sgp.ts:5-7` |

`xiaomiModels` 的 compat／thinking 映射复用 `catalog/providers/xiaomi.ts:11-47`。

### 2.3 默认模型

`packages/coding-agent/src/core/model-resolver.ts:54-61`：五个新 provider 全部登记默认模型——Qwen 四个都是 `qwen3.8-max`，Xiaomi 两个都是 `mimo-v2.6-pro`。

### 2.4 上游数据实测

2026-11-21 现场拉取 `https://models.dev/experimental/v2.0/api.json` 并与已提交快照比对：

- 现有 15 个 provider 键的模型集合**零增删、零字段变化**，只有对象键序不同（生成器输出不依赖键序）。
- 新键存在：`alibaba-token-plan`（32 条，23 chat，3 deprecated）、`xiaomi-token-plan-ams`（8 条，4 chat，2 deprecated）、`xiaomi-token-plan-sgp`（同 AMS）。

按 pi 过滤后预计新增 chat 数：`qwen-token-plan` 20、`qwen-token-plan-individual` 13、`qwen-token-plan-individual-cn` 13、`xiaomi-token-plan-ams` 2、`xiaomi-token-plan-sgp` 2，合计 **＋50**（645 → 695）。

## 3. pi-java 现状与差距

| 项 | 现状 | 差距 |
|---|---|---|
| 注册表 | 21 个内置 provider（`ProviderCatalog.all()`） | 缺 5 个 |
| `qwen-token-plan-cn` | 显示名 `Qwen Token Plan CN`、baseUrl `token-plan.cn-beijing.maas.aliyuncs.com`、单键 | 显示名、域名、密钥链三处过期 |
| 分片 | 18 个（`BuiltinModelCatalogs.SHARD_IDS`） | 缺 5 个；`MODELS_DEV_PROVIDER_KEYS` 缺 3 个上游键 |
| vendor 规则 | 有 `catalog/providers/qwen-token-plan.ts`，无 Individual／AMS／SGP | 缺 4 个文件 |
| 密钥 | `ProviderConfig.apiKeyEnvVar` 单字符串 | 不支持回落链 |
| 默认模型表 | 17 键（＋radius） | 缺 5 键 |
| 计数器 | 测试硬编码 21／18／645／723 | 全部要跟着改 |

## 4. 改动方案

### 4.1 数据面（catalog-generator）

1. 从 pi `a00a5048d` 逐字节复制 4 个 vendor 文件：`qwen-token-plan-individual.ts`、`qwen-token-plan-individual-cn.ts`、`xiaomi-token-plan-ams.ts`、`xiaomi-token-plan-sgp.ts`；`VENDOR.json` 追加 sha256。
2. `generate-java-catalog.ts`：加 import、加 `PROVIDER_IDS`／`PROVIDERS`（18 → 23）、加 `MODELS_DEV_PROVIDER_KEYS`（`alibaba-token-plan`、`xiaomi-token-plan-ams`、`xiaomi-token-plan-sgp`）。
3. 运行 `node pi-java-ai/catalog-generator/generate-java-catalog.ts --fetch` 重取快照并重生成；随后 `--check` 必须零差异、`--verify-vendor` 全绿。
4. 生成物：5 个新分片、`ATTRIBUTION.txt`、`.manifest.json`（23 个文件、新 `generatedAt`）。

`--fetch` 会刷新全部四个快照文件，但第 2.4 节实测表明现有分片内容不变；若实际 diff 出现既有模型变化，暂停并回到用户裁决。

### 4.2 Provider 类与注册表

新增 5 个类，模式照 `XiaomiTokenPlanCnProvider`：

```java
public final class QwenTokenPlanProvider extends OpenAiCompatibleProvider {
    @Override protected ProviderConfig config() {
        return ProviderConfig.single(
            "qwen-token-plan", "Qwen Token Plan Team",
            "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
            List.of("QWEN_TOKEN_PLAN_TEAM_API_KEY", "QWEN_TOKEN_PLAN_API_KEY"),
            Protocol.OPENAI_COMPLETIONS, BuiltinModelCatalogs.catalog("qwen-token-plan"));
    }
}
```

`QwenTokenPlanCnProvider` 改为 `Qwen Token Plan Team CN` ＋新域名＋双键；其余照表。`ProviderCatalog.all()` 在 qwen／xiaomi 分组内插入，顺序：

```
qwen-token-plan, qwen-token-plan-cn, qwen-token-plan-individual, qwen-token-plan-individual-cn
xiaomi, xiaomi-token-plan-cn, xiaomi-token-plan-ams, xiaomi-token-plan-sgp
```

`BuiltinModelCatalogs.SHARD_IDS` 同步加 5 个 id，类注释从「18 个分片／`56cae7823`」改为「23 个分片／`a00a5048d`」。

### 4.3 环境变量键回落链（跨层小改）

**`ProviderConfig`**：组件 `String apiKeyEnvVar` 变为 `List<String> apiKeyEnvVars`；保留派生方法 `apiKeyEnvVar()` 返回首个（空链返回 `null`）。为降低重建点风险：

- `single(...)` 的 `String` 重载保留，内部包成 `List.of(value)`；
- 新增 `single(..., List<String> apiKeyEnvVars, ...)` 重载；
- 构造器过滤空白项并 `List.copyOf`；空链 ≙ 原来 `null`。
- 8 个 `new ProviderConfig(...)` 直接构造点同步改签名（B156 纪律：改组件必须搜全部重建点）。

**`ConfigurableProvider.effectiveOptions`**：baseUrl 补默认之后、`enrichWithIdentity` 之前，若 `options.apiKey` 为空，按链逐个 `ProviderEnv.value(name, options.env())`，命中即注入 `apiKey`。这样 8 个协议构造器与 `resolveAuth` 的签名都不用动；直接构造协议类仍只认单变量（库级入口，不是 pi 行为路径）。

**`EnvApiKeyResolver`**：映射表值改为 `List<String>`；新增

```java
public record EnvMatch(String name, String value) {}
public Optional<EnvMatch> resolveApiKeyMatch(String provider) // 按链取第一个非空
public Optional<String> resolveApiKey(String provider)        // 委托，保留旧口
```

`envVarName(provider)` 返回首个名字（诊断文案与 profile 扩展仍只看主键）。

**`Credentials`**：默认 env 层改用 `resolveApiKeyMatch`，`source` 记为实际命中的变量名（pi `envApiKeyAuth` 的 `source: envVar`）。

### 4.4 默认模型表

`DefaultModelIds` 追加 5 条（值见 2.3）；`DefaultModelIdsTest` 的常量表同步为 22 键，循环仍钉「每个默认 id 存在于对应分片」。

### 4.5 测试与计数

| 文件 | 改动 |
|---|---|
| `ProviderRegistryTest` | 21 → 26，新增 5 个 `get(...)` 存在断言 |
| `ProviderCatalogConformance` ＋ `ProviderCatalogTest` | case 名与 21 → 26 |
| `BuiltinModelCatalogsTest` | 新 5 行 chat 计数；总 645 → 695；聚合 723 → 773；`shardIds` 缺席断言加新 provider 名的长度/内容检查 |
| `BuiltinModelManifestTest` | 文件数 18 → 23；`generatedAt` 换新值 |
| `DefaultProviders`／`DefaultProvidersRemoteCatalogTest` | 注释数量；断言新 provider 被远程目录包装（pi 对 radius 之外一律包装） |
| `ProviderConfigTest` | 空白过滤、空链、`apiKeyEnvVar()` 首键 |
| `CredentialsTest`（新增用例） | 主键缺席、回落键命中时 `source` 是回落键名 |
| 5 个 provider 新测试 | 显示名、baseUrl、密钥链、目录首条与条数 |

## 5. 测试计划（RED-first 与变异探针）

先写红夹具再写实现：

1. **密钥链优先级**：只设 `QWEN_TOKEN_PLAN_API_KEY` 时 `qwen-token-plan` 能解析；变异探针把链顺序对调 ⇒ 变红。
2. **source 归属**：命中回落键时 `RecordedCredential.source()` 必须是该键名；把 `Credentials` 改回主键名 ⇒ 变红。
3. **provider 元数据**：5 个新类的 name／displayName／baseUrl 逐字断言；把 CN 域名改回 aliyuncs ⇒ 变红。
4. **目录条数与首条**：新分片的 chat 条数与首个模型 id 断言；生成器漏掉 `skipDeprecated` ⇒ 条数变红。
5. **注册与默认**：26 个 provider；五个默认 id 必须能在目录里找到。
6. **生成器守护**：`--check`、`--verify-vendor` 必须零退出；`BuiltinModelManifestTest` 逐文件 sha256。

验收：`mvn clean verify -Dfrontend.skip=true` 零错误零告警；ai、coding-agent、evals 相关测试全绿。

## 6. 裁决

- **A1 用 `List<String>` 链扩展 `ProviderConfig`**
  > 【场景】pi 的 Qwen 四个 provider 各有「专属键 → 区域共享键」两层回落；Java 只存单键，无法表达。
  > 【决定】把组件改为 `List<String> apiKeyEnvVars`，保留 `apiKeyEnvVar()` 派生方法与 `single(String)` 重载，最小化既有调用点改动。
  > 【风险】record 组件变更会静默漏改重建点（B156 已知风险）。对策：编译期通过 canonical 构造器签名强制暴露，实施时逐点核对 8 处直接构造；[内部，模型不可见]。

- **A2 链解析放在 provider 归一化点，不改协议构造器**
  > 【场景】协议类构造器都收单 `envVar`，`resolveAuth` 也是单值；若逐个改成 List 会波及 8 个类。
  > 【决定】`ConfigurableProvider.effectiveOptions` 在 `enrichWithIdentity` 前按链注入 `apiKey`。`Credentials` 链另走 `EnvApiKeyResolver` 的链解析，二者命中同一环境。
  > 【风险】直接用 `OpenAIImagesApi(options, "VAR")` 之类构造器的库用户没有链；这是库级入口，不是 pi 产品路径，登记为已知边界；[内部，模型不可见]。

- **A3 重新拉取上游快照**
  > 【场景】`alibaba-token-plan` 等 3 个 models.dev 键不在已提交的过滤快照里，本地无法推导。
  > 【决定】用生成器的 `--fetch` 重取四个快照并重生成，接受 `SOURCES.json#fetchedAt` 与 manifest 时间戳变化。
  > 【风险】上游是每日漂移数据；实测现有 15 键零模型变化，若 diff 超出预期则暂停回审；[内部，模型不可见]。

- **A4 范围包含国际团队版 `qwen-token-plan`**
  > 【场景】`docs/09` P3 行写「Qwen 网关/Individual CN/env 键」，未逐字列团队国际版。
  > 【决定】纳入。理由：密钥链引用了它的专属变量 `QWEN_TOKEN_PLAN_TEAM_API_KEY`；目录规则 `qwen-token-plan.ts` 已在 vendor 集合中；缺它会让链语义悬空。
  > 【风险】R2 长尾判据可能被质疑；按 `docs/04` 已批准主流的 Token Plan 家族处理，明细由用户审核确认；[模型可见面：provider 列表与默认模型]。

- **A5 不扩 classifier／图像面**
  > 【场景】pi 对这批 provider 只生成 chat 目录。
  > 【决定】Java 只装 chat；`models.dev` 里的 image／video／speech 条目继续丢弃。
  > 【风险】无；与 `docs/04` R5 分类器裁决一致；[内部，模型不可见]。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 上游快照漂移导致既有分片变化 | 第 2.4 节已实测零变化；实施后先跑 `--check` 看 diff，异常即停 |
| `ProviderConfig` 组件改造漏重建点 | 逐点核对 8 处；全仓 `mvn clean verify` 兜底 |
| 新 provider 远端目录 overlay 不存在 | 包装逻辑自动回落到静态目录，无需特殊分支 |
| 测试计数硬编码多 | 在本包一次性全改，收尾全仓 verify |
| 文件行数 | 每个 provider 类约 20 行；`ProviderConfig`、`EnvApiKeyResolver` 都不足 500 行 |

## 8. 实施步骤与提交拆分

1. `feat(ai): add qwen and xiaomi token-plan providers` —— vendor＋生成器＋5+1 个 provider 类＋注册表＋分片＋计数测试（数据文件由生成器产出）。
2. `feat(ai): support multi-variable api key env chains` —— `ProviderConfig`／`EnvApiKeyResolver`／`Credentials`／`effectiveOptions`＋链测试。
3. 闭环回填：`docs/04`（R2 行）、`docs/map/02-ai.md`（provider 表）、`docs/05`（不动总分直到 P12）、`docs/09` 进度；删除本文。
