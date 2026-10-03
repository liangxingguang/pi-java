package com.pijava.ai.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * docs/69：pi {@code GrammarToolInputJsonBuffer} ＋
 * {@code appendGrammarToolInputJsonDelta}（{@code constrained-sampling.ts:139-187}）
 * 的状态机：把逐帧到达的**原始输入串**增量翻译成重组 JSON 片段
 * （{@code {"property":"..."} 前缀流）。
 *
 * <p>三条不变量：append-only（下一帧须以前一帧为前缀，否则「changed non-monotonically」）、
 * 关闭幂等（关闭后同值再关 ⇒ 无片段；变值 ⇒ 「changed after it was closed」）、
 * 转义逐字符（pi 用 {@code JSON.stringify}，Jackson 同口径）。</p>
 */
final class GrammarInputBuffer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String property;
    private String input = "";
    private boolean started;
    private boolean closed;

    GrammarInputBuffer(String property) {
        this.property = property;
    }

    /**
     * pi {@code appendGrammarToolInputJsonDelta:157-187}。
     *
     * @param nextInput 本帧为止的完整输入串
     * @param close     本帧是否关闭
     * @return 追加到重组 JSON 的片段；无内容追加时 {@code null}
     */
    String append(String nextInput, boolean close) {
        if (closed) {
            if (close && nextInput.equals(input)) {
                return null;
            }
            throw new IllegalStateException("grammar tool input for property \"" + property
                + "\" changed after it was closed");
        }
        if (!nextInput.startsWith(input)) {
            throw new IllegalStateException("grammar tool input for property \"" + property
                + "\" changed non-monotonically");
        }
        String inputDelta = nextInput.substring(input.length());
        if (!close && inputDelta.isEmpty()) {
            return null;
        }
        var delta = new StringBuilder();
        if (!started) {
            delta.append('{').append(quote(property)).append(":\"");
            started = true;
        }
        delta.append(escape(inputDelta));
        input = nextInput;
        if (close) {
            delta.append("\"}");
            closed = true;
        }
        return delta.toString();
    }

    /** Current accumulated raw input. */
    String input() {
        return input;
    }

    /** Whether the buffer is closed. */
    boolean closed() {
        return closed;
    }

    /** JSON-escape a string (pi JSON.stringify). */
    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot escape a string", e);
        }
    }

    /** JSON-escape a string and strip the surrounding quotes (pi :179 slice(1,-1)). */
    private static String escape(String value) {
        var quoted = quote(value);
        return quoted.substring(1, quoted.length() - 1);
    }
}
