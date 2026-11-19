package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/55 §2.3：file-trigger 验收——非空触发消息+清空、空/异常/非 UI 三臂、Watching 通知。 */
class FileTriggerExtensionTest {

    private static final class FakeFs implements FileTriggerExtension.FileSource {
        String content = "";
        boolean throwOnRead;
        final List<String> writes = new ArrayList<>();
        Runnable callback;

        public String read(String path) throws Exception {
            if (throwOnRead) {
                throw new Exception("missing");
            }
            return content;
        }
        public void write(String path, String text) {
            writes.add(text);
            content = text;
        }
        public void watch(String path, Runnable cb) {
            callback = cb;
        }
    }

    @Test
    void triggerMessageText() {
        assertThat(FileTriggerExtension.triggerMessage("Run the tests"))
            .isEqualTo("External trigger: Run the tests");
        assertThat(FileTriggerExtension.defaultTriggerPath()).endsWith("agent-trigger.txt");
    }

    @Test
    void nonEmptyTriggersMessageAndClears() {
        var w = new W3Context();
        var fs = new FakeFs();
        fs.content = "  Run the tests  \n";
        var ext = new FileTriggerExtension();
        ext.register(w.ctx);
        ext.fileSource("/fake/trigger.txt", fs);

        ext.onTrigger(w.ctx);

        assertThat(fs.writes).containsExactly("");
        assertThat(w.appended).isEmpty();
        // 消息内容在纯函数断言；W3Context 只记 customType
        assertThat(w.sentMessages).containsExactly("file-trigger");
    }

    @Test
    void emptyAndExceptionNoop() {
        var w = new W3Context();
        var fs = new FakeFs();
        var ext = new FileTriggerExtension();
        ext.register(w.ctx);
        ext.fileSource("/fake/trigger.txt", fs);

        fs.content = "   ";
        ext.onTrigger(w.ctx);
        fs.throwOnRead = true;
        ext.onTrigger(w.ctx);

        assertThat(fs.writes).isEmpty();
        assertThat(w.sentMessages).isEmpty();
    }

    @Test
    void sessionStartWatchesAndNotifiesWhenUi() {
        var w = new W3Context();
        var fs = new FakeFs();
        var ext = new FileTriggerExtension();
        ext.register(w.ctx);
        ext.fileSource("/fake/trigger.txt", fs);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(fs.callback).isNotNull();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Watching /fake/trigger.txt");
    }

    @Test
    void nonUiNoNotify() {
        var w = new W3Context();
        w.mode = "print";
        var ext = new FileTriggerExtension();
        ext.register(w.ctx);
        ext.fileSource("/fake/trigger.txt", new FakeFs());

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.notices()).isEmpty();
    }
}
