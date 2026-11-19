# 53 - B286 沙箱执行后端接缝设计（shell operations ＋ user_bash operations）

> 【背景摘要】用户提出「pi-java 不能接入 Docker 这类沙箱，pi 是怎么支持的」，并裁决
> 「按这个方案来」。取证结论：pi 也没有内置沙箱，它靠**两种隔离方式**——整进程进容器，
> 或给内置工具注入 `operations` 执行后端；后者对 bash/powershell 是 `BashOperations`，
> `!` 会话命令另有 `user_bash` 的 `{ operations }` 臂。pi-java 两处都缺：bash 工厂没有
> 执行器参数（`ShellToolSupport` 内部有 `executorOverride`，`BashTool` 恒传 `null`），
> `UserBashEventResult` 只有 `{ result }` 臂（D10）。
>
> **本包只补 shell 执行后端接缝（bash/powershell ＋ user_bash）**，即 pi `sandbox` 示例
> 的等价能力。文件工具（read/write/edit/grep/find/ls）另立 B287：`grep`/`find`/`ls`
> 直接走 NIO、`edit` 混用 NIO 与 `FileSystem`，改造面远大于本包。
>
> **文档状态：已批准实施并闭环（2026-11-19）。** 实施记录见 §8。

- pi 锚点：`56cae782341c339d3bb820081f0f9425d8c5b1ec`
- 台账：`docs/04` B286（本包）、B287（文件工具 operations，后续包）
- 相关既有裁决：D10（`user_bash` 只移植 `{result}` 臂）、D232（examples 的原生沙箱出分母）
- 上游文档：`packages/coding-agent/docs/containerization.md`、`extensions.md#remote-execution`、`security.md`

## 0. 取证口径

1. pi 侧行为逐字取自锚点源码，路径均以 `packages/coding-agent/` 起。
2. Java 侧改动只落在 `pi-java-agent-core`（工具工厂）与 `pi-java-coding-agent`
   （`user_bash` 结果联合与接线）；**不新增 `ExtensionContext` 成员**。
3. 「沙箱」指**执行后端替换**，不是内置隔离：本包不引 Docker 依赖、不加 settings/CLI 开关；
   Docker/SSH/微 VM 由扩展自行实现 `ShellExecutor` 并注册。

## 1. 目标与范围

### 1.1 目标

让扩展能按 pi `sandbox.ts` / `ssh.ts` 的方式接入外部执行后端：

- 注册同名 `bash` 工具（`BashTool.create(..., executor)`），模型调用走外部后端；
- `!` / RPC 会话 bash 经 `user_bash` 返回 `{ operations }`，也走同一后端；
- 后端可以是 `docker exec`、`ssh`、micro-VM 或任何 `ShellExecutor` 实现。

### 1.2 非目标（登记在案，不在本包实施）

| # | 不做项 | 理由 / 去向 |
|---|---|---|
| 1 | read/write/edit/grep/find/ls 的 operations 接缝 | `grep`/`find`/`ls` 是纯 NIO（`Files.walkFileTree`/`Files.list`），`edit` 混用 `Files.readString` 与 `context.fs()`；单独立 **B287** |
| 2 | `spawnHook`（改 command/cwd/env） | 扩展可用 `tool_call` 的 `input` 原地改写（`EngineHookBridge.mutableArguments`）近似；cwd/env 级改写登记 D240 |
| 3 | 内置 Docker 实现 / Docker 依赖 | 与 D232 口径一致；扩展自带后端 |
| 4 | `createLocalBashOperations()` 导出 | Java 的本地后端就是 `new DefaultShellExecutor()`，无需再包一层 |
| 5 | CLI/settings 的沙箱开关 | 归属扩展（pi 的 `--no-sandbox` 也是示例 flag） |
| 6 | `user_bash` 的非法形状错误文案 | 见 §4.2：Java 用 sealed 类型在编译期排除「两个臂同时给」 |

## 2. pi 事实清单

