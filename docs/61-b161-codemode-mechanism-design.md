# 61 - B161 codemode 机制等价设计（能力版）

> 【背景摘要】用户提出「codemode 能否实现」「是否必须用 JS」「JVM 脚本语言是否更好」三问。
> 本设计给出建议：**实现 pi codemode 的机制等价能力，模型可见语言保持 JavaScript，
> v1 首选 Endive 1.1.0 + quickjs-wasi（2026-11-20 实测运行时全能力可用，见 §2.2），
> GraalJS 经同一 SPI 兜底，Chicory 第三备选；codemode 继续不进对齐分母。**
> 本文覆盖问题定义、模型可见契约、引擎裁决、模块架构、pi 逐件对位、Java 集成点、
> 裁剪与窄差、测试计划、实施批次与台账影响。
>
> **文档状态：设计待审（2026-11-19；§2.2 引擎实测数据于 2026-11-20 补入）。** 用户批准后才写代码。
>
> 【术语】
> - **codemode**：模型写一段 JavaScript，脚本内调用其他工具；只有脚本输出回到模型上下文。
> - **对齐分母**：`docs/05` 计分表中的计分单元集合；出分母即不计分、不拉低完成度。
> - **窄差**：Java 与 pi 无法完全一致、已登记的行为差异（D 编号）。
> - **模型可见面 / 内部面**：前者是模型能读到的文本、schema、报错；后者是线程、IO、栈等实现细节。
> - **SPI**：可替换实现的接口缝；本设计指 `CodemodeVmLauncher`。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 台账：`docs/04` B161（重开）、B177（MCP 默认 codemode 激活）、R5（修订建议）、拟新增 B288（loadout 接缝）
- 上游裁决：`docs/01` §R5、`docs/04` §5.1、`docs/08` R5
- 相关既有实现：`NestedToolCallRunner`、`ToolExposure.CODEMODE`、`ToolReach`、`tool_search`、`GrammarSampling`

## 0. 取证口径

1. pi 行为逐字取自锚点源码。路径前缀：
   - 沙箱包：`packages/codemode/src/`
   - 扩展：`packages/coding-agent/src/extensions/codemode/`
   - 设置/MCP：`packages/coding-agent/src/core/settings-schema.ts`、`extensions/mcp/index.ts`
2. 模型可见文本（工具描述、结果头、错误、globals 帮助、settings 描述）按原文引用，作为 Java 断言依据。
3. Java 侧只消费既有公开面；新增面单独列在 §6，并逐条说明是否属于模型可见面。
4. 本包**不进入 `docs/05` 对齐分母**，因此不以逐字计分，但仍以 pi 行为作为默认证口径；差异登记窄差。
5. 不实现 Cloudflare/remote/inline/确定性运行；不实现 `models.classify` / `models.generateImages`（Java 无 classifier/图像生成 API，D233）。

## 1. 问题定义

### 1.1 codemode 解决什么

【场景】不带 codemode 时，每个工具调用都是一次模型往返；N 个工具调用产生 N+1 次请求，且每条 tool_call 与 tool_result 都进入上下文。大输出还会触发截断。

【机制】模型输出一段程序，程序里工具是普通函数，脚本在沙箱执行；**脚本内部的 N 次工具调用与结果不进入 LLM 上下文，只有脚本的输出与返回值进入**。pi README 原文：

> Nested tool calls never enter the LLM context; only the script's output and return value do.

由此得到六项能力：

1. **减少往返与上下文占用**：一次 `codemode` 调用替代多次工具调用。
2. **组合与控制流**：循环、条件、`Promise.allSettled` 批量、链式依赖、`try/catch`。
3. **大结果过滤**：`bash` 给模型截到 2000 行/50KB，脚本内可拿到最多 1 MiB 并自行筛选。
4. **声明预算与工具发现**：`deferred` 工具不进描述；脚本用 `ALL_TOOLS`/`searchTools`/`describeTool`/`describeNamespace` 自助发现；内联声明有 3000 token 预算。
5. **跨调用小状态**：`store`/`load` 以会话自定义条目保存 JSON 值，按分支隔离。
6. **能力最小化**：脚本只能触达注入的 `tools` 与 `models`；无文件、网络、定时器。

### 1.2 目标

- 在 `pi-java` CLI 中提供 `codemode` 工具：模型写 JS，脚本调用会话可调用工具，结果格式与 pi 一致。
- 复用 pi 的 prelude、`@options` 解析、声明渲染、store、输出限制与结果排版。
- 与 Java 既有能力接线：嵌套调用管线、`codemode`/`deferred` 工具暴露、MCP 工具、`tool_search` BM25、会话自定义条目、渲染器、settings。
- 仅本地 CLI 进程内执行；不引入外部服务。

### 1.3 非目标

- `RemoteCodemodeSandbox`、`InlineCodemodeSandbox`、Cloudflare/Durable Objects（pi `runtime/remote.ts`、`inline.ts`、`cloudflare.ts`）。
- 确定性 `start()`（`execution.ts:530-566`、`deterministic.test.ts`）；消费者是 durable/chord。
- `models.classify`、`models.generateImages`；`models` 目录查询视 review 结论决定是否做最小集合。
- TypeScript 扩展运行时、`pi-env`、chord、durable。
- 对齐计分：不改 `docs/05` 分母与完成度。

## 2. 关键裁决

### 2.1 R5 口径：能力版，不进分母

> 【场景】`docs/04` R5 当前写明「codemode 沙箱与工具，包含 B161 和 MCP 默认 codemode 激活 B177」不做、不进分母。用户现在要求实现。
>
> 【决定】把 B161 从「不做」改为「做能力版」：
> - 实现完整机制与模型可见契约，但**不进入 `docs/05` 对齐分母**，不改变模块完成度。
> - 行为以 pi 为默认基准；与 pi 的差异按 D 编号登记到 `docs/map/04`。
> - R5 保留对其他面（durable/chord/pico3/micro）的排除；仅 codemode 一项重开。
>
> 【风险/代价】
> - [内部] 读者可能误以为 R5 整体解除：`docs/04` §5.1 必须加一行「codemode 已重开为能力版」。
> - [模型可见] 若未来把 codemode 纳入计分，GraalJS 与 QuickJS 的栈帧差异会先成为窄差；见 §2.2。
> - 不影响现有 92.7% 的 coding-agent 账面，因为它本就不在分母。

### 2.2 引擎：Endive 首选、GraalJS 兜底、Chicory 备选（2026-11-20 实测更新）

