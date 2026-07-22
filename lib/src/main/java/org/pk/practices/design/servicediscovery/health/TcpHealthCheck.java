package org.pk.practices.design.servicediscovery.health;

import org.pk.practices.design.servicediscovery.model.ServiceInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Health check that verifies a TCP connection can be established to the instance.
 *
 * <h2>When to use</h2>
 * Suitable for any protocol (HTTP, gRPC, Postgres, Redis, etc.) when you only need
 * to know the port is open and accepting connections — not that the app is ready to
 * serve requests. For deeper readiness checks, layer an HTTP /health check on top.
 *
 * <h2>Timeout</h2>
 * The {@code connectTimeoutMs} bounds how long we wait for the OS to complete the
 * TCP three-way handshake. A small value (1–2 s) keeps the scheduler cycle fast.
 * A value too small may produce false positives under transient network hiccups.
 */
public final class TcpHealthCheck implements HealthCheck {

    private final int connectTimeoutMs;

    public TcpHealthCheck(int connectTimeoutMs) {
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException("timeout must be > 0");
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** Default 2-second connect timeout. */
    public TcpHealthCheck() {
        this(2_000);
    }

    @Override
    public boolean check(ServiceInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(instance.host(), instance.port()),
                    connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
