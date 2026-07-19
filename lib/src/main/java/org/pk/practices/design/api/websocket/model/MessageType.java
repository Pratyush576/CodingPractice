package org.pk.practices.design.api.websocket.model;

/**
 * Discriminates the purpose of a {@link ChatMessage} so clients can render each
 * type differently (e.g. style JOIN/LEAVE differently from CHAT text).
 *
 * <p>Having an explicit type field is a common WebSocket protocol convention —
 * without it, clients must guess intent from the message content.
 */
public enum MessageType {

    /** A user connected to the room. The server broadcasts this to all other members. */
    JOIN,

    /** A regular chat message from a connected user. */
    CHAT,

    /** A user disconnected from the room. The server broadcasts this to remaining members. */
    LEAVE,

    /** An error notification pushed from the server (e.g. connection rejected). */
    ERROR
}