> 【场景】pi 用 QuickJS(WASM) + 每执行一个 worker 线程。Java 无内置 JS 引擎；可选：
> (a) Chicory（纯 Java WASM）加载同一份 `quickjs-wasi`；(b) GraalJS（JVM 内 JS）；
> (c) Rhino/Nashorn；(d) Kotlin/Groovy/Lua 等 JVM 脚本语言；(e) 受限 DSL。
>
> 【决定】**B0 以 Endive 1.1.0 + quickjs-wasi 为首选后端**（机制已实测走通，见下），
> 在 `CodemodeVmLauncher` SPI 后实现；**GraalJS Community**（Maven
> `org.graalvm.polyglot:js-community:25.0.5`，JDK 25 LTS 线）作为同 SPI 的**兜底**——
> 若 Endive 的 Java ABI 移植超出 B0 预算，直接切 GraalJS 继续，不阻塞机制开发。
> Chicory 作为第三备选（绑定可复用 Endive 的 Java 包装）。
> Maven Central 已确认 `polyglot:25.0.5` 与 `js-community:25.0.5` 存在
> （若与构建 JDK `25.0.4` 出现兼容问题，回落 `25.0.4`；B0 锁定精确版本）。
>
> 已核实的关键能力与约束：
> - `Context#interrupt(Duration)` 自 20.3 起存在：**软取消**，guest `finally` 会执行；
>   不能中断「不可中断的宿主等待/宿主代码」时抛 `TimeoutException`；`close(true)` 是硬取消。
> - `ResourceLimits.statementLimit(...)` 存在（仅 statement 计数），可作为 CPU 兜底；
>   没有 per-context 堆上限 API（`sandbox.MaxHeapMemory` 是软限制，且纯 Java CE 是否可用未确认）。
> - **社区版在纯 Java fat jar 中不是官方安全沙箱**；官方 `SandboxPolicy.UNTRUSTED` 走
>   Native Image isolate（`js-isolate-community`，四平台合计约 246 MB，含原生库），不属本包范围。
> - `allowAllAccess(false)` 的默认 host 访问是 `EXPLICIT`，**必须显式 `HostAccess.NONE`**；
>   IO 默认 `IOAccess.NONE`；线程/进程/原生/环境变量默认关闭。
> - stock JDK 25 走**解释模式**，会在 stderr 打 `[engine] WARNING: ... fallback runtime`；
>   用引擎选项 `engine.WarnInterpreterOnly=false` 关闭，避免污染 CLI 输出。
> - 纯 Java 依赖闭合约 64.47 MB（25.0.5；25.5.x 约 70 MB）；`js-language` 含
>   `META-INF/services`，fat jar 必须保留/合并服务描述（dist 已有 `ServicesResourceTransformer`）。
> - 语言保持 JavaScript：prelude 逐字复用（只用 `Proxy`/`Reflect`/`Promise`/`Object.defineProperty`，GraalJS 全支持），declarations、source、tool 描述、docs、测试都可复用。
> - 直接换 Kotlin/Groovy/Lua 会同时丢掉模型熟练度、Promise/async 与 JSON 一等公民地位，并要重写整套模型契约；不采纳。
> - Rhino/Nashorn 是 ES5/5.1，没有 Promise/async-await，`Promise.allSettled` 与 top-level await 全废；不采纳。
> - Java/Kotlin/Groovy 在 JDK 24+ 无 SecurityManager（JEP 486），进程内无法作为安全边界；不采纳。
>
> 【风险/代价】
> - [内部] fat jar 增加约 **64 MB（25.0.5）**；当前 `pi-java.jar` 约 148 MB ⇒ 约 212 MB。
> - [内部] stock JDK 上 GraalJS 走解释模式，首次 Context 创建与执行比 QuickJS(~20ms) 慢一个量级；官方仅称「significantly worse」，无具体数值。
> - [内部] **没有 per-context 内存上限**：`runtime/execution.ts` 的 256 MiB `memoryLimitBytes` 无法对位（D267）；`ResourceLimits.statementLimit` 只能兜底 CPU/语句数，不能限内存。
> - [内部] GraalJS CE 不提供官方 SandboxPolicy；`HostAccess.NONE` + 无 IO 是**功能限制**，不是安全边界。若 codemode 被多租户/web 暴露，需进程后端或 Chicory。
> - [内部] 依赖许可为 UPL/MIT/ICU（`icu4j`），需法务核对；`js-community` POM 标注 deprecated，B0 需确认是否切 `org.graalvm.polyglot:js`。
> - [模型可见] GraalJS 的 `name/message/stack` 与 QuickJS 不完全一致；按 §7 登记窄差。
> - **Spike 是硬闸门**：若 GraalJS 不能在 JDK 25 上完成「prelude 往返 + 工具调用 + settle 回调 + 微任务排空 + 超时取消」，改走 **Endive 1.1.0 + quickjs-wasi**（已实测全部能力可用，见下），Chicory 次选。
>
> 【Chicory 备选】改动仅在 `CodemodeVmLauncher` 实现层：加载 `quickjs-wasi/quickjs.wasm`，Java 侧重绑 `qjs_*` 导出（wasm 已直接导出 `qjs_init`/`qjs_eval`/`qjs_call`/`qjs_set_memory_limit`/`qjs_set_interrupt_handler`/`qjs_is_job_pending`/`qjs_execute_pending_job` 等，绑定面比预想小）。收益是内存上限、可中断、栈帧与 pi 同源。
>
> **Chicory 本机实测（2026-11-20，Chicory 1.7.5 + quickjs-wasi 3.6.2 + JDK 25.0.4）：**
> - 体积：`runtime` 151,620 B＋`wasm` 213,420＋`wasi` 59,497＋`log` 9,419＋`quickjs.wasm` 637,405 ≈ **1.07 MB（约 1.02 MiB）**；对比 GraalJS **+64 MB**。
> - 启动：`Parser.parse` 首次 297ms、热 136–158ms（可每进程缓存一次）；fresh `Instance` 热 1.5–2.6ms；`qjs_init`（Runtime+Context）**23–48ms/脚本**；简单 `qjs_eval` 0.7–1.5ms；globalThis lockdown 28–48ms。
> - 计算：10k 次简单 JS 循环 **0.48–1.0s**（Chicory 解释器 + QuickJS 解释器双层解释）；JSON 解析/大数组过滤会显著慢于 GraalJS 或原生 QuickJS。是否可用取决于 AOT/compiler 引擎的实测，需另做 spike。
> - 结论：**体积和启动接近 pi；计算速度是主要风险。**
>
> **Endive 1.1.0 + quickjs-wasi 运行时实测（2026-11-20，JDK 25（GraalVM）/ JDK 26 双跑一致 + Endive 1.1.0 + quickjs-wasi 3.6.2 的 `quickjs.wasm`）**：
> - **AOT 编译**：`endive-compiler-maven-plugin:1.1.0` 能把 `quickjs.wasm` 编成 JVM 字节码
>   （`QuickJsModuleMachine` 等 ~1.41 MB class＋`QuickJsModule.meta` 637 KB）。
>   `1.2.0` 编译同一 wasm **失败**：`Index 66991 out of bounds for length 51891`（编译器 bug）
>   ⇒ 锁定 **1.1.0**。
> - **运行时走通**（逐项实测，不再是「预计」）：
>   - `Instance.builder(...).withInitialize(true)`＋`_initialize`＋`qjs_init` 成功；
>     **`withInitialize(false)` 会跳过 globals/memory 初始化**，随后 `_initialize` 里
>     `readGlobal` NPE——这是接入时必踩的坑（global 0 = stack pointer 为 null）。
>   - `qjs_eval` 执行、`globalThis` 跨 eval 持久、异常经 `qjs_get_exception`＋`qjs_get_string` 可读。
>   - **宿主函数**：`qjs_new_host_function(namePtr, nameLen, 0)` ＋ `env.host_call(namePtr,
>     nameLen, thisPtr, argc, argvPtr)`（argv 是 4 字节指针数组）走通；Java 回填字符串结果后
>     JS 读到 `bridge:15.0`；`qjs_call` 从宿主重入 wasm 也走通（宿主→wasm→宿主再入不死锁）。
>   - **微任务**：`qjs_is_job_pending`/`qjs_execute_pending_job` 可排空 Promise 队列，
>     `Promise.resolve().then(...)` 与手工 `settle` 回填都实测成功。
>   - **取消**：`qjs_set_interrupt_handler(1)` ＋ `host_interrupt` 返回 1，`while(true){}`
>     在 **3.7 ms / 2 次轮询**后以 `InternalError: interrupted` 终止，VM 事后仍可用。
>   - **内存上限**：`qjs_set_memory_limit` 导出存在（未压 OOM 边界，B0 补测）。
> - **体积**：`runtime` 168,605 B＋`wasm` 213,209＋`wasi` 59,029＋`log` 9,256
>   ＋AOT class/meta 1,413,266 B = **2.34 MB 解压 / 约 0.95 MB 压缩 jar**（实测打 jar 后 953,795 B）。
> - **启动**（同一 JVM，冷启动/热启动）：
>   - 首次 `QuickJsModule.load()`（解析内嵌 `.meta`）**123 ms**（每进程一次，可启动时预热）。
>   - 首个 VM：instantiate 23 ms、`_initialize` 23 ms、`qjs_init` 25 ms（含类加载与 JIT）。
>   - 热 VM：instantiate **1–2 ms**、`_initialize` **≈0 ms**、`qjs_init` **2–6 ms**。
> - **计算**（warm，20 次中位数；循环包在 IIFE 内，避免顶层 `let` 重声明导致后续 eval 失败）：
>   - 10k 次 JS 加法循环 **≈30 ms（约 3 µs/次迭代）**；1M 次 **≈3.1 s**。
>   - 100 KB JSON（500 对象）`JSON.parse` **≈5.9 ms**；parse＋filter＋map **≈4.7 ms**。
>   - `Promise.resolve().then(...)`＋排空 **≈0.3 ms**；1000 个微任务 **≈0.3 ms**。
>   - 与 Chicory 对比：同一 10k 循环 Chicory **0.48–1.0 s** ⇒ Endive AOT 快 **15–30 倍**。
> - **ABI 代价**：quickjs-wasi 的 JS 包装器（`evalCode`/`newFunction`/`setProp`/`drain`）需在 Java
>   侧按 `qjs_*` 导出重写一版（约 500–800 行，不含 snapshot）；这是 Endive 路线的主要工作量。
> - **接入坑（实测）**：`qjs_eval` 的源码缓冲区必须 **malloc(len+1) 且 NUL 结尾**——按 `len`
>   分配会越界写坏堆，表现为**随机 `SyntaxError: expecting ';'`**（本项目 spike 首轮即踩到；
>   quickjs-wasi 的 `writeString` 也是 `len+1`）。`F64` 返回值是 **原始 long 位**，
>   须 `Double.longBitsToDouble`。`argv` 是 **uint32 数组、步长 4 字节**。
>
> **Quickjs4J（0.1.1，2026-10）：**
> - Maven `io.roastedroot:quickjs4j`，jar 754,712 B（压缩）/ 2,268,923 B（未压缩）；依赖 `run.endive:runtime/wasi:1.1.0` + Jackson；Apache-2.0；Java 11 基线。
> - 它用 **Javy 版 QuickJS + Endive AOT**（`JavyPluginModule` 编译进 jar），不是 `quickjs-wasi`，ABI/栈帧/引擎版本不同。
> - API 是 `Runner`/`Engine`/`Builtins`/同步 `HostFunction`/JSR-223；**没有文档化的 Promise job 排空、interrupt/timeout、per-context 内存限制**。pi prelude 依赖 `settle` 异步回填 + `qjs_is_job_pending`/`qjs_execute_pending_job`，因此 `Promise.allSettled` 并发与超时/内存限制无法直接保留。
> - 结论：**可作为简化同步后端或参考实现，不是 pi codemode 的 drop-in。**
>
> **建议的引擎优先级（2026-11-20 实测后）：**
> 1. **Endive 1.1.0 + quickjs-wasi（首选忠实后端）**：同一 wasm、+0.95 MB（压缩）、
>    内存上限＋可中断＋Promise 排空均实测可用；热启动 1–2 ms（VM）＋2–6 ms（`qjs_init`），
>    1M 循环约 3.1 s。代价是 Java 侧重写 quickjs-wasi 的 `qjs_*` 包装（约 500–800 行）
>    ＋ Maven 编译插件（AOT codegen）＋锁 1.1.0。**若接受该工作量，优先于 GraalJS。**
> 2. **GraalJS（兜底/最快落地）**：无绑定工作、语义最快（10k 循环 <1 ms 量级）；
>    代价 +64 MB fat jar、进程内无内存上限、CE 非官方 sandbox。适合先跑通机制（B0 闸门）。
>    若 Endive ABI 移植超出预算，回落本项。
> 3. **Chicory + quickjs-wasi**：体积同量级、无 codegen 插件、绑定可复用 Endive 的 Java 包装；
>    但计算 **0.48–1.0 s/10k 循环**（比 Endive 慢 15–30 倍），只适合几乎不做 JS 计算的脚本。
> 4. **Quickjs4J**：仅作同步化降级/参考，不作主路线。
>
> **风险排序**：GraalJS = 体积 + 无内存上限；Endive = ABI 工作量 + 计算仍比原生 QuickJS 慢
> （~3 µs/迭代）+ 1.1.0 编译器锁版；Chicory = 计算速度。三者都不改变模型可见契约。

