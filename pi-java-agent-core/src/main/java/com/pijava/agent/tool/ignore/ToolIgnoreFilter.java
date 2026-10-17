package com.pijava.agent.tool.ignore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.ignore.IgnoreNode;

/**
 * 工具面（grep/find/技能扫描）的忽略过滤 —— 基于 JGit {@code IgnoreNode}
 * （Git 官方完整 gitignore 语义，设计 §6.4.1），不自研子集。
 *
 * <p>{@code checkIgnored} 三态：{@code null}=本层无规则匹配需上溯父目录，
 * {@code TRUE}=忽略，{@code FALSE}=被 {@code !} 取反须停止上溯。深层规则覆盖浅层
 * （最后匹配者胜，同 Git）。忽略文件名集合 {@code .gitignore/.ignore/.fdignore}
 * 用同一解析器。</p>
 *
 * <p>配合 {@code Files.walkFileTree}：进入目录 {@link #push}、离开 {@link #pop}，
 * 链从根到深按序累积，检查时从最深一层开始上溯。<b>push/pop 逐层对称</b>：
 * push 无条件入链（无忽略文件的目录入空层，{@code checkIgnored} 返回 null 自然上溯），
 * pop 才能精确撤掉本层——条件入链会让「无忽略文件的子目录」pop 错删父层规则。</p>
 *
 * <p>批 3/docs/22 §2.4：自 coding-agent skill 包下沉（原 package-private
 * {@code IgnoreFilter}），供 agent-core 内置工具与 coding-agent 技能扫描共用。</p>
 */
public final class ToolIgnoreFilter {

    /** pi: IGNORE_FILE_NAMES。 */
    private static final String[] IGNORE_FILE_NAMES = {
        ".gitignore", ".ignore", ".fdignore"
    };

    private static final String PATH_SEPARATOR = "/";

    /** 每一层：IgnoreNode + 该层相对根的目录前缀（"" 表示根）。 */
    private record Level(IgnoreNode node, String prefix) {}

    private final Path root;
    private final List<Level> chain = new ArrayList<>();

    public ToolIgnoreFilter(Path root) {
        this.root = root;
    }

    /** 进入目录：加载其忽略文件，追加到链（无忽略文件也入空层，保持 push/pop 对称）。 */
    public void push(Path dir) {
        String prefix = toPosix(root.relativize(dir));
        var node = new IgnoreNode();
        for (var name : IGNORE_FILE_NAMES) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    node.parse(in);
                } catch (IOException e) {
                    // 忽略文件损坏则跳过该文件
                }
            }
        }
        chain.add(new Level(node, prefix));
    }

    /** 离开目录：移除本层。 */
    public void pop() {
        if (!chain.isEmpty()) {
            chain.remove(chain.size() - 1);
        }
    }

    /**
     * 判断根相对路径是否被忽略。
     *
     * @param posixRelPath 根相对路径，用 {@code /} 分隔（Windows 需 POSIX）
     * @param isDirectory  true=目录（build/ 类目录限定规则需要）
     */
    public boolean isIgnored(String posixRelPath, boolean isDirectory) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            var level = chain.get(i);
            String rel = relativeToLevel(posixRelPath, level.prefix());
            if (rel == null) {
                continue;
            }
            Boolean result = level.node().checkIgnored(rel, isDirectory);
            if (result != null) {
                return result;
            }
        }
        return false;
    }

    /** 本层规则相对路径：剥掉前缀；不在本层子树内返回 null。 */
    private static String relativeToLevel(String posixRelPath, String prefix) {
        if (prefix.isEmpty()) {
            return posixRelPath;
        }
        if (posixRelPath.equals(prefix)) {
            return "";
        }
        if (posixRelPath.startsWith(prefix + PATH_SEPARATOR)) {
            return posixRelPath.substring(prefix.length() + 1);
        }
        return null;
    }

    /** Windows Path → POSIX 相对路径（设计 §6.4.1 注意点 2）。 */
    public static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }
}
