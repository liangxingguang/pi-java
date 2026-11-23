package dev.tamboui.tui.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

/** docs/26 §4.4（K32）：modifyOtherKeys 与 CSI-u 备用键组解析。 */
class EventParserModifyOtherKeysTest {

    private static KeybindingsManager.KeyStroke parse(String sequence) throws IOException {
        var backend = new QueueBackend();
        backend.feed(sequence);
        Event event = EventParser.readEvent(backend, 20);
        assertThat(event).isInstanceOf(KeyEvent.class);
        return TamboUIAdapter.toStroke((KeyEvent) event);
    }

    @Test
    void modifyOtherKeysShiftLetterKeepsLowercaseIdentity() throws Exception {
        var stroke = parse("\u001b[27;2;97~");
        assertThat(stroke.key()).isEqualTo("a");
        assertThat(stroke.shift()).isTrue();
        assertThat(stroke.ctrl()).isFalse();
        assertThat(stroke.alt()).isFalse();
    }

    @Test
    void modifyOtherKeysCtrlPlus() throws Exception {
        var stroke = parse("\u001b[27;5;43~");
        assertThat(stroke.key()).isEqualTo("+");
        assertThat(stroke.ctrl()).isTrue();
        assertThat(stroke.shift()).isFalse();
    }

    @Test
    void modifyOtherKeysConsumesImplicitShiftForSymbols() throws Exception {
        // Ctrl+Shift+= 物理键上报 '+'（43）＋ shift：逻辑匹配按 Ctrl+'+'。
        var stroke = parse("\u001b[27;6;43~");
        assertThat(stroke.key()).isEqualTo("+");
        assertThat(stroke.ctrl()).isTrue();
        assertThat(stroke.shift()).isFalse();
    }

    @Test
    void modifyOtherKeysSpecialKeys() throws Exception {
        assertThat(parse("\u001b[27;5;13~").key()).isEqualTo("enter");
        assertThat(parse("\u001b[27;2;9~").key()).isEqualTo("tab");
        assertThat(parse("\u001b[27;2;27~").key()).isEqualTo("esc");
        assertThat(parse("\u001b[27;5;127~").key()).isEqualTo("backspace");
    }

    @Test
    void modifyOtherKeysMalformedIsUnknown() throws Exception {
        assertThat(parse("\u001b[27;x;97~").key()).isEqualTo("unknown");
        assertThat(parse("\u001b[27;2~").key()).isEqualTo("unknown");
    }

    @Test
    void csiUAlternateShiftedGroupKeepsCodepointIdentity() throws Exception {
        var stroke = parse("\u001b[97:65;2u");
        assertThat(stroke.key()).isEqualTo("a");
        assertThat(stroke.shift()).isTrue();
    }

    @Test
    void csiULogicalShiftedSymbolConsumesShift() throws Exception {
        var stroke = parse("\u001b[61:43;6u");
        assertThat(stroke.key()).isEqualTo("+");
        assertThat(stroke.ctrl()).isTrue();
        assertThat(stroke.shift()).isFalse();
    }

    @Test
    void csiUPlainAndShiftEnterStillWork() throws Exception {
        var enter = parse("\u001b[13;2u");
        assertThat(enter.key()).isEqualTo("enter");
        assertThat(enter.shift()).isTrue();

        var digit = parse("\u001b[49;5u");
        assertThat(digit.key()).isEqualTo("1");
        assertThat(digit.ctrl()).isTrue();
    }

    /** Minimal queue-backed terminal for driving the real parser headlessly. */
    private static final class QueueBackend implements Backend {

        private final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();

        void feed(String text) {
            for (char c : text.toCharArray()) {
                input.add((int) c);
            }
        }

        @Override
        public int read(int timeoutMillis) {
            try {
                var value = input.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                return value == null ? -1 : value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public int peek(int timeoutMillis) {
            long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
            while (true) {
                var value = input.peek();
                if (value != null) {
                    return value;
                }
                if (System.currentTimeMillis() >= deadline) {
                    return -1;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }

        @Override
        public void writeRaw(byte[] data) {
        }

        @Override
        public void draw(DiffResult diffResult) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void clear() {
        }

        @Override
        public Size size() {
            return new Size(80, 24);
        }

        @Override
        public void showCursor() {
        }

        @Override
        public void hideCursor() {
        }

        @Override
        public Position getCursorPosition() {
            return Position.ORIGIN;
        }

        @Override
        public void setCursorPosition(Position position) {
        }

        @Override
        public void enterAlternateScreen() {
        }

        @Override
        public void leaveAlternateScreen() {
        }

        @Override
        public void enableRawMode() {
        }

        @Override
        public void disableRawMode() {
        }

        @Override
        public void onResize(Runnable callback) {
        }

        @Override
        public void close() {
            input.clear();
        }
    }
}