### 2.3 `models` 命名空间：v1 不做

> 【场景】pi 的工具描述与 `docs/codemode.md` 都包含 `models.*`（目录、classifier、图像生成）。Java 无 `findOfType`/`classify`（D233），也无 `generateImages`。
>
> 【决定】v1 以 `models = false` 构造工具：不声明 `models`，脚本调用 `models.*` 得到 `models is not defined`。`docs/codemode.md` 的 Models 一节保留但标注「pi-java 暂不可用」。
>
> 【风险/代价】
> - [模型可见] 描述少一行 `models` 提示；与 pi 默认 `models: true` 有差。登记窄差。
> - 使用 classify/图像模型的脚本不能运行；若未来 Java 补 classifier/图像 API，再开 B 包接入。

### 2.4 host 接缝：`prepareLoadout`（拟新增 B288）

> 【场景】pi 的 codemode 通过 `ToolDefinition.prepareLoadout`（`tool.ts:343-379`）在激活时重写：
> - codemode 自身描述 = 可调用工具目录（含 TS 声明）；
> - `on` 模式：给已声明工具的描述追加 `Codemode: tools.x(args) resolves to ...`；
> - `only` 模式：把 active direct 工具从请求声明中隐藏。
> Java 当前只有静态 `AgentTool.description()` 与 `ModelToolControl.applyToolLoadout(names)`，没有该接缝。
>
> 【决定】新增 `AgentTool.prepareLoadout(ToolLoadout)` 可选方法（默认返回 empty），由请求装配点消费；具体见 §6.6。B288 与 B161 同批实施或作为 B161 的前置批次。
>
> 【风险/代价】
> - [内部] 请求装配点改动，影响所有工具；必须保持无 hook 工具的行为完全不变。
> - [模型可见] 不实现该接缝则 `codemode.mode` 与内联目录无法对位；v1 若 review 拒绝 B288，则两个 settings 键与目录能力降级并登记窄差。

