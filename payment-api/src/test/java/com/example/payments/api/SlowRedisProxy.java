package com.example.payments.api;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local TCP proxy that forwards to a real Redis instance but delays every response chunk
 * (target -&gt; caller direction) by a fixed amount, simulating a latent Redis without a new
 * Testcontainers dependency (BUDG-04: "IT com Redis latente ... latência injetada").
 *
 * <p>Requests (caller -&gt; target) are forwarded immediately; only responses are slowed, so every
 * command issued against the proxy - not just the first one on a connection - pays the injected
 * latency.
 */
final class SlowRedisProxy implements Closeable {

    private final ServerSocket serverSocket;
    private final String targetHost;
    private final int targetPort;
    private final Duration responseDelay;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = true;

    SlowRedisProxy(String targetHost, int targetPort, Duration responseDelay) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.responseDelay = responseDelay;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        executor.submit(this::acceptLoop);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket caller = serverSocket.accept();
                executor.submit(() -> handle(caller));
            } catch (IOException closed) {
                // expected once close() closes the server socket
            }
        }
    }

    private void handle(Socket caller) {
        try (caller; Socket target = new Socket(targetHost, targetPort)) {
            var requests = executor.submit(() -> pipe(caller, target, Duration.ZERO));
            var responses = executor.submit(() -> pipe(target, caller, responseDelay));
            requests.get();
            responses.get();
        } catch (Exception ignored) {
            // connection torn down (test teardown, client closed) - nothing to recover
        }
    }

    private void pipe(Socket from, Socket to, Duration delayPerChunk) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!delayPerChunk.isZero()) {
                    Thread.sleep(delayPerChunk.toMillis());
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception ignored) {
            // socket closed from the other side - normal end of this half of the pipe
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // already closing
        }
        executor.shutdownNow();
    }
}
