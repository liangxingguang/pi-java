package com.pijava.ai.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 原 docs/69：pi {@code GrammarToolInputJsonBuffer} ＋
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
        if (close) {
            return close(nextInput);
        }
        if (closed) {
            throw changedAfterClose();
        }
        if (!nextInput.startsWith(input)) {
            throw nonMonotonic();
        }
        return appendDelta(nextInput.substring(input.length()));
    }

    /** Append only the new raw input fragment, without closing the reconstructed object. */
    String appendDelta(String delta) {
        if (closed) {
            throw changedAfterClose();
        }
        if (delta.isEmpty()) {
            return null;
        }
        var json = new StringBuilder();
        if (!started) {
            json.append('{').append(quote(property)).append(":\"");
            started = true;
        }
        json.append(escape(delta));
        input += delta;
        return json.toString();
    }

    /** Close against the provider's authoritative complete input, or the current input when absent. */
    String close(String finalInput) {
        if (closed) {
            if (finalInput == null || finalInput.equals(input)) {
                return null;
            }
            throw changedAfterClose();
        }
        String complete = finalInput == null ? input : finalInput;
        if (!complete.startsWith(input)) {
            throw nonMonotonic();
        }
        var json = new StringBuilder();
        if (!started) {
            json.append('{').append(quote(property)).append(":\"");
            started = true;
        }
        json.append(escape(complete.substring(input.length()))).append("\"}");
        input = complete;
        closed = true;
        return json.toString();
    }

    private IllegalStateException changedAfterClose() {
        return new IllegalStateException("grammar tool input for property \"" + property
            + "\" changed after it was closed");
    }

    private IllegalStateException nonMonotonic() {
        return new IllegalStateException("grammar tool input for property \"" + property
            + "\" changed non-monotonically");
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
