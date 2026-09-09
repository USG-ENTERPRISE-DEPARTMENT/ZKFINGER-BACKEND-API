package com.unionsg.zkfinger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.device.FakeFingerprintDevice;
import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.exception.DeviceException;
import com.unionsg.zkfinger.exception.DeviceNotReadyException;
import com.unionsg.zkfinger.repository.FingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FingerprintServiceTest {

    @Mock
    private FingerprintRepository repository;

    @Mock
    private IdentificationService identificationService;

    private FakeFingerprintDevice device;
    private FingerprintProperties properties;
    private FingerprintService service;

    @BeforeEach
    void setUp() {
        device = new FakeFingerprintDevice();
        properties = new FingerprintProperties();
        properties.getIdentification().setMatchScoreThreshold(FakeFingerprintDevice.MATCH_SCORE);
        service = new FingerprintService(device, repository, identificationService, properties);

        when(repository.save(anyString(), any(Finger.class), any(), any(), anyInt(), anyInt()))
                .thenReturn(true);
    }

    @Test
    void storesTheCapturedTemplateAgainstTheChosenFinger() {
        device.queueCapture(new byte[] {5, 6, 7});

        FingerprintService.CaptureResult result = service.captureAndStore("R1", Finger.TWO);

        assertThat(result.stored()).isTrue();
        assertThat(result.templateSize()).isEqualTo(3);
        assertThat(result.base64Image()).isNotBlank();

        ArgumentCaptor<byte[]> template = ArgumentCaptor.forClass(byte[].class);
        verify(repository).save(org.mockito.ArgumentMatchers.eq("R1"),
                org.mockito.ArgumentMatchers.eq(Finger.TWO),
                template.capture(), any(), anyInt(), anyInt());
        assertThat(template.getValue()).containsExactly(5, 6, 7);
    }

    @Test
    void reportsAFailedSaveRatherThanClaimingSuccess() {
        when(repository.save(anyString(), any(Finger.class), any(), any(), anyInt(), anyInt()))
                .thenReturn(false);

        assertThat(service.captureAndStore("R1", Finger.ONE).stored()).isFalse();
    }

    @Test
    void enrolmentMergesThreePressesOfTheSameFinger() {
        byte[] finger = {1, 2, 3};
        device.queueCapture(finger);
        device.queueCapture(finger);
        device.queueCapture(finger);

        FingerprintService.CaptureResult result = service.enroll("R1", Finger.ONE);

        assertThat(result.stored()).isTrue();
    }

    @Test
    void enrolmentRejectsPressesFromDifferentFingers() {
        device.queueCapture(new byte[] {1, 2, 3});
        device.queueCapture(new byte[] {9, 9, 9});
        device.queueCapture(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.enroll("R1", Finger.ONE))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("same finger");
    }

    @Test
    void enrolmentTakesASinglePressWhenConfiguredForOneSample() {
        properties.getDevice().setEnrollSamples(1);
        device.queueCapture(new byte[] {4, 4});

        assertThat(service.enroll("R1", Finger.ONE).stored()).isTrue();
        // Only one press should have been taken, leaving no queued captures consumed beyond it.
        assertThat(device.matchCalls()).isZero();
    }

    @Test
    void rejectsAnEnrolSampleCountTheMatcherCannotHonour() {
        assertThatThrownBy(() -> properties.getDevice().setEnrollSamples(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 1 or 3");
    }

    @Test
    void verifyingAgainstOneCustomerReportsAMatch() {
        byte[] template = {7, 7, 7};
        device.queueCapture(template);
        when(repository.findByRelationNo("R1")).thenReturn(java.util.List.of(
                new com.unionsg.zkfinger.domain.FingerprintRecord("R1", template, 3, 100)));

        var outcome = service.verifyAgainst("R1", Finger.ONE);

        assertThat(outcome.matchFound()).isTrue();
        assertThat(outcome.relationNo()).isEqualTo("R1");
    }

    @Test
    void verifyingAgainstACustomerWithNoTemplateReportsNoMatch() {
        device.queueCapture(new byte[] {7});
        when(repository.findByRelationNo("R1")).thenReturn(java.util.List.of());

        var outcome = service.verifyAgainst("R1", Finger.ONE);

        assertThat(outcome.matchFound()).isFalse();
        assertThat(outcome.recordsChecked()).isZero();
    }

    @Test
    void aClosedReaderPreventsCapture() {
        device.setReady(false);

        assertThatThrownBy(() -> service.captureAndStore("R1", Finger.ONE))
                .isInstanceOf(DeviceNotReadyException.class);
    }
}
