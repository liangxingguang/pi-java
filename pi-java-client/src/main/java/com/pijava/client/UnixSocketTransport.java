package com.pijava.client;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

import com.pijava.protocol.ByteConnection;
import com.pijava.protocol.ByteConnections;

/**
 * Unix Domain Socket 传输（JDK AF_UNIX，Windows 亦支持）。
 */
public final class UnixSocketTransport implements ByteTransport {

    private final Path socketPath;

    /** @param socketPath Unix socket 路径 */
    public UnixSocketTransport(Path socketPath) {
        this.socketPath = socketPath;
    }

    @Override
    public ByteConnection connect() {
        try {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return ByteConnections.from(channel);
        } catch (IOException e) {
            throw new PiClientException("Cannot connect to " + socketPath, e);
        }
    }

    @Override
    public void close() {
        // 无共享资源
    }
}