## 3. 模型可见契约（v1 基准）

### 3.1 工具定义

| 项 | pi 原文 | Java v1 |
|---|---|---|
| name / label | `codemode` | 同 |
| input schema | `{ code: { type: "string", description: "Raw JavaScript source." } }` | 同（`tool.ts:78-82`） |
| exposure | `model-only` | `ToolExposure.MODEL_ONLY` |
| defaultActive | `false` | `defaultActive() = false` |
| reachesTools | 含 discovery global ⇒ all；否则源码单词 | `ToolReach.All` / `ToolReach.Words`（`tool.ts:381-392`） |
| constrainedSampling | `{ type: "grammar", variants: { openai_lark: CODEMODE_SOURCE_GRAMMAR } }` | `new GrammarSampling(Map.of("openai_lark", ...))` |
| prompt snippet | `Run JavaScript that calls other tools` | 同（`tool.ts:108-109`） |
| prompt guideline | `Use codemode to batch independent tool calls (Promise.allSettled), chain them, or filter large output, instead of many separate calls.` | 同（`tool.ts:110-112`） |

工具描述开头逐字（`tool.ts:118-120`）；v1 只把 `QuickJS` 改为 `JavaScript sandbox`（D264，见 §7）：

```text
Run JavaScript that calls other tools. The input is raw JavaScript (not JSON, no code fence), run as an async function body in a JavaScript sandbox: top-level `await` and `return` work. No Node, file system, network, or timers.
- `await tools.<name>({ ...args })` resolves to a string, or an object if the tool's declaration says so, and rejects with an Error on failure. A tool whose declaration returns an object resolves on failure too when it returned that object; check its fields. Calls still running when the script ends are cancelled.
- Optional first line: `// @options: {"max_output_tokens": 10000, "timeout_ms": 60000}`
```

`describeGlobals` 逐字（`tool.ts:123-134`），`models=false` 时省略最后一行：

```text
Globals:
- `text(value)`, `image(dataUrlOrImageBlock)`, `console.log(...)`, and top-level `return` add output; `exit()` ends the script. With several text items, each starts with a `==> text N/M <==` line, and `console` lines follow the other output in one `<console_output>` block. `image()` also saves the image to a temp file and the result names its path.
- `store(key, value)` and `load(key)` keep JSON values across codemode calls.
- `ALL_TOOLS`, `await searchTools(query, { limit?, namespace? })`, `await describeTool(name)`, `await describeNamespace(name)`: find unlisted tools, such as MCP tools.
```

### 3.2 结果格式

逐字对位 `execute.ts`：

| 位置 | 文本 | 锚点 |
|---|---|---|
| 结果头 | `Script completed\nWall time <n.n> seconds\nOutput:\n` / `Script failed\n...` | `execute.ts:643` |
| 多文本项 | `==> text <i>/<n> <==\n<text>` | `execute.ts:334` |
| console 块 | `<console_output>\n<lines>\n</console_output>` | `execute.ts:338` |
| 失败尾部 | `Script error:\n<head>\n\n<call summary>` | `execute.ts:627,379-390` |
| 超时/中止/沙箱 | `Script timed out: ...` / `Script aborted: ...` / `Script sandbox failed: ...` | `execute.ts:381-389` |
| 截断 | `Warning: truncated output (original token count: N)\nTotal output lines: N\n\n<head>…<n> tokens truncated…<tail>\n\n[Full output: <path> (read with offset/limit)]` | `execute.ts:462-467` |
| 图片保存 | `[Image saved to <path> (<mime>, <size>)]` | `execute.ts:425` |
| 未展示生成图 | `Note: models.generateImages() returned ...`（v1 不触发） | `execute.ts:628-633` |
| 无调用摘要 | `No tool calls were made.` / `Tool calls made before the failure (they are not undone): ...` | `execute.ts:374-377` |

### 3.3 globals / 限制

- `tools.<jsName>(args)` 与 `tools["<raw name>"]`；`ALL_TOOLS` 为 `{name: jsName, description}`（prelude）。
- `text` 非字符串 JSON 化；`console.*` 标记 `console: true`；`image` 只接受 base64 data URI / `{image_url}` / image block，拒绝远程 URL。
- 输出上限：16 MiB 文本+base64、100000 项；超限脚本失败且捕获不能续写（`prelude-source.ts:337-355`）。
- store：单值 256 Ki、总量 1 Mi；成功脚本写 `codemode-store` 条目。
- `searchTools` 默认 8、BM25；`describeTool`/`describeNamespace` 见 `execute.ts:669-735`。
- 无定时器/IO；脚本等待一个永远不能 settle 的 Promise 时立即失败（`prelude-source.ts` 的 `stalled()`，`vm.ts:241-250`）。

### 3.4 settings

| 键 | 默认 | pi 描述（`settings-schema.ts:154-167`） | Java v1 |
|---|---|---|---|
| `codemode.mode` | `"on"` | `How codemode presents tools. "on" keeps direct tools declared and annotates tools callable from scripts; codemode lists only tools without direct exposure. "only" lists every script-callable tool in codemode and does not declare active direct tools.` | 同（依赖 B288） |
| `codemode.inlineBudget` | `3000` | `Estimated tokens available for inline codemode tool declarations.` | 同（依赖 B288） |

### 3.5 引擎措辞

- GraalJS 后端：`QuickJS` → `JavaScript sandbox`，登 D264。
- Chicory 后端：可保留 `QuickJS` 逐字。
- 其余 globals/结果/错误文案不随引擎变化。

## 4. 架构与模块

### 4.1 模块与依赖

新增 Maven 子模块 **`pi-java-codemode`**（第 16 个，reactor 17 项）：

```
pi-java-codemode         ← 无 pi-java 依赖（只依赖 GraalJS/Jackson/jspecify）
pi-java-coding-agent     ← 依赖 pi-java-codemode
pi-java-dist             ← 经 coding-agent 传递，shade 进 fat jar
```

理由：pi 的 `packages/codemode` 本身无 pi 依赖，可独立使用/测试；沙箱与 coding-agent 的会话接线分开，避免把引擎与工具管线绑死。

BOM/父 POM 改动：
- `version.graaljs=25.0.5`（JDK 25 LTS 线；B0 锁版，必要时回落 25.0.4）。
- 依赖坐标：`org.graalvm.polyglot:polyglot` + `org.graalvm.polyglot:js-community`（均 `pom` 包装）。
- 内部模块 `pi-java-codemode` 进 `<dependencyManagement>` 与 reactor。
- `pi-java-dist` 无需显式添加（传递）；若 shade 漏服务，补 `ServicesResourceTransformer` 已有配置。

### 4.2 包结构（`com.pijava.codemode`）

```
api/        CodemodeTool, CodemodeOutputItem, CodemodeCall, CodemodeError,
            CodemodeResult, CodemodeStoreWrites, CodemodeExecuteOptions
            CodemodeIdentifier, CodemodeSource, CodemodeDeclarations
