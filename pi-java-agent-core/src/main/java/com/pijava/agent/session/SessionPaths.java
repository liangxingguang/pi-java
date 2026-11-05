package com.pijava.agent.session;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * B278（docs/24 W4c）：pi {@code utils/paths.ts:46-70} 的路径比较键。
 *
 * <p>{@code pathsEqual} 的语义是「两路径指向同一位置」：先 resolve 成绝对路径再
 * 比较；Windows 大小写不敏感（L3 docs/23 W2 已实证 Java {@code WindowsPath} 的
 * {@code equals} 与之等价），POSIX 大小写敏感。本类只用于比较，不用于展示或文件
 * 访问。</p>
 */
public final class SessionPaths {

    private SessionPaths() {}

    /**
     * @return two paths resolve to the same location; {@code null}/空路径按 pi 的
     *         {@code resolvePath(undefined)} 语义回落（null ⇒ false，空串 ⇒ 当前目录）
     */
    public static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return Path.of(left).toAbsolutePath().normalize()
                .equals(Path.of(right).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return left.equals(right);
        }
    }
}
