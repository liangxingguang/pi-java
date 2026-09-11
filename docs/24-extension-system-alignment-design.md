# 24 — 扩展系统对齐 pi 设计（含热加载可行性）

> 对齐基准：pi `packages/coding-agent/src/core/extensions/`（`types.ts` / `loader.ts` / `runner.ts`）。
> 关联：`docs/23`（run/turn/tool 生命周期事件，可复用为扩展事件源）。
> 编制日期：2026-09-12。
>
> **代码行号基准**：`docs/22` D7 拆分之后；工具定义过滤点已随 assistant 流执行移入
> `AssistantStreamExecutor.java:75-79`（原 `ActionExecutor:391-397`）。

## 0. 结论先行

1. **pi-java 有扩展系统**，但是「装配期注册型」：能加工具/命令/Provider/技能/模板/主题，**不能**订阅运行时事件、不能拦截工具、不能改提示词与上下文。pi 有 30 个事件。
2. **热加载可以做，当前实现不支持**。障碍有 4 个且都可解（§4.1），但 **GraalVM native 模式下基本不可行**（§4.2）。
3. **四个候选方案待评审**（§4.5 决策矩阵）：A 自研热加载 / B 引入 PF4J /
   C Registry 统一接口先行 / **D Tool Group 激活/停用（参考 AgentScope Java，与热加载正交）**。
   其中 **D 成本最低、零风险，且可能消解热加载的大部分诉求**（§4.9、§4.10）。

---

## 1. 现状：pi-java 扩展系统

`pi-java-coding-agent/src/main/java/com/pijava/coding/agent/extension/`

```java
// PiExtension.java:9-32 —— SPI 接口，四个方法
public interface PiExtension {
    String name();
    default String description() { return ""; }
    void register(ExtensionContext ctx);                     // 装配期注册
    default ResourcePaths sessionStartResources() { ... }    // 对齐 pi resources_discover
}
```

| 类 | 职责 | 关键行 |
|---|---|---|
| `ExtensionManager` | `discover()` 走 ServiceLoader；`loadJar(Path)` 用 `URLClassLoader` | `:33` / `:55-67` |
| `ExtensionContext` | `tools()` / `slashCommands()` / `providers()` / `skills()` / `settings()` / `ui()` / `sendMessage()` | `:17-65` |
| `ExtensionUI` | 单方法 `request(RpcExtensionUIRequest)`，默认 `noop()` | `:10-19` |
| `ExtensionPackageManager` | 安装/卸载（配合 `install/remove/list` 子命令） | — |
| `ResourcePaths` | skills / prompts / themes 三类路径 + `plus()` 保序去重 | `:18-67` |

**形态差异**：pi = TypeScript 工厂函数 + **jiti 主进程内 import**（`loader.ts:455-459`）；
pi-java = **Java SPI（`META-INF/services`）+ JAR**（`URLClassLoader`）。

---

## 2. 能力对照

### 已对齐 ✅

注册工具 / slash 命令 / Provider / 技能；`resources_discover` → `sessionStartResources` → `ResourcePaths`；
RPC `extension_ui_request/response`（`RpcExtensionUIRequest`：select/confirm/input/editor/notify/
setStatus/setWidget/setTitle/set_editor_text）；唯一命名 + 去重 + `list-extensions`。

### pi-java 独有 ✅

`ExtensionContext.settings()` —— pi 的扩展 API **没有**任何设置读写接口（`types.ts` 全文搜索
`settings` 仅注释性提及），扩展只能通过 `ctx.ui.setTheme` 间接影响。

### 缺失 ❌

| 能力 | pi | 现状 |
|---|---|---|
| **运行时事件订阅** | **30 个**（`types.ts:1203-1244`） | **0 个** |
| 工具拦截 | `tool_call` → `{block, reason, terminate}`（`agent-loop.ts:636-643`） | 无扩展 API |
| 工具结果覆盖 | `tool_result` | 无 |
| 改系统提示词 | `before_agent_start` → `systemPrompt` | 无 |
| 改上下文 | `context` 事件替换 messages | 无 |
| 快捷键 / CLI flag / 渲染器 | `registerShortcut`（19 保留键冲突检测）/ `registerFlag` / `registerMessageRenderer` / `registerEntryRenderer` | 无 |
| TUI 扩展 UI | `custom<T>()`、`setFooter`/`setHeader`、`setEditorComponent`、主题 | 仅 RPC 通道，TUI 回落 `noop()` |
| 热重载 | `/reload`（`agent-session.ts:2610-2635`） | 无 |

