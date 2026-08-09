package com.example.platform.asyncredis.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A TCP gate in front of the real Redis, so a test can make Redis unreachable and reachable again on
 * a port the application was configured with before either was true.
 *
 * <p>Closed means "not listening", which is a refused connection — the same failure a worker sees
 * when Redis is down at startup. Opening binds the same port and forwards to Redis; closing again
 * drops the live sockets, which is the mid-loop outage.
 */
final class RedisGate implements AutoCloseable {

    private final int listenPort;
    private final int targetPort;
    private final List<Socket> live = Collections.synchronizedList(new ArrayList<>());

    private volatile ServerSocket server;
    private volatile boolean accepting;

    RedisGate(int listenPort, int targetPort) {
        this.listenPort = listenPort;
        this.targetPort = targetPort;
    }

    /** Reserves a port nothing is listening on, so the gate can bind it later. */
    static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    int port() {
        return listenPort;
    }

    /** Starts forwarding to Redis. */
    void open() throws IOException {
        if (accepting) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("localhost", listenPort));
        this.server = socket;
        this.accepting = true;
        Thread acceptor = new Thread(this::acceptLoop, "redis-gate-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Stops listening and drops every connection already through the gate. */
    void shut() {
        accepting = false;
        closeQuietly(server);
        server = null;
        synchronized (live) {
            live.forEach(RedisGate::closeQuietly);
            live.clear();
        }
    }

    private void acceptLoop() {
        while (accepting) {
            ServerSocket socket = server;
            if (socket == null) {
                return;
            }
            try {
                Socket downstream = socket.accept();
                Socket upstream = new Socket("localhost", targetPort);
                live.add(downstream);
                live.add(upstream);
                pump(downstream, upstream);
                pump(upstream, downstream);
            } catch (IOException e) {
                if (accepting) {
                    // A refused or dropped connection is the condition under test, not a test failure.
                    continue;
                }
                return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // Expected whenever the gate shuts.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "redis-gate-pump");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Nothing useful to do while tearing a socket down.
        }
    }

    @Override
    public void close() {
        shut();
    }
}
