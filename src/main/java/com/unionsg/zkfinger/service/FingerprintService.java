package com.unionsg.zkfinger.service;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.device.FingerprintDevice;
import com.unionsg.zkfinger.device.FingerprintImageCodec;
import com.unionsg.zkfinger.domain.CapturedFingerprint;
import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.domain.IdentificationOutcome;
import com.unionsg.zkfinger.exception.DeviceException;
import com.unionsg.zkfinger.repository.FingerprintRepository;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Capture and enrolment use cases.
 *
 * <p>Access to the reader is serialised here rather than in the controller: the device has one
 * platen, so two concurrent captures would interleave presses from different people. Requests
 * queue instead of racing.
 */
@Service
public class FingerprintService {

    private static final Logger log = LoggerFactory.getLogger(FingerprintService.class);

    private final FingerprintDevice device;
    private final FingerprintRepository repository;
    private final IdentificationService identificationService;
    private final FingerprintProperties properties;

    /** Serialises reader access; the platen cannot serve two callers at once. */
    private final Object captureLock = new Object();

    public FingerprintService(FingerprintDevice device,
                              FingerprintRepository repository,
                              IdentificationService identificationService,
                              FingerprintProperties properties) {
        this.device = device;
        this.repository = repository;
        this.identificationService = identificationService;
        this.properties = properties;
    }

    /** Result of a capture that was persisted. */
    public record CaptureResult(String base64Image, int templateSize, int quality, boolean stored) {
    }

    /**
     * Captures one press and stores it against the customer.
     *
     * @param relationNo customer business key
     * @param finger     which of the two finger slots to write
     */
    public CaptureResult captureAndStore(String relationNo, Finger finger) {
        CapturedFingerprint captured;
        synchronized (captureLock) {
            captured = device.capture();
        }

        String base64Image = FingerprintImageCodec.toBase64Jpeg(
                captured.imageBytes(), captured.width(), captured.height(),
                properties.getImage().getJpegQuality());

        byte[] imageBytes = Base64.getDecoder().decode(base64Image);

        boolean stored = repository.save(relationNo, finger, captured.template(),
                imageBytes, captured.templateSize(), captured.quality());

        log.info("Captured fingerprint for relation_no={} finger={} size={} stored={}",
                relationNo, finger.code(), captured.templateSize(), stored);

        return new CaptureResult(base64Image, captured.templateSize(), captured.quality(), stored);
    }

    /**
     * Enrols a finger from several presses merged into one template.
     *
     * <p>Merging three presses is what the ZKFinger SDK is designed for and produces a
     * noticeably more reliable template than a single capture, which matters for one-to-many
     * search. A single-press enrolment is still possible by setting the sample count to 1.
     */
    public CaptureResult enroll(String relationNo, Finger finger) {
        int samples = properties.getDevice().getEnrollSamples();

        synchronized (captureLock) {
            if (samples == 1) {
                return captureAndStoreLocked(relationNo, finger, device.capture());
            }

            // The native merge accepts exactly three samples.
            byte[][] collected = new byte[3][];
            CapturedFingerprint last = null;

            for (int i = 0; i < 3; i++) {
                CapturedFingerprint sample = device.capture();
                last = sample;

                // Every press must be the same finger, or the merged template is meaningless.
                if (i > 0) {
                    int score = device.match(collected[i - 1], sample.template());
                    if (score < properties.getIdentification().getMatchScoreThreshold()) {
                        throw new DeviceException(
                                "Press the same finger for every sample; sample "
                                        + (i + 1) + " did not match the previous one.");
                    }
                }
                collected[i] = sample.template();
                log.debug("Enrolment sample {}/3 captured for relation_no={}", i + 1, relationNo);
            }

            byte[] merged = device.merge(collected);
            return storeTemplate(relationNo, finger, merged, merged.length, last);
        }
    }

    private CaptureResult captureAndStoreLocked(String relationNo, Finger finger,
                                                CapturedFingerprint captured) {
        return storeTemplate(relationNo, finger, captured.template(),
                captured.templateSize(), captured);
    }

    private CaptureResult storeTemplate(String relationNo, Finger finger, byte[] template,
                                        int templateSize, CapturedFingerprint preview) {
        String base64Image = FingerprintImageCodec.toBase64Jpeg(
                preview.imageBytes(), preview.width(), preview.height(),
                properties.getImage().getJpegQuality());

        byte[] imageBytes = Base64.getDecoder().decode(base64Image);

        boolean stored = repository.save(relationNo, finger, template, imageBytes,
                templateSize, template.length);

        log.info("Enrolled fingerprint for relation_no={} finger={} size={} stored={}",
                relationNo, finger.code(), templateSize, stored);

        return new CaptureResult(base64Image, templateSize, template.length, stored);
    }

    /** Captures a live finger and searches it against every stored template. */
    public IdentificationOutcome captureAndIdentify(Finger finger) {
        CapturedFingerprint captured;
        synchronized (captureLock) {
            captured = device.capture();
        }

        int minCaptureQuality = properties.getIdentification().getMinCaptureQuality();
        if (captured.quality() < minCaptureQuality) {
            throw new DeviceException(
                    "The captured fingerprint is too weak to search (quality " + captured.quality()
                            + " is below the minimum of " + minCaptureQuality
                            + "). Clean the sensor and press firmly.");
        }

        return identificationService.identify(captured.template(), finger);
    }

    /** Verifies a live finger against the templates stored for one customer. */
    public IdentificationOutcome verifyAgainst(String relationNo, Finger finger) {
        CapturedFingerprint captured;
        synchronized (captureLock) {
            captured = device.capture();
        }

        int threshold = properties.getIdentification().getMatchScoreThreshold();
        int checked = 0;
        int best = 0;

        for (var record : repository.findByRelationNo(relationNo)) {
            checked++;
            int score = device.match(captured.template(), record.template());
            best = Math.max(best, score);
            if (score >= threshold) {
                return new IdentificationOutcome(true, relationNo, score, checked, 1, false);
            }
        }

        return new IdentificationOutcome(false, null, best, checked, 1, false);
    }
}