| 事实 | 位置 |
|---|---|
| `BashOperations` 只有一个 `exec(command, cwd, {onData, signal, timeout, env}) → {exitCode}` | `src/core/tools/bash.ts:75-94` |
| 工具工厂接受 `BashToolOptions.operations`，缺省回落本地执行 | `src/core/tools/bash.ts:214-225`、`:243-251` |
| 本地后端可复用（`createLocalShellOperations` / `createLocalBashOperations`） | `src/core/tools/bash.ts:97-176` |
| `user_bash` 结果是判别联合：`{operations}` 或 `{result}`，二选一 | `src/core/extensions/types.ts:1485-1496` |
| `emitUserBash` 首个非 `undefined` 处理器胜出；非法形状抛固定文案 | `src/core/extensions/runner.ts:1269-1298` |
| 会话 bash：`options.operations ?? createLocalBashOperations({shellPath})`，结果照常落会话 | `src/core/agent-session.ts:3922-3949` |
| sandbox 示例：覆盖内置 bash（`createBashTool(cwd,{operations})`）＋ `user_bash` 返回 `{operations}` | `examples/extensions/sandbox/index.ts:132-232` |
| ssh 示例：四个工具都以「同名覆盖 + `operations`」远程执行 | `examples/extensions/ssh.ts:81-140` |
| 两层隔离口径：整进程进容器 vs 宿主 Pi + 工具委托 | `docs/containerization.md:5-16` |
| pi 自述**无内置沙箱** | `docs/security.md:99` |

## 3. pi-java 现状与差距

| 接缝 | pi-java 现状 | 证据 |
|---|---|---|
| bash 执行器覆盖 | `ShellToolSupport.create` 已有 `executorOverride`，但 `BashTool.create` 恒传 `null` | `ShellToolSupport.java:52-54`、`:145-147`；`BashTool.java:70-74` |
| powershell 执行器覆盖 | 内部恒 `new PowerShellShellExecutor()`，无公开注入点 | `PowerShellTool.java:41-46` |
| `user_bash` operations 臂 | 只有 `{result}` 臂（D10） | `ExtensionResults.java:84-89` |
| `user_bash` 消费 | firer 返回 `Optional<ShellResult>`；会话 bash 恒 `toolContext.shell()` | `SessionEventFirer.java:303-325`；`SessionMessagingLifecycle.java:73-102` |
| 同名工具覆盖 | 已具备：`ToolRegistry.register` 是 `put`；扩展在 `wireSession` 之后加载 | `ToolRegistry.java:33-36`；`AgentSessionFactory.java:377-385` |
| 宿主级执行器注入 | 有，但只在装配层 `ToolContext`；CLI 硬编默认实现 | `ToolContext.java:28-51`；`AgentSessionFactory.java:52-56` |

结论：扩展**已能**注册同名工具，缺的只是「复用内置 bash 语义 + 换执行器」的工厂重载，
以及 `user_bash` 的 operations 臂。

## 4. 改动方案

### 4.1 shell 工具工厂（`pi-java-agent-core`）

新增两个公开重载，既有签名零改签：

```java
// BashTool
public static AgentTool<BashInput, BashDetails> create(
        String commandPrefix, boolean exposeSessionEnvironment,
        @Nullable ShellExecutor executorOverride);

// PowerShellTool
public static AgentTool<BashInput, BashDetails> create(
        boolean exposeSessionEnvironment,
        @Nullable ShellExecutor executorOverride);
```

- 既有 `BashTool.create()` / `create(String)` / `create(String, boolean)` 委托到新重载并传
  `null`；既有 `PowerShellTool.create()` / `create(boolean)` 委托并传
  `new PowerShellShellExecutor()`。
- `executorOverride != null ? executorOverride : context.shell()` 的分派点已在
  `ShellToolSupport` 内（powershell 走的就是它），本包不改分派逻辑。
- 类型对位：`ShellExecutor` ≙ pi `BashOperations`；`ShellOptions` 已携带
  cwd/env/inheritEnv/timeout/signal/outputSink/envRemovals，覆盖 pi `exec` 的全部入参，
  其中 `outputSink` 对位 `onData`。外部后端必须返回完整的 `ShellResult`
  （见 D240）。

### 4.2 `UserBashEventResult` 改为判别联合（`pi-java-coding-agent`）

```java
public sealed interface UserBashEventResult {
    /** pi { operations }：换执行后端，仍由宿主落会话/发事件。 */
    record Operations(ShellExecutor operations) implements UserBashEventResult {}
    /** pi { result }：扩展已执行完，宿主直接采用结果。 */
    record Result(ShellResult result) implements UserBashEventResult {}
}
```

