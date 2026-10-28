# pi-java Examples

pi `packages/coding-agent/examples/`（锚点 `ea448f454`）的 Java 移植件集合——
每件一个顶层 public `PiExtension` 类（`com.pijava.examples.*`），移植说明与
逐字规格见对应批次设计稿（docs/40 起）。

## 装载语义

示例库：**classpath 上有本模块即全部启用**（main 资源
`META-INF/services/com.pijava.coding.agent.extension.PiExtension` 全量登记，
ServiceLoader 自动发现）。逐件选用请用 `-e`/`--no-*` 机制自行裁剪。

本模块**不进** `pi-java-dist` 发行（docs/39 裁决③）。

## 分组（批 28–37 移植序）

- 组 1（批 28）：事件守卫与输入改写——hello / permission-gate / protected-paths /
  confirm-destructive / dirty-repo-guard / project-trust / input-transform /
  input-transform-streaming / inline-bash / pirate / claude-rules / prompt-customizer /
  event-bus / provider-payload / structured-output / dynamic-tools
- 组 2–10：随后续批次（docs/39 §7 分批表）
