package org.pk.practices.design.api.grpc.server;

import io.grpc.stub.StreamObserver;
import org.pk.practices.design.api.grpc.proto.GreeterGrpc.GreeterImplBase;
import org.pk.practices.design.api.grpc.proto.GreetingService;

/**
 * Server-side implementation of the {@code Greeter} gRPC service.
 *
 * <p>The protobuf compiler generates {@code GreeterImplBase} from {@code GreetingService.proto}.
 * It contains one abstract method per RPC defined in the {@code service} block. Extending it
 * and overriding those methods is how you attach business logic to the gRPC framework.
 *
 * <p>Each overridden method receives:
 * <ul>
 *   <li>A typed request object, deserialized from the protobuf binary on the wire.</li>
 *   <li>A {@link StreamObserver} — a callback interface through which the response is
 *       pushed back to the client.</li>
 * </ul>
 *
 * <p>For a <b>unary RPC</b> (one request → one response, as used here), the handler
 * pattern is always:
 * <ol>
 *   <li>Build the reply message.</li>
 *   <li>Call {@code responseObserver.onNext(reply)} to send it.</li>
 *   <li>Call {@code responseObserver.onCompleted()} to signal the RPC is finished.</li>
 * </ol>
 *
 * <p>For streaming RPCs, {@code onNext} would be called multiple times before
 * {@code onCompleted}. Call {@code responseObserver.onError(throwable)} to signal failure.
 */
public class GreetingServerImpl extends GreeterImplBase {

    /**
     * Handles the {@code SayHello} unary RPC.
     *
     * <p>The client sends exactly one {@link GreetingService.HelloRequest} and expects
     * exactly one {@link GreetingService.HelloReply} back. The gRPC framework deserializes
     * the incoming protobuf bytes into {@code req} before calling this method.
     *
     * @param req              the request message sent by the client
     * @param responseObserver the callback used to send the response back to the client
     */
    @Override
    public void sayHello(GreetingService.HelloRequest req,
                         StreamObserver<GreetingService.HelloReply> responseObserver) {
        GreetingService.HelloReply reply = GreetingService.HelloReply.newBuilder()
                .setMessage("Hello " + req.getName())
                .build();

        responseObserver.onNext(reply);     // send the single response message
        responseObserver.onCompleted();      // signal that no more messages will follow
    }

    /**
     * Handles the {@code SayHelloAgain} unary RPC — identical pattern to {@link #sayHello}.
     *
     * <p>Each entry in the {@code service} block of the proto becomes a distinct remote call
     * with its own generated stub method and its own server handler. This shows that adding
     * a new RPC only requires: (1) a new entry in the proto, (2) an override here.
     *
     * @param req              the request message sent by the client
     * @param responseObserver the callback used to send the response back to the client
     */
    @Override
    public void sayHelloAgain(GreetingService.HelloRequest req,
                              StreamObserver<GreetingService.HelloReply> responseObserver) {
        GreetingService.HelloReply reply = GreetingService.HelloReply.newBuilder()
                .setMessage("Hello again " + req.getName())
                .build();

        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
