package com.unionsg.zkfinger.domain;

/**
 * One successful acquisition from the reader.
 *
 * @param template   the extracted biometric template, trimmed to its real length
 * @param quality    template quality score reported by the extractor
 * @param imageBytes the raw 8-bit greyscale image buffer, one byte per pixel
 * @param width      image width in pixels
 * @param height     image height in pixels
 */
public record CapturedFingerprint(byte[] template, int quality, byte[] imageBytes, int width, int height) {

    public int templateSize() {
        return template.length;
    }
}
