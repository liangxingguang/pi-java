package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Anthropic message threads 的会话态（pi {@code anthropic-messages.ts:264-306}）。
 *
 * <p>每个会话记住「下一条请求可以从哪里继续」：上一次完整请求的消息条数、前缀哈希、
 * 以及可被 continue 的回复 id；会话一旦被判「该端点不支持 threads」就置 {@link #OFF}，
 * 之后整会话不再发 {@code thread}，避免每轮一次 400。</p>
 *
 * <p>容量语义与 pi 一致：最多 {@link #MAX_SESSIONS} 个会话，重插保持「最久未更新优先」，
 * 超限丢最旧；{@link #clear(String)} 供宿主在会话结束时释放，参数为空表示清空全部。</p>
 */
public final class AnthropicThreadState {

    /** pi {@code MAX_THREAD_STATE_ENTRIES}（{@code :275}）。 */
    public static final int MAX_SESSIONS = 10_000;

    /**
     * 一个可续会话的快照（pi {@code ThreadEntry}，{@code :264-272}）。
     *
     * @param prefixCount 上次完整请求的消息条数 ＋ 转换后的回复条数（**本仓行数口径**）
     * @param prefixHash  {@link AnthropicThreadHash} 对上一份前缀算出的短哈希
     * @param replyId     可继续的回复 id（pi {@code output.responseId}）
     */
    public record Entry(int prefixCount, String prefixHash, String replyId) {
    }

    /** 「该会话的端点不支持 threads」的终局标记（pi 的字符串 {@code "off"}）。 */
    private static final Object OFF = new Object();

    /** 最久未更新在前（重插即刷新位置），与 pi 的 Map 顺序语义一致。 */
    private static final Map<String, Object> STATE = new LinkedHashMap<>();

    private AnthropicThreadState() {
    }

    /** 读取会话态；不存在或为 {@link #OFF} 时为空。 */
    public static Optional<Entry> get(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        synchronized (STATE) {
            return STATE.get(sessionId) instanceof Entry entry
                ? Optional.of(entry) : Optional.empty();
        }
    }

    /**
     * 读并删（pi {@code :373-375}）：每条回复只能被继续一次，同时防止并发请求重复续。
     *
     * @param sessionId 会话号
     * @return 被消费的会话态；不存在或为 {@link #OFF} 时为空
     */
    public static Optional<Entry> consume(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        synchronized (STATE) {
            return STATE.remove(sessionId) instanceof Entry entry
                ? Optional.of(entry) : Optional.empty();
        }
    }

    /** 记录可续点（pi {@code setThreadState}，{@code :294-302}）。 */
    public static void put(String sessionId, Entry entry) {
        if (sessionId == null || sessionId.isEmpty() || entry == null) {
            return;
        }
        synchronized (STATE) {
            STATE.remove(sessionId);
            STATE.put(sessionId, entry);
            while (STATE.size() > MAX_SESSIONS) {
                var oldest = STATE.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** 置「该端点不支持 threads」（pi 的 {@code "off"}）：之后的请求整会话不再发 {@code thread}。 */
    public static void off(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        synchronized (STATE) {
            STATE.remove(sessionId);
            STATE.put(sessionId, OFF);
            while (STATE.size() > MAX_SESSIONS) {
                var oldest = STATE.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** 该会话是否已被判「不支持 threads」。 */
    public static boolean isOff(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        synchronized (STATE) {
            return STATE.get(sessionId) == OFF;
        }
    }

    /**
     * 释放（pi {@code cleanupSessionResources} 的注册体，{@code :304-306}）。
     *
     * @param sessionId 会话号；null 或空串表示清空全部
     */
    public static void clear(String sessionId) {
        synchronized (STATE) {
            if (sessionId == null || sessionId.isEmpty()) {
                STATE.clear();
            } else {
                STATE.remove(sessionId);
            }
        }
    }

    /** 当前条目数（测试用）。 */
    static int size() {
        synchronized (STATE) {
            return STATE.size();
        }
    }
}