runtime/    Protocol (sealed messages), CodemodeExecution, CodemodeSandboxBase
backend/    CodemodeVmLauncher (SPI), GraalJsVmLauncher, GraalJsVm
resources   codemode-prelude.js（逐字 vendored，MIT）
```

### 4.3 运行时 SPI

pi 的 `VmLauncher` / `VmChannel` / `VmEvents`（`execution.ts:63-82`）在 Java 中同形：

```java
public interface CodemodeVmLauncher {
    VmChannel launch(VmStartData data, VmEvents events);   // 失败 ⇒ sandbox error
}
public interface VmEvents {
    void message(VmToHostMessage message);
    void failure(CodemodeError error);
}
public interface VmChannel {
    void post(HostToVmMessage message);
    void stop();                                            // 请求关闭，不保证已退出
}
```

`VmStartData`（`protocol.ts:14-28`）：`code`、`tools`（`name/jsName/description`）、`globals`（`name/spread`）、`store`（key→JSON text）、`memoryLimitBytes`（GraalJS 忽略，登记窄差）。

`Protocol` 用 sealed 接口 + record 逐字对位 `protocol.ts:43-60`；JSON 字符串仍是唯一跨边界值形式。这样 host 侧 `CodemodeExecution` 可**先于引擎实现并用假后端测试**（对位 pi `execution.ts`）。

### 4.4 线程模型与生命周期

```
AgentSession 工具线程
 └ CodemodeExecutor（准入 Semaphore，默认 2；60s/截止时间）
    ├ CodemodeExecution（host：输出、调用记录、截止时间、abort）
    └ GraalJsVmLauncher
        └ daemon 平台线程 "codemode-vm-N"
            └ Graal Context + prelude + 脚本
                └ bridge → 消息队列 → host
    └ 嵌套工具在工具执行器上运行；结果经 settle 回到 VM 线程
```

- **准入**：`Semaphore` 公平 acquire；等待计入 `timeout_ms`；默认上限 2，读 `PI_CODEMODE_MAX_WORKERS`；等满 60s 的错误文案逐字 `execute.ts:560-567`。释放发生在 VM 线程实际退出后（pi `acquireWorker` 对位）。
- **超时**：host 计时到点 → 置 `finished`、abort 所有 pending 调用、请求 VM stop，结果 `kind=timeout`，文案 `Execution timed out after N ms`（`execution.ts:216`）。
- **中止**：`AbortSignal` 触发同上，`kind=aborted`。
- **终止**：Java 无 `Thread.stop`；使用 GraalJS 的两级取消：
  1. 先 `context.interrupt(Duration.ofSeconds(1))`（软取消；guest `finally` 会执行）；
  2. 再 `context.close(true)`（硬取消）；`close` 不等待时放弃 daemon 线程。
  若 `interrupt` 抛 `TimeoutException`（脚本卡在不可中断的宿主等待），先把结果标记为 `timeout`/`aborted`，
  再放弃 daemon 线程；该边缘差异登记 D269。
  另用 `ResourceLimits.statementLimit(N, onLimit)` 作 CPU 兜底：超限即取消，避免完全依赖截止时间。
  该终止语义与 pi 在 Bun 上的注记同类（`host.ts:12-19`；`vm.ts` 的 interrupt flag 由本设计换成引擎 API）。
- **并发**：每个执行独立 Context/线程，无共享状态；sandbox 只持有工具表与默认值（`execution.ts:451-459`）。
- **单线程约束**：Graal Context 同一时刻只允许一个线程进入。host 的工具线程不直接调用 `settle`，
  而是把结果入队；VM 线程在两次 `eval` 之间取出结果、调用 prelude 的 `settle`，再排空微任务。
  bridge 是 `ProxyExecutable`，在 VM 线程上只负责把 `call/global/output/done` 入队给 host。

## 5. pi 逐件对位

### 5.1 纯函数层（可直接移植）

| pi | 行数 | Java | 口径 |
|---|---|---|---|
| `identifier.ts` | 12 | `CodemodeIdentifier` | 逐字算法：首字符 `[A-Za-z_$]`，其后 `[A-Za-z0-9_$]`，非法→`_`，空→`_` |
| `source.ts` | 125 | `CodemodeSource` | 逐字：首行 `// @options:`、行号保持、非首行报错、字段校验、`timeout_ms` 上界 |
| `declarations.ts` | 340 | `CodemodeDeclarations` | 逐字：`schemaToType`、本地 `$ref`、标签、`inputMaxChars`、`renderToolSample`、`renderDeclarations` |
| `types.ts` | 254 | 多个 record/sealed | 只保留 execute 路径使用的成员；删 `start()`/确定性字段 |

`source.ts` 关键错误文案（`source.ts:56-125`）全部保留，包括：

```text
The // @options: line must be the first line of the input; found one on line N
@options only supports `max_output_tokens` and `timeout_ms`; got `x`
@options field `timeout_ms` must be a positive integer up to 2147483647
```

`declarations.ts` 是模型可见文本（工具声明）的主要生产者，必须完整移植；其测试 `declarations.test.ts` 328 行逐条转成 Java 断言。

### 5.2 沙箱运行时

| pi | 行数 | Java | 口径 |
|---|---|---|---|
| `prelude-source.ts` | 522 | `codemode-prelude.js` 资源 + `CodemodePrelude` | **逐字 vendoring**（MIT）；常量 `MAX_OUTPUT_CHARS`/`MAX_OUTPUT_ITEMS`/`MAX_STORE_*` 由 Java 注入或保留 |
| `protocol.ts` | 93 | `Protocol` | sealed 消息逐字段对位 |
| `execution.ts` | 573 | `CodemodeExecution` + `CodemodeSandboxBase` | 逐字语义：首个终局事件胜出、done 解析、pending 中止、unsent 队列、错误分类 |
| `vm.ts` | 341 | `GraalJsVm` | 适配：bridge/run/settle/stalled 接线；Promise job 排空；异常格式化 |
| `host.ts` | 88 | `GraalJsVmLauncher` | 适配：每执行一个线程/Context；stop 语义 |
| `worker.ts` | 39 | 不移植 | 由 launcher 取代 |
| `inline.ts`/`remote.ts`/`cloudflare.ts` | 478 | 不移植 | 非目标 |

vm 接线必须逐字对位（`vm.ts:254-341`）：

- bridge 的四种 kind：`call`/`global`/`output`/`done`；
- prelude 以 `(bridge, toolsJson, globalsJson, storeJson)` 求值，返回 `{ settle, run, stalled }`；
- 脚本作为 `(async (tools, console) => { <code>\n})` 求值，源码名 `codemode.js`，使行号对齐；
- `run` 调用后排空微任务；无 pending 调用时 `stalled()` 直接失败；
- `done` 携带 `ok/value/writes` 或 `ok=false/error`。

### 5.3 工具层

