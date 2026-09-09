package com.unionsg.zkfinger.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the fingerprint device, matcher and identification pipeline.
 * Every value is overridable via application.yml or environment variables.
 */
@Validated
@ConfigurationProperties(prefix = "fingerprint")
public class FingerprintProperties {

    private final Device device = new Device();
    private final Identification identification = new Identification();
    private final Image image = new Image();

    public Device getDevice() {
        return device;
    }

    public Identification getIdentification() {
        return identification;
    }

    public Image getImage() {
        return image;
    }

    public static class Device {

        /** Index of the reader to open when several are attached. */
        @Min(0)
        private int index = 0;

        /** Open the reader eagerly at startup instead of waiting for /init. */
        private boolean autoInitialize = true;

        /**
         * Template format written to the database.
         * 0 = ANSI-378, 1 = ISO 19794-2, 2 = ZK proprietary.
         *
         * <p>This MUST match the format of the templates already stored, otherwise
         * matching silently fails for every record.
         */
        @Min(0)
        @Max(2)
        private int templateFormat = 0;

        /** Maximum template size in bytes. The SDK uses 2048 throughout. */
        @Min(256)
        private int maxTemplateSize = 2048;

        /** How long a capture call waits for a finger before giving up. */
        @NotNull
        private Duration captureTimeout = Duration.ofSeconds(15);

        /** Delay between polls of the non-blocking acquire call. */
        @NotNull
        private Duration capturePollInterval = Duration.ofMillis(120);

        /** Reject fingers the reader flags as spoofed, where the reader supports it. */
        private boolean fakeDetectionEnabled = false;

        /**
         * Number of distinct presses merged into one enrolment template.
         *
         * <p>Only 1 or 3 are meaningful: the native merge entry point takes exactly three
         * samples, so 3 enrols a merged template and 1 stores a single capture. Any other
         * value is rejected at startup rather than silently behaving as 3.
         */
        @Min(1)
        @Max(3)
        private int enrollSamples = 3;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public boolean isAutoInitialize() {
            return autoInitialize;
        }

        public void setAutoInitialize(boolean autoInitialize) {
            this.autoInitialize = autoInitialize;
        }

        public int getTemplateFormat() {
            return templateFormat;
        }

        public void setTemplateFormat(int templateFormat) {
            this.templateFormat = templateFormat;
        }

        public int getMaxTemplateSize() {
            return maxTemplateSize;
        }

        public void setMaxTemplateSize(int maxTemplateSize) {
            this.maxTemplateSize = maxTemplateSize;
        }

        public Duration getCaptureTimeout() {
            return captureTimeout;
        }

        public void setCaptureTimeout(Duration captureTimeout) {
            this.captureTimeout = captureTimeout;
        }

        public Duration getCapturePollInterval() {
            return capturePollInterval;
        }

        public void setCapturePollInterval(Duration capturePollInterval) {
            this.capturePollInterval = capturePollInterval;
        }

        public boolean isFakeDetectionEnabled() {
            return fakeDetectionEnabled;
        }

        public void setFakeDetectionEnabled(boolean fakeDetectionEnabled) {
            this.fakeDetectionEnabled = fakeDetectionEnabled;
        }

        public int getEnrollSamples() {
            return enrollSamples;
        }

        public void setEnrollSamples(int enrollSamples) {
            if (enrollSamples != 1 && enrollSamples != 3) {
                throw new IllegalArgumentException(
                        "fingerprint.device.enroll-samples must be 1 or 3, but was " + enrollSamples
                                + ". The native merge accepts exactly three samples.");
            }
            this.enrollSamples = enrollSamples;
        }
    }

    public static class Identification {

        /** Rows fetched per page by each worker thread. */
        @Min(1)
        private int batchSize = 1000;

        /** Datasets smaller than this are searched on the request thread. */
        @Min(1)
        private int singleThreadThreshold = 100;

        /** Upper bound on worker threads; 0 means one per available processor. */
        @Min(0)
        private int maxThreads = 0;

        /** Hard limit on a single identification request. */
        @NotNull
        private Duration timeout = Duration.ofSeconds(60);

        /** Stored templates below this quality are excluded from the search. */
        @Min(0)
        private int minStoredQuality = 30;

        /** A live capture below this quality is rejected before searching. */
        @Min(0)
        private int minCaptureQuality = 20;

        /**
         * Minimum DBMatch score treated as a match. The ZKFinger matcher returns a
         * similarity score rather than a boolean; the SDK demo treats any positive
         * score as a match, which is too permissive for a 1:N search.
         */
        @Min(1)
        private int matchScoreThreshold = 60;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getSingleThreadThreshold() {
            return singleThreadThreshold;
        }

        public void setSingleThreadThreshold(int singleThreadThreshold) {
            this.singleThreadThreshold = singleThreadThreshold;
        }

        public int getMaxThreads() {
            return maxThreads;
        }

        public void setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMinStoredQuality() {
            return minStoredQuality;
        }

        public void setMinStoredQuality(int minStoredQuality) {
            this.minStoredQuality = minStoredQuality;
        }

        public int getMinCaptureQuality() {
            return minCaptureQuality;
        }

        public void setMinCaptureQuality(int minCaptureQuality) {
            this.minCaptureQuality = minCaptureQuality;
        }

        public int getMatchScoreThreshold() {
            return matchScoreThreshold;
        }

        public void setMatchScoreThreshold(int matchScoreThreshold) {
            this.matchScoreThreshold = matchScoreThreshold;
        }
    }

    public static class Image {

        /** JPEG quality applied to the preview image returned to callers. */
        private float jpegQuality = 0.2f;

        public float getJpegQuality() {
            return jpegQuality;
        }

        public void setJpegQuality(float jpegQuality) {
            this.jpegQuality = jpegQuality;
        }
    }
}
