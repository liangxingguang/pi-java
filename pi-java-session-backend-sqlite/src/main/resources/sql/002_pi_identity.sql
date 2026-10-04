-- 包 12（docs/12 §6 D2）：本仓的三族合成行要落成 **pi 的条目**，而 pi 的每一行都是
-- SessionEntryBase（session-manager.ts:57-63）—— parentId / timestamp 必填。
--
-- 列可空：001 之前的库没有这些值，历史行的 parentId 为 NULL、timestamp 为 NULL，
-- 读侧按 NULL 处理（JSONL 导出时落成 parentId:null / epoch）。
ALTER TABLE records ADD COLUMN parent_id TEXT NULL;
ALTER TABLE lane_moves ADD COLUMN parent_id TEXT NULL;
ALTER TABLE lane_moves ADD COLUMN timestamp TEXT NULL;
ALTER TABLE facts ADD COLUMN parent_id TEXT NULL;
ALTER TABLE facts ADD COLUMN timestamp TEXT NULL;