### 部分替代：HookSystem

`HookSystem` 的 `before_tool` ≈ `tool_call`、`after_tool` ≈ `tool_result`、`transform_context` ≈ `context`、
`should_stop_after_turn` ≈ 部分 `session_before_*`。

**但扩展拿不到它**——`ExtensionContext` 未暴露 `hookSystem()` / `harness`，钩子只能由宿主代码注册。

---

## 3. 差距分级

| 优先级 | 项 | 理由 |
|---|---|---|
| **P0** | `ExtensionContext` 暴露 `hookSystem()` | 一行改动即获得工具拦截/上下文改写，无需新建事件系统 |
| **P0** | 扩展订阅 `AgentSessionEvent` | 搭 `docs/23` 的车，不另造事件 |
| **P1** | `PiExtension.dispose()` 生命周期 | 热加载与干净关停的前提 |
| **P1** | 热加载（JVM 模式） | 见 §4 |
| **P2** | TUI 扩展 UI、registerShortcut/Flag/渲染器 | 需要 TUI 侧组件模型，工作量大 |

---

## 4. 热加载专章

### 4.1 现状为什么不能热加载（四个障碍）

**① ClassLoader 用完即关**

```java
// ExtensionManager.java:55-67
public Set<String> loadJar(Path jar) {
    var names = new LinkedHashSet<String>();
    try (var loader = new URLClassLoader(          // ← try-with-resources
            new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
        for (var ext : ServiceLoader.load(PiExtension.class, loader)) {
            load(ext);
```

`URLClassLoader` 在方法返回时即 `close()`。已注册的对象实例仍可用（类已加载），但：
- 该 loader 无法再加载任何新类
- 卸载/重载时无从下手——没有持有 loader 引用

**② `unload` 不撤销注册**

```java
// ExtensionManager.java:70（unload）
/** 卸载指定扩展（从已加载表移除；扩展无 close 生命周期）。 */
public void unload(String name) {
    loaded.remove(name);
}
```

只从 `loaded` Map 移除，**工具/命令/Provider/技能全部留在 registry 里继续生效**。注释已自陈「扩展无 close 生命周期」。

**③ Registry 缺按名单撤销**

| Registry | remove 能力 |
|---|---|
| `CommandRegistry` | ✅ `unregister(name)`（`:40`） |
| `ProviderRegistry` | ✅ `remove(name)`（`:90`）/ `clear()`（`:95`） |
| `ToolRegistry` | ❌ 只有 `clear()`（`:59`），无单个 remove |
| `SkillManager` | ❌ 无 remove（仅 `register` / `all` / `registerTemplate`） |

**④ 无 dispose 生命周期**

`PiExtension` 只有 `register`，没有 `dispose`。扩展持有的线程、文件句柄、静态状态、TUI 组件引用无法释放
→ 旧 ClassLoader 被强引用 → **类无法卸载，元空间泄漏**。这是 Java 插件热加载最经典的坑。

### 4.2 Java 热加载的原理与硬约束

热加载 = **用新的 ClassLoader 重新加载同一批类**。可回收的充要条件是旧 loader 满足：

1. loader 实例本身不可达（无强引用）
2. 该 loader 加载的所有 **Class 对象**不可达
3. 这些 Class 的**任何实例**不可达

常见泄漏源：线程未停止、`ThreadLocal`、静态字段缓存、JNI 全局引用、注册的监听器未注销、
TUI 组件树引用、正在执行的工具调用。

**GraalVM native 约束（重要）**：native image 基于**封闭世界假设**，运行时尚未存在的类无法加载。
`-Pnative` 产物下 `URLClassLoader` 动态加载外部 JAR **基本不可行**。
因此热加载**只对 JVM 模式（`-jar` / classpath 运行）生效**，native 构建时应降级为「编译期固定扩展集」。

### 4.3 现成框架参考

