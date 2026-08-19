package com.pijava.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

/**
 * {@link ByteConnection} 工厂 —— 从 {@link SocketChannel}（含 AF_UNIX）适配。
 * server 监听器与 client 传输共用。
 */
public final class ByteConnections {

    private ByteConnections() {
    }

    /** 适配 SocketChannel 为字节连接。 */
    public static ByteConnection from(SocketChannel channel) {
        return new SocketChannelConnection(channel);
    }

    private static final class SocketChannelConnection implements ByteConnection {

        private final SocketChannel channel;

        SocketChannelConnection(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public InputStream in() {
            // AF_UNIX 通道在 Windows 不支持 channel.socket()，用 Channels 适配
            return Channels.newInputStream(channel);
        }

        @Override
        public OutputStream out() {
            return Channels.newOutputStream(channel);
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭中
            }
        }
    }
}
