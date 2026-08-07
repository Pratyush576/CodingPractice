package org.pk.practices.cabreservation.geo;

import java.time.Instant;

/** DESIGN.md §3 — emitted by both driver and (during an active trip) rider apps. */
public record LocationPing(String entityId, double lat, double lng, Double heading, Double speed, Instant timestamp) {}
