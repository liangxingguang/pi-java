package com.pijava.examples.sdk;

import java.util.ArrayList;
import java.util.List;

/** pi {@code examples/sdk/13-session-runtime.ts} 等价改写（docs/56 §2.2）——会话替换+重新绑定。 */
public final class Sdk13SessionRuntime {

    /** pi L46 队列事件文本逐字。 */
    public static String queuedLine(int steering, int followUp) {
        return "Queued: " + (steering + followUp);
    }

    /** pi L54/L58/L63 会话文件标识变化文本。 */
    public static String initialLine(String file) {
        return "Initial session: " + file;
    }

    public static String afterNewLine(String file) {
        return "After newSession(): " + file;
    }

    public static String afterSwitchLine(String file) {
        return "After switchSession(): " + file;
    }

    /** bindSession 重绑序列投影（unsubscribe→session→bindExtensions→subscribe）。 */
    public static List<String> bindSessionSteps() {
        return List.of("unsubscribe", "runtime.session", "bindExtensions", "subscribe");
    }

    /** 记录多次绑定的文件标识（模拟 newSession/switchSession 后变化）。 */
    public static List<String> sessionFiles(String initial, String afterNew, String afterSwitch) {
        var files = new ArrayList<String>();
        files.add(initial);
        files.add(afterNew);
        if (initial != null) {
            files.add(afterSwitch);
        }
        return files;
    }
}
