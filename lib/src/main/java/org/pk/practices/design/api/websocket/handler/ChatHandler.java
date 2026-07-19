package org.pk.practices.design.api.websocket.handler;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import org.pk.practices.design.api.websocket.model.ChatMessage;
import org.pk.practices.design.api.websocket.room.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Handles all four WebSocket lifecycle events for the {@code /chat/{room}} endpoint.
 *
 * <h2>WebSocket lifecycle</h2>
 * Every WebSocket connection goes through these events in order:
 * <pre>
 *   onConnect  →  (zero or more) onMessage  →  onClose
 *                                           ↘  onError  →  onClose
 * </pre>
 *
 * <h2>Session state across events</h2>
 * The username resolved in {@code onConnect} is stored on the WebSocket session via
 * {@code ctx.attribute("username", value)}. This persists for the lifetime of the
 * connection and is readable in all subsequent events with
 * {@code ctx.attribute("username")}, even though each event creates a new context
 * wrapper object — they all share the same underlying Jetty session and its attribute
 * map.
 *
 * <h2>Method signatures</h2>
 * Each method matches the corresponding Javalin functional interface
 * ({@code WsConnectHandler}, {@code WsMessageHandler}, etc.) so they can be wired as
 * method references in {@code ChatServer}:
 * <pre>
 *   ws.onConnect(handler::onConnect);
 * </pre>
 */
public class ChatHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final String ATTR_USERNAME = "username";

    private final RoomManager roomManager;

    public ChatHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    /**
     * Fires when a client completes the WebSocket handshake.
     *
     * <p>The username is taken from the {@code ?username=} query parameter on the
     * connection URL. If absent or blank, a default name is generated from the first
     * six characters of the session ID (e.g. {@code Guest#a1b2c3}).
     *
     * <p>The context is registered in {@link RoomManager} so that subsequent broadcasts
     * can reach this client. A JOIN message is then broadcast to everyone in the room
     * (including this new arrival) so all members see the notification.
     *
     * @param ctx the connect context — carries path params, query params, and headers
     *            from the HTTP upgrade request
     */
    public void onConnect(WsConnectContext ctx) {
        String room     = ctx.pathParam("room");
        String username = resolveUsername(ctx);
        ctx.attribute(ATTR_USERNAME, username);

        roomManager.join(room, ctx);
        log.info("'{}' connected to room '{}' (session: {}, members now: {})",
                username, room, ctx.sessionId(), roomManager.memberCount(room));

        roomManager.broadcast(room, ChatMessage.join(username, room));
    }

    /**
     * Fires when a text frame arrives from a connected client.
     *
     * <p>Blank messages are silently dropped — they add no value and spamming blank
     * frames is a trivial denial-of-service vector.
     *
     * <p>The content is broadcast as-is to the room. In a production system this is
     * where you would enforce message size limits, rate limiting per session, and
     * content moderation.
     *
     * @param ctx the message context — {@code ctx.message()} returns the raw text frame
     */
    public void onMessage(WsMessageContext ctx) {
        String room     = ctx.pathParam("room");
        String username = ctx.attribute(ATTR_USERNAME);
        String content  = ctx.message();

        if (content == null || content.isBlank()) return;

        log.info("[{}] {}: {}", room, username, content);
        roomManager.broadcast(room, ChatMessage.chat(username, room, content));
    }

    /**
     * Fires when a client closes the connection (either end may initiate).
     *
     * <p>The client is removed from the room <em>before</em> broadcasting the LEAVE
     * message, so the departing user does not receive their own leave notification.
     * Remaining members do receive it.
     *
     * <p>{@code ctx.status()} is the WebSocket close code (1000 = normal, 1001 = going
     * away, etc.). {@code ctx.reason()} is an optional human-readable string the client
     * may send with the close frame.
     *
     * @param ctx the close context — provides the close code and optional reason string
     */
    public void onClose(WsCloseContext ctx) {
        String room     = ctx.pathParam("room");
        String username = ctx.attribute(ATTR_USERNAME);

        roomManager.leave(room, ctx);
        log.info("'{}' disconnected from room '{}' (code: {}, reason: '{}')",
                username, room, ctx.status(), ctx.reason());

        if (username != null) {
            roomManager.broadcast(room, ChatMessage.leave(username, room));
        }
    }

    /**
     * Fires when an unrecoverable error occurs on the connection (e.g. network reset).
     *
     * <p>After {@code onError}, Javalin will also fire {@code onClose}, so the
     * {@link RoomManager} cleanup and LEAVE broadcast happen there — this handler only
     * needs to log the technical detail.
     *
     * @param ctx the error context — {@code ctx.error()} returns the cause throwable
     */
    public void onError(WsErrorContext ctx) {
        Throwable error = ctx.error();
        log.error("WebSocket error in room '{}' (session: {}): {}",
                ctx.pathParam("room"), ctx.sessionId(),
                error != null ? error.getMessage() : "unknown");
    }

    /**
     * Resolves a display name for the connecting client.
     *
     * <p>Prefers the {@code ?username=} query parameter. Falls back to a deterministic
     * default derived from the session ID so every client always has a name.
     */
    private String resolveUsername(WsConnectContext ctx) {
        return Optional.ofNullable(ctx.queryParam("username"))
                .filter(u -> !u.isBlank())
                .orElse("Guest#" + ctx.sessionId().substring(0, 6));
    }
}
