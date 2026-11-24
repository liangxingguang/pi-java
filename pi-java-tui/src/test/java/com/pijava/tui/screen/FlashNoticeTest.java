package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L7 W6（S2c）：flash 生命周期。 */
class FlashNoticeTest {

    @Test
    void showsForDefaultSecond() {
        var notice = new FlashNotice();
        notice.show("Copied!");
        long now = System.nanoTime();
        assertThat(notice.messageAt(now)).isEqualTo("Copied!");
        assertThat(notice.messageAt(now + 1_000_000_000L)).isNull();
    }

    @Test
    void supportsCustomDurationAndOverwrite() {
        var notice = new FlashNotice();
        notice.show("Copy failed", 5000);
        long now = System.nanoTime();
        assertThat(notice.messageAt(now + 4_000_000_000L)).isEqualTo("Copy failed");
        notice.show("Copied!");
        assertThat(notice.messageAt(now)).isEqualTo("Copied!");
        notice.clear();
        assertThat(notice.messageAt(now)).isNull();
    }
}
