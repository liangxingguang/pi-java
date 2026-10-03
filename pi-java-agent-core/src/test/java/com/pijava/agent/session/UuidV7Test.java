package com.pijava.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * {@link UuidV7} —— RFC 9562 §5.7 的版本/变体/时间戳三件套。
 *
 * <p>回归自 web UI 用户报障：「会话名为什么是 {@code fffffff}」。实测生成的一律长成
 * {@code ffffffff-ffff-f090-9878-…}：`(long) rand[n] &lt;&lt; k` 对 **byte 做了符号扩展**
 * （{@code 0xDD} → {@code 0xFFFF…FFDD}），把 48 位时间戳整段涂成 1、并把变体位涂成 3。</p>
 */
class UuidV7Test {

    @Test
    void versionAndVariantFollowRfc9562() {
        for (int i = 0; i < 50; i++) {
            var id = UuidV7.uuid();
            assertThat(id.version()).as("UUIDv7 的版本号必须是 7").isEqualTo(7);
            assertThat(id.variant()).as("变体必须是 2（bits 63-62 = 10）").isEqualTo(2);
        }
    }

    @Test
    void embeddedTimestampIsNow() {
        long before = System.currentTimeMillis();
        var id = UuidV7.uuid();
        long after = System.currentTimeMillis();

        long embedded = id.getMostSignificantBits() >>> 16;
        assertThat(embedded)
            .as("前 48 位是毫秒时间戳 —— 被符号扩展污染时会变成 0xffffffffffff")
            .isBetween(before, after);
    }

    /** v7 的存在意义就是**按时间可排序**（会话/记录按 id 排即按时间排）。 */
    @Test
    void idsAreTimeOrdered() throws Exception {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) {
            ids.add(UuidV7.uuid());
            Thread.sleep(2);
        }

        var sorted = new ArrayList<UUID>(ids);
        sorted.sort(Comparator.comparingLong(UUID::getMostSignificantBits)
            .thenComparingLong(UUID::getLeastSignificantBits));
        assertThat(sorted).as("后生成的必须排在后面").isEqualTo(ids);
    }

    @Test
    void nextReturnsUuidString() {
        assertThat(UuidV7.INSTANCE.next())
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    /** 同一毫秒内也不该撞（rand 部分要真的随机）。 */
    @Test
    void idsAreDistinctWithinTheSameMillisecond() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(UuidV7.INSTANCE.next());
        }
        assertThat(ids).doesNotHaveDuplicates();
    }
}
