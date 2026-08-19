package com.pijava.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 增量分帧 —— 有状态，非线程安全，每连接一个实例（对齐 pi {@code FrameDecoder}）。
 *
 * <p>{@link #push(byte[])} 累积字节并返回本次凑齐的完整帧载荷（可能 0 个或多个）。
 * 不假设一次读到一个完整帧。状态机：{@link State#OPEN} → {@link State#ENDED}；
 * 长度超上限或非法操作 → {@link State#FAILED}。</p>
 */
public final class FrameDecoder {

    /** 解码器生命周期状态。 */
    public enum State {
        OPEN, ENDED, FAILED
    }

    private final int maxFrameLength;
    private State state = State.OPEN;
    private final byte[] header = new byte[4];
    private int headerRead;
    private byte[] payload;
    private int payloadRead;

    /** @param maxFrameLength 载荷长度上限 */
    public FrameDecoder(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    /** 累积字节，返回本次凑齐的完整载荷（可能 0 个或多个）。 */
    public List<byte[]> push(byte[] chunk) {
        if (state == State.FAILED) {
            throw new FrameException("FrameDecoder is in failed state");
        }
        if (state == State.ENDED) {
            throw new FrameException("FrameDecoder already ended");
        }
        var frames = new ArrayList<byte[]>();
        int offset = 0;
        while (offset < chunk.length) {
            if (headerRead < 4) {
                int n = Math.min(4 - headerRead, chunk.length - offset);
                System.arraycopy(chunk, offset, header, headerRead, n);
                headerRead += n;
                offset += n;
                if (headerRead == 4) {
                    int length = readLength(header);
                    if (length > maxFrameLength) {
                        state = State.FAILED;
                        throw new FrameException("Frame length " + length
                            + " exceeds maximum " + maxFrameLength);
                    }
                    if (length == 0) {
                        // 空载荷帧：立即发出，无需 payload 阶段
                        frames.add(new byte[0]);
                        headerRead = 0;
                        continue;
                    }
                    payload = new byte[length];
                    payloadRead = 0;
                }
            }
            if (headerRead == 4 && offset < chunk.length) {
                int n = Math.min(payload.length - payloadRead, chunk.length - offset);
                System.arraycopy(chunk, offset, payload, payloadRead, n);
                payloadRead += n;
                offset += n;
                if (payloadRead == payload.length) {
                    frames.add(payload);
                    payload = null;
                    payloadRead = 0;
                    headerRead = 0;
                }
            }
        }
        return frames;
    }

    /** 流结束；若有不完整帧残留则抛 {@link FrameException}。 */
    public void end() {
        if (headerRead != 0 || payload != null) {
            throw new FrameException("Incomplete frame at end of stream");
        }
        state = State.ENDED;
    }

    /** 当前解码器状态。 */
    public State state() {
        return state;
    }

    private static int readLength(byte[] header) {
        return ((header[0] & 0xff) << 24)
            | ((header[1] & 0xff) << 16)
            | ((header[2] & 0xff) << 8)
            | (header[3] & 0xff);
    }
}
