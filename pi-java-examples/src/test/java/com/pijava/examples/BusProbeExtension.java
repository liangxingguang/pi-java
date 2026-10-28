package com.pijava.examples;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * P13 验收的第二扩展（跨件互发断言用）——订阅 {@code my:notification} 录表；
 * 提供 {@code emitProbe} 静态口反向发 {@code probe:call}（EventBusExtension 不订阅
 * 该频道，证明频道隔离；互发对位是「probe 收到 event-bus 的 emit」）。
 *
 * <p>顶层 public（ServiceLoader 硬要求，批 27 钉）。</p>
 */
public final class BusProbeExtension implements PiExtension {

    /** 收到的 {@code my:notification} 载荷（测试断言面）。 */
    public static final List<Map<?, ?>> RECEIVED = new CopyOnWriteArrayList<>();

    /** 反向 emit 的载体（session_start 捕获的上下文）。 */
    private static volatile ExtensionContext lastCtx;

    /** 清空录表（逐测试隔离）。 */
    public static void reset() {
        RECEIVED.clear();
        lastCtx = null;
    }

    /** 经最近会话的扩展总线发一条 {@code probe:call}。 */
    public static void emitProbeCall(String message) {
        var ctx = lastCtx;
        if (ctx != null) {
            ctx.events().emit("probe:call", Map.of("message", message));
        }
    }

    @Override
    public String name() {
        return "bus-probe";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.events().on("my:notification", data -> {
            if (data instanceof Map<?, ?> map) {
                RECEIVED.add(map);
            }
        });
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            lastCtx = c;
            return null;
        });
    }
}
