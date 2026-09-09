package com.unionsg.zkfinger.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unionsg.zkfinger.config.FingerprintProperties;
import com.unionsg.zkfinger.device.FingerprintDevice;
import com.unionsg.zkfinger.domain.Finger;
import com.unionsg.zkfinger.domain.IdentificationOutcome;
import com.unionsg.zkfinger.exception.CaptureTimeoutException;
import com.unionsg.zkfinger.exception.DeviceNotReadyException;
import com.unionsg.zkfinger.repository.FingerprintRepository;
import com.unionsg.zkfinger.service.CustomerLookupClient;
import com.unionsg.zkfinger.service.FingerprintService;
import com.unionsg.zkfinger.service.IdentificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Locks the HTTP contract inherited from the BioMini service. These key names are what
 * existing clients read, so a change here breaks callers even if the service still works.
 */
@WebMvcTest(controllers = FingerprintController.class)
@EnableConfigurationProperties(FingerprintProperties.class)
class FingerprintControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FingerprintService fingerprintService;

    @MockBean
    private IdentificationService identificationService;

    @MockBean
    private CustomerLookupClient customerLookup;

    @MockBean
    private FingerprintDevice device;

    @MockBean
    private FingerprintRepository repository;

    @Test
    void captureReturnsTheLegacySuccessShape() throws Exception {
        when(fingerprintService.captureAndStore(anyString(), any(Finger.class)))
                .thenReturn(new FingerprintService.CaptureResult("QUJD", 512, 512, true));

        mockMvc.perform(post("/capture").param("relation_no", "R1").param("thumbprint", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_code").value(1))
                .andExpect(jsonPath("$.response_msg").value("Fingerprint captured and saved successfully"))
                .andExpect(jsonPath("$.image").value("QUJD"))
                .andExpect(jsonPath("$.template_size").value(512))
                .andExpect(jsonPath("$.quality").value(512));
    }

    @Test
    void captureReportsAStorageFailureAsCodeMinusOne() throws Exception {
        when(fingerprintService.captureAndStore(anyString(), any(Finger.class)))
                .thenReturn(new FingerprintService.CaptureResult("QUJD", 512, 512, false));

        mockMvc.perform(post("/capture").param("relation_no", "R1").param("thumbprint", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_code").value(-1));
    }

    @Test
    void verifyReturnsTheMatchedCustomerDetails() throws Exception {
        when(fingerprintService.captureAndIdentify(any(Finger.class)))
                .thenReturn(new IdentificationOutcome(true, "R42", 145, 900, 4, false));
        when(customerLookup.getRelatedAccounts("R42"))
                .thenReturn(List.of(Map.of("account", "0001")));

        mockMvc.perform(post("/verify").param("thumbprint", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_verified").value(true))
                .andExpect(jsonPath("$.matched_relation_no").value("R42"))
                .andExpect(jsonPath("$.total_records_checked").value(900))
                .andExpect(jsonPath("$.threads_used").value(4))
                .andExpect(jsonPath("$.customer_details[0].account").value("0001"))
                .andExpect(jsonPath("$.verification_time_ms").exists());
    }

    @Test
    void verifyReportsNoMatchWithoutACustomerLookup() throws Exception {
        when(fingerprintService.captureAndIdentify(any(Finger.class)))
                .thenReturn(IdentificationOutcome.noMatch(1200, 4, false));

        mockMvc.perform(post("/verify").param("thumbprint", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_verified").value(false))
                .andExpect(jsonPath("$.matched_relation_no").doesNotExist())
                .andExpect(jsonPath("$.response_msg").value("No matching fingerprint found"));
    }

    @Test
    void aClosedReaderIsReportedAsServiceUnavailable() throws Exception {
        when(fingerprintService.captureAndIdentify(any(Finger.class)))
                .thenThrow(new DeviceNotReadyException("reader is closed"));

        mockMvc.perform(post("/verify"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.response_code").value(-1))
                .andExpect(jsonPath("$.user_verified").value(false));
    }

    @Test
    void aCaptureTimeoutIsReportedAsRequestTimeout() throws Exception {
        when(fingerprintService.captureAndStore(anyString(), any(Finger.class)))
                .thenThrow(new CaptureTimeoutException("no finger presented"));

        mockMvc.perform(post("/capture").param("relation_no", "R1").param("thumbprint", "1"))
                .andExpect(status().isRequestTimeout())
                .andExpect(jsonPath("$.response_msg").value("no finger presented"));
    }

    @Test
    void aMissingRelationNumberIsRejected() throws Exception {
        mockMvc.perform(post("/capture").param("thumbprint", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.response_code").value(-1));
    }

    @Test
    void healthReportsDegradedWhenTheReaderIsClosed() throws Exception {
        when(device.isReady()).thenReturn(false);
        when(device.deviceCount()).thenReturn(0);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.database").value("CONNECTED"));
    }

    @Test
    void initReturnsOneWhenTheReaderOpens() throws Exception {
        mockMvc.perform(get("/init"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("1"));
    }
}