| pi | 行数 | Java | 口径 |
|---|---|---|---|
| `tool.ts` | 434 | `CodemodeToolDefinition` + `CodemodeDescription` | 逐字：描述目录、`selectCatalog`、`describeScriptCall`、`getScriptReach`、schema/snippet/guidelines |
| `execute.ts` | 850 | `CodemodeExecutor` | 逐字：准入、嵌套调用、globals、store、输出排版、截断、图片保存、错误 |
| `index.ts` | 49 | `CodemodeExtension` | 注册形状与 settings 读取 |
| `renderer.ts` | 156 | `CodemodeToolRenderer` | 文本级对位（无主题色断言） |

`execute.ts` 的关键流程（`execute.ts:500-652`）按顺序保留：

1. `parseCodemodeSource`；
2. `publish()` 先发 running 快照；
3. 可调用工具 = `ctx.tools` 去掉 `codemode` 自身；
4. 每个工具生成 `renderToolSample`（`ALL_TOOLS`/`describeTool` 用）；
5. 准入等待（计入 deadline）；
6. 构造 sandbox（tools + discovery globals + 可选 models globals）；
7. `readCodemodeStore(ctx.sessionManager.getBranch())`；
8. 执行、关闭、复原 running 调用为 cancelled；
9. 成功脚本 `appendEntry("codemode-store", {set, delete})`；
10. 返回值按 `text` 追加；
11. `formatOutput` → 错误尾部 → 截断 → 图片保存 → 头部；
12. `details.calls` 与 `fullOutputPath`。

### 5.4 输出/截断/图片

- `formatOutput`：多文本项编号、console 合并（`execute.ts:322-341`）。
- `joinAdjacentText`：相邻文本合并，分隔判断看最后一个非空片段（`execute.ts:348-372`）。
- `truncateOutput`：预算 = tokens × 4；保留头尾、写临时文件、加提示（`execute.ts:449-472`）。
- `saveImages`：每个图片写临时文件并在其前插入标签；同 data 只写一次（`execute.ts:414-442`）。
- Java 侧已有可复用的公开工具 `McpOutputSaver.saveText(byte[], String)`（`extension/mcp/McpOutputSaver.java:18-28,38-40`）：
  `pi-mcp-*` 临时文件 + owner-only 权限。建议抽出共享 `OutputFiles`（或在 codemode 内复用）；若路径前缀改为
  `pi-codemode-*` 则与 pi 结果文本有差，登 D272。

### 5.5 MCP 激活（B177）

pi 在 `extensions/mcp/index.ts:498-535` 的 `ensureDiscoveryActive` 中，对 `exposure: codemode` 的服务器自动激活 codemode（`autoEnableCodemode` 默认 true），并要求该工具声明了 `reachesTools`：

```ts
const hasCodemode = hasReachingTool(CODEMODE_TOOL_NAME);
if (needsCodemode && hasCodemode && autoEnableCodemode && !active.includes(CODEMODE_TOOL_NAME)) {
    activate.push(CODEMODE_TOOL_NAME);
}
```

Java 侧已有 `McpExposure.CODEMODE`（`McpExposure.java:20-21`）与 `autoEnableCodemode` 配置
（`McpConfig.java:272-276`），只缺激活臂；`McpExtension.ensureDiscoveryActive` 当前只有 tool_search 臂。
本包补 codemode 臂与告警文案：

```text
MCP tools are only reachable from the codemode or tool_search tool, but neither is active; they cannot be called.
```

同时补 `mcp_servers` 提示段的 codemode 措辞（`index.ts:165-172`）：

```text
MCP servers whose tools are not declared to you. Call the tools of `codemode` servers from codemode scripts.
```

## 6. Java 集成点

### 6.1 扩展注册

- `CodemodeExtension implements PiExtension`，`NAME = "codemode"`；在
  `META-INF/services/com.pijava.coding.agent.extension.PiExtension` 追加一行
  （现有 `McpExtension`/`ToolSearchExtension` 同文件），`ExtensionManager.discover()`（`ExtensionManager.java:35-41`）自动发现。
- **Java 没有 `ExtensionContext.registerTool`**：注册走 `ctx.tools()`（`ExtensionContext.java:23-24`）返回的
  `ToolRegistry`，方法为 `ToolRegistry.register(AgentTool)`（`ToolRegistry.java:34-36`）。
- `defaultActive=false`、`exposure=MODEL_ONLY`、`reachesTools`（`AgentTool.java:92-94`）、
  `constrainedSampling`（`AgentTool.java:127-129`），`inputSchema` 为 `Map<String,Object>`。
- `--tools +codemode` 与 `defaultTools` 走 `ToolSelection`（`ToolSelection.java:193-228`），无需新逻辑。
- 若要支持 `--no-codemode`，需在 `ArgsParser`/`ExtensionLoading` 的 disabled builtin 集合中加名
  （当前生产只禁 `mcp`，`ExtensionLoading.java:240-245`）。
- `CodemodeExtension` 读取 `ctx.settings()` 的 `codemode.mode`/`inlineBudget`，构造 tool 时注入。

### 6.2 settings

- `core/Settings.java` 是公开字段 POJO（`:23-137`，`@JsonAnySetter` 容忍未知键）：新增
  `CodemodeSettings` 字段（`mode`、`inlineBudget`）。
- `SettingsManager.merge()`（`:348-367`）做深合并；Java 无 `SettingsDefaults` 类，默认值在
  `SettingsAccessors` 提供（`on`、`3000`），与 pi `settings-defaults.ts:57-60` 一致。
- 扩展只经 `ExtensionContext.settings()`（`ExtensionContext.java:36`）读 settings。

### 6.3 嵌套调用

- `CodemodeExecutor` 的每个 sandbox tool 调
  `ctx.executeTool(callerToolCallId, signal, name, args, onUpdate)`（`ExtensionContext.java:418-429`；
  `DefaultExtensionContext.java:260-271` 路由到 `AgentSession.executeNestedToolCall`）。
- 可调用集合：活跃 direct + 全部 `codemode`/`deferred`（`ModelToolControl.callableTools`，`:397-408`）；
  `getCodemodeCallableTools` 只去掉 `codemode` 自身。
- 结果映射 `toScriptValue`（`execute.ts:480-486`）：有 `outputSchema` ⇒ `structuredContent`；否则文本内容；
  `isError` ⇒ 抛出 `Error`。Java 的 `PiLoop.ToolOutcome` 携带完整 `ToolResult<?>`（`PiLoop.java:126`），
  可直接读 `result.structuredContent()`（`ToolResult.java:27-35`）；**codemode 成为该字段的首个消费者**
  （当前注释写明尚无消费者）。不依赖 `ToolResultMessage`（它不携带 structuredContent）。
- 调用记录 `id=<codemodeCallId>/<n>`、`args` 预览 200 字符、错误 500 字符（`execute.ts:44-46,529-551`）。

### 6.4 store

- 写：成功脚本调用 `AgentSession.appendCustomEntry("codemode-store", map)`（`AgentSession.java:406-408`），
  或扩展面 `ExtensionContext.appendEntry`（`ExtensionContext.java:259`）。
- 读：遍历 `ExtensionSessionView.getBranch()`（`ExtensionSessionView.java:26-27`）中的 custom 条目，
  按根到叶 `delete` 后 `set`（`execute.ts:293-303`）。
