package com.unionsg.zkfinger.web;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.device.FingerprintDevice;
import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.domain.IdentificationOutcome;
import com.unionsg.zkfinger.repository.FingerprintRepository;
import com.unionsg.zkfinger.service.CustomerLookupClient;
import com.unionsg.zkfinger.service.FingerprintService;
import com.unionsg.zkfinger.service.IdentificationService;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP contract for the fingerprint service.
 *
 * <p>The paths, parameter names and response keys deliberately mirror the Suprema BioMini
 * service so an existing client can be pointed at this port with no code change. The success
 * and failure codes carried in {@code response_code} keep the same 1 and -1 values, and the
 * HTTP status is set alongside them so ordinary HTTP tooling also sees failures.
 */
@Validated
@RestController
public class FingerprintController {

    private static final Logger log = LoggerFactory.getLogger(FingerprintController.class);

    /** Legacy response codes carried in the body, inherited from the BioMini service. */
    static final int CODE_SUCCESS = 1;
    static final int CODE_FAILURE = -1;

    private final FingerprintService fingerprintService;
    private final IdentificationService identificationService;
    private final CustomerLookupClient customerLookup;
    private final FingerprintDevice device;
    private final FingerprintRepository repository;
    private final FingerprintProperties properties;

    public FingerprintController(FingerprintService fingerprintService,
                                 IdentificationService identificationService,
                                 CustomerLookupClient customerLookup,
                                 FingerprintDevice device,
                                 FingerprintRepository repository,
                                 FingerprintProperties properties) {
        this.fingerprintService = fingerprintService;
        this.identificationService = identificationService;
        this.customerLookup = customerLookup;
        this.device = device;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Opens the reader.
     *
     * <p>Returns the bare integer the BioMini service returned, so existing callers that read
     * the body as a number keep working. 1 means ready.
     */
    @GetMapping("/init")
    public int init() {
        device.initialize();
        return CODE_SUCCESS;
    }

    /**
     * Captures one press and stores it against a customer.
     *
     * @param relationNo customer business key
     * @param thumbprint which finger slot to write: "1" or "2"
     */
    @PostMapping("/capture")
    public Map<String, Object> capture(@RequestParam("relation_no") @NotBlank String relationNo,
                                       @RequestParam("thumbprint") String thumbprint) {

        Finger finger = Finger.fromCode(thumbprint);
        FingerprintService.CaptureResult result = fingerprintService.captureAndStore(relationNo, finger);

        Map<String, Object> body = new LinkedHashMap<>();
        if (result.stored()) {
            body.put("response_code", CODE_SUCCESS);
            body.put("response_msg", "Fingerprint captured and saved successfully");
            body.put("image", result.base64Image());
            body.put("template_size", result.templateSize());
            body.put("quality", result.quality());
        } else {
            body.put("response_code", CODE_FAILURE);
            body.put("response_msg", "Failed to save fingerprint to database");
        }
        return body;
    }

    /**
     * Enrols a finger by merging several presses into one template.
     *
     * <p>New relative to the BioMini service. Merged templates identify far more reliably in a
     * one-to-many search, which is what the ZKFinger matcher is tuned for.
     */
    @PostMapping("/enroll")
    public Map<String, Object> enroll(@RequestParam("relation_no") @NotBlank String relationNo,
                                      @RequestParam(value = "thumbprint", defaultValue = "1") String thumbprint) {

        Finger finger = Finger.fromCode(thumbprint);
        FingerprintService.CaptureResult result = fingerprintService.enroll(relationNo, finger);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("response_code", result.stored() ? CODE_SUCCESS : CODE_FAILURE);
        body.put("response_msg", result.stored()
                ? "Fingerprint enrolled successfully"
                : "Failed to save fingerprint to database");
        body.put("image", result.base64Image());
        body.put("template_size", result.templateSize());
        body.put("quality", result.quality());
        body.put("samples_merged", properties.getDevice().getEnrollSamples());
        return body;
    }

    /**
     * Captures a live finger and searches it against every stored template.
     *
     * <p>Response keys match the BioMini service: {@code user_verified},
     * {@code matched_relation_no}, {@code customer_details}, {@code total_records_checked},
     * {@code verification_time_ms} and {@code threads_used}.
     */
    @PostMapping("/verify")
    public Map<String, Object> verify(
            @RequestParam(value = "thumbprint", defaultValue = "1") String thumbprint) {

        long startedAt = System.currentTimeMillis();
        Finger finger = Finger.fromCode(thumbprint);
        IdentificationOutcome outcome = fingerprintService.captureAndIdentify(finger);
        long elapsed = System.currentTimeMillis() - startedAt;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_verified", outcome.matchFound());
        body.put("total_records_checked", outcome.recordsChecked());
        body.put("verification_time_ms", elapsed);
        body.put("threads_used", outcome.threadsUsed());

        if (outcome.matchFound()) {
            body.put("matched_relation_no", outcome.relationNo());
            body.put("match_score", outcome.score());
            body.put("customer_details", customerLookup.getRelatedAccounts(outcome.relationNo()));
            log.info("Identified relation_no={} score={} in {}ms after {} comparisons",
                    outcome.relationNo(), outcome.score(), elapsed, outcome.recordsChecked());
        } else {
            body.put("response_msg", outcome.timedOut()
                    ? "Verification timed out before searching every record"
                    : "No matching fingerprint found");
            log.info("No match after {} comparisons in {}ms (timedOut={})",
                    outcome.recordsChecked(), elapsed, outcome.timedOut());
        }
        return body;
    }

    /**
     * Verifies a live finger against one specific customer.
     *
     * <p>A one-to-one check, which is much cheaper than {@code /verify} when the caller
     * already knows who the person claims to be.
     */
    @PostMapping("/verify-one")
    public Map<String, Object> verifyOne(@RequestParam("relation_no") @NotBlank String relationNo,
                                         @RequestParam(value = "thumbprint", defaultValue = "1") String thumbprint) {

        long startedAt = System.currentTimeMillis();
        IdentificationOutcome outcome =
                fingerprintService.verifyAgainst(relationNo, Finger.fromCode(thumbprint));
        long elapsed = System.currentTimeMillis() - startedAt;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_verified", outcome.matchFound());
        body.put("relation_no", relationNo);
        body.put("match_score", outcome.score());
        body.put("total_records_checked", outcome.recordsChecked());
        body.put("verification_time_ms", elapsed);
        if (!outcome.matchFound()) {
            body.put("response_msg", outcome.recordsChecked() == 0
                    ? "No fingerprint is enrolled for this relation number"
                    : "Fingerprint did not match the enrolled template");
        }
        return body;
    }

    /** Liveness and dependency status. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("initialized", device.isReady());
        body.put("scanner_count", device.deviceCount());
        body.put("timestamp", System.currentTimeMillis());

        try {
            repository.healthCheck();
            body.put("database", "CONNECTED");
        } catch (RuntimeException e) {
            body.put("database", "ERROR: " + e.getMessage());
            body.put("status", "DEGRADED");
        }

        if (!device.isReady()) {
            body.put("status", "DEGRADED");
        }
        return body;
    }

    /** Tuning and runtime detail for the identification pipeline. */
    @GetMapping("/verification-stats")
    public Map<String, Object> verificationStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available_processors", Runtime.getRuntime().availableProcessors());
        body.put("worker_threads", identificationService.configuredWorkers());
        body.put("batch_size", properties.getIdentification().getBatchSize());
        body.put("timeout_seconds", properties.getIdentification().getTimeout().toSeconds());
        body.put("match_score_threshold", properties.getIdentification().getMatchScoreThreshold());
        body.put("single_thread_threshold", properties.getIdentification().getSingleThreadThreshold());
        body.put("template_format", properties.getDevice().getTemplateFormat());
        body.put("device_ready", device.isReady());
        body.put("image_width", device.imageWidth());
        body.put("image_height", device.imageHeight());
        return body;
    }

    /** Reports whether a customer already has a template on file. */
    @GetMapping("/enrolled")
    public Map<String, Object> enrolled(@RequestParam("relation_no") @NotBlank String relationNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("relation_no", relationNo);
        body.put("enrolled", repository.exists(relationNo));
        body.put("templates", repository.findByRelationNo(relationNo).size());
        return body;
    }

    /** Releases the reader so another process can open it. */
    @PostMapping("/shutdown-device")
    public Map<String, Object> shutdownDevice() {
        device.shutdown();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("response_code", CODE_SUCCESS);
        body.put("response_msg", "Reader released");
        return body;
    }

    /** Kept so the legacy smoke test in existing clients still answers. */
    @GetMapping("/hello")
    public String hello() {
        return "Hello, World!";
    }

}