- 结构上排除 pi 的「两个臂同时给」非法形状 ⇒ pi 的
  `Invalid user_bash handler result: ...` 错误文案在 Java 不可达，不移植。
- 破坏面：`new UserBashEventResult(x)` 的三处（2 个测试 + 无生产构造点）改为
  `new UserBashEventResult.Result(x)`；生产侧只有 `SessionEventFirer` 读它。
- `ExtensionEventSpec.USER_BASH` 的类型参数不变，仅结果类型换新联合。

### 4.3 firer 与会话接线

`SessionEventFirer.userBash` 返回类型改为 `Optional<UserBashEventResult>`：

- 逐处理器调用；`null` ⇒ 继续（pi `undefined`）；首个非 `null` 胜出（pi 逐字）；
- 处理器异常：`emitError` 后重抛（现状不变，调用方不得回落本地执行）。

`SessionMessagingLifecycle.executeBash` 改为三路：

```java
var outcome = extensionFirer() == null ? Optional.<UserBashEventResult>empty()
        : extensionFirer().userBash(command, excludeFromContext, toolContext.cwd());
if (outcome.isPresent() && outcome.get() instanceof UserBashEventResult.Result r) {
    recordBashResult(command, r.result(), excludeFromContext);
    return r.result();
}
var executor = outcome.isPresent()
        ? ((UserBashEventResult.Operations) outcome.get()).operations()
        : toolContext.shell();
// 以下照旧：ShellOptions 构造、emit BashExecutionUpdate、recordBashResult
```

语义与 pi 一致：`{result}` 不执行；`{operations}` 由扩展后端执行、宿主照常落会话；
无发言 ⇒ 本地执行。

### 4.4 扩展侧完整用法（示意，不落仓）

```java
public final class DockerSandboxExtension implements PiExtension {
    private final ShellExecutor docker = new DockerShellExecutor("pi-sandbox");

    @Override public String name() { return "docker-sandbox"; }

    @Override public void register(ExtensionContext ctx) {
        // ! / RPC 会话 bash
        ctx.on(ExtensionEventSpec.USER_BASH,
            (event, c) -> new UserBashEventResult.Operations(docker));
        // 同名覆盖内置 bash（默认 active 名单已含 "bash"）
        ctx.tools().register(BashTool.create(null, true, docker));
    }
}
```

`DockerShellExecutor` 的职责：把 `ShellOptions.cwd()` 映射到容器路径，执行
`docker exec -i <container> bash -lc <command>`，把输出喂给
`ShellOptions.outputSink()`，并按 timeout/abort 结束进程；返回 `ShellResult`。

### 4.5 窄差（实施后登记 D240）

| 编号 | 内容 | 处置 |
|---|---|---|
| D240 | `ShellExecutor` ≙ `BashOperations`/`PowerShellOperations`，但 Java 的返回值是完整 `ShellResult`（output/exitCode/timedOut/truncated/outputLines/outputBytes），pi 只要 `{exitCode}` + `onData`。外部后端须自行填 `timedOut/truncated` 与行列计数（可置 0/false）；工具层的截断/notice 仍由 `ShellToolSupport.formatOutput` 统一产出，模型可见文本不变。另：`spawnHook`（command/cwd/env 改写）无对位，扩展改用 `tool_call` 的 `input` 原位改写，无 cwd/env 级 hook | 实施后写入 docs/04 |

## 5. 测试计划（RED-first）

| 测试类（模块） | 断言 |
|---|---|
| `BashToolExecutorOverrideTest`（agent-core） | ① override 命中：`ToolContext.shell` 为「调用即失败」的假执行器，结果仍来自 override；② 无 override 时回落 `context.shell`；③ override 返回 `exitCode≠0` ⇒ 结果 `isError=true`；④ override 返回 `timedOut=true` ⇒ 超时文案；⑤ `onUpdate` 的 `outputSink` 被转交（假后端写增量 ⇒ 回调收到） |
| `PowerShellToolExecutorOverrideTest`（agent-core） | override 命中且**不触达平台门**（非 Windows 也能跑）；无 override 时仍按 `PowerShellShellExecutor`（平台门文案不变） |
| `UserBashOperationsTest`（coding-agent） | ① `Operations` 臂：假执行器被调用、宿主仍落 `bashExecution` 条目与 `BashExecutionUpdate`；② `Result` 臂语义不变（不执行）；③ 首个非 null 胜出、`null` 继续；④ 处理器异常重抛 |
| `ExtensionBashOverrideTest`（coding-agent） | 扩展注册同名 `bash`（带假执行器）后，一次 faux 工具调用走假执行器（证明 `wireSession` → 扩展注册的覆盖序生效） |

