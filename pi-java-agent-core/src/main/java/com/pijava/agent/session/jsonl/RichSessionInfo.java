package com.pijava.agent.session.jsonl;

/**
 * pi {@code SessionInfo}（{@code buildSessionInfo} 产物，session-manager.ts:799-882）
 * 对位字段（#79/B55）—— 富列表的全文件流式读产物：
 * 名字（最新 {@code session_info}，空名 ⇒ {@code null} ≙ pi undefined）、消息计数、
 * 活动时刻（lastActivityTime，缺席回落 header 时刻）、首条 user 消息文本
 * （缺席 ⇒ {@code "(no messages)"}，pi :866）、{@code allMessagesText} 供选择器搜索。
 *
 * @param createdMs   header 时刻（epoch ms）
 * @param modifiedMs  活动时刻（epoch ms；pi 的 modified）
 * @param parentSessionPath header {@code parentSession}（pi session-manager.ts:857 同字段；
 *                          缺席 ⇒ {@code null}）——threaded 排序的树认父（批 14，docs/25 T5）
 */
public record RichSessionInfo(String id, String path, String name, String cwd,
                              long createdMs, long modifiedMs, int messageCount,
                              String firstMessage, String allMessagesText,
                              String parentSessionPath) {}
