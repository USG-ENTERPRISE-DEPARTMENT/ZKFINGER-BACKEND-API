package com.unionsg.zkfinger.config;

import com.unionsg.zkfinger.device.FingerprintDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opens the reader at startup and keeps it open.
 *
 * <p>Startup deliberately does not fail when the reader is missing. A fingerprint terminal is
 * routinely started before the device is plugged in, and a service that refuses to boot in that
 * case cannot serve its health endpoint to say why. Instead the failure is logged, the service
 * reports itself degraded, and a periodic task reconnects once the reader appears.
 */
@Component
public class DeviceInitializer {

    private static final Logger log = LoggerFactory.getLogger(DeviceInitializer.class);

    private final FingerprintDevice device;
    private final FingerprintProperties properties;

    /** Suppresses repeated identical warnings while the reader stays absent. */
    private volatile boolean warnedAboutFailure;

    public DeviceInitializer(FingerprintDevice device, FingerprintProperties properties) {
        this.device = device;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openOnStartup() {
        if (!properties.getDevice().isAutoInitialize()) {
            log.info("Reader auto-initialisation is off; call GET /init to open it");
            return;
        }
        tryOpen(true);
    }

    /** Reconnects a reader that was absent at startup or was unplugged and returned. */
    @Scheduled(fixedDelayString = "${fingerprint.device.reconnect-interval-ms:30000}",
            initialDelayString = "${fingerprint.device.reconnect-interval-ms:30000}")
    public void reconnectIfNeeded() {
        if (!properties.getDevice().isAutoInitialize() || device.isReady()) {
            return;
        }
        tryOpen(false);
    }

    private void tryOpen(boolean startup) {
        try {
            device.initialize();
            if (warnedAboutFailure) {
                log.info("Fingerprint reader reconnected");
            }
            warnedAboutFailure = false;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            if (!warnedAboutFailure) {
                log.warn("Fingerprint reader unavailable, the service will retry: {}", e.getMessage());
                if (startup) {
                    log.warn("Capture endpoints return 503 until the reader is available. "
                            + "Check /health for current status.");
                }
                warnedAboutFailure = true;
            }
        }
    }
}
