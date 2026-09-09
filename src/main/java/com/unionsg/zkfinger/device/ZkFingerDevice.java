package com.unionsg.zkfinger.device;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.domain.CapturedFingerprint;
import com.unionsg.zkfinger.exception.CaptureTimeoutException;
import com.unionsg.zkfinger.exception.DeviceException;
import com.unionsg.zkfinger.exception.DeviceNotReadyException;
import com.zkteco.biometric.FingerprintSensorEx;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link FingerprintDevice} backed by the ZKFinger Reader SDK.
 *
 * <p>Two properties of the native layer shape this class:
 *
 * <ul>
 *   <li>The device and matcher handles are not thread-safe, so every native call is
 *       serialised. Capture holds a different lock from matching, so a long capture does not
 *       block an in-flight identification, which runs many matches in sequence.
 *   <li>{@code AcquireFingerprint} is non-blocking: it returns an error immediately when no
 *       finger is on the platen. A blocking capture is built by polling it until the
 *       configured timeout expires, mirroring the worker thread in the vendor demo.
 * </ul>
 */
@Component
public class ZkFingerDevice implements FingerprintDevice {

    private static final Logger log = LoggerFactory.getLogger(ZkFingerDevice.class);

    /** SDK parameter codes, from the ZKFinger Reader SDK for Java manual. */
    private static final int PARAM_IMAGE_WIDTH = 1;
    private static final int PARAM_IMAGE_HEIGHT = 2;
    private static final int PARAM_FAKE_STATUS = 2004;
    private static final int DB_PARAM_TEMPLATE_FORMAT = 5010;

    /** All five low bits set means every liveness sub-check passed. */
    private static final int FAKE_STATUS_GENUINE_MASK = 31;

    /** The matcher merge entry point accepts exactly this many samples. */
    private static final int MERGE_SAMPLE_COUNT = 3;

    private final FingerprintProperties properties;

    /** Guards the device handle: capture and parameter reads. */
    private final ReentrantLock deviceLock = new ReentrantLock();

    /** Guards the matcher handle: match and merge. */
    private final ReentrantLock matcherLock = new ReentrantLock();

    /** Guards initialize and shutdown transitions. */
    private final Object lifecycleLock = new Object();

    private volatile long deviceHandle;
    private volatile long dbHandle;
    private volatile boolean ready;
    private volatile int width;
    private volatile int height;

    public ZkFingerDevice(FingerprintProperties properties) {
        this.properties = properties;
    }

    @Override
    public void initialize() {
        synchronized (lifecycleLock) {
            if (ready) {
                return;
            }

            int rc = FingerprintSensorEx.Init();
            // ERR_ALREADY_INIT is benign: an earlier shutdown may have left the library loaded.
            if (rc != ZkErrorCodes.OK && rc != ZkErrorCodes.ERR_ALREADY_INIT) {
                throw new DeviceException(
                        "ZKFinger SDK initialisation failed: " + ZkErrorCodes.format(rc)
                                + ". The native library libzkfp must expose the JNI bridge; "
                                + "the vendor ZKFinger Reader driver package installs it.",
                        rc);
            }

            int devices = FingerprintSensorEx.GetDeviceCount();
            if (devices <= 0) {
                FingerprintSensorEx.Terminate();
                throw DeviceException.of("No fingerprint reader detected", ZkErrorCodes.ERR_NO_DEVICE);
            }

            long device = FingerprintSensorEx.OpenDevice(properties.getDevice().getIndex());
            if (device == 0) {
                FingerprintSensorEx.Terminate();
                throw DeviceException.of(
                        "Failed to open the reader at index " + properties.getDevice().getIndex(),
                        ZkErrorCodes.ERR_OPEN);
            }

            long db = FingerprintSensorEx.DBInit();
            if (db == 0) {
                FingerprintSensorEx.CloseDevice(device);
                FingerprintSensorEx.Terminate();
                throw DeviceException.of("Failed to initialise the matcher", ZkErrorCodes.ERR_INIT);
            }

            // The template format must match what is already stored, or nothing will ever match.
            FingerprintSensorEx.DBSetParameter(db, DB_PARAM_TEMPLATE_FORMAT,
                    properties.getDevice().getTemplateFormat());

            this.deviceHandle = device;
            this.dbHandle = db;

            try {
                this.width = readIntParameter(device, PARAM_IMAGE_WIDTH);
                this.height = readIntParameter(device, PARAM_IMAGE_HEIGHT);
            } catch (RuntimeException e) {
                closeQuietly();
                throw e;
            }

            if (width <= 0 || height <= 0) {
                int reportedWidth = width;
                int reportedHeight = height;
                closeQuietly();
                throw new DeviceException(
                        "The reader reported an invalid image size " + reportedWidth + "x" + reportedHeight);
            }

            this.ready = true;
            log.info("ZKFinger reader ready: index={} devices={} image={}x{} templateFormat={}",
                    properties.getDevice().getIndex(), devices, width, height,
                    properties.getDevice().getTemplateFormat());
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (!ready && deviceHandle == 0 && dbHandle == 0) {
                return;
            }
            closeQuietly();
            log.info("ZKFinger reader released");
        }
    }

