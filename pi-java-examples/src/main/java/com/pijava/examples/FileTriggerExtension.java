package com.pijava.examples;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext.SendMessageOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/file-trigger.ts:1-42} 移植（docs/55 §2.3）——
 * 监听触发文件，外部写入内容时发 file-trigger 消息触发挥下一回合并清空文件。
 * fs.watch 无宿主对位（D246），用注入式 {@link FileSource} 手动触发回调；路径用
 * java.io.tmpdir 对位 /tmp（可注入覆盖）。
 */
public final class FileTriggerExtension implements PiExtension {

    interface FileSource {
        String read(String path) throws Exception;
        void write(String path, String text);
        void watch(String path, Runnable callback);
    }

    /** pi L16：默认 /tmp/agent-trigger.txt → Java tmpdir。 */
    static String defaultTriggerPath() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "agent-trigger.txt")
            .toString();
    }

    /** pi L24-26：消息文本。 */
    static String triggerMessage(String content) {
        return "External trigger: " + content;
    }

    private String triggerPath = defaultTriggerPath();
    private FileSource fs;

    @Override
    public String name() {
        return "file-trigger";
    }

    @Override
    public void register(ExtensionContext ctx) {
        fs = new FileSource() {
            public String read(String path) throws Exception {
                return java.nio.file.Files.readString(java.nio.file.Path.of(path));
            }
            public void write(String path, String text) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(path), text);
                } catch (java.io.IOException ignored) {
                }
            }
            public void watch(String path, Runnable callback) {
                // 无宿主 watch（D246）；生产无操作，测试注入 fake 触发回调
            }
        };
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            fs.watch(triggerPath, () -> onTrigger(c));
            if (c.hasUi()) {
                c.ui().notify("Watching " + triggerPath, NotifyType.INFO);
            }
            return null;
        });
    }

    void fileSource(String path, FileSource fs) {
        this.triggerPath = path;
        this.fs = fs;
    }

    /** pi L19-34：回调逻辑。 */
    void onTrigger(ExtensionContext ctx) {
        String content;
        try {
            content = fs.read(triggerPath);
        } catch (Exception ignored) {
            return; // File might not exist yet：静默吞
        }
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        var trimmed = content.trim();
        ctx.sendMessage("file-trigger", CustomMessageContent.of(triggerMessage(trimmed)),
            true, java.util.Map.of("content", trimmed), new SendMessageOptions(true));
        fs.write(triggerPath, ""); // Clear after reading
    }
}
