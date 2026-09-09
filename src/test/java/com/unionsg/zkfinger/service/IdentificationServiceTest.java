package com.unionsg.zkfinger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.device.FakeFingerprintDevice;
import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.domain.FingerprintRecord;
import com.unionsg.zkfinger.domain.IdentificationOutcome;
import com.unionsg.zkfinger.repository.FingerprintRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentificationServiceTest {

    @Mock
    private FingerprintRepository repository;

    private FakeFingerprintDevice device;
    private FingerprintProperties properties;

    @BeforeEach
    void setUp() {
        device = new FakeFingerprintDevice();
        properties = new FingerprintProperties();
        properties.getIdentification().setMatchScoreThreshold(FakeFingerprintDevice.MATCH_SCORE);
        properties.getIdentification().setMinStoredQuality(0);
    }

    private IdentificationService service() {
        return new IdentificationService(device, repository, properties);
    }

    /** Backs the paged repository calls with an in-memory list. */
    private void givenStored(List<FingerprintRecord> records) {
        when(repository.countTemplates(eq(Finger.ONE), anyInt())).thenReturn(records.size());
        when(repository.findPage(eq(Finger.ONE), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(2);
                    int offset = invocation.getArgument(3);
                    if (offset >= records.size()) {
                        return List.of();
                    }
                    return records.subList(offset, Math.min(records.size(), offset + limit));
                });
    }

    private static FingerprintRecord record(String relationNo, byte... template) {
        return new FingerprintRecord(relationNo, template, template.length, 100);
    }

    @Test
    void reportsNoMatchWhenNothingIsStored() {
        when(repository.countTemplates(eq(Finger.ONE), anyInt())).thenReturn(0);

        IdentificationOutcome outcome = service().identify(new byte[] {1}, Finger.ONE);

        assertThat(outcome.matchFound()).isFalse();
        assertThat(outcome.recordsChecked()).isZero();
    }

    @Test
    void findsTheMatchingRecordOnTheRequestThread() {
        givenStored(List.of(record("A", (byte) 1), record("B", (byte) 2), record("C", (byte) 3)));

        IdentificationOutcome outcome = service().identify(new byte[] {3}, Finger.ONE);

        assertThat(outcome.matchFound()).isTrue();
        assertThat(outcome.relationNo()).isEqualTo("C");
        assertThat(outcome.threadsUsed()).isEqualTo(1);
    }

    @Test
    void stopsScanningAsSoonAsAMatchIsFound() {
        givenStored(List.of(record("A", (byte) 1), record("B", (byte) 2), record("C", (byte) 3)));

        IdentificationOutcome outcome = service().identify(new byte[] {1}, Finger.ONE);

        assertThat(outcome.relationNo()).isEqualTo("A");
        // Only the first record should have been compared.
        assertThat(outcome.recordsChecked()).isEqualTo(1);
        assertThat(device.matchCalls()).isEqualTo(1);
    }

    @Test
    void reportsNoMatchWhenNoStoredTemplateMatches() {
        givenStored(List.of(record("A", (byte) 1), record("B", (byte) 2)));

        IdentificationOutcome outcome = service().identify(new byte[] {9}, Finger.ONE);

        assertThat(outcome.matchFound()).isFalse();
        assertThat(outcome.relationNo()).isNull();
        assertThat(outcome.recordsChecked()).isEqualTo(2);
        assertThat(outcome.timedOut()).isFalse();
    }

    @Test
    void pagesThroughEveryRecordWhenSearchingConcurrently() {
        // Above the single-thread threshold, so the worker pool is used.
        properties.getIdentification().setSingleThreadThreshold(10);
        properties.getIdentification().setBatchSize(25);

        List<FingerprintRecord> records = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            records.add(record("R" + i, (byte) (i % 97), (byte) (i / 97)));
        }
        givenStored(records);

        // A template that matches nothing, forcing a full scan.
        IdentificationOutcome outcome = service().identify(new byte[] {(byte) 200, (byte) 200}, Finger.ONE);

        assertThat(outcome.matchFound()).isFalse();
        assertThat(outcome.timedOut()).isFalse();
        // Every record must be compared exactly once: no gaps and no duplicates.
        assertThat(outcome.recordsChecked()).isEqualTo(records.size());
    }

    @Test
    void findsAMatchNearTheEndOfALargeConcurrentScan() {
        properties.getIdentification().setSingleThreadThreshold(10);
        properties.getIdentification().setBatchSize(25);

        List<FingerprintRecord> records = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            records.add(record("R" + i, (byte) (i % 251), (byte) (i / 251)));
        }
        givenStored(records);

        FingerprintRecord target = records.get(295);
        IdentificationOutcome outcome = service().identify(target.template(), Finger.ONE);

        assertThat(outcome.matchFound()).isTrue();
        assertThat(outcome.relationNo()).isEqualTo(target.relationNo());
    }

    @Test
    void treatsAScoreBelowTheThresholdAsNoMatch() {
        properties.getIdentification().setMatchScoreThreshold(FakeFingerprintDevice.MATCH_SCORE + 1);
        givenStored(List.of(record("A", (byte) 1)));

        IdentificationOutcome outcome = service().identify(new byte[] {1}, Finger.ONE);

        assertThat(outcome.matchFound()).isFalse();
    }
}
