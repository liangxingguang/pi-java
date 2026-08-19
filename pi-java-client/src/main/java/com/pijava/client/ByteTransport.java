package com.pijava.client;

import java.io.Closeable;

import com.pijava.protocol.ByteConnection;

/**
 * 传输抽象 SPI —— 建立到服务器的字节连接（对齐 pi {@code ByteTransport}）。
 */
public interface ByteTransport extends Closeable {

    /** 建立连接；失败抛 {@link PiClientException}。 */
    ByteConnection connect();

    @Override
    void close();
}