- 数据形状：`{ set: Record<string, unknown>, delete: string[] }`。

### 6.5 渲染器与文档

- `CodemodeToolRenderer` 经 `ToolRendererRegistry.register(resolver)`（`ToolRendererRegistry.java:17-19`）
  或扩展面 `ExtensionContext.registerToolRenderer`（`:148`）注入。
- **TUI 消费**：`ToolRenderers.install`（`tui/component/ToolRenderers.java:21-23`），`MessageBubble.java:183-208`，
  fallback `ToolCallCard`。
- **Web 不消费 Java 渲染器注册表**：前端 `pi-web-ui` 有独立 registry，且 `main.ts:881,892` 把
  `.tools=[]` 写死；v1 的 codemode 渲染只做 TUI，web 走通用工具卡片（登记 D271）。
- 文本级对位：调用行、结果行、`Elapsed`/`Took`、`... (N more ...)`、`Full output: <path>`；颜色/动画不逐字。
- `docs/codemode.md` 作为 `pi-java-coding-agent` 资源；路径由 `PiJavaPaths` 暴露，工具描述引用同一路径。
  Models 段标注「pi-java 暂不可用」。

### 6.6 `prepareLoadout` 接缝（B288）

最小形状（名称可按 Java 风格调整）：

```java
public record ToolLoadout(
    List<AgentTool<?, ?>> declared,
    List<AgentTool<?, ?>> callable,
    Set<String> active,
    Function<String, ToolExposure> exposureOf,
    Function<String, ToolNamespace> namespaceOf,
    Function<String, List<String>> guidelinesOf) {}

public record ToolLoadoutChanges(
    Map<String, String> descriptions,
    List<String> hiddenDeclarations) {}

// AgentTool 新增默认方法
default Optional<ToolLoadoutChanges> prepareLoadout(ToolLoadout loadout) {
    return Optional.empty();
}
```

消费点：
- 请求工具声明由 `PiLaneEngine.activeTools(lane)`（`PiLaneEngine.java:364-371`）组装；在此按激活工具调用
  `prepareLoadout`，覆盖同名 `description`、移除 `hiddenDeclarations`。
- 系统提示的 snippet/guidelines 由 `ContextAssembler.promptOptions`（`:80-127`）组装；`only` 模式隐藏声明后，
  snippet 也应同步消失（否则系统提示仍宣告工具）。
- 无 hook 的工具保持现有行为；用「无 hook 工具输出逐字节不变」测试锁定。

Codemode 侧逐字实现 `prepareCodemodeLoadout`（`tool.ts:343-379`）：

- `mode=on`：已声明工具描述追加 `Codemode: tools.<id>(args) resolves to <type>.`；
- `mode=only`：目录列出所有 callable；`hiddenDeclarations` = 已声明的 direct 工具；
- 目录输入：列出非 direct 工具；`deferred` 不进目录但保持可调用；
- 目录预算 `inlineBudget`；`selectCatalog` 逐字（`tool.ts:203-220`）。

若 B288 未获批准：`codemode.mode` 与 `inlineBudget` 不实施，工具描述固定为「globals + 请用 discovery globals 查工具」，登 D265。

## 7. 裁剪与窄差

拟新增（编号接 docs/60 的 D263，使用 D264–D272；避免与并行 docs/59/60 冲突）：

| 编号 | 差异 | 级别 | 说明 |
|---|---|---|---|
| D264 | 工具描述 `QuickJS` → `JavaScript sandbox`（仅 GraalJS 后端） | [模型可见]文案差异 | 引擎不同，保留 QuickJS 会误导 |
| D265 | `codemode.mode`/`inlineBudget` 与内联目录（若 B288 不批准） | [模型可见] | 模型改用 `ALL_TOOLS`/`describeTool` |
| D266 | `models` 命名空间缺席（v1） | [模型可见] | 描述少一行；脚本调用报未定义 |
| D267 | 无 per-context 内存上限（GraalJS） | [内部] | QuickJS 的 256 MiB 无法对位 |
| D268 | 栈帧格式差异（GraalJS vs QuickJS） | [模型可见]边缘 | 行号保留；列号/帧前缀可能不同 |
| D269 | CPU 紧循环超时可能无法立即终止 | [内部] | daemon 线程放弃；见 §4.4 |
| D270 | `Remote`/`Inline`/确定性 `start()` 不实现 | [内部] | 非目标；API 不暴露 |
| D271 | TUI 渲染器颜色/动画不逐字；Web 不消费 Java 渲染器、走通用工具卡片 | [内部]/UI | 前端 registry 与 `.tools=[]` 写死，见 §6.5 |
| D272 | 临时输出文件前缀/路径与 pi `writeOutputFile` 可能不同 | [模型可见] | 仅路径文本；复用 `McpOutputSaver.saveText` 时为 `pi-mcp-*` |

输出上限、store 上限、globals、错误文案、结果排版不裁剪。

## 8. 测试计划

RED-first；每个新增类先落夹具。分组：

### 8.1 纯函数（B1）

- `CodemodeIdentifierTest`：`mcp__docs__search` 保持、`my-tool`→`my_tool`、首字符非法、空→`_`。
- `CodemodeSourceTest`：逐条转 `source.test.ts` 5 组（plain、options/行号、仅首行、错位、非法）。
- `CodemodeDeclarationsTest`：逐条转 `declarations.test.ts`（基元/字面量/联合、对象排序、描述注释、约束标签、非空分支、draft-04、长标签转义、超预算去标签→unknown、本地/递归 `$ref`、数组/元组、签名、MCP 输出形状、sample、tools/globals、命名空间、注释转义）。
- `CodemodePreludeResourceTest`：资源存在、逐字哈希锁定（防误改）。

### 8.2 host 执行层（B2，假后端）

- `CodemodeExecutionTest`：done/ok+value/writes、done/error、crash、首个终局事件、unsent 队列、malformed payload→sandbox error、pending 调用 abort、call 记录口径。
- `CodemodeSandboxBaseTest`：工具重名、globals 校验（保留名/命名空间冲突）、close 后 execute、并行执行隔离、deadline/abort。

### 8.3 GraalJS 后端（B3，spike 闸门）

- `GraalJsSpikeTest`（保留为回归）：
  1. 组件可用性、25.0.5 与构建 JDK 25.0.4 的兼容；`META-INF/services` 在 shade 后仍被发现；
  2. prelude 求值 + `run/settle/stalled` 可达；
  3. 工具调用往返、`undefined` 参数/结果；
  4. `settle` 从 host 线程回调后微任务排空；
  5. top-level await；
  6. `Context.interrupt(Duration)` 能停止 `while(true)` 与 `while(true) await null`（软取消）；
     若 `TimeoutException` 出现，记录 `close(true)` 后行为；
  7. `ResourceLimits.statementLimit` 超限取消；
  8. `HostAccess.NONE` + `IOAccess.NONE` + 线程/进程/原生/环境变量全关；探测
     `Java`/`Polyglot`/`Graal`/`load`/`print`/`WebAssembly` 是否泄漏并处理（必要时脚本前置删除）；
  9. `engine.WarnInterpreterOnly=false` 后 stderr 无引擎警告；
  10. 冷启动时间、内存行为与 fat jar 增量记录（预期 +64 MB）。
