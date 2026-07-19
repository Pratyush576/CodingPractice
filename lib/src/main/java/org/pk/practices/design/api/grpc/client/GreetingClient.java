package org.pk.practices.design.api.grpc.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.pk.practices.design.api.grpc.proto.GreeterGrpc;
import org.pk.practices.design.api.grpc.proto.GreetingService;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * gRPC client for the {@code Greeter} service defined in {@code GreetingService.proto}.
 *
 * <h2>How gRPC client communication works</h2>
 * <ol>
 *   <li><b>{@link ManagedChannel}</b> — represents the virtual connection to the server.
 *       It manages the underlying HTTP/2 connection pool, reconnection, and load balancing.
 *       One channel can be shared safely across threads and should be reused for the
 *       lifetime of the client.</li>
 *   <li><b>Stub</b> — a generated proxy object that translates method calls into gRPC frames
 *       and sends them over the channel. The protobuf compiler generates three stub flavours
 *       from the proto service definition:
 *       <ul>
 *         <li>{@code GreeterBlockingStub} — each call blocks the calling thread until the
 *             server responds. Simple to use; not suitable for high-concurrency paths.</li>
 *         <li>{@code GreeterStub} — fully async; responses arrive via callbacks.</li>
 *         <li>{@code GreeterFutureStub} — returns a {@code ListenableFuture} per call.</li>
 *       </ul>
 *       This class uses the blocking stub for simplicity.</li>
 * </ol>
 *
 * <p>This class implements {@link AutoCloseable} so callers can use try-with-resources
 * to guarantee the channel is always shut down and OS resources are released.
 */
public class GreetingClient implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(GreetingClient.class.getName());

    /**
     * The HTTP/2 connection to the server. Must be shut down via {@link #close()} when
     * the client is no longer needed; otherwise the background I/O threads will leak.
     */
    private final ManagedChannel channel;

    /**
     * Thread-safe proxy that serialises calls into gRPC request frames.
     * Constructed once and reused for every RPC call on this client instance.
     */
    private final GreeterGrpc.GreeterBlockingStub blockingStub;

    /**
     * Creates a client connected to the Greeter service at {@code host:port}.
     *
     * <p>{@code usePlaintext()} disables TLS so the connection works without certificates —
     * suitable for local development only. Production deployments should use TLS via
     * {@code ManagedChannelBuilder.forAddress(host, port)} with a credential configured.
     *
     * @param host the server hostname or IP address
     * @param port the server port
     */
    public GreetingClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = GreeterGrpc.newBlockingStub(channel);
    }

    /**
     * Sends a {@code SayHello} RPC followed by a {@code SayHelloAgain} RPC and logs both
     * responses.
     *
     * <p>Both calls share the same immutable {@link GreetingService.HelloRequest} message —
     * protobuf messages are thread-safe and can be reused across calls.
     *
     * @param name the name to include in the greeting request
     */
    public void greet(String name) {
        logger.info("Sending greeting requests for: " + name);

        GreetingService.HelloRequest request = GreetingService.HelloRequest.newBuilder()
                .setName(name)
                .build();

        callSayHello(request);
        callSayHelloAgain(request);
    }

    /**
     * Executes the {@code SayHello} RPC.
     *
     * <p>A {@link StatusRuntimeException} is thrown when the server returns a non-OK gRPC
     * status (e.g. {@code UNAVAILABLE} if the server is unreachable, {@code DEADLINE_EXCEEDED}
     * on timeout). The status code provides transport-level failure information; the exception
     * message often carries an application-level detail string from the server.
     *
     * @param request the pre-built request message to send
     */
    private void callSayHello(GreetingService.HelloRequest request) {
        try {
            GreetingService.HelloReply response = blockingStub.sayHello(request);
            logger.info("SayHello response: " + response.getMessage());
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "SayHello RPC failed: {0}", e.getStatus());
        }
    }

    /**
     * Executes the {@code SayHelloAgain} RPC — same error-handling pattern as
     * {@link #callSayHello}, illustrating that every proto service method maps 1-to-1
     * to a separate stub method and a separate handler on the server.
     *
     * @param request the pre-built request message to send
     */
    private void callSayHelloAgain(GreetingService.HelloRequest request) {
        try {
            GreetingService.HelloReply response = blockingStub.sayHelloAgain(request);
            logger.info("SayHelloAgain response: " + response.getMessage());
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "SayHelloAgain RPC failed: {0}", e.getStatus());
        }
    }

    /**
     * Shuts down the {@link ManagedChannel}, waiting up to 5 seconds for any in-flight
     * RPCs to finish before forcing the connection closed.
     *
     * <p>Always call this (or use try-with-resources) when the client is no longer needed
     * to avoid leaking the background Netty I/O threads managed by the channel.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    @Override
    public void close() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
