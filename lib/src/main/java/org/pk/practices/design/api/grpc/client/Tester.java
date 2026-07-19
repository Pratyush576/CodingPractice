package org.pk.practices.design.api.grpc.client;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.pk.practices.design.api.grpc.server.GreetingServerImpl;

import java.io.IOException;

/**
 * End-to-end test harness that starts the gRPC server and immediately exercises it with
 * a client call, all within a single JVM process.
 *
 * <p>In a real deployment the server and client would be separate processes — potentially
 * on separate machines — communicating over the network. Running them together here lets
 * you observe the full request/response cycle without needing two terminal windows.
 *
 * <h2>Threading model</h2>
 * <pre>
 *  main thread                       client thread
 *  ──────────────────────────────    ─────────────────────────────────
 *  start server on port 8080    →    (spawned)
 *  block on awaitTermination()       greet("Pratyush Kumar")
 *                                      → SayHello RPC  → server handles → response logged
 *                                      → SayHelloAgain → server handles → response logged
 *                                    close client channel
 *                                    server.shutdown()
 *  awaitTermination() returns   ←
 *  process exits
 * </pre>
 *
 * <p>The client must run on a separate thread because {@code server.awaitTermination()}
 * on the main thread blocks until {@code server.shutdown()} is called. If the client ran
 * on the main thread the two calls would deadlock.
 */
public class Tester {

    public static void main(String[] args) throws IOException, InterruptedException {
        // ServerBuilder wires together the Netty network listener and the service
        // implementation. Multiple services can be registered on a single server.
        Server server = ServerBuilder.forPort(8080)
                .addService(new GreetingServerImpl())
                .build()
                .start();

        System.out.println("Server started on port 8080");

        // Spin up the client on a background thread so the main thread can safely
        // block on awaitTermination() below.
        Thread clientThread = new Thread(() -> {
            // try-with-resources guarantees GreetingClient.close() is called,
            // which shuts down the ManagedChannel even if an exception is thrown.
            try (GreetingClient client = new GreetingClient("localhost", 8080)) {
                client.greet("Pratyush Kumar");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // Initiate a graceful server shutdown once the client is done.
                // In-flight requests are allowed to complete before the server stops.
                server.shutdown();
            }
        });
        clientThread.start();

        // Block the main thread here. Returns only after server.shutdown() is called
        // by the client thread above.
        server.awaitTermination();
        System.out.println("Server shut down");
    }
}
