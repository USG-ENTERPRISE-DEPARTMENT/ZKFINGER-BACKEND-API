package com.unionsg.zkfinger.web;

import com.unionsg.zkfinger.exception.CaptureTimeoutException;
import com.unionsg.zkfinger.exception.DeviceException;
import com.unionsg.zkfinger.exception.DeviceNotReadyException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into the response shape callers already expect.
 *
 * <p>Every body carries {@code response_code} and {@code response_msg} so a client written
 * against the BioMini service can read failures the same way, while the HTTP status
 * distinguishes a caller error from a device or database fault. Stack traces are logged, never
 * returned: they reveal internal paths and SQL to the caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** No finger presented in time. Not a fault, so it reads as a normal failed attempt. */
    @ExceptionHandler(CaptureTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(CaptureTimeoutException e) {
        log.info("Capture timed out: {}", e.getMessage());
        return respond(HttpStatus.REQUEST_TIMEOUT, e.getMessage());
    }

    /** The reader has not been opened, or was unplugged. */
    @ExceptionHandler(DeviceNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleNotReady(DeviceNotReadyException e) {
        log.warn("Reader not ready: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** Any other native or reader failure. */
    @ExceptionHandler(DeviceException.class)
    public ResponseEntity<Map<String, Object>> handleDevice(DeviceException e) {
        log.error("Reader failure (code {}): {}", e.getErrorCode(), e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        log.debug("Rejected request: {}", e.getMessage());
        return respond(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDatabase(DataAccessException e) {
        log.error("Database failure", e);
        return respond(HttpStatus.SERVICE_UNAVAILABLE,
                "The fingerprint database is unavailable. Check the connection settings and retry.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled failure", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. See the service log for details.");
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("response_code", FingerprintController.CODE_FAILURE);
        body.put("response_msg", message == null ? status.getReasonPhrase() : message);
        // Kept so clients that branch on the identification result do not see a missing key.
        body.put("user_verified", false);
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(status).body(body);
    }
}