**变异探针**（每条去掉后必须有测试转红）：

1. `ShellToolSupport` 的 `executorOverride != null` 改成恒 `context.shell()`；
2. `userBash` 对 `Operations` 臂返回 `empty`；
3. `executeBash` 忽略 `outcome`；
4. 扩展同名注册改为不同名（`bash-override`）——只有 `ExtensionBashOverrideTest` 会红。

**回归**：既有 `SessionEventFirerTest`、`SessionLifecycleEventsTest`、`BashToolTest`、
`PowerShellTool` 相关测试全绿；`user_bash` 的两处旧构造改 `Result` 臂。

## 6. 验收与文档回填

1. `mvn test -pl pi-java-agent-core`、`mvn test -pl pi-java-coding-agent -am` 全绿；
2. 包末 `mvn clean verify`（Checkstyle ≤120 列、文件 ≤500 行；SpotBugs 零告警）；
3. 无新外部依赖、无 `pom.xml` 变更；
4. 回填：`docs/04`（B286 销号、D240 登记；B287 保持开放）、`docs/03` 工具执行器小节、
   `docs/map/03-agent-core.md` 与 `docs/map/04-coding-agent.md` 对应行；
5. `docs/04` §7 维护规则第 6 条的「下一新文档号」改为 docs/54。

## 7. 风险

| 风险 | 处置 |
|---|---|
| `UserBashEventResult` 由 record 改 sealed interface 是源码级破坏 | 生产读点仅 `SessionEventFirer`；构造点仅测试；全仓编译即暴露，无二进制兼容承诺 |
| 外部后端返回的 `ShellResult` 计数不准影响截断/notice | D240 明确契约；工具层仍以 `formatOutput` 的文本截断为准，计数只进 `details` |
| 同名 `bash` 覆盖发生在 `wireSession` 之后 | 已验证注册序（`AgentSessionFactory.java:377-385`）＋ `ToolRegistry` `put` 覆盖；用 `ExtensionBashOverrideTest` 钉住 |
| 文件工具未覆盖，用户误以为「全沙箱」 | 文档与通知口径写明：本包只覆盖命令执行；文件工具立 B287 |

## 8. 实施记录（2026-11-19）

- `pi-java-agent-core`：`BashTool.create(String, boolean, ShellExecutor)`、
  `PowerShellTool.create(boolean, ShellExecutor)` 落盘；既有重载委托，行为不变。
- `pi-java-coding-agent`：`UserBashEventResult` 改 sealed 联合（`Operations`/`Result`，
  构造期 `requireNonNull`）；`SessionEventFirer.userBash` 返回
  `Optional<UserBashEventResult>`；`SessionMessagingLifecycle.executeBash` 三路
  （`Result` 直接落盘返回／`Operations` 换执行器／无发言回落本地）。
- 测试落点与设计稿的差异：`UserBashOperationsTest` 未单开类，操作臂断言并入
  `SessionEventFirerTest`（首个非 null 胜出、`null` 跳过）与
  `SessionLifecycleEventsTest`（宿主落 `BashExecution` 条目）；`ExtensionBashOverrideTest`
  按设计落盘。
- 验证：`mvn clean test -pl pi-java-agent-core` 全绿；`mvn clean test -pl pi-java-coding-agent`
  全绿（新增 3 个测试类＋2 个既有测试类的新用例）；`mvn clean verify -pl pi-java-agent-core,pi-java-coding-agent`
  通过（Checkstyle／SpotBugs 零告警）；无 `pom.xml` 变更、无新依赖。
- 已知环境噪声：本机 `mvn -am` 增量编译偶发残留旧 class，需 `clean` 后重跑；
  与本包代码无关。
