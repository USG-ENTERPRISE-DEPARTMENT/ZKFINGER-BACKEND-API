package com.unionsg.zkfinger.service;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.device.FingerprintDevice;
import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.domain.FingerprintRecord;
import com.unionsg.zkfinger.domain.IdentificationOutcome;
import com.unionsg.zkfinger.repository.FingerprintRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One-to-many search of a live capture against every stored template.
 *
 * <p>Work is split by page: each worker claims the next unclaimed page through a shared
 * counter, so a worker that hits a slow page does not hold up the others. All workers watch a
 * single {@code matchFound} flag and stop as soon as any of them wins, and the whole search is
 * bounded by a timeout.
 *
 * <p>Note that the matcher itself is serialised inside the device layer, since the native
 * handle is not thread-safe. The concurrency here therefore buys overlap between database
 * paging and matching rather than parallel matching. Small datasets skip the pool entirely.
 */
@Service
public class IdentificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentificationService.class);

    private final FingerprintDevice device;
    private final FingerprintRepository repository;
    private final FingerprintProperties properties;
    private final ExecutorService executor;

    public IdentificationService(FingerprintDevice device,
                                 FingerprintRepository repository,
                                 FingerprintProperties properties) {
        this.device = device;
        this.repository = repository;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(workerCount(), namedDaemonFactory());
    }

    private int workerCount() {
        int configured = properties.getIdentification().getMaxThreads();
        int available = Runtime.getRuntime().availableProcessors();
        return configured > 0 ? configured : Math.min(available, 8);
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "fp-identify-" + counter.incrementAndGet());
            // Daemon threads so a hung native match cannot keep the JVM alive on shutdown.
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Searches every stored template for the given finger against {@code probe}. */
    public IdentificationOutcome identify(byte[] probe, Finger finger) {
        int minQuality = properties.getIdentification().getMinStoredQuality();
        int total = repository.countTemplates(finger, minQuality);

        if (total == 0) {
            return IdentificationOutcome.noMatch(0, 0, false);
        }

        if (total < properties.getIdentification().getSingleThreadThreshold()) {
            log.debug("Identifying against {} records on the request thread", total);
            return searchSingleThreaded(probe, finger, minQuality);
        }

        log.debug("Identifying against {} records across {} workers", total, workerCount());
        return searchConcurrently(probe, finger, minQuality);
    }

    private IdentificationOutcome searchSingleThreaded(byte[] probe, Finger finger, int minQuality) {
        int batchSize = properties.getIdentification().getBatchSize();
        int threshold = properties.getIdentification().getMatchScoreThreshold();
        long deadline = System.nanoTime() + properties.getIdentification().getTimeout().toNanos();

        int checked = 0;
        int offset = 0;

        while (true) {
            if (System.nanoTime() > deadline) {
                return IdentificationOutcome.noMatch(checked, 1, true);
            }

            List<FingerprintRecord> page = repository.findPage(finger, minQuality, batchSize, offset);
            if (page.isEmpty()) {
                return IdentificationOutcome.noMatch(checked, 1, false);
            }

            for (FingerprintRecord record : page) {
                checked++;
                int score = device.match(probe, record.template());
                if (score >= threshold) {
                    return new IdentificationOutcome(true, record.relationNo(), score, checked, 1, false);
                }
            }

            offset += page.size();
        }
    }

    private IdentificationOutcome searchConcurrently(byte[] probe, Finger finger, int minQuality) {
        int batchSize = properties.getIdentification().getBatchSize();
        int threshold = properties.getIdentification().getMatchScoreThreshold();
        int workers = workerCount();
        long timeoutNanos = properties.getIdentification().getTimeout().toNanos();
        long deadline = System.nanoTime() + timeoutNanos;

        // Workers claim pages through this counter, so no page is scanned twice.
        AtomicInteger nextPage = new AtomicInteger();
        AtomicInteger checked = new AtomicInteger();
        AtomicBoolean matchFound = new AtomicBoolean();
        AtomicBoolean exhausted = new AtomicBoolean();

        // Holds the winning score and record; only the first winner writes.
        AtomicLong winningScore = new AtomicLong();
        List<String> winner = java.util.Collections.synchronizedList(new ArrayList<>(1));

        List<Callable<Void>> tasks = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            tasks.add(() -> {
                while (!matchFound.get() && !exhausted.get() && System.nanoTime() < deadline) {
                    int page = nextPage.getAndIncrement();
                    List<FingerprintRecord> records =
                            repository.findPage(finger, minQuality, batchSize, page * batchSize);

                    if (records.isEmpty()) {
                        // A short page means this worker ran past the end of the table.
                        exhausted.set(true);
                        return null;
                    }

                    for (FingerprintRecord record : records) {
                        if (matchFound.get() || System.nanoTime() > deadline) {
                            return null;
                        }
                        checked.incrementAndGet();
                        int score = device.match(probe, record.template());
                        if (score >= threshold && matchFound.compareAndSet(false, true)) {
                            winningScore.set(score);
                            winner.add(record.relationNo());
                            return null;
                        }
                    }
                }
                return null;
            });
        }

        boolean completed;
        try {
            List<Future<Void>> futures =
                    executor.invokeAll(tasks, timeoutNanos, TimeUnit.NANOSECONDS);
            completed = futures.stream().noneMatch(Future::isCancelled);

            for (Future<Void> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }
                try {
                    future.get();
                } catch (ExecutionException e) {
                    log.error("Identification worker failed", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IdentificationOutcome.noMatch(checked.get(), workers, true);
        }

        if (matchFound.get() && !winner.isEmpty()) {
            return new IdentificationOutcome(true, winner.get(0), (int) winningScore.get(),
                    checked.get(), workers, false);
        }

        // Timed out only if the workers were cut short before covering the table.
        boolean timedOut = !completed && !exhausted.get();
        return IdentificationOutcome.noMatch(checked.get(), workers, timedOut);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Identification workers did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Exposed for the diagnostics endpoint. */
    public int configuredWorkers() {
        return workerCount();
    }
}
