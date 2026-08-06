package org.pk.practices.design.videoStreaming.metadata;

/** A rendition that finished encoding successfully and is ready to serve. */
public record ReadyRendition(String resolution, int bitrateKbps, String objectKey) {
}
