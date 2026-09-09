package com.unionsg.zkfinger.device;

import com.unionsg.zkfinger.domain.CapturedFingerprint;
import com.unionsg.zkfinger.exception.DeviceNotReadyException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link FingerprintDevice} for tests, so the service layer can be exercised
 * without a reader attached and without the vendor JNI library.
 *
 * <p>Matching is exact byte equality, which is enough to test the search plumbing: the real
 * matcher's scoring is the vendor's concern, not this codebase's.
 */
public class FakeFingerprintDevice implements FingerprintDevice {

    public static final int MATCH_SCORE = 100;

    private static final int WIDTH = 8;
    private static final int HEIGHT = 8;

    private final Deque<byte[]> queuedCaptures = new ArrayDeque<>();
    private final AtomicInteger matchCalls = new AtomicInteger();
    private boolean ready = true;

    /** Queues the template the next capture will return. */
    public void queueCapture(byte[] template) {
        queuedCaptures.add(template);
    }

    public int matchCalls() {
        return matchCalls.get();
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    @Override
    public void initialize() {
        ready = true;
    }

    @Override
    public void shutdown() {
        ready = false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public int deviceCount() {
        return ready ? 1 : 0;
    }

    @Override
    public CapturedFingerprint capture() {
        if (!ready) {
            throw new DeviceNotReadyException("fake reader is closed");
        }
        byte[] template = queuedCaptures.isEmpty() ? new byte[] {1, 2, 3} : queuedCaptures.poll();
        return new CapturedFingerprint(template, template.length,
                new byte[WIDTH * HEIGHT], WIDTH, HEIGHT);
    }

    @Override
    public byte[] merge(byte[][] samples) {
        return samples[0];
    }

    @Override
    public int match(byte[] probe, byte[] candidate) {
        matchCalls.incrementAndGet();
        if (probe == null || candidate == null) {
            return 0;
        }
        return java.util.Arrays.equals(probe, candidate) ? MATCH_SCORE : 0;
    }

    @Override
    public int imageWidth() {
        return WIDTH;
    }

    @Override
    public int imageHeight() {
        return HEIGHT;
    }
}
