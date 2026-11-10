package com.pijava.coding.agent.extension;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.core.SessionTreeNode;

/**
 * 批 28（docs/40 T2，裁决③）：pi {@code ReadonlySessionManager}
 * （session-manager.ts:245-261，15 成员 Pick）的对位——扩展可见的会话<b>只读</b>面。
 *
 * <p>docs/50 B285：补齐本锚点 examples 真实消费的 9 成员。
 * {@code getSessionName} 归 {@code ExtensionContext}（既有）；{@code getSessionDir}/
 * {@code getHeader}/{@code buildContextEntries}/{@code buildSessionProjection}
 * 零消费且属内部 API，不做（docs/50 §3.1 裁决 A）。</p>
 */
public interface ExtensionSessionView {

    /**
     * pi {@code SessionManager.getEntries()}——全分支提交序条目快照。
     * 无持久面（{@code --no-session}）⇒ harness 转录兜底（pi-java 既有
     * {@code accumulatedEntries()} 语义，docs/40 F1 取证）。
     */
    List<Entry> getEntries();

    /** pi {@code getBranch()}——当前叶到根的路径，提交序（root → leaf）。 */
    List<Entry> getBranch();

    /** pi {@code getEntry(id)}——按 id 取条目；未命中 {@code null}。 */
    Entry getEntry(String id);

    /** pi {@code getLabel(id)}——条目标签；无标签 {@code null}。 */
    String getLabel(String targetId);

    /** pi {@code getLeafEntry()}——当前叶条目；叶缺失 {@code null}。 */
    Entry getLeafEntry();

    /** pi {@code getSessionFile()}——会话文件路径；无持久面 {@code null}。 */
    String getSessionFile();

    /** pi {@code getCwd()}——会话工作目录。 */
    String getCwd();

    /** pi {@code getSessionId()}——会话 id。 */
    String getSessionId();

    /** pi {@code getLeafId()}——当前叶 id；空会话 {@code null}。 */
    String getLeafId();

    /** pi {@code getTree()}——完整会话树（供树选择器等 UI）。 */
    List<SessionTreeNode> getTree();

    /** 无宿主时的占位视图（{@link ExtensionContext#sessionManager()} 默认臂）。 */
    static ExtensionSessionView unbound() {
        return new ExtensionSessionView() {
            @Override public List<Entry> getEntries() { throw unbound(); }
            @Override public List<Entry> getBranch() { throw unbound(); }
            @Override public Entry getEntry(String id) { throw unbound(); }
            @Override public String getLabel(String targetId) { throw unbound(); }
            @Override public Entry getLeafEntry() { throw unbound(); }
            @Override public String getSessionFile() { throw unbound(); }
            @Override public String getCwd() { throw unbound(); }
            @Override public String getSessionId() { throw unbound(); }
            @Override public String getLeafId() { throw unbound(); }
            @Override public List<SessionTreeNode> getTree() { throw unbound(); }

            private static IllegalStateException unbound() {
                return new IllegalStateException(
                    "tool control requires a bound session; none is available yet");
            }
        };
    }
}
