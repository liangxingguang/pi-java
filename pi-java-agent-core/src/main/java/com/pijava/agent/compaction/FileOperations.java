package com.pijava.agent.compaction;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * File paths touched by a compacted history range（pi
 * {@code harness/compaction/utils.ts:5-21} 的 {@code FileOperations}）。
 *
 * <p>三个累加器与 pi 的 {@code read}/{@code written}/{@code edited} 一一对应：同一个
 * 路径可以同时落在多个集合里，「只读 / 已修改」的拆分在
 * {@link CompactionFiles#compute} 里做。**可变是刻意的** —— pi 也是边走消息边往同一个
 * 对象里 {@code add}。</p>
 */
final class FileOperations {

    /** 读过但不一定改过的文件。 */
    final Set<String> read = new LinkedHashSet<>();

    /** 被 {@code write} 整文件写过的文件。 */
    final Set<String> written = new LinkedHashSet<>();

    /** 被 {@code edit} 改过的文件。 */
    final Set<String> edited = new LinkedHashSet<>();
}
