package com.pijava.ai.api;

/**
 * How a transport can express tool changes after the first request.
 *
 * <p>pi {@code utils/transcript.ts:221-230} 的字符串联合
 * {@code "none" | "additions" | "changes"}。</p>
 *
 * <ul>
 *   <li>{@link #NONE}：只有请求级工具表，表里永远是当前工具集。</li>
 *   <li>{@link #ADDITIONS}：只能在中途新增；删除或重定义把顶层表的边界移动一次。</li>
 *   <li>{@link #CHANGES}：每个工具都在系统消息里就地声明，同名后声明覆盖前声明。</li>
 * </ul>
 */
public enum TranscriptToolSupport {
    NONE,
    ADDITIONS,
    CHANGES
}
