package com.pijava.agent.hook;

import java.util.Map;

import com.pijava.ai.Usage;

/**
 * pi {@code SessionBeforeCompactResult.compaction}（types.ts:1481-1484，载荷形状
 * {@code compaction.ts:105-113} 的 {@code CompactionResult}）—— 扩展供应的压缩产物：
 * 引擎跳过默认摘要生成，直接以此装配新转录（{@code Entry.Compaction.fromHook=true}）。
 *
 * @param summary          摘要文本
 * @param firstKeptEntryId 第一条保留条目的 id（找不到 ⇒ 自留压缩，仅留标记本身）
 * @param tokensBefore     压缩前 token 数（以扩展值为准，pi 同）
 * @param usage            生成用量，{@code null} ≙ pi undefined
 * @param details          扩展自带细节，{@code null} ≙ 无
 */
public record SuppliedCompaction(String summary, String firstKeptEntryId, long tokensBefore,
                                 Usage usage, Map<String, Object> details) {}
