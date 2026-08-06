package org.pk.practices.design.videoStreaming.transcode;

/** One rung of the bitrate ladder requested for a transcode job. */
public record RenditionSpec(String resolution, int bitrateKbps, String codec) {
}
