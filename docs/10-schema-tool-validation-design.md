# 10 - 工具参数 pi-schema 校验对齐设计（P1）

> 【背景摘要】pi 1.5.0 把工具参数校验从 TypeBox 换成 `@earendil-works/pi-schema`，并对模型可见的强制转换与报错文本产生变化。本设计是 docs/09 计划 P1 的实施稿，目标锚点 `a00a5048d`。
>
> **状态：设计待审。** 用户批准后才写代码。本文只覆盖工具参数校验，不移植 pi-schema 的构建器 API。

## 1. 场景

pi 的 `validateToolCall()` 在每次工具调用前执行：

- 证据：`packages/ai/src/utils/validation.ts:37-68`、`packages/agent/src/agent-loop.ts:736`。
- 校验器由 `compile(schema, { formats, coerce: true, unresolvedRefs: "reject" })` 生成（`validation.ts:15`）。
- 参数先 `structuredClone`，再 `parse()`；失败时抛出的文本对模型可见。

Java 现状：`pi-java-agent-core` 的 `ToolArgumentsValidator` 只支持 `type/required/properties/items` 的浅校验，不做强制转换，报错文本是 `missing required argument "x"` / `argument "x" has wrong type`，与 pi 不同。

## 2. 决定

### 2.1 放置

在 `pi-java-ai` 新增包 `com.pijava.ai.validation`，对位 pi 把校验放在 `ai` 的分层：

- `ToolArgumentValidation`：公开入口，返回转换后的参数，失败抛 `IllegalArgumentException`。
- `SchemaCheck`：布尔检查。
- `SchemaCoerce`：三遍强制转换。
- `SchemaErrors`：失败原因与文案。
- `SchemaRefResolver`：本地 `$ref` 解析。
- `SchemaFormats`：JSON Schema 2020-12 标准 format。
- `JsJson`：按 `JSON.stringify(value, null, 2)` 形状输出错误里的参数。

`pi-java-agent-core` 的 `ToolArgumentsValidator` 改为薄委托，保留现有调用点（`PiToolRunner:161`、`ToolRegistry:148`）不变。

### 2.2 模型可见契约（逐字对齐）

1. 外层文本：
   ```text
   Validation failed for tool "<name>":
     - <path>: <message>
     ...

   Received arguments:
   <JSON.stringify(arguments, null, 2)>
   ```
2. 路径：JSON Pointer 转点路径。`""` → `root`；`/edits/0/oldText` → `edits.0.oldText`；`~1`/`~0` 反转义（`validation.ts:21-28`）。
3. 关键字文案按 `packages/schema/src/explain.ts`：`missing`、`unknown field`、`expected number, got string`、`expected >= 1`、`expected one of [...]` 等。
4. 悬空 `$ref` 只在该位置报 `unresolved reference <ref>`，不让整个工具调用失败（`validation.ts:12-15`、`explain.ts:96-100`）。
5. 强制转换按 `packages/schema/src/coerce.ts` 三遍：可选 `null` 丢弃、TypeBox 转换规则、按 type 的原生转换。典型：`null`→`"null"`（必填 string）、`"3.5"`→`3`（integer）、单值包成数组。

### 2.3 支持范围

- 检查：布尔 schema、`$ref`、`type`（含 type 数组）、`const`、`enum`、`allOf`/`anyOf`/`oneOf`/`not`、`if`/`then`/`else`；string 的 `minLength`/`maxLength`/`pattern`/`format`；number 的 `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`/`multipleOf`；object 的 `properties`/`patternProperties`/`additionalProperties`/`required`/`propertyNames`/`minProperties`/`maxProperties`/`dependentRequired`/`dependencies`；array 的 `items`/`prefixItems`/`additionalItems`/`minItems`/`maxItems`/`uniqueItems`/`contains`/`minContains`/`maxContains`。
- 强制转换：上述结构对应的转换、tuple、`$ref`、`enum`/`const`、union、`allOf`。
- format：`date`、`date-time`、`time`、`duration`、`email`、`hostname`、`ipv4`、`ipv6`、`uri`、`uri-reference`、`url`、`uuid`、`json-pointer`、`json-pointer-uri-fragment`、`regex`、`relative-json-pointer`。

### 2.4 不移植

- pi-schema 的构建器 API 与 `Static` 类型：Java 侧 schema 是 JSON `Map`。
- `$dynamicRef`、`unevaluatedProperties`/`unevaluatedItems`、`bytes`/`bigint`/`undefined`：工具参数来自 JSON，无这些值。
- 完整 JSON-Schema-Test-Suite 2,222 条：只覆盖工具 schema 用到的关键字；未覆盖关键字登记窄差。

## 3. 风险/代价

- [模型可见] 强制转换会接受以前 Java 拒绝的参数（例如字符串数字），属有意行为变化；必须用夹具锁定。
- [模型可见] JS 正则与 Java `Pattern` 在 Unicode 属性转义上不完全一致，登记窄差。
- [内部] `JsJson` 必须按 JS 形状输出，不能用 Jackson 默认 pretty printer（默认会在冒号前加空格）。
- [内部] 递归 `$ref` 需要深度保护，避免自引用 schema 栈溢出。

## 4. 测试

- `ToolArgumentValidationTest`：coercion 典型值、必填/null、数组包装、enum/const、union、悬空 `$ref`、错误文本逐字。
- `SchemaErrorsTest`：required/additionalProperties/type/minimum/pattern/oneOf 文案与路径。
- `ToolArgumentsValidator` 既有测试保持通过；`PiToolRunnerTest`、`ToolRegistry` 回归。
