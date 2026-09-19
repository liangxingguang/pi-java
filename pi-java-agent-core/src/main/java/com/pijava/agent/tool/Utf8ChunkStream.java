package com.pijava.agent.tool;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 按**字符边界**增量解码 UTF-8 字节流（包⑧，{@code docs/35}）。
 *
 * <p>为什么需要它：shell 的读循环按固定大小的缓冲切字节（{@code DefaultShellExecutor}
 * 是 8 KiB），切点会落在任意字节上 —— 一个汉字（3 字节）、一个 emoji（4 字节）随时
 * 会被劈成两半。直接 {@code new String(buf, 0, n, UTF_8)} 会把半个字符解成替换符
 * {@code U+FFFD}，而这些替换符会**逐块推给宿主**、再也拼不回来。</p>
 *
 * <p>故本类把「不完整的尾巴」留在内部，等下一块到来再接上；{@link #finish()} 收尾时
 * 把真正的残片（进程输出了半个字符就结束）按替换符解出。</p>
 *
 * <p><b>包内可见</b>：它是 {@code DefaultShellExecutor} 的实现细节，但被独立夹具
 * 用**逐字节**喂入做最狠的切分（这是最容易写错的一处，值得单独钉）。</p>
 */
final class Utf8ChunkStream {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /**
     * 吃进一块字节，返回其中**完整的**字符。
     *
     * @param buf 字节缓冲
     * @param off 起始偏移
     * @param len 长度
     * @return 本次可安全解码的前缀（不含替换符的伪影）；尾部不完整的字节留在流内
     */
    String accept(byte[] buf, int off, int len) {
        pending.write(buf, off, len);
        byte[] all = pending.toByteArray();
        int complete = completePrefixLength(all);
        pending.reset();
        if (complete < all.length) {
            pending.write(all, complete, all.length - complete);
        }
        return new String(all, 0, complete, StandardCharsets.UTF_8);
    }

    /** 收尾：把残留的不完整字节解出（真的是残片时会是替换符，这是对的）。 */
    String finish() {
        byte[] rest = pending.toByteArray();
        pending.reset();
        return rest.length == 0 ? "" : new String(rest, StandardCharsets.UTF_8);
    }

    /**
     * 返回 {@code b} 中可完整解码的前缀长度。
     *
     * <p>非法起始字节按「一个字节」吞掉而不是停滞 —— 否则一段垃圾输入会让流永远
     * 不前进。它的解码结果本来就是替换符，与 {@code String} 的宽容解码一致。</p>
     */
    private static int completePrefixLength(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            int need = c < 0x80 ? 1
                : (c & 0xE0) == 0xC0 ? 2
                : (c & 0xF0) == 0xE0 ? 3
                : (c & 0xF8) == 0xF0 ? 4
                : 1;
            if (i + need > b.length) {
                return i;
            }
            i += need;
        }
        return b.length;
    }
}
