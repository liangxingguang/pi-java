package com.pijava.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-9a: FrameCodec + FrameDecoder — 编码与增量分帧边界。
 */
class FrameCodecTest {

    @Test
    void encodePrependsBigEndianLength() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] frame = FrameCodec.encode(payload);
        assertThat(frame).hasSize(4 + 5);
        // 4 字节大端长度
        assertThat(frame[0]).isZero();
        assertThat(frame[1]).isZero();
        assertThat(frame[2]).isZero();
        assertThat(frame[3]).isEqualTo((byte) 5);
        assertThat(new String(frame, 4, 5, StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void emptyPayloadFrame() {
        byte[] frame = FrameCodec.encode(new byte[0]);
        assertThat(frame).hasSize(4);
        var frames = new FrameDecoder(1024).push(frame);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0)).isEmpty();
    }

    @Test
    void roundTripThroughDecoder() {
        byte[] a = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] b = "beta".getBytes(StandardCharsets.UTF_8);
        byte[] wire = concat(FrameCodec.encode(a), FrameCodec.encode(b));

        var decoder = new FrameDecoder(1024);
        var frames = decoder.push(wire);
        assertThat(frames).hasSize(2);
        assertThat(new String(frames.get(0), StandardCharsets.UTF_8)).isEqualTo("alpha");
        assertThat(new String(frames.get(1), StandardCharsets.UTF_8)).isEqualTo("beta");
        decoder.end();
    }

    @Test
    void lengthBoundaryAtLimit() {
        byte[] payload = new byte[16 * 1024];
        byte[] frame = FrameCodec.encode(payload);
        var frames = new FrameDecoder(ProtocolVersion.DEFAULT_MAX_FRAME_LENGTH).push(frame);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0)).hasSize(16 * 1024);
    }

    @Test
    void frameAcrossMultiplePushes() {
        byte[] payload = "cross-frame".getBytes(StandardCharsets.UTF_8);
        byte[] frame = FrameCodec.encode(payload);
        var decoder = new FrameDecoder(1024);

        // 逐个字节 push
        var frames = new java.util.ArrayList<byte[]>();
        for (byte b : frame) {
            frames.addAll(decoder.push(new byte[] {b}));
        }
        assertThat(frames).hasSize(1);
        assertThat(new String(frames.get(0), StandardCharsets.UTF_8))
            .isEqualTo("cross-frame");
    }

    @Test
    void headerSplitAcrossPushes() {
        byte[] payload = "header-split".getBytes(StandardCharsets.UTF_8);
        byte[] frame = FrameCodec.encode(payload);
        var decoder = new FrameDecoder(1024);

        // header 1/2/3 字节分别到达
        var frames = new java.util.ArrayList<byte[]>();
        frames.addAll(decoder.push(new byte[] {frame[0]}));
        frames.addAll(decoder.push(new byte[] {frame[1], frame[2]}));
        frames.addAll(decoder.push(new byte[] {frame[3]}));
        frames.addAll(decoder.push(java.util.Arrays.copyOfRange(frame, 4, frame.length)));
        assertThat(frames).hasSize(1);
        assertThat(new String(frames.get(0), StandardCharsets.UTF_8))
            .isEqualTo("header-split");
    }

    @Test
    void oversizedFrameThrowsAndFails() {
        var decoder = new FrameDecoder(16);
        // 声明长度 100 > 16
        byte[] chunk = {(byte) 0, (byte) 0, (byte) 0, (byte) 100};
        assertThatThrownBy(() -> decoder.push(chunk))
            .isInstanceOf(FrameException.class)
            .hasMessageContaining("exceeds");
        assertThat(decoder.state()).isEqualTo(FrameDecoder.State.FAILED);
    }

    @Test
    void endWithResidualFrameThrows() {
        var decoder = new FrameDecoder(1024);
        decoder.push(new byte[] {0, 0, 0, 5, 'h', 'i'});
        assertThatThrownBy(decoder::end)
            .isInstanceOf(FrameException.class)
            .hasMessageContaining("Incomplete frame");
    }

    @Test
    void pushAfterEndThrows() {
        var decoder = new FrameDecoder(1024);
        decoder.end();
        assertThatThrownBy(() -> decoder.push(new byte[] {0}))
            .isInstanceOf(FrameException.class);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