    private void closeQuietly() {
        ready = false;
        try {
            if (dbHandle != 0) {
                FingerprintSensorEx.DBFree(dbHandle);
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.warn("Failed to free the matcher handle", e);
        } finally {
            dbHandle = 0;
        }

        try {
            if (deviceHandle != 0) {
                FingerprintSensorEx.CloseDevice(deviceHandle);
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.warn("Failed to close the reader", e);
        } finally {
            deviceHandle = 0;
        }

        try {
            FingerprintSensorEx.Terminate();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.warn("Failed to terminate the SDK", e);
        }
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public int deviceCount() {
        try {
            return Math.max(0, FingerprintSensorEx.GetDeviceCount());
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return 0;
        }
    }

    @Override
    public int imageWidth() {
        return width;
    }

    @Override
    public int imageHeight() {
        return height;
    }

    @Override
    public CapturedFingerprint capture() {
        requireReady();

        int maxTemplate = properties.getDevice().getMaxTemplateSize();
        long deadline = System.nanoTime() + properties.getDevice().getCaptureTimeout().toNanos();
        long pollMillis = Math.max(1L, properties.getDevice().getCapturePollInterval().toMillis());

        byte[] image = new byte[width * height];
        byte[] template = new byte[maxTemplate];
        int[] templateLength = new int[1];

        int lastError = ZkErrorCodes.ERR_CAPTURE;

        while (System.nanoTime() < deadline) {
            // Reset on every attempt: the SDK reads this as the buffer capacity.
            templateLength[0] = maxTemplate;

            int rc;
            deviceLock.lock();
            try {
                requireReady();
                rc = FingerprintSensorEx.AcquireFingerprint(deviceHandle, image, template, templateLength);
            } finally {
                deviceLock.unlock();
            }

            if (rc == ZkErrorCodes.OK) {
                if (templateLength[0] <= 0 || templateLength[0] > maxTemplate) {
                    throw new DeviceException(
                            "The reader returned an out-of-range template length: " + templateLength[0]);
                }
                if (properties.getDevice().isFakeDetectionEnabled() && isFakeFinger()) {
                    throw new DeviceException(
                            "The reader flagged this finger as non-genuine. Present a real finger.",
                            ZkErrorCodes.ERR_CAPTURE);
                }
                byte[] trimmed = Arrays.copyOf(template, templateLength[0]);
                return new CapturedFingerprint(trimmed, qualityOf(trimmed), image, width, height);
            }

            // ERR_CAPTURE means no finger is on the platen yet; anything else is a real fault.
            if (rc != ZkErrorCodes.ERR_CAPTURE) {
                throw DeviceException.of("Fingerprint capture failed", rc);
            }
            lastError = rc;

            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeviceException("Capture was interrupted", e);
            }
        }

        throw new CaptureTimeoutException("No finger was presented within "
                + properties.getDevice().getCaptureTimeout().toSeconds() + "s"
                + " (last reader status: " + ZkErrorCodes.format(lastError) + ")");
    }

    /**
     * The ZKFinger extractor exposes no separate quality score through this API, so template
     * length stands in as a proxy: richer minutiae sets yield longer templates. It is used only
     * for relative comparison and for the stored quality column.
     */
    private int qualityOf(byte[] template) {
        return template.length;
    }

    private boolean isFakeFinger() {
        byte[] value = new byte[4];
        int[] size = new int[] {4};
        int rc = FingerprintSensorEx.GetParameters(deviceHandle, PARAM_FAKE_STATUS, value, size);
        if (rc != ZkErrorCodes.OK) {
            // This reader does not support liveness detection; do not block the capture.
            return false;
        }
        return (toInt(value) & FAKE_STATUS_GENUINE_MASK) != FAKE_STATUS_GENUINE_MASK;
    }

    @Override
    public byte[] merge(byte[][] samples) {
        requireReady();
        if (samples.length != MERGE_SAMPLE_COUNT) {
            throw new IllegalArgumentException(
                    "The ZKFinger matcher merges exactly " + MERGE_SAMPLE_COUNT
                            + " samples, received " + samples.length);
        }

        int maxTemplate = properties.getDevice().getMaxTemplateSize();
        byte[] merged = new byte[maxTemplate];
        int[] mergedLength = new int[] {maxTemplate};

        matcherLock.lock();
        try {
            int rc = FingerprintSensorEx.DBMerge(
                    dbHandle, samples[0], samples[1], samples[2], merged, mergedLength);
            if (rc != ZkErrorCodes.OK) {
                throw DeviceException.of("Failed to merge the enrolment samples", rc);
            }
        } finally {
            matcherLock.unlock();
        }

        return Arrays.copyOf(merged, mergedLength[0]);
    }

    @Override
    public int match(byte[] probe, byte[] candidate) {
        if (probe == null || candidate == null || probe.length == 0 || candidate.length == 0) {
            return 0;
        }
        requireReady();

        matcherLock.lock();
        try {
            // A negative return is an error code rather than a score, so clamp it to no match.
            return Math.max(0, FingerprintSensorEx.DBMatch(dbHandle, probe, candidate));
        } finally {
            matcherLock.unlock();
        }
    }

    private int readIntParameter(long handle, int code) {
        byte[] value = new byte[4];
        int[] size = new int[] {4};
        int rc = FingerprintSensorEx.GetParameters(handle, code, value, size);
        if (rc != ZkErrorCodes.OK) {
            throw DeviceException.of("Failed to read reader parameter " + code, rc);
        }
        return toInt(value);
    }

    /** Decodes the little-endian 32-bit integers the SDK returns in a byte buffer. */
    private static int toInt(byte[] bytes) {
        return (bytes[0] & 0xFF)
                | ((bytes[1] & 0xFF) << 8)
                | ((bytes[2] & 0xFF) << 16)
                | ((bytes[3] & 0xFF) << 24);
    }

    private void requireReady() {
        if (!ready) {
            throw new DeviceNotReadyException(
                    "The fingerprint reader is not open. Call GET /init, or check that the reader is connected.");
        }
    }
}
