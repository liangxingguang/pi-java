# pi 锚点与漂移检测

> 【背景摘要】本文档只记录当前有效锚点、目标锚点和取证规则。历史换锚过程不在这里保存，历史在 git。

## 当前状态

```text
CURRENT_PI_PIN=ea448f454af474f3e2fe9733b331e17b0662f5d6
TARGET_PI_PIN=56cae782341c339d3bb820081f0f9425d8c5b1ec
TARGET_PI_DATE=2026-10-31
```

- 现有地图中的旧证据默认对应 `CURRENT_PI_PIN`。
- 新基线工作统一使用 `TARGET_PI_PIN`，完整规则见 `docs/08-pi-rebaseline-master-plan.md`。
- 每层地图完成重测后，必须在自己的头部写明 `PI_PIN=56cae7823` 和 pi-java 当层 SHA。
- 全部层完成前，不把全局当前锚点切换为目标锚点，也不发布新全仓完成度。

## 取证规则

pi 侧只读固定提交，不依赖可能移动的工作树：

```bash
git -C D:/workplaceForai/pi show 56cae7823:<path>
```

新旧行为比较：

```bash
git -C D:/workplaceForai/pi diff ea448f454..56cae7823 -- <path>
```

规则：

1. changelog 只用于圈定候选差异，不能替代源码证据。
2. 引用必须带足够路径上下文，避免 `types.ts` 等同名文件歧义。
3. pi 删除项必须给删除提交或目标锚点缺席证据，再按 R1 出分母。
4. pi-java 侧以当层盘点时的 HEAD 为准。
5. 本轮不追逐移动的 main；后续版本另开换锚周期。

## 漂移检测

```bash
bash docs/map/check-drift.sh
```

脚本只负责圈定可能变化的文件，不直接给出对齐结论：

- 【确定】表示 basename 唯一，变化需要复核。
- 【可能】表示存在同名文件，必须人工确认实际路径。
- 脚本比较本地 HEAD 与锚点；换锚期间仍以固定 SHA 的手工取证为准。

## 范围口径

- **R1**：pi 已删除的面，pi-java 也评估删除。
- **R2**：只覆盖已批准的主流 Provider。
- **R5**：codemode、durable、chord、pico3、micro 不对齐、不进分母。
- R5 按消费链判断，不排除 tool_search、MCP、OAuth 等共享主流行为。

## 完成条件

只有七份地图全部以目标锚点重测、docs/04 与 docs/06 同步、总分可由逐行权重复算后，才允许：

1. 将 `CURRENT_PI_PIN` 更新为 `56cae7823`；
2. 删除上方的目标锚点过渡状态；
3. 在 docs/05 发布新全仓读数。