| 框架 | 语言/形态 | 热加载 | 体积/依赖 | 与本项目契合度 |
|---|---|---|---|---|
| **PF4J** | Java 插件框架，`Plugin` 基类 + `ExtensionPoint` 接口 + `@Extension` 注解 | ✅ `unloadPlugin` + `loadPlugin` + `startPlugin`；`DevelopmentPluginManager` 支持 dev 模式自动重载 | 核心 ~200KB，仅依赖 slf4j | **高**（生产级最主流） |
| **Apache Felix / Eclipse Equinox（OSGi）** | Bundle + 服务注册表 | ✅ `bundle.update()` 原生热更新 | 重（需容器）、学习曲线陡 | 低（与 classpath 模型冲突） |
| **Apache Karaf** | 基于 OSGi 的运行容器 | ✅ | 很重 | 低 |
| **JPF（Java Plugin Framework）** | 老牌插件框架 | ✅ | 轻 | ❌ 停更多年，不推荐 |
| **Layrry** | 基于 **JPMS** 的动态模块层 | ✅ | 中 | ❌ 要求模块化，pi-java 明确无 JPMS |
| **HotswapAgent / DCEVM / JRebel** | JVM 字节码热替换 | ✅（替换已有类定义） | — | ❌ 面向开发期调试，非插件分发机制 |
| **Spring DevTools / LaunchedURLClassLoader** | 重启式 | ⚠️ 实际是快速重启 | — | ❌ 非真热加载 |

> 若愿意接受依赖与 API 改造，**PF4J 是最省事的选择**：它已经把 §4.1 的四个障碍全部解决
> （插件包装器持有 loader、`stop()`/`delete()` 生命周期、扩展点自动注销）。

### 4.4 决策前提：先定目标档位

评审前必须先确定热加载的目标档位——它直接决定方案成本：

| 档位 | 语义 | 需回收 ClassLoader | 测试可靠性 |
|---|---|---|---|
| **A. 逻辑重载** | 撤销注册 + `dispose()` + 重新装配；旧类常驻 | 否 | **可靠**（断言 registry 状态） |
| **B. 真卸载** | A + 旧 loader 与所有实例不可达、元空间回收 | 是 | **不可靠**（`System.gc()` 无保证，CI flaky） |

**pi 自己也只做到 A**：jiti import 的模块常驻内存，`/reload` 是
`emitSessionShutdownEvent` → `oldRunner.invalidate()` → 重建 runtime
（`agent-session.ts:2610-2635`、`runner.ts:543-550`），靠**逻辑失效**而非 GC 回收。

> **建议本项目目标定为 A。** 档位 B 的收益（元空间回收）在低频 reload 下几乎无感，
> 却引入无法可靠测试的能力。下文方案 A / B 均按档位 A 评估。

### 4.5 决策矩阵

| 维度 | 权重 | 方案 A：自研（档位 A） | 方案 B：引入 PF4J | 方案 C：Registry 统一接口先行 | **方案 D：Tool Group 激活/停用** |
|---|---|---|---|---|---|
| 解决的问题 | — | 改代码后不重启 | 改代码后不重启 | 为 A/B 铺路 | **启用/禁用工具 + 上下文聚焦** |
| 一次性成本 | 高 | ~200 行 + 3 处 registry 补方法 | 依赖引入 + descriptor 约定 + `ExtensionManager` 改造 + 测试迁移 | ~80 行（纯重构） | **~120 行**（ToolGroup + 过滤 + 1 命令） |
| **持续维护成本** | **高** | **中**：贡献清单纪律（可缓解，§4.6.2） | **低**：框架承担扩展点注销 | 无 | **低**：无卸载、无泄漏面 |
| 依赖新增 | 中 | **零** | `org.pf4j:pf4j`（slf4j 已有） | 零 | **零** |
| GraalVM native | 中 | 需禁用热加载 | 需 reflect-config（可缓解） | 无影响 | **完全兼容**（无类加载） |
| 现有扩展改造 | 中 | **无需改动** | 加 `@Extension` + descriptor | 无需改动 | **无需改动** |
| 测试边界 | 中 | 可控（registry 断言） | 可控 + 框架自带 | 可控 | **最可控**（纯状态断言） |
| 生态与演进 | 低 | 自行演进 | 版本管理/dev 自动重载开箱 | 为 A 或 B 铺路 | 可与 MCP 等外部机制自然衔接 |
| 与 pi 对齐度 | 低 | 一致（同为 A 档） | 超过 pi | — | **超过 pi**（pi 无按需暴露机制） |

