package com.pijava.coding.agent.pkg;

import java.util.Locale;

/**
 * pi {@code ProgressEvent}（package-manager.ts:95-101）。L5 W8 起 {@code result}
 * 型落地：update 对每个版本发生变化的包发一条（CLI 四臂汇总的数据源）。
 */
public record ProgressEvent(Type type, Action action, String source, String message) {

    /** pi {@code type} 字面量闭集 ⇒ enum。 */
    public enum Type {
        START, PROGRESS, COMPLETE, ERROR, RESULT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** pi {@code action} 字面量闭集 ⇒ enum。 */
    public enum Action {
        INSTALL, REMOVE, UPDATE, CLONE, PULL;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static ProgressEvent start(Action action, String source, String message) {
        return new ProgressEvent(Type.START, action, source, message);
    }

    public static ProgressEvent complete(Action action, String source) {
        return new ProgressEvent(Type.COMPLETE, action, source, null);
    }

    /** 批 19 起首个 {@code progress} 型生产点（pi :1222-1228 的「版本没变」警告；
     *  L5 W5 §6.2 补）。 */
    public static ProgressEvent progress(Action action, String source, String message) {
        return new ProgressEvent(Type.PROGRESS, action, source, message);
    }

    public static ProgressEvent error(Action action, String source, String message) {
        return new ProgressEvent(Type.ERROR, action, source, message);
    }

    /** L5 W8（pi :1311-1317）：一个已变化的包（{@code source} 起、版本箭头文案）。 */
    public static ProgressEvent result(Action action, String source, String message) {
        return new ProgressEvent(Type.RESULT, action, source, message);
    }
}
