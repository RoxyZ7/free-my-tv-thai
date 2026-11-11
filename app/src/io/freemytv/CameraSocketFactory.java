package io.freemytv;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import javax.net.SocketFactory;

/** Keep Media3 connections on the selected LAN camera, including after RTSP redirects. */
final class CameraSocketFactory extends SocketFactory {
    private final CameraLan lan;
    private final InetAddress peer;
    private final int port;

    CameraSocketFactory(CameraLan lan, URI stream) {
        this.lan = lan;
        peer = lan.address(stream);
        port = stream.getPort() == -1 ? 554 : stream.getPort();
    }

    @Override public Socket createSocket(String host, int requestedPort) throws IOException {
        if (peer == null || !peer.getHostAddress().equals(host) || port != requestedPort)
            throw new IOException("RTSP destination differs from the selected camera");
        Socket socket = lan.network.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(peer, port), 2500);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    @Override public Socket createSocket(InetAddress address, int requestedPort) throws IOException {
        return createSocket(address.getHostAddress(), requestedPort);
    }

    @Override public Socket createSocket(String host, int port, InetAddress bind, int bindPort) throws IOException {
        throw new IOException("Unsupported RTSP socket binding");
    }

    @Override public Socket createSocket(InetAddress address, int port, InetAddress bind, int bindPort) throws IOException {
        throw new IOException("Unsupported RTSP socket binding");
    }
}
