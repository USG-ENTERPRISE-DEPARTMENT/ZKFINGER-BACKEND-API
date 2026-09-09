package com.unionsg.zkfinger;

import com.unionsg.zkfinger.config.FingerprintProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the ZKFinger fingerprint service.
 *
 * <p>The service exposes the same HTTP contract as the Suprema BioMini backend so the two
 * can be swapped by changing a base URL only. It listens on port 8070 by default.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FingerprintProperties.class)
public class ZkFingerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZkFingerApplication.class, args);
    }
}
