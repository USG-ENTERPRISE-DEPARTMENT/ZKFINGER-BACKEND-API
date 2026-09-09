package com.unionsg.zkfinger.device;

import com.unionsg.zkfinger.domain.CapturedFingerprint;

/**
 * The reader and matcher operations the service layer depends on.
 *
 * <p>Kept as an interface so the ZKFinger JNI binding is the only thing that has to change
 * if the native access strategy changes, and so the service layer can be tested without a
 * physical reader attached.
 */
public interface FingerprintDevice {

    /** Opens the reader and matcher. Safe to call repeatedly; a second call is a no-op. */
    void initialize();

    /** Closes the reader and matcher and releases native handles. */
    void shutdown();

    /** Whether the reader is currently open and usable. */
    boolean isReady();

    /** Number of readers the SDK reports as attached. */
    int deviceCount();

    /**
     * Blocks until a finger is presented and a template is extracted, or the configured
     * capture timeout elapses.
     *
     * @throws com.unionsg.zkfinger.exception.CaptureTimeoutException if no finger arrives in time
     * @throws com.unionsg.zkfinger.exception.DeviceException on any native failure
     */
    CapturedFingerprint capture();

    /**
     * Merges several captures of the same finger into one enrolment template.
     *
     * @param samples exactly {@code fingerprint.device.enroll-samples} templates
     */
    byte[] merge(byte[][] samples);

    /**
     * Compares two templates.
     *
     * @return the matcher similarity score; higher is closer, values at or below zero mean no match
     */
    int match(byte[] probe, byte[] candidate);

    /** Width in pixels of the images this reader produces. */
    int imageWidth();

    /** Height in pixels of the images this reader produces. */
    int imageHeight();
}