**关键修正（此前评估有误，需评审注意）**：PF4J 的迁移成本被高估了。
`PiExtension` 可以**保留为 PF4J 的 `ExtensionPoint` 接口**，实现类加 `@Extension` 注解即可，
**不必**重写为 `Plugin` 基类模型。真正的改造集中在 `ExtensionManager`（改为持有 `PluginManager`）。

**同时必须承认**：PF4J **不消除**泄漏纪律——插件作者仍要在 `Plugin.stop()` 里正确释放线程与句柄。
它的价值是把「状态机 + 扩展点发现/注销」从宿主移到框架，**不是**让泄漏消失。

### 4.6 方案 A：自研最小热加载（档位 A）

#### 4.6.1 设计

**① 一扩展一 ClassLoader，且不关闭**

```java
record LoadedExtension(
    PiExtension instance,
    URLClassLoader loader,             // 持有引用，卸载时 close()
    ExtensionContributions contributed // 贡献清单
) {}
```

`loadJar` 去掉 try-with-resources（`ExtensionManager.java:57-58`），改为把 loader 存进 `LoadedExtension`。

**② 贡献清单（关键）**

```java
record ExtensionContributions(
    Set<String> toolNames,
    Set<String> commandNames,
    Set<String> providerNames,
    Set<String> skillNames,
    Set<String> templateNames
) {}
```

`register(ctx)` 时用一个**记录型上下文**替换裸 `ExtensionContext`，自动收集贡献；卸载时按清单撤销。

**③ 补齐 Registry 的撤销能力**

- `ToolRegistry` 新增 `unregister(String name)`
- `SkillManager` 新增 `unregister(String name)` / `unregisterTemplate(String name)`
- `CommandRegistry.unregister`（`:40`）/ `ProviderRegistry.remove`（`:90`）已具备 ✅

**④ 生命周期回调**

```java
// PiExtension
default void dispose() { }   // 释放线程/句柄/监听器；抛异常被隔离
```

卸载顺序：`dispose()` → 按清单撤销注册 → `loader.close()` → 丢弃 `LoadedExtension` 引用。

**⑤ 触发方式**

| 触发 | 说明 |
|---|---|
| slash 命令 `/reload` | 对齐 pi `agent-session.ts:2610-2635`；需先等当前 run 结束或 abort |
| RPC `reload` 命令 | 供宿主驱动 |
| 文件监听（dev 模式） | 可选；对齐 PF4J `DevelopmentPluginManager` |

**⑥ 边界与风险**

| 风险 | 处置 |
|---|---|
| native 模式 | `ImageInfo.inImageCode()` 或构建期开关禁用热加载，返回明确错误而非崩溃 |
| 运行中的 run | 重载前 `abort()` 并等待 `AgentSettled`，否则旧扩展类的工具实例正在执行 |
| 同名重载 | 按 `name()` 定位，先卸载旧的再加载新的 |
| **旧类常驻（档位 A 的既定代价）** | **可接受**：与 pi 一致；reload 是低频操作，元空间增长有限。**不做** GC 可回收性断言 |

#### 4.6.2 持续维护成本与缓解（评审重点）

自研**无法消除**的持续成本是**贡献清单纪律**：

> 以后每新增一个「扩展可以注册的东西」（如未来的 `registerTheme` / `registerHook` / `registerRenderer`），
> 必须同步更新 `ExtensionContributions` 与撤销逻辑。**忘了就是静默泄漏**，
> 且要等到长时间运行后元空间 OOM 才暴露。

三条缓解，按性价比排序：

1. **统一 Registry 接口**（最有效）
   ```java
   interface Registry<T> {
       void register(T item);
       void unregister(String name);
       Set<String> names();
   }
   ```
   让 `ToolRegistry` / `CommandRegistry` / `ProviderRegistry` / `SkillManager` 全部实现。
   新增 registry 时**编译器强制**实现 `unregister`，把「记得更新清单」从约定变成类型约束。
2. **架构测试**：反射扫描 `extension` 与 registry 包，断言每个 `register*` 都有配对的 `unregister*`，缺失即红灯。
3. **记录型上下文自动收集**（② 已含）：不让扩展作者声明贡献，减少人为遗漏。

> 即便如此，成本仍是**非零**的：方案 A 的持续成本 ≈ 中，方案 B ≈ 低。这是决策的核心权衡点。

### 4.7 方案 B：引入 PF4J

#### 4.7.1 模型映射