- 若 6/7/8 不可满足，停止 GraalJS 路线，改 Chicory。

### 8.4 沙箱行为（B3）

逐条转 `sandbox.test.ts`（67-869）：
- 返回值 JSON 往返、top-level await、text/image/console 顺序、非法 text/image、wrapped/large base64、exit 保留输出与 store、失败保留部分输出、语法/抛错行号、非 Error 抛出、不可序列化返回值、工具并发/标识符/ALL_TOOLS/undefined、工具错误可捕获、未知工具、未 await 调用取消、`timeoutMs=Infinity`、stalled、微任务循环超时、signal abort、输出上限、close、并行隔离、深递归 RangeError。
- escape hatches：无宿主全局、eval/Function 留在 VM、动态 import 拒绝、内建补丁无效、实例可覆写、非字符串错误、tools/console 冻结。GraalJS 差异逐条登记。

### 8.5 工具层（B4）

- `CodemodeToolDescriptionTest`：intro/globals、目录选择、deferred 排除、命名空间分组、inlineBudget、`on`/`only`（若 B288）。
- `CodemodeExecutorTest`：嵌套调用记录、`toScriptValue`、输出排版、截断+fullOutputPath、图片保存、store 读写、结果头、准入等待错误、deadline。
- `CodemodeStoreTest`：分支重放、delete/set 顺序。
- `CodemodeLoadoutTest`：`prepareLoadout` 覆盖描述/隐藏声明；无 hook 工具不变。
- `CodemodeRendererTest`：文本行、折叠、fullOutputPath、状态图标降级为文本。

### 8.6 集成（B5）

- 端到端：faux provider 发 `codemode` 调用，脚本调用 `read`/`bash` 假工具，断言最终消息与 details。
- MCP：`exposure=codemode` 的服务器 ⇒ 自动激活 codemode；`autoEnableCodemode=false` ⇒ 告警。
- `--tools +codemode` 与 `defaultTools` 激活。
- 会话恢复：`codemode-store` 条目跨分支。
- TUI 渲染消费；web 走通用工具卡片（D271）。

### 8.7 验收

- `mvn clean verify` 零错误零警告；无 `System.out.println`；SpotBugs 0。
- 引擎 spike 全部通过；fat jar 体积与冷启动数据记录在实施记录。
- 不改变 `docs/05` 计分。

## 9. 实施批次

| 批 | 内容 | 预估 |
|---|---|---|
| B0 | **引擎 spike**：GraalJS 依赖锁版 + `GraalJsSpikeTest`；记录启动/体积/取消/宿主泄漏。Endive 运行时机制已于 2026-11-20 实测走通；若选 Endive，B0 追加 `QuickJsWasiBinding` 最小实现（eval/host_call/job drain/interrupt）与 OOM 边界 | ~200 行测试＋（Endive 时）~600 行绑定，1–3 天 |
| B1 | 模块骨架 + identifier/source/declarations + 资源哈希测试 | ~900 行 |
| B2 | protocol + execution + sandbox base + 假后端测试 | ~900 行 |
| B3 | 首选引擎的 launcher/vm + prelude 资源 + 沙箱行为测试（GraalJS 或 Endive，B0 定） | ~1,200–1,500 行 |
| B4 | tool/execute + store + 输出/截断/图片 + discovery globals | ~1,200 行 |
| B5 | settings + B288 loadout 接缝 + 扩展注册 + MCP 激活 + renderer + docs + e2e | ~900 行 |
| B6（可选） | Chicory 后端 / 进程后端；`models` 最小目录 | 另立 |

每批独立可编译、独立 commit（200–500 行/次），B0 失败则停止并回写本设计。

## 10. 风险与开放问题

### 10.1 风险

- **引擎取消**：`Context.interrupt(Duration)` 对常规 JS 循环有效，但「不可中断的宿主等待」会抛 `TimeoutException`；此时放弃 daemon 线程（D269）。需要 spike 实测 `while(true)` / `while(true) await null`。
- **内存 DoS**：无 per-context 硬上限（D267）；`ResourceLimits.statementLimit` 只兜底语句数。本地 CLI/本地单用户 web 可接受；若 web 部署为多租户/远程，风险放大，需进程后端或默认关闭 codemode。
- **体积**：GraalJS 路线 fat jar 约 +64 MB（25.0.5；148 MB ⇒ ~212 MB）；Endive 路线实测仅
  **+0.95 MB（压缩）**，但需 Java 侧 quickjs-wasi 绑定与 AOT 编译插件（§2.2）。
- **许可证**：GraalJS 25.0.5 的依赖为 UPL/MIT/ICU（`icu4j`）；`js-community` POM 标注 deprecated，B0 需核对是否切 `org.graalvm.polyglot:js`，并记录许可结论。
- **官方安全边界**：纯 Java CE 无 `SandboxPolicy`；`UNTRUSTED` 需 Native Image isolate（四平台约 246 MB，含原生库）。本包只承诺「功能限制」，威胁模型是模型写错/写坏，不是恶意多租户。
- **stderr 污染**：解释模式会打 `[engine] WARNING`；必须设 `engine.WarnInterpreterOnly=false`，并在集成测试中断言 stderr 干净（CLI 输出纪律）。
- **宿主接缝**：B288 改请求装配点，回归面广；必须用「无 hook 工具行为不变」测试锁定。
- **模型误用**：`models` 缺席、`only` 模式缺失会让脚本报错；docs 必须写清 v1 范围。

### 10.2 待用户拍板

| 编号 | 问题 | 建议 |
|---|---|---|
| F1 | R5 是否按 §2.1 改为「codemode 能力版、不进分母」？ | 建议同意 |
| F2 | 引擎是否按 §2.2 选型：Endive 1.1.0（保真、+0.95 MB、需 ABI 绑定）优先，GraalJS（+64 MB、零绑定）兜底，Chicory 备选？ | 建议同意；Endive 机制已实测，B0 只剩 Java 绑定与 OOM 边界；若绑定超预算回落 GraalJS |
| F3 | 是否批准 B288 `prepareLoadout` 接缝以对位 `mode`/内联目录？ | 建议批准；否则降级并登 D265 |
| F4 | `models` 命名空间 v1 是否直接不做？ | 建议不做（Java 无 classifier/图像 API） |
| F5 | web 模式是否默认允许 codemode？ | 建议与 CLI 同策略（本地单用户默认可用，可经工具选择关闭）；多租户/远程部署默认关闭或接进程后端 |

## 11. 台账回填计划（闭环时）

- `docs/04`：B161 重开 → 实施/闭环；B177 随 MCP 激活闭环；B288 实施/闭环；R5 §5.1 加注；新增 D264–D272 指向 `docs/map/04`。
- `docs/map/04` G-6/G-7：从「面外 0」改为「能力版已实施、不计分」；E2-45 同步。
- `docs/05`：**不改分母与完成度**，只在说明栏加「codemode 能力版存在，未计分」。
- `docs/02`：模块数 15→16，补依赖方向。
- 本文档：实施记录与 spike 数据（启动、体积、取消行为）。
