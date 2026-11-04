package com.pijava.ai.provider.faux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * docs/20 §5.2：pi {@code splitStringByTokenSize}（{@code providers/faux.ts:289-299}）
 * 与随机 token 边界的 Java 载体。
 *
 * @param minTokenSize   随机下界（归一后 ≥ 1）
 * @param maxTokenSize   随机上界（归一后 ≥ min）
 * @param tokensPerSecond 节奏；null／≤ 0 表示不睡（pi {@code scheduleChunk}）
 */
record FauxChunking(int minTokenSize, int maxTokenSize, Double tokensPerSecond) {

    static final int DEFAULT_MIN_TOKEN_SIZE = 3;
    static final int DEFAULT_MAX_TOKEN_SIZE = 5;

    /** pi {@code :462-466}：把用户给的两端归一成 {@code min ≥ 1}、{@code max ≥ min}。 */
    static FauxChunking of(FauxTokenSize size, Double tokensPerSecond) {
        int requestedMin = size == null || size.min() == null
            ? DEFAULT_MIN_TOKEN_SIZE : size.min();
        int requestedMax = size == null || size.max() == null
            ? DEFAULT_MAX_TOKEN_SIZE : size.max();
        int min = Math.max(1, Math.min(requestedMin, requestedMax));
        int max = Math.max(min, requestedMax);
        return new FauxChunking(min, max, tokensPerSecond);
    }

    /**
     * 切块：每块 {@code max(1, tokenSize * 4)} 个 UTF-16 码元，{@code tokenSize}
     * 在 {@code [min, max]} 随机；空文本仍产生一个空串块（pi {@code :298}）。
     */
    List<String> split(String text) {
        var chunks = new ArrayList<String>();
        int index = 0;
        while (index < text.length()) {
            int tokenSize = minTokenSize
                + ThreadLocalRandom.current().nextInt(maxTokenSize - minTokenSize + 1);
            int charSize = Math.max(1, tokenSize * 4);
            int end = Math.min(text.length(), index + charSize);
            chunks.add(text.substring(index, end));
            index = end;
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    /** pi {@code scheduleChunk}：有节奏时按「块 token 数 ÷ 每秒 token 数」等待。 */
    void schedule(String chunk) throws InterruptedException {
        if (tokensPerSecond == null || tokensPerSecond <= 0) {
            return;
        }
        long estimatedTokens = (chunk.length() + 3L) / 4L;
        long delayMs = Math.max(0L, Math.round((estimatedTokens / tokensPerSecond) * 1000.0));
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }
}
