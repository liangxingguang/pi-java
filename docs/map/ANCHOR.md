# pi 锚点与漂移检测

> 【背景摘要】本文档只记录当前有效锚点、参照锚点和取证规则。历史换锚过程不在这里保存，历史在 git。

## 当前状态

```text
CURRENT_PI_PIN=a00a5048dfae964fe89dce593215d0c3d275017c
CURRENT_PI_DATE=2026-11-20
REFERENCE_PI_PIN=56cae782341c339d3bb820081f0f9425d8c5b1ec
REFERENCE_PI_DATE=2026-10-31
```

- 当前锚点 `a00a5048d` 为 2026-11-20 main HEAD（v1.6.0 后 12 个提交），2026-11-24 P12 收口后切换；换锚过程见已归档的 `docs/09-pi-latest-reanchor-plan.md`。
- 七层（L0–L7）与 L8 已按该锚点复核：发布读数 **1,369.5/1,389＝98.6%**、L8 按件 **88/88**（`docs/05` §1；B293 落地后 L6 判 49/49）。
- 旧锚点 `56cae7823` 转为参照（`REFERENCE_PI_PIN`），其证据仍保留在个别行内作对照；凡冲突时以 `CURRENT_PI_PIN` 为准。
- 全仓 `mvn clean verify -Dfrontend.skip=true` 已于 2026-11-24 通过（零失败、零告警）。

## 其他文档里的 SHA 怎么读

- **`CURRENT_PI_PIN=a00a5048d`**：现行判定锚点。文档正文/判定表出现它 ⇒ 当前有效证据。
- **`REFERENCE_PI_PIN=56cae7823`**：上一锚点，只作换锚对照与历史证据；出现它的读数若未注明「旧锚点」，按历史值理解。
- **`ea448f454`、`edc8749d`**：更早基线与历史对照；凡与 `CURRENT_PI_PIN` 冲突，以当前锚点为准，不参与当前打分。
- **其他 8–10 位 SHA**（如 map/06 的 `9966a1f5`、map/07 的 `30abb76c`）：当层盘点时的 **pi-java HEAD**，不是 pi 锚点，只用于定位 java 侧证据。
- 代码注释/正文里的 `docs/NN` 是历史批次编号（设计稿大多已删），只是溯源标记，不是有效链接；现行结论以 `docs/04`＋`docs/05`＋`docs/map/` 为准。

## 取证规则

pi 侧只读固定提交，不依赖可能移动的工作树：

```bash
git -C D:/workplaceForai/pi show a00a5048d:<path>
```

新旧行为比较：

```bash
git -C D:/workplaceForai/pi diff 56cae7823..a00a5048d -- <path>
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
- **R5**：codemode、durable、chord、env、pico3、micro、apps/p2、apps/p2-mac、apps/p3 不对齐、不进分母。
- codemode 沙箱出分母，但 `tool_search` 消费的 `resolveSchemaRef`（本地 `$ref` 跟随）属于共享主流行为，在册。
- R5 按消费链判断，不排除 tool_search、MCP、OAuth 等共享主流行为。

## 完成条件（换锚维护规则）

切锚已于 2026-11-24 完成。后续任何换锚只有同时满足以下条件，才允许更新 `CURRENT_PI_PIN` 并发布新读数：

1. 七份地图全部以新目标锚点逐行重测、逐行有证据；
2. `docs/04` 与 `docs/05` 同步，总分可由逐行权重复算；
3. 全仓 `mvn clean verify -Dfrontend.skip=true` 零失败、零告警；
4. 旧锚点降为 `REFERENCE_PI_PIN`，过渡状态与临时记法清理完毕。