```java
// PiExtension 保留，改为继承 PF4J 的 ExtensionPoint
public interface PiExtension extends ExtensionPoint {
    String name();
    default String description() { return ""; }
    void register(ExtensionContext ctx);
    default ResourcePaths sessionStartResources() { return ResourcePaths.none(); }
    default void dispose() { }
}

// 扩展实现类加注解
@Extension
public class MyTools implements PiExtension { ... }

// JAR 内补 descriptor：plugin.properties
//   plugin.id=my-tools
//   plugin.class=com.example.MyPlugin   （可选，无则用 BasePlugin）
//   plugin.version=1.0.0
```

#### 4.7.2 改造清单

| 项 | 内容 |
|---|---|
| 依赖 | `org.pf4j:pf4j`（slf4j 已有），BOM 需锁版本 |
| `PiExtension` | `extends ExtensionPoint` |
| 现有扩展 | 实现类加 `@Extension`；JAR 补 `plugin.properties` |
| `ExtensionManager` | 改为持有 `PluginManager`；`discover()` → `getExtensions(PiExtension.class)`；`loadJar` → `loadPlugin` + `startPlugin` |
| 卸载 | `stopPlugin` → `unloadPlugin`（框架释放 loader 与扩展点） |
| 测试 | `ExtensionManagerTest` 等 6 个测试类 + `SampleExtension` 迁移到 PF4J 模型 |
| native | `@Extension` 扫描需 reflect-config；native 下禁用热加载、静态注册，影响可控 |

#### 4.7.3 收益

- `PluginWrapper` 状态机（CREATED/RESOLVED/STARTED/STOPPED/DISABLED）与 `stop()`/`delete()` 生命周期开箱可用
- **扩展点发现与注销由框架承担** —— 这正是方案 A 的持续成本所在
- `DevelopmentPluginManager` + `-Dpf4j.mode=development` 提供 dev 自动重载（对齐 §4.6.1 ⑤）
- 版本管理、插件间依赖、`pf4j-update` 远程更新可用

#### 4.7.4 成本与风险

| 项 | 说明 |
|---|---|
| 一次性迁移 | 中（见 §4.7.2），非此前高估的「全部重写」 |
| 依赖与体积 | 新增 ~200KB；与项目「依赖克制」取向有张力 |
| 注解 + 反射 | 与 GraalVM native 目标存在张力（可缓解：native 下静态注册） |
| **不消除泄漏纪律** | 插件作者仍需在 `Plugin.stop()` 正确释放线程/句柄；框架不管这个 |
| 抽象泄漏 | `PluginManager` / `PluginWrapper` 概念会进入 `ExtensionManager` 的公开行为 |

### 4.8 方案 C：Registry 统一接口先行（推荐作为前置）

无论最终选 A 还是 B，建议**先做**这一步：

1. 定义 `Registry<T>` 接口（`register` / `unregister` / `names`）
2. `ToolRegistry` / `CommandRegistry` / `ProviderRegistry` / `SkillManager` 实现它
3. 补 `ToolRegistry.unregister` 与 `SkillManager.unregister` / `unregisterTemplate`

价值：
- 约 80 行纯重构，**零风险**
- 选 A：直接就是 §4.6.1 ③，且把持续成本从「中」压到「中偏低」
- 选 B：registry 的 `unregister` 仍是 `dispose` 逻辑需要的，不浪费
- 无论是否做热加载，干净关停（`session.close()`）都需要它

### 4.9 方案 D：Tool Group 激活/停用（参考 AgentScope Java）

#### 4.9.1 思路来源

AgentScope Java 面对同类扩展需求时，**选择绕开类加载问题而非解决它**：

- **进程外**：MCP server 作为独立进程（STDIO / SSE / Streamable HTTP），启停进程即装卸工具，
  天然无 ClassLoader 泄漏
- **进程内**：`Toolkit` 注册全部 tool / MCP / skill，按 **Tool Group** 分组；
  **未激活 group 中的 tool 不出现在模型的工具 schema 中**，由内置 `reset_tools` meta tool
  或 skill 加载来切换激活态

> 来源：`java.agentscope.io/v2/zh/docs/building-blocks/tool`（未读源码，以官方文档为准）

#### 4.9.2 映射到 pi-java

```java
// ToolRegistry 增加分组与激活态
record ToolGroup(String name, String description, ToolGroupScope scope, boolean active) {}

toolRegistry.registerGroup(new ToolGroup("database", "DB tools", ToolGroupScope.SESSION, false));
toolRegistry.registration().tool(new DatabaseTools()).group("database").apply();
toolRegistry.setGroupActive("database", true);
```

