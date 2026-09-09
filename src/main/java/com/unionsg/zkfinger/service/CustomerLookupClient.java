package com.unionsg.zkfinger.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Fetches the customer records attached to a matched relation number.
 *
 * <p>The upstream service is treated as advisory: a match is still a match even when the
 * lookup is unavailable, so failures degrade to an empty list rather than failing the
 * identification response. That mirrors the BioMini service.
 */
@Service
public class CustomerLookupClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerLookupClient.class);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> RECORD_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final Duration timeout;

    public CustomerLookupClient(WebClient webClient,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${external.api.timeout-seconds:30}") long timeoutSeconds) {
        this.webClient = webClient;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /** Returns every account related to the given relation number, or an empty list on failure. */
    public List<Map<String, Object>> getRelatedAccounts(String relationNo) {
        try {
            List<Map<String, Object>> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/get_all_related_accounts-{relationNo}")
                            .build(relationNo))
                    .retrieve()
                    .bodyToMono(RECORD_LIST)
                    .timeout(timeout)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .filter(this::isRetryable))
                    .onErrorResume(error -> {
                        log.warn("Customer lookup failed for relation_no={}: {}",
                                relationNo, error.toString());
                        return Mono.just(List.<Map<String, Object>>of());
                    })
                    .block();

            return response == null ? List.of() : response;
        } catch (RuntimeException e) {
            log.warn("Customer lookup failed for relation_no={}", relationNo, e);
            return List.of();
        }
    }

    /** Retries transient faults only; a 4xx will not succeed on a second attempt. */
    private boolean isRetryable(Throwable error) {
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        return true;
    }
}
