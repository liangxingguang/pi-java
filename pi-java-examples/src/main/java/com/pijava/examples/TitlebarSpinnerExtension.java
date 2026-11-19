package com.pijava.examples;

import java.nio.file.Path;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/titlebar-spinner.ts:1-59} 移植（docs/55 §2.2）——
 * agent 活跃时标题栏盲文旋转。真定时器不建（D246），用注入式 {@link TickDriver} 手动
 * 驱动纯逻辑；标题经 {@code ui.setTitle}（TUI 空操作，D245）。
 */
public final class TitlebarSpinnerExtension implements PiExtension {

    static final int FRAME_INTERVAL_MS = 80;
    static final String[] BRAILLE_FRAMES = {
        "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /** 注入式 tick 驱动；测试手动调 runnable。 */
    interface TickDriver {
        void start(Runnable tick);
        void stop();
    }

    private int frameIndex;
    private boolean running;
    private TickDriver driver = new TickDriver() {
        public void start(Runnable tick) { }
        public void stop() { }
    };

    @Override
    public String name() {
        return "titlebar-spinner";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.AGENT_START, (event, c) -> {
            startAnimation(ctx);
            return null;
        });
        ctx.on(ExtensionEventSpec.AGENT_SETTLED, (event, c) -> {
            stopAnimation(ctx);
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_SHUTDOWN, (event, c) -> {
            stopAnimation(ctx);
            return null;
        });
    }

    void driver(TickDriver driver) {
        this.driver = driver;
    }

    /** pi L16-20：基础标题。 */
    static String baseTitle(String cwdName, String session) {
        return session == null || session.isEmpty()
            ? "π - " + cwdName
            : "π - " + session + " - " + cwdName;
    }

    /** pi L41：动画标题。 */
    static String spinnerTitle(String frame, String cwdName, String session) {
        return session == null || session.isEmpty()
            ? frame + " π - " + cwdName
            : frame + " π - " + session + " - " + cwdName;
    }

    static String cwdName(String cwd) {
        var p = Path.of(cwd);
        var name = p.getFileName();
        return name == null ? cwd : name.toString();
    }

    void startAnimation(ExtensionContext ctx) {
        stopAnimation(ctx);
        running = true;
        driver.start(() -> {
            if (!running) {
                return;
            }
            var frame = BRAILLE_FRAMES[frameIndex % BRAILLE_FRAMES.length];
            var session = ctx.getSessionName();
            ctx.ui().setTitle(spinnerTitle(frame, cwdName(ctx.cwd()), session));
            frameIndex++;
        });
    }

    void stopAnimation(ExtensionContext ctx) {
        running = false;
        driver.stop();
        frameIndex = 0;
        ctx.ui().setTitle(baseTitle(cwdName(ctx.cwd()), ctx.getSessionName()));
    }

    int frameIndex() {
        return frameIndex;
    }
}
