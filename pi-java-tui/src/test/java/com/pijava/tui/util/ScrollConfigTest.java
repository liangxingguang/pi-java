package com.pijava.tui.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12 T12（docs/23 §3/§7，裁决⑥）：{@code fullscreenWheelScrollLines}
 * 数字臂 ⇒ 固定行数（{@code withWheelLines}）；{@code "auto"}/缺席 ⇒
 * 既有 Codex 有界加速模型不动（窄差 D60＝K38）。
 */
class ScrollConfigTest {

    @Test
    void integerWheelLinesOverridesToFixedRows() {
        var config = ScrollConfig.from(null, 3, Integer.valueOf(7));

        assertThat(config.wheelLines()).isEqualTo(7);
        // 其余字段维持默认/设置解析，不受第三参影响。
        assertThat(config.eventsPerTick()).isEqualTo(3);
        assertThat(config.trackpadLines()).isEqualTo(ScrollConfig.defaults().trackpadLines());
    }

    @Test
    void autoKeepsTheExistingAccelerationModel() {
        assertThat(ScrollConfig.from(null, 3, "auto").wheelLines())
            .isEqualTo(ScrollConfig.defaults().wheelLines());
        assertThat(ScrollConfig.from(null, 3, null).wheelLines())
            .isEqualTo(ScrollConfig.defaults().wheelLines());
    }

    @Test
    void withWheelLinesCopiesTheRecord() {
        var base = ScrollConfig.defaults();
        var fixed = base.withWheelLines(12);

        assertThat(fixed.wheelLines()).isEqualTo(12);
        assertThat(base.wheelLines()).isEqualTo(ScrollConfig.defaults().wheelLines());
        assertThat(fixed.mode()).isEqualTo(base.mode());
        assertThat(fixed.streamGapMs()).isEqualTo(base.streamGapMs());
    }
}
