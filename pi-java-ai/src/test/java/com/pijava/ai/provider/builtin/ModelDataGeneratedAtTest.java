package com.pijava.ai.provider.builtin;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原 docs/70 R5/§4.7：静态模型表的生成时间戳必须存在且**不在未来** ——
 * 远程目录守卫（{@code RemoteCatalogProvider.remoteModels}）会丢弃
 * Last-Modified 不晚于它的远端条目，一个未来的值会让远程覆盖永远不生效。
 */
class ModelDataGeneratedAtTest {

    @Test
    void generatedAtIsAPastInstant() {
        var generatedAt = ModelData.generatedAt();

        assertThat(generatedAt).isNotNull();
        assertThat(generatedAt).isBefore(Instant.now());
    }

    @Test
    void generatedAtIsAfterASanityFloor() {
        // 挡住手滑写成 Instant.EPOCH / 1970：那样守卫恒真，等于没有守卫。
        assertThat(ModelData.generatedAt())
            .isAfter(Instant.parse("2025-01-01T00:00:00Z"));
    }
}
