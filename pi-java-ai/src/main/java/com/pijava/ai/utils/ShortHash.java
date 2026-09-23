package com.pijava.ai.utils;

/**
 * 快速确定性哈希，用于缩短长字符串 —— pi {@code packages/ai/src/utils/hash.ts:2-13}
 * 的逐字移植（包 B14 步 1，{@code docs/47}）。
 *
 * <p>出参必须与 pi <b>逐字节相同</b>：Completions（40 字符截断）与 Responses
 * （{@code fc_} 前缀）把它拼进线上 id，差一个字符就是另一个 id。等价性由
 * {@code ShortHashTest} 用 pi 实现经 node 生成的 12 条 oracle 值钉死。</p>
 *
 * <p><b>JS → Java 三处陷阱</b>（docs/47 §4.2）：</p>
 * <ul>
 * <li>{@code Math.imul(a, b)} ≡ Java {@code int} 乘法（两者都 32 位回绕）—— 直接
 * {@code *}。</li>
 * <li>{@code (x >>> 0).toString(36)} ≡ {@link Integer#toUnsignedString(int, int)}。</li>
 * <li>常量 {@code 2654435761} / {@code 2246822507} / {@code 3266489909} /
 * {@code 1597334677} 都 &gt; {@code Integer.MAX_VALUE}，十进制字面量编译不过，写
 * {@code (int) 2654435761L} 形式；{@code 0xdeadbeef} / {@code 0x41c6ce57} 是十六进制
 * 可直接用。</li>
 * <li>{@code str.charCodeAt(i)} ≡ {@code str.charAt(i)} —— Java {@code char} 自动拓宽
 * 为 {@code int}，与 JS 的 UTF-16 码元一致（不用 {@code codePointAt}）。</li>
 * </ul>
 */
public final class ShortHash {

    private ShortHash() {}

    /**
     * pi {@code shortHash(str)}（hash.ts:3）：两路乘法混合（h1/h2 交叉雪崩），
     * 出参 = 无符号 h2 的 Base36 ＋ 无符号 h1 的 Base36。
     */
    public static String of(String str) {
        int h1 = 0xdeadbeef;
        int h2 = 0x41c6ce57;
        for (int i = 0; i < str.length(); i++) {
            int ch = str.charAt(i);
            h1 = (h1 ^ ch) * (int) 2654435761L;
            h2 = (h2 ^ ch) * (int) 1597334677L;
        }
        h1 = ((h1 ^ (h1 >>> 16)) * (int) 2246822507L) ^ ((h2 ^ (h2 >>> 13)) * (int) 3266489909L);
        h2 = ((h2 ^ (h2 >>> 16)) * (int) 2246822507L) ^ ((h1 ^ (h1 >>> 13)) * (int) 3266489909L);
        return Integer.toUnsignedString(h2, 36) + Integer.toUnsignedString(h1, 36);
    }
}
