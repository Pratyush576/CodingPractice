package org.pk.practices.design.api.websocket.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsContext;
import org.pk.practices.design.api.websocket.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of WebSocket rooms and their connected members.
 *
 * <h2>Data structure</h2>
 * <pre>
 *   rooms:  Map&lt;roomName, Map&lt;sessionId, WsContext&gt;&gt;
 * </pre>
 * The outer map is keyed by room name. The inner map is keyed by session ID so that
 * contexts from different events on the same connection (onConnect, onMessage, onClose)
 * can all be matched and removed by their stable session ID.
 *
 * <h2>Why store WsContext by session ID (not the context object itself)?</h2>
 * Each WebSocket lifecycle event creates a <em>new</em> context wrapper
 * ({@code WsConnectContext}, {@code WsMessageContext}, etc.) around the same underlying
 * Jetty {@code Session}. Storing by session ID lets us look up and replace the stored
 * context precisely. We keep the {@code WsConnectContext} from {@code onConnect} as the
 * canonical reference because it is the first and longest-lived wrapper — calling
 * {@code send()} on it remains valid for the lifetime of the connection.
 *
 * <h2>Thread safety</h2>
 * Both the outer and inner maps are {@link ConcurrentHashMap}s. Javalin dispatches
 * each WebSocket event on a Jetty thread pool thread, so {@link #join}, {@link #leave},
 * and {@link #broadcast} may be called concurrently from different threads.
 * {@code ConcurrentHashMap} makes individual operations atomic without a global lock,
 * giving good throughput for rooms with many concurrent users.
 *
 * <h2>Broadcast serialisation</h2>
 * The message is serialised to JSON once per broadcast call, then the same string is
 * sent to every member. This avoids redundant serialisation work that scales linearly
 * with room size.
 */
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** room name → { sessionId → WsContext } */
    private final Map<String, Map<String, WsContext>> rooms = new ConcurrentHashMap<>();

    /**
     * Registers a new member in the given room.
     *
     * <p>{@code computeIfAbsent} atomically creates the inner map if this is the
     * first connection to the room, avoiding the check-then-act race that a manual
     * {@code if (rooms.containsKey(room))} would introduce.
     *
     * @param room the room name extracted from the WebSocket path parameter
     * @param ctx  the connection context from {@code onConnect} — stored as the
     *             canonical send handle for this session
     */
    public void join(String room, WsContext ctx) {
        rooms.computeIfAbsent(room, r -> new ConcurrentHashMap<>())
             .put(ctx.sessionId(), ctx);
        log.debug("Session {} joined room '{}' ({} members)", ctx.sessionId(), room, memberCount(room));
    }

    /**
     * Removes a member from the given room by session ID.
     *
     * <p>The context passed here ({@code WsCloseContext}) is a different Java object
     * than the one stored in {@link #join} ({@code WsConnectContext}), but they share
     * the same {@code sessionId} — which is why removal is keyed by ID, not by object
     * identity.
     *
     * <p>Empty rooms are pruned from the outer map to prevent unbounded memory growth
     * in servers that host many short-lived rooms.
     *
     * @param room the room the departing session belonged to
     * @param ctx  the context from {@code onClose} or {@code onError}
     */
    public void leave(String room, WsContext ctx) {
        Map<String, WsContext> members = rooms.get(room);
        if (members == null) return;

        members.remove(ctx.sessionId());

        if (members.isEmpty()) {
            rooms.remove(room);
            log.info("Room '{}' closed — all members left", room);
        } else {
            log.debug("Session {} left room '{}' ({} members remain)", ctx.sessionId(), room, members.size());
        }
    }

    /**
     * Returns the number of connected members in a room.
     *
     * @param room the room name
     * @return 0 if the room does not exist or has no members
     */
    public int memberCount(String room) {
        Map<String, WsContext> members = rooms.get(room);
        return members == null ? 0 : members.size();
    }

    /**
     * Sends a {@link ChatMessage} to every connected member in the given room.
     *
     * <p>The message is serialised to JSON once and the resulting string is sent to each
     * session. If a send fails (e.g. the underlying TCP connection dropped before the
     * {@code onClose} event fired), the error is logged and iteration continues — one
     * bad session must not prevent delivery to healthy ones.
     *
     * @param room    the target room
     * @param message the message to broadcast
     */
    public void broadcast(String room, ChatMessage message) {
        Map<String, WsContext> members = rooms.get(room);
        if (members == null || members.isEmpty()) return;

        String json = toJson(message);
        members.values().forEach(ctx -> {
            try {
                ctx.send(json);
            } catch (Exception e) {
                log.warn("Failed to send to session {} in room '{}': {}",
                        ctx.sessionId(), room, e.getMessage());
            }
        });
    }

    private String toJson(ChatMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise ChatMessage", e);
        }
    }
}