| 能力 | 说明 |
|---|---|
| `ToolGroup(name, scope, active)` | 命名分组；保留名 `"basic"` 始终激活（对应现有 `register` 默认行为） |
| 激活/停用 | **未激活 group 的工具不进 LLM 的 tool definitions** —— 复用现有过滤点 `AssistantStreamExecutor.java:75-79`（`activeTools` 过滤 `toolRegistry.toToolDefinitions()`） |
| meta tool（可选） | 注册 `reset_tools`，让模型自管激活态（对齐 AgentScope `enableMetaTool(true)`） |
| Skill 绑定（可选） | `SkillToolGroup`：skill 加载时自动激活绑定 group（pi 与 pi-java 目前都无此机制） |

#### 4.9.3 成本与边界

- **成本**：~120 行（ToolGroup record + registry 分组 + 激活态过滤 + 1 个 slash 命令），
  **零依赖、零泄漏风险、native 完全兼容**
- **解决了**：「让新工具生效 / 临时禁用某工具」——扩展开发最常见的诉求；
  同时获得上下文优化（未激活工具不进 schema，省 token）
- **不解决**：真正的代码更新（改了 Java 代码仍需重启或重装）；元空间回收
- **与 A/B 的关系**：**正交**。可先做 D，做完再评估是否仍需要热加载

### 4.10 决策建议（供评审选择）

| 若…… | 建议 |
|---|---|
| **诉求主要是「启用/禁用工具」与上下文聚焦** | **方案 D 优先**（配合 C） |
| 诉求是「改代码不重启」（扩展开发迭代） | 方案 A 或 B（先做 C） |
| 预期第三方扩展生态、长期演进 | 方案 B |
| 暂不确定 | **先做 C + D，热加载整体后置到 P2** |

**关键提示**：D 与 A/B 正交，且成本最低、风险为零。
若先落地 D，很可能发现「热加载」的诉求已被消解大半——
届时 §4.11 的决策可能从「选 A 还是 B」收敛为「不做热加载」。

**附加建议**：热加载的真实需求是**开发扩展时的迭代效率**；安装/卸载已有
`install` / `remove` / `list` 子命令。若只为开发便利，**可只做 dev 模式**
（文件监听 + 重建 session），成本远低于通用热加载。

### 4.11 决策记录（ADR，待评审填写）

| 项 | 内容 |
|---|---|
| 决策项 | 扩展能力演进路径：A（自研热加载） / B（引入 PF4J） / C 先行后置 / **D（Tool Group）** —— 可多选组合 |
| 目标档位 | A（逻辑重载）—— 待确认 |
| 结论 | **待填**（建议先答："热加载的真实诉求是改代码不重启，还是启用/禁用工具？"） |
| 参与人 | **待填** |
| 日期 | **待填** |
| 关键依据 | §4.5 决策矩阵、§4.6.2 A 的持续成本、§4.7.4 PF4J 风险、§4.9 方案 D（正交且零风险） |
| 后续动作 | 结论确定后回填本文档，并更新 §6 实施步骤与 §7 验收 |

---

## 5. 扩展能力补齐设计（P0）

### 5.1 暴露 HookSystem

```java
// ExtensionContext
/** Harness 钩子系统（扩展可注册 before_tool / transform_context 等）。 */
HookSystem hookSystem();
```

`DefaultExtensionContext` 持有 `AgentSession.harness().hookSystem()`。
一行接入即获得 pi `tool_call` / `tool_result` / `context` 的大部分能力。

### 5.2 事件订阅（搭 docs/23 的车）

`AgentSession.subscribe(Consumer<AgentSessionEvent>)` 已存在（`AgentSession.java:488`）。
扩展侧：

```java
// ExtensionContext
/** 订阅会话事件；返回句柄用于退订（扩展 dispose 时自动退订）。 */
AutoCloseable onEvent(Consumer<AgentSessionEvent> listener);
```

`docs/23` 正在为 Web/RPC 补齐 `TurnStart` / `TurnEnd` / `ToolExecution*` 等事件，
扩展订阅复用同一套，不另造。退订句柄由 `ExtensionManager` 在 `dispose` 后统一 close（防泄漏）。

### 5.3 不做的部分（明确记录）

