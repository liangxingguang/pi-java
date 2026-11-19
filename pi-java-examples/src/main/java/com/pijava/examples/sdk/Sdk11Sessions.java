package com.pijava.examples.sdk;

import java.util.ArrayList;
import java.util.List;

/**
 * pi {@code examples/sdk/11-sessions.ts} 等价改写（docs/56 §2.1）——
 * SessionManager 四入口（inMemory/create/continueRecent/open）+ list 投影（形状差 D247）。
 */
public final class Sdk11Sessions {

    public record SessionInfo(String id, String firstMessage) {}

    /** pi L33-35 list 输出逐字（slice 8/30）。 */
    public static List<String> describeSessions(List<SessionInfo> sessions) {
        var lines = new ArrayList<String>();
        lines.add("\nFound " + sessions.size() + " sessions:");
        for (var info : sessions) {
            var id = info.id().substring(0, Math.min(8, info.id().length()));
            var first = info.firstMessage().substring(0, Math.min(30, info.firstMessage().length()));
            lines.add("  " + id + "... - \"" + first + "...\"");
        }
        return lines;
    }

    /** pi L13/L20/L27/L28 四入口输出逐字。 */
    public static String inMemoryLine() {
        return "In-memory session: (none)";
    }

    public static String newSessionLine(String file) {
        return "New session file: " + file;
    }

    public static String continuedLine(String file) {
        return "Continued session: " + file;
    }

    public static String openedLine(String id) {
        return "\nOpened: " + id;
    }
}
