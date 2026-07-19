package org.pk.practices.design.api.websocket;

import io.javalin.Javalin;
import org.pk.practices.design.api.websocket.handler.ChatHandler;
import org.pk.practices.design.api.websocket.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the WebSocket chat server.
 *
 * <h2>WebSocket vs HTTP</h2>
 * HTTP is request/response: the client always initiates, the server always replies,
 * and the connection closes after each exchange. WebSocket upgrades an HTTP connection
 * to a persistent, full-duplex channel:
 * <ul>
 *   <li>The connection stays open until either side closes it.</li>
 *   <li>Either side can send a frame at any time — the server can push without a request.</li>
 *   <li>Overhead per message is minimal (2–10 bytes framing vs ~800 bytes HTTP headers).</li>
 * </ul>
 * This makes WebSocket the right choice for chat, live feeds, collaborative editing, and
 * any scenario where the server needs to push state changes to clients.
 *
 * <h2>The WebSocket handshake</h2>
 * The client opens with a normal HTTP GET carrying an {@code Upgrade: websocket} header.
 * The server responds 101 Switching Protocols and from that point the TCP connection
 * speaks the WebSocket framing protocol instead of HTTP.
 *
 * <h2>Endpoint design</h2>
 * {@code ws://localhost:8083/chat/{room}} — the room name is a path parameter so
 * clients choose their room in the URL. Multiple rooms share one server and one
 * {@link RoomManager}; Javalin routes each connection to the same handler, which
 * then partitions sessions by room name.
 *
 * <h2>Port</h2>
 * Runs on {@value #PORT} to coexist with gRPC (8080), REST (8081), GraphQL (8082).
 */
public class ChatServer {

    private static final Logger log = LoggerFactory.getLogger(ChatServer.class);
    static final int PORT = 8083;

    public static void main(String[] args) {

        // --- Wiring ------------------------------------------------------------------
        RoomManager roomManager = new RoomManager();
        ChatHandler handler     = new ChatHandler(roomManager);

        // --- Server ------------------------------------------------------------------
        Javalin app = Javalin.create(config ->
                // Log every HTTP request (includes the initial WS upgrade request)
                config.requestLogger.http((ctx, ms) ->
                        log.info("{} {} -> {} ({}ms)",
                                ctx.method(), ctx.path(), ctx.status(), ms.intValue())));

        // --- WebSocket route ---------------------------------------------------------
        // ws() registers a WebSocket endpoint. The lambda receives a WsConfig on which
        // you attach one handler per lifecycle event. Method references keep ChatServer
        // focused on wiring while ChatHandler owns all event logic.
        app.ws("/chat/{room}", ws -> {
            ws.onConnect(handler::onConnect);   // TCP open + WS handshake complete
            ws.onMessage(handler::onMessage);   // text frame received from client
            ws.onClose(handler::onClose);       // connection closed (either side)
            ws.onError(handler::onError);       // unrecoverable protocol/network error
        });

        // --- Utility routes ----------------------------------------------------------
        app.get("/health", ctx -> ctx.result("OK"));

        // --- Exception handler -------------------------------------------------------
        // Covers HTTP-level errors (e.g. malformed upgrade request).
        // WebSocket errors are handled by onError in ChatHandler.
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled HTTP exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).result("Internal server error");
        });

        // --- Start + shutdown --------------------------------------------------------
        app.start(PORT);
        log.info("Chat server ready  →  ws://localhost:{}/chat/{{room}}?username={{name}}", PORT);
        log.info("Health check       →  http://localhost:{}/health", PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping server");
            app.stop();
        }));
    }
}