- **TUI 扩展 UI 组件**（`custom<T>()` / `setFooter` 等）：需 TUI 组件模型与焦点管理，工作量大，列 P2
- **`registerShortcut` / `registerFlag` / 渲染器**：依赖上一条的 UI 模型，列 P2
- **`sendMessage` 的 `triggerTurn`**：当前抛 `UnsupportedOperationException`
  （`ExtensionContext.java:60-61`）；触发 LLM 回合涉及队列语义（steer/followUp/nextRun），单独立项

---

## 6. 实施步骤（按决策分叉）

### 阶段 0 —— 方案无关，可立即开始

1. 定义 `Registry<T>` 接口（`register` / `unregister` / `names`），四个 registry 实现之
2. 补 `ToolRegistry.unregister(name)`、`SkillManager.unregister(name)` / `unregisterTemplate(name)`
3. `PiExtension.dispose()` 默认方法
4. 架构测试：断言每个 `register*` 有配对 `unregister*`
5. `ExtensionContext.hookSystem()` + `onEvent(...)`（§5，P0 能力补齐）
6. **（方案 D，独立于热加载）** `ToolGroup` record + `ToolRegistry` 分组注册 +
   `AssistantStreamExecutor.java:75-79` 按激活态过滤 tool definitions + `/tools` slash 命令

> 阶段 0 的前 5 项是 §4.8 方案 C 的全部内容，且是干净关停的前提——**即使最终不做热加载也不浪费**。
> 第 6 项是 §4.9 方案 D，与热加载**正交**，可随时独立交付。

### 阶段 1 —— 按 §4.11 的决策结论选择路径

**路径 A（自研热加载）**

6A. `ExtensionManager`：`LoadedExtension`（loader + 贡献清单）、`loadJar` 不关 loader、`unload` 完整撤销
7A. 记录型 `ExtensionContext` 包装（自动收集贡献）
8A. `/reload` slash 命令 + RPC `reload`（abort 等待 + native 降级）

**路径 B（PF4J）**

6B. 引入 `org.pf4j:pf4j`，BOM 锁版本
7B. `PiExtension extends ExtensionPoint`；现有扩展加 `@Extension` + `plugin.properties`
8B. `ExtensionManager` 改为持有 `PluginManager`；`discover`/`loadJar`/`unload` 映射到插件生命周期
9B. 迁移 `ExtensionManagerTest` 等 6 个测试类与 `SampleExtension`

**路径 D（Tool Group，可与 A/B 并存）**

6D. 若阶段 0 已做第 6 项则跳过；否则见上文
7D.（可选）`reset_tools` meta tool —— 让模型自管 group 激活态
8D.（可选）`SkillToolGroup` —— skill 加载时自动激活绑定 group

### 阶段 2 —— 共同收尾

9. dev 模式文件监听（可选）
10. native 构建验证：热加载禁用路径不崩溃；方案 D 在 native 下正常工作

## 7. 验收

- `mvn clean verify` 零错误零警告
- 阶段 0：`Registry` 接口一致性测试；架构测试能捕获「新增 register 未配对 unregister」
- **方案 D**：
  - `inactiveGroupToolsAbsentFromSchema` —— 未激活 group 的工具不出现在 `toToolDefinitions()` 结果中
  - `activatingGroupExposesTools` —— 激活后立即可用（无需重载）
  - `basicGroupAlwaysActive` —— 保留组不受激活态影响
  - native 构建下功能正常（方案 D 不依赖类加载）
- 阶段 1（A/B 两条路径通用语义）：
  - `unloadRemovesToolsCommandsProvidersSkills` —— 卸载后四个 registry 中该扩展的贡献全部消失
  - `reloadPicksUpNewJarVersion` —— 重载后生效的是新版本类（用版本标记字段断言，而非 GC）
  - `disposeExceptionIsIsolated` —— `dispose()` 抛异常不阻断其余扩展卸载
  - `reloadWaitsForRunningRun` —— 有 run 在跑时重载会 abort 并等待 `AgentSettled`
- native 构建下 `/reload` 返回明确错误而非崩溃

> **明确不做**：ClassLoader 可回收性（`WeakReference` + `System.gc()`）断言。
> 理由见 §4.4 —— 该测试天然 flaky（`System.gc()` 无保证、CI 不稳定），
> 且档位 A 本就不追求回收；写进去只会得到一个时红时绿的假保障。
